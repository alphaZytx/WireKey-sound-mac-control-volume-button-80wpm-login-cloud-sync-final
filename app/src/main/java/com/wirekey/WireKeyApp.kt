package com.wirekey

import android.app.Application
import com.wirekey.bluetooth.HidKeyboardController

class WireKeyApp : Application() {
    companion object {
        lateinit var hidKeyboardController: HidKeyboardController
            private set
            
        lateinit var settingsRepository: com.wirekey.data.SettingsRepository
            private set

        lateinit var acousticTypingEngine: com.wirekey.audio.AcousticTypingEngine
            private set

        lateinit var typingSessionManager: com.wirekey.service.TypingSessionManager
            private set

        lateinit var remoteControlCoordinator: com.wirekey.remote.RemoteControlCoordinator
            private set

        lateinit var bleRemoteControlServer: com.wirekey.remote.BleRemoteControlServer
            private set

        /** Supabase account state. Independent of Bluetooth — see [com.wirekey.cloud]. */
        lateinit var cloudAuthRepository: com.wirekey.cloud.CloudAuthRepository
            private set

        /** Cloud copy of the Compose Mode text, kept in step across the account's phones. */
        lateinit var composeSyncCoordinator: com.wirekey.cloud.ComposeSyncCoordinator
            private set

        /** Volume keys -> Compose Mode typing speed. Stateless until a screen registers. */
        val volumeKeyWpmController = com.wirekey.ui.composemode.VolumeKeyWpmController()
    }

    override fun onCreate() {
        super.onCreate()
        hidKeyboardController = HidKeyboardController(this)
        settingsRepository = com.wirekey.data.SettingsRepository(this)
        acousticTypingEngine = com.wirekey.audio.AcousticTypingEngine(this)
        typingSessionManager = com.wirekey.service.TypingSessionManager(this)

        // Constructed last: it depends on all four singletons above.
        remoteControlCoordinator = com.wirekey.remote.RemoteControlCoordinator(
            context = this,
            typingManager = typingSessionManager,
            settingsRepository = settingsRepository
        )

        // The one wire between the Bluetooth layer and remote control: raw host->device
        // output reports (the LED block) become gesture input.
        hidKeyboardController.hostOutputReportListener = { data, source ->
            remoteControlCoordinator.onHostOutputReport(data, source)
        }

        // Everything else the host says. Carries no LED state, but it is what tells the
        // diagnostics panel apart from a dead channel.
        hidKeyboardController.hostActivityListener = { label, detail ->
            remoteControlCoordinator.onHostActivity(label, detail)
        }

        // Unlike Bluetooth HID LED reports, this channel carries an explicit command from
        // the macOS helper. It is how physical Caps Lock presses on a Mac reliably reach
        // the phone.
        // Cloud sync. Constructed unconditionally but inert without Supabase credentials in
        // the build, and never in the path of a keystroke: nothing below this line can affect
        // the HID connection or the typing engine.
        cloudAuthRepository = com.wirekey.cloud.CloudAuthRepository()
        composeSyncCoordinator = com.wirekey.cloud.ComposeSyncCoordinator(cloudAuthRepository)

        bleRemoteControlServer = com.wirekey.remote.BleRemoteControlServer(
            context = this,
            settingsRepository = settingsRepository,
            onTrigger = { source -> remoteControlCoordinator.onBleTrigger(source) },
            onDiagnostic = { label, detail -> remoteControlCoordinator.logNote(label, detail) }
        )
    }
}
