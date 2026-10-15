package com.enjoyyourtime.globalfitness.cloud

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException

/** Spanish messages for cloud errors and whether retrying later can help. */
object CloudErrors {
    private val RETRYABLE = setOf(
        Code.UNAVAILABLE,
        Code.DEADLINE_EXCEEDED,
        Code.RESOURCE_EXHAUSTED,
        Code.ABORTED,
        Code.INTERNAL,
    )

    fun isRetryable(e: Throwable): Boolean = when (e) {
        is FirebaseFirestoreException -> e.code in RETRYABLE
        is FirebaseNetworkException, is IOException, is CloudTimeoutException -> true
        else -> false
    }

    fun message(e: Throwable): String = when {
        e is FirebaseFirestoreException -> when (e.code) {
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED -> "Sin conexión con la nube. Se reintentará automáticamente."
            Code.PERMISSION_DENIED -> "La nube ha rechazado el acceso. Cierra sesión y vuelve a iniciarla."
            Code.UNAUTHENTICATED -> "La sesión ha caducado. Vuelve a iniciar sesión."
            Code.RESOURCE_EXHAUSTED -> "La nube está saturada. Se reintentará más tarde."
            else -> "Error de la nube (${e.code.name.lowercase()}). Inténtalo de nuevo más tarde."
        }
        e is FirebaseNetworkException || e is IOException -> "Sin conexión a Internet. Se reintentará automáticamente."
        e is CloudTimeoutException -> "La nube no ha respondido a tiempo. Comprueba la conexión e inténtalo de nuevo."
        else -> "Error al sincronizar: ${e.message ?: e.javaClass.simpleName}"
    }
}
