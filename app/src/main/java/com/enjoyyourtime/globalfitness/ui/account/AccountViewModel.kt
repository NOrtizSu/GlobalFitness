package com.enjoyyourtime.globalfitness.ui.account

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.enjoyyourtime.globalfitness.AppContainer
import com.enjoyyourtime.globalfitness.GlobalFitnessApp
import com.enjoyyourtime.globalfitness.account.AccountUser
import com.enjoyyourtime.globalfitness.account.AuthResult
import com.enjoyyourtime.globalfitness.ads.AdSuppression
import com.enjoyyourtime.globalfitness.cloud.CloudSyncStatus
import com.enjoyyourtime.globalfitness.cloud.ConflictChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AccountAction { SIGN_IN, SIGN_OUT }

class AccountViewModel(private val container: AppContainer) : ViewModel() {
    private val engine = container.cloudSync

    val user: StateFlow<AccountUser?> = container.account.user
    val status: StateFlow<CloudSyncStatus> = engine.status

    private val _busy = MutableStateFlow<AccountAction?>(null)
    val busy: StateFlow<AccountAction?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-shot Spanish feedback (this screen's actions and sync notices), cleared with [consumeMessage]. */
    val message: StateFlow<String?> = combine(_message, engine.messages) { own, engineMessage -> own ?: engineMessage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun signIn(activity: Activity) = perform(AccountAction.SIGN_IN, AdSuppression.SIGN_IN) {
        when (val result = container.account.signIn(activity)) {
            AuthResult.Success -> "Sesión iniciada. Sincronizando tus datos…"
            AuthResult.Cancelled -> null
            is AuthResult.Failure -> result.message
        }
    }

    fun signOut() = perform(AccountAction.SIGN_OUT, null) {
        engine.signOut()
        "Sesión cerrada. Tus datos siguen en este teléfono."
    }

    fun syncNow() = engine.syncNow()

    fun resolveConflict(choice: ConflictChoice) = engine.resolveConflict(choice)

    /** Runs in the app scope (keeps going if this screen closes); progress in [status], result in [message]. */
    fun deleteCloudDataAndAccount(activity: Activity) = engine.deleteCloudDataAndAccount(activity)

    fun consumeMessage() {
        _message.value = null
        engine.consumeMessage()
    }

    private fun perform(action: AccountAction, suppressAds: String?, block: suspend () -> String?) {
        if (_busy.value != null) return
        _busy.value = action
        suppressAds?.let { container.adSuppression.set(it, true) }
        viewModelScope.launch {
            try {
                _message.value = block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Error inesperado: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                suppressAds?.let { container.adSuppression.set(it, false) }
                _busy.value = null
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GlobalFitnessApp
                AccountViewModel(app.container)
            }
        }
    }
}
