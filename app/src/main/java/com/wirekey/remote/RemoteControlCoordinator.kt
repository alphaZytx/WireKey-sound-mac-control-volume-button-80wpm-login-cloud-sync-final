package com.wirekey.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wirekey.data.SettingsRepository
import com.wirekey.service.TypingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Handle returned by [RemoteControlCoordinator.registerStartHandler].
 *
 * Identity-checked on the way out: only the *current* registration can clear the slot.
 * Without that, a ComposeModeViewModel destroyed after its replacement registered --
 * Compose gives no ordering guarantee there -- would wipe the live handler and silently
 * break remote Start.
 */
class StartHandlerRegistration internal constructor(
    private val coordinator: RemoteControlCoordinator,
    internal val handler: () -> Boolean
) {
    fun unregister() = coordinator.unregisterStartHandler(this)
}

/**
 * Drives Start / Pause / Resume from the WireKey Remote macOS helper.
 *
 * Pause and Resume are applied straight to the app-scoped [TypingSessionManager], so they
 * work from any screen and even with the app backgrounded. Start needs the draft text and
 * its A/B markers, which only ComposeModeViewModel has, so it is delegated to a handler
 * that screen registers -- see [registerStartHandler].
 *
 * The macOS helper watches the physical Caps Lock key, then writes a deliberately tiny BLE
 * GATT command to the phone. Every command is marshalled onto `Dispatchers.Main.immediate`,
 * which serializes it with the on-screen buttons. BLE callbacks arrive on a Binder thread,
 * so this is not optional.
 */
