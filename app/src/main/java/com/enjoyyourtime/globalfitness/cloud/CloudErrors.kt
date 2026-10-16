package com.enjoyyourtime.globalfitness.cloud

import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.util.Locale

/** User-facing messages for cloud errors and whether retrying later can help. */
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

    fun message(e: Throwable): UiText = when {
        e is FirebaseFirestoreException -> when (e.code) {
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED -> UiText.res(R.string.cloud_error_offline_retry)
            Code.PERMISSION_DENIED -> UiText.res(R.string.cloud_error_permission_denied)
            Code.UNAUTHENTICATED -> UiText.res(R.string.cloud_error_session_expired)
            Code.RESOURCE_EXHAUSTED -> UiText.res(R.string.cloud_error_busy)
            else -> UiText.res(R.string.cloud_error_code, e.code.name.lowercase(Locale.ROOT))
        }
        e is FirebaseNetworkException || e is IOException -> UiText.res(R.string.cloud_error_no_internet)
        e is CloudTimeoutException -> UiText.res(R.string.cloud_error_timeout)
        else -> UiText.res(R.string.cloud_error_sync, e.message ?: e.javaClass.simpleName)
    }
}
