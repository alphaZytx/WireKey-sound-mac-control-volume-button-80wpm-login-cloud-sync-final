package com.wirekey

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.wirekey.ui.WireKeyNavigation
import com.wirekey.ui.composemode.VolumeKeyWpmController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appTheme by WireKeyApp.settingsRepository.appTheme.collectAsState(initial = "system")
            val useDarkTheme = when (appTheme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MaterialTheme(
                colorScheme = if (useDarkTheme) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WireKeyNavigation()
                }
            }
        }
    }

    /**
     * Turns the hardware volume keys into a typing-speed control while Compose Mode is on
     * screen, and leaves them completely alone everywhere else.
     *
     * Intercepted here rather than in a composable because only the activity sees the keys:
     * volume is a system-level event that never reaches the Compose focus system.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val deltaWpm = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeKeyWpmController.STEP_WPM
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VolumeKeyWpmController.STEP_WPM
            // Every other key, including VOLUME_MUTE, is none of our business.
            else -> return super.dispatchKeyEvent(event)
        }

        // Checked before consuming anything, so with no Compose Mode screen registered the
        // event takes the normal path and the system volume behaves as usual.
        if (!WireKeyApp.volumeKeyWpmController.isActive) return super.dispatchKeyEvent(event)

        // Act on the press, including auto-repeat while the key is held, so holding a key
        // ramps the speed the way holding a volume key normally ramps volume.
        if (event.action == KeyEvent.ACTION_DOWN) {
            WireKeyApp.volumeKeyWpmController.adjust(deltaWpm)
        }

        // ACTION_UP is swallowed too: leaving it to the system pops the volume panel on some
        // OEM skins even when the matching down event was handled.
        return true
    }
}
