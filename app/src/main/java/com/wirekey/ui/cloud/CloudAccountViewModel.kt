package com.wirekey.ui.cloud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wirekey.WireKeyApp
import com.wirekey.cloud.AuthCredentials
import com.wirekey.cloud.AuthResult
import com.wirekey.cloud.CloudAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the auth form is signing an existing user in or creating a new account. */
enum class AuthMode { SIGN_IN, REGISTER }

/**
 * Backs both the sign-in/register screen and the account screen — they are two views of the
 * same thing, and sharing one ViewModel keeps the "signed in?" answer from differing between
 * them mid-transition.
 */
class CloudAccountViewModel : ViewModel() {

    private val authRepository = WireKeyApp.cloudAuthRepository

    val authState: StateFlow<CloudAuthState> = authRepository.authState
    val syncStatus = WireKeyApp.composeSyncCoordinator.syncStatus

    private val _mode = MutableStateFlow(AuthMode.SIGN_IN)
    val mode: StateFlow<AuthMode> = _mode.asStateFlow()

    /** True while a call is in flight, so the form can lock and show a spinner. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Blocking problem with the last attempt, shown in red under the form. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Non-error information, e.g. "check your inbox to confirm". */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun setMode(mode: AuthMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        clearMessages()
    }

    fun clearMessages() {
        _error.value = null
        _notice.value = null
    }

    fun submit(email: String, password: String) {
        if (_busy.value) return
        val validationError = AuthCredentials.validate(email, password)
        if (validationError != null) {
            _error.value = validationError
            _notice.value = null
            return
        }

        clearMessages()
        _busy.value = true
        viewModelScope.launch {
            val result = when (_mode.value) {
                AuthMode.SIGN_IN -> authRepository.signIn(email, password)
                AuthMode.REGISTER -> authRepository.register(email, password)
            }
            when (result) {
                is AuthResult.Success -> Unit // authState changes; the screen navigates away.
                is AuthResult.ConfirmationRequired -> {
                    _notice.value =
                        "Account created. Confirm your email address, then sign in."
                    _mode.value = AuthMode.SIGN_IN
                }
                is AuthResult.Failure -> _error.value = result.message
            }
            _busy.value = false
        }
    }

    fun signOut() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = authRepository.signOut()
            if (result is AuthResult.Failure) _error.value = result.message
            _busy.value = false
        }
    }
}
