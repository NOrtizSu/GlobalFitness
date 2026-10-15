package com.enjoyyourtime.globalfitness.account

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.enjoyyourtime.globalfitness.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** The signed-in Google account, as shown in the UI. */
data class AccountUser(
    val uid: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?,
)

sealed interface AuthResult {
    data object Success : AuthResult
    data object Cancelled : AuthResult
    data class Failure(val message: String) : AuthResult
}

/**
 * Optional "Sign in with Google" (Credential Manager) on top of Firebase Auth. The app works fully
 * without it; signing in only enables the cloud backup.
 */
class AccountManager(context: Context) {
    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(appContext)
    private val webClientId = appContext.getString(R.string.default_web_client_id)

    private val _user = MutableStateFlow(auth.currentUser?.toAccountUser())

    /** Null when signed out. */
    val user: StateFlow<AccountUser?> = _user.asStateFlow()

    init {
        auth.addAuthStateListener { _user.value = it.currentUser?.toAccountUser() }
    }

    /** The uid Firebase holds right now (updated before [user] observers run). */
    fun currentUid(): String? = auth.currentUser?.uid

    suspend fun signIn(activity: Activity): AuthResult {
        val token = when (val r = googleIdToken(activity, GetSignInWithGoogleOption.Builder(webClientId).build())) {
            is TokenResult.Token -> r.idToken
            is TokenResult.Error -> return r.result
        }
        return try {
            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed", e)
            AuthResult.Failure(AuthErrors.firebase(e))
        }
    }

    /** Fresh Google login for sensitive operations (account deletion). Must be the same account. */
    suspend fun reauthenticate(activity: Activity): AuthResult {
        val current = auth.currentUser ?: return AuthResult.Failure("No hay ninguna sesión iniciada.")
        val authorizedOnly = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .build()
        var result = googleIdToken(activity, authorizedOnly)
        if (result is TokenResult.Error && result.noCredential) {
            result = googleIdToken(activity, GetSignInWithGoogleOption.Builder(webClientId).build())
        }
        val token = when (result) {
            is TokenResult.Token -> result.idToken
            is TokenResult.Error -> return result.result
        }
        return try {
            current.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Re-authentication failed", e)
            AuthResult.Failure(AuthErrors.reauth(e, current.email))
        }
    }

    /** Deletes the Firebase user. Call [reauthenticate] first. */
    suspend fun deleteUser(): AuthResult {
        val current = auth.currentUser ?: return AuthResult.Failure("No hay ninguna sesión iniciada.")
        return try {
            current.delete().await()
            clearCredentials()
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Account deletion failed", e)
            AuthResult.Failure(AuthErrors.firebase(e))
        }
    }

    /** Signs out of Firebase and forgets the Google credential choice. Local data stays. */
    suspend fun signOut() {
        auth.signOut()
        clearCredentials()
    }

    private suspend fun clearCredentials() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            Log.w(TAG, "clearCredentialState failed", e)
        }
    }

    private sealed interface TokenResult {
        data class Token(val idToken: String) : TokenResult
        data class Error(val result: AuthResult, val noCredential: Boolean = false) : TokenResult
    }

    private suspend fun googleIdToken(activity: Activity, option: CredentialOption): TokenResult {
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = credentialManager.getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type in GOOGLE_TOKEN_TYPES) {
                TokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                TokenResult.Error(AuthResult.Failure("Google ha devuelto un tipo de credencial no admitido."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            TokenResult.Error(AuthResult.Cancelled)
        } catch (e: NoCredentialException) {
            TokenResult.Error(AuthResult.Failure(AuthErrors.NO_ACCOUNT), noCredential = true)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "getCredential failed", e)
            TokenResult.Error(AuthResult.Failure(AuthErrors.credential(e)))
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG, "Invalid Google ID token", e)
            TokenResult.Error(AuthResult.Failure("La respuesta de Google no es válida. Inténtalo de nuevo."))
        }
    }

    private fun FirebaseUser.toAccountUser() = AccountUser(
        uid = uid,
        name = displayName?.takeIf { it.isNotBlank() },
        email = email,
        photoUrl = photoUrl?.toString(),
    )

    private companion object {
        const val TAG = "AccountManager"
        val GOOGLE_TOKEN_TYPES = setOf(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
        )
    }
}
