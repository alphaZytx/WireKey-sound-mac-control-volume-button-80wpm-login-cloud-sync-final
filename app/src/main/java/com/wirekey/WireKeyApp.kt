package com.wirekey

import android.app.Application
import com.wirekey.bluetooth.HidKeyboardController

class WireKeyApp : Application() {
    companion object {
        lateinit var hidKeyboardController: HidKeyboardController
            private set
            
        lateinit var settingsRepository: com.wirekey.data.SettingsRepository
            private set

        lateinit var typingSessionManager: com.wirekey.service.TypingSessionManager
            private set
    }

    override fun onCreate() {
        super.onCreate()
        hidKeyboardController = HidKeyboardController(this)
        settingsRepository = com.wirekey.data.SettingsRepository(this)
        typingSessionManager = com.wirekey.service.TypingSessionManager(this)
    }
}
