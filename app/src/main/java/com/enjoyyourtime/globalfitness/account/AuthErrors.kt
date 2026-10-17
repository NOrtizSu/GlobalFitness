package com.enjoyyourtime.globalfitness.account

import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException

/** User-facing messages for sign-in errors. */
internal object AuthErrors {
    val NO_ACCOUNT: UiText = UiText.res(R.string.auth_error_no_account)

    fun credential(e: GetCredentialException): UiText {
        val detail = e.errorMessage?.toString().orEmpty()
        return when {
            e is GetCredentialProviderConfigurationException || e is GetCredentialUnsupportedException ->
                UiText.res(R.string.auth_error_unsupported)
            // Credential Manager reports a missing SHA-1 / OAuth client as "[28444] Developer console is not set up correctly."
            "28444" in detail || "Developer console" in detail -> UiText.res(R.string.auth_error_not_configured)
            detail.isNotBlank() -> UiText.res(R.string.auth_error_sign_in_detail, detail)
            else -> UiText.res(R.string.auth_error_sign_in)
        }
    }

    fun firebase(e: Exception): UiText = when (e) {
        is FirebaseNetworkException -> UiText.res(R.string.auth_error_network)
        is FirebaseTooManyRequestsException -> UiText.res(R.string.auth_error_too_many)
        is FirebaseAuthRecentLoginRequiredException -> UiText.res(R.string.auth_error_recent_login)
        is FirebaseAuthInvalidUserException -> UiText.res(R.string.auth_error_invalid_user)
        is FirebaseAuthInvalidCredentialsException -> UiText.res(R.string.auth_error_invalid_credential)
        else -> UiText.res(R.string.auth_error_account, e.message ?: e.javaClass.simpleName)
    }

    fun reauth(e: Exception, expectedEmail: String?): UiText = when (e) {
        // ERROR_USER_MISMATCH: a different Google account was chosen.
        is FirebaseAuthInvalidUserException ->
            if (expectedEmail != null) UiText.res(R.string.auth_error_same_account_email, expectedEmail) else UiText.res(R.string.auth_error_same_account)
        else -> firebase(e)
    }
}