class RemoteControlCoordinator(
    private val context: Context,
    private val typingManager: TypingSessionManager,
    settingsRepository: SettingsRepository,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var settings: RemoteControlSettings = RemoteControlSettings.DEFAULT

    private val startHandler = AtomicReference<StartHandlerRegistration?>(null)

    private var lastCommandAt = 0L
    private var notificationChannelReady = false

    // ── Observable state, for the Settings diagnostics panel ──────────────────

    private val _lastNotice = MutableStateFlow<RemoteNotice?>(null)
    val lastNotice: StateFlow<RemoteNotice?> = _lastNotice.asStateFlow()

    private val _reportLog = MutableStateFlow<List<LedReportLog>>(emptyList())
    val reportLog: StateFlow<List<LedReportLog>> = _reportLog.asStateFlow()

    private val _reportCount = MutableStateFlow(0)
    val reportCount: StateFlow<Int> = _reportCount.asStateFlow()

    /** Host->device callbacks that were not LED reports. See [onHostActivity]. */
    private val _hostActivityCount = MutableStateFlow(0)
    val hostActivityCount: StateFlow<Int> = _hostActivityCount.asStateFlow()

    // Diagnostics-only edge tracking, deliberately separate from the detector's own
    // state so that reading the panel can never perturb gesture recognition.
    private var diagLastBit: Boolean? = null
    private var diagLastEdgeAt = 0L

    init {
        scope.launch {
            combine(
                settingsRepository.remoteControlEnabled,
                settingsRepository.remoteTriggerKey,
                settingsRepository.remoteGestureWindowMs,
                settingsRepository.remoteHapticsEnabled
            ) { enabled, keyId, window, haptics ->
                RemoteControlSettings(
                    enabled = enabled,
                    triggerKey = LockKey.fromId(keyId),
                    gestureWindowMs = RemoteControlSettings.coerceWindow(window),
                    hapticsEnabled = haptics
                )
            }.collect { updated ->
                settings = updated
            }
        }
    }

    // ── Start-handler registry ────────────────────────────────────────────────

    /**
     * Registers the callback used to begin a send remotely.
     *
     * @param handler must return true only if a send actually started -- an empty draft
     *   returns false so the user gets the "rejected" haptic instead of silence.
     */
    fun registerStartHandler(handler: () -> Boolean): StartHandlerRegistration {
        val registration = StartHandlerRegistration(this, handler)
        startHandler.set(registration)
        return registration
    }

    internal fun unregisterStartHandler(registration: StartHandlerRegistration) {
        startHandler.compareAndSet(registration, null)
    }

    // ── Inbound reports ───────────────────────────────────────────────────────

    /**
     * Records raw host->device HID output reports for legacy diagnostics only.
     *
     * These reports are intentionally not commands. macOS does not reliably mirror Caps
     * Lock state from the physical Mac keyboard to a separate Bluetooth HID keyboard, which
     * is why this feature uses [onBleTrigger] instead.
     */
    fun onHostOutputReport(data: ByteArray?, source: String) {
        scope.launch {
            recordDiagnostics(data, source, settings.triggerKey)
            logNote("HID LED", "recorded only — Mac Remote uses Bluetooth LE")
        }
    }

    /** Called by [BleRemoteControlServer] after the macOS helper sends its trigger command. */
    fun onBleTrigger(source: String) {
        scope.launch {
            if (!settings.enabled) {
                logNote("BLE", "ignored command from $source — Mac Remote Control is switched off")
                return@launch
            }
            logNote("BLE", "trigger received from $source")
            executeCommand()
        }
    }

    /**
     * Entry point for host->device HID callbacks that carry no LED state -- GET_REPORT,
     * protocol changes, the link coming up. Recorded purely so the diagnostics panel can
     * show that the host is talking at all. Called from a Bluetooth callback thread.
     */
    fun onHostActivity(label: String, detail: String) {
        logNote(label, detail)
        scope.launch { _hostActivityCount.value = _hostActivityCount.value + 1 }
    }

    /**
     * Writes a non-report line into the diagnostics log. Used for host callbacks and for
     * the self-test's own progress markers, so the whole exchange reads in one timeline.
     */
    fun logNote(label: String, detail: String) {
        scope.launch {
            _reportLog.value = (_reportLog.value + LedReportLog(
                wallClockMs = System.currentTimeMillis(),
                rawHex = detail,
                ledByte = null,
                source = label,
                triggerBitOn = null,
                wasEdge = false,
                gapSincePreviousEdgeMs = null,
                isHostActivity = true
            )).takeLast(MAX_LOG_ENTRIES)
        }
    }

    private fun executeCommand() {
        val now = clock()
        if (now - lastCommandAt < MIN_COMMAND_INTERVAL_MS) return
        lastCommandAt = now

        val registration = startHandler.get()
        val action = RemoteCommandResolver.resolve(
            isSending = typingManager.isSending.value,
            isPaused = typingManager.isPaused.value,
            hasStartHandler = registration != null
        )

        when (action) {
            RemoteAction.START -> {
                val started = try {
                    registration?.handler?.invoke() ?: false
                } catch (e: Exception) {
                    Log.e(TAG, "Start handler threw", e)
                    false
                }
                if (started) {
                    vibrate(PATTERN_START)
                    notice(RemoteNotice("Started sending from your Mac"))
                } else {
                    vibrate(PATTERN_REJECTED)
                    notice(RemoteNotice("Nothing to send", isWarning = true))
                }
            }

            RemoteAction.PAUSE -> {
                typingManager.pauseSend()
                vibrate(PATTERN_PAUSE)
                notice(RemoteNotice("Paused from your Mac"))
            }

            RemoteAction.RESUME -> {
                typingManager.resumeSend()
                vibrate(PATTERN_RESUME)
                notice(RemoteNotice("Resumed from your Mac"))
            }

            RemoteAction.IGNORED_NO_SCREEN -> {
                vibrate(PATTERN_REJECTED)
                notice(RemoteNotice("Open Compose Mode to start a send", isWarning = true))
            }

            RemoteAction.IGNORED_NOTHING_TO_SEND -> {
                vibrate(PATTERN_REJECTED)
                notice(RemoteNotice("Nothing to send", isWarning = true))
            }
        }
    }

    // ── Feedback ──────────────────────────────────────────────────────────────

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "No vibrator available", e)
            null
        }
    }

    /** @param pattern waveform timings, `[delay, on, delay, on, ...]`. */
    private fun vibrate(pattern: LongArray) {
        if (!settings.hapticsEnabled) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun notice(value: RemoteNotice) {
        _lastNotice.value = value
    }

    private fun postNotification(title: String, text: String) {
        try {
            ensureNotificationChannel()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // POST_NOTIFICATIONS may be denied on API 33+. The haptic already fired, so
            // this is supplementary -- never let it take down the callback path.
            Log.w(TAG, "Could not post remote-control notification", e)
        }
    }

    private fun ensureNotificationChannel() {
        if (notificationChannelReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mac Remote Control",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when a remote command needs your attention"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        notificationChannelReady = true
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /**
     * Records every inbound report, whether or not the feature is enabled -- the Settings
     * panel is how the user confirms their Mac sends LED reports at all, which has to be
     * answerable *before* switching the feature on.
     */
    private fun recordDiagnostics(data: ByteArray?, source: String, key: LockKey) {
        val led = LockKeyGestureDetector.extractLedByte(data)
        val bit = led?.let { (it and key.mask) != 0 }
        val now = clock()

        var gap: Long? = null
        var wasEdge = false
        if (bit != null) {
            val previous = diagLastBit
            if (previous != null && previous != bit) {
                wasEdge = true
                if (diagLastEdgeAt != 0L) gap = now - diagLastEdgeAt
                diagLastEdgeAt = now
            }
            diagLastBit = bit
        }

        val entry = LedReportLog(
            wallClockMs = System.currentTimeMillis(),
            rawHex = data?.joinToString(" ") { String.format("%02X", it) } ?: "(empty)",
            ledByte = led,
            source = source,
            triggerBitOn = bit,
            wasEdge = wasEdge,
            gapSincePreviousEdgeMs = gap
        )

        _reportLog.value = (_reportLog.value + entry).takeLast(MAX_LOG_ENTRIES)
        _reportCount.value = _reportCount.value + 1
    }

    /**
     * Empties the log and counters, but deliberately keeps [diagLastBit].
     *
     * Blanking it used to make the next report print without its "change" marker, which
     * reads exactly like a swallowed keypress -- twice now that has sent a debugging
     * session chasing a bug that was not there. Clearing the record of what happened must
     * not also clear what the next record is measured against.
     */
    fun clearDiagnostics() {
        _reportLog.value = emptyList()
        _reportCount.value = 0
        _hostActivityCount.value = 0
        _lastNotice.value = null
        diagLastEdgeAt = 0L
    }

    companion object {
        private const val TAG = "RemoteControl"

        /** Absorbs accidental duplicate BLE commands. */
        private const val MIN_COMMAND_INTERVAL_MS = 300L

        private const val MAX_LOG_ENTRIES = 24
        private const val CHANNEL_ID = "wirekey_remote_control"
        private const val NOTIFICATION_ID = 43

        private val PATTERN_START = longArrayOf(0, 60)
        private val PATTERN_PAUSE = longArrayOf(0, 40, 80, 40)
        private val PATTERN_RESUME = longArrayOf(0, 120)
        private val PATTERN_REJECTED = longArrayOf(0, 25, 60, 25, 60, 25)
    }
}
