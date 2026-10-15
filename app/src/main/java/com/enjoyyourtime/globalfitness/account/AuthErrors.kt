package com.enjoyyourtime.globalfitness.account

import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException

/** Spanish messages for sign-in errors. */
internal object AuthErrors {
    const val NO_ACCOUNT = "No hay ninguna cuenta de Google en este teléfono. Añade una en Ajustes del sistema y vuelve a intentarlo."

    fun credential(e: GetCredentialException): String {
        val detail = e.errorMessage?.toString().orEmpty()
        return when {
            e is GetCredentialProviderConfigurationException || e is GetCredentialUnsupportedException ->
                "Este teléfono no permite iniciar sesión con Google (falta o está desactualizado Google Play Services)."
            // Credential Manager reports a missing SHA-1 / OAuth client as "[28444] Developer console is not set up correctly."
            "28444" in detail || "Developer console" in detail ->
                "El inicio de sesión con Google no está configurado para esta versión de la app."
            else -> "No se pudo iniciar sesión con Google${detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
        }
    }

    fun firebase(e: Exception): String = when (e) {
        is FirebaseNetworkException -> "Sin conexión a Internet. Inténtalo de nuevo."
        is FirebaseTooManyRequestsException -> "Demasiados intentos. Espera un momento y vuelve a intentarlo."
        is FirebaseAuthRecentLoginRequiredException -> "Por seguridad, vuelve a identificarte con Google e inténtalo de nuevo."
        is FirebaseAuthInvalidUserException -> "La cuenta ya no existe o está desactivada."
        is FirebaseAuthInvalidCredentialsException -> "Google no ha aceptado la credencial. Inténtalo de nuevo."
        else -> "Error de la cuenta: ${e.message ?: e.javaClass.simpleName}"
    }

    fun reauth(e: Exception, expectedEmail: String?): String = when (e) {
        // ERROR_USER_MISMATCH: a different Google account was chosen.
        is FirebaseAuthInvalidUserException ->
            "Elige la misma cuenta de Google con la que iniciaste sesión${expectedEmail?.let { " ($it)" } ?: ""}."
        else -> firebase(e)
    }
}
