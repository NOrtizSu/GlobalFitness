package com.enjoyyourtime.globalfitness.ui.band

import androidx.annotation.StringRes
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.band.BandAction
import com.enjoyyourtime.globalfitness.band.BandMessage
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKey
import com.enjoyyourtime.globalfitness.band.xiaomi.AuthKeyError
import com.enjoyyourtime.globalfitness.band.xiaomi.BandFailure
import com.enjoyyourtime.globalfitness.ui.Fmt

/** Localized texts for the pure-Kotlin band layer. */

@StringRes
fun BandFailure.messageRes(): Int = when (this) {
    BandFailure.BLUETOOTH_UNAVAILABLE -> R.string.band_failure_no_bluetooth
    BandFailure.BLUETOOTH_OFF -> R.string.band_failure_bluetooth_off
    BandFailure.MISSING_PERMISSION -> R.string.band_failure_permission
    BandFailure.NO_KEY -> R.string.band_failure_no_key
    BandFailure.NO_DEVICE -> R.string.band_failure_no_device
    BandFailure.NOT_FOUND -> R.string.band_failure_not_found
    BandFailure.CONNECT_FAILED -> R.string.band_failure_connect
    BandFailure.NO_RESPONSE -> R.string.band_failure_no_response
    BandFailure.WRONG_KEY -> R.string.band_failure_wrong_key
    BandFailure.AUTH_REJECTED -> R.string.band_failure_auth_rejected
    BandFailure.LINK_LOST -> R.string.band_failure_link_lost
    BandFailure.REQUEST_TIMEOUT -> R.string.band_failure_timeout
    BandFailure.CLOSED -> R.string.band_status_disconnected
}

@StringRes
private fun BandAction.labelRes(): Int = when (this) {
    BandAction.FIND -> R.string.band_find
    BandAction.STOP_FIND -> R.string.band_stop_find
    BandAction.SYNC_TIME -> R.string.band_sync_time
    BandAction.REFRESH_STATUS -> R.string.band_refresh_battery
    BandAction.LIVE_HEART_RATE -> R.string.band_live_heart_rate
    BandAction.STOP_LIVE_HEART_RATE -> R.string.band_stop_live_heart_rate
    BandAction.ALARMS -> R.string.band_alarms_title
    BandAction.SAVE_ALARM -> R.string.band_save_alarm
    BandAction.DELETE_ALARM -> R.string.band_delete_alarm
    BandAction.TEST_NOTIFICATION -> R.string.band_test_notification
}

fun BandMessage.toUiText(): UiText = when (this) {
    BandMessage.TimeSynced -> UiText.res(R.string.band_time_synced)
    BandMessage.TestNotificationSent -> UiText.res(R.string.band_test_notification_sent)
    BandMessage.ConnectFirst -> UiText.res(R.string.band_connect_first)
    BandMessage.Found -> UiText.res(R.string.band_found)
    is BandMessage.ActionFailed -> UiText.res(R.string.common_error_detail, UiText.res(action.labelRes()), UiText.res(failure.messageRes()))
}

fun AuthKeyError.toUiText(length: Int): UiText = when (this) {
    AuthKeyError.EMPTY -> UiText.res(R.string.band_key_error_empty)
    AuthKeyError.NOT_HEX -> UiText.res(R.string.band_key_error_not_hex)
    AuthKeyError.WRONG_LENGTH -> UiText.res(R.string.band_key_error_length, Fmt.count(AuthKey.HEX_LENGTH.toLong()), Fmt.count(length.toLong()))
}
