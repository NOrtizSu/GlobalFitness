package com.enjoyyourtime.globalfitness.ui.wearables

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.KeyProblem
import com.enjoyyourtime.globalfitness.wearable.WearableAction
import com.enjoyyourtime.globalfitness.wearable.WearableConnection
import com.enjoyyourtime.globalfitness.wearable.WearableDriver
import com.enjoyyourtime.globalfitness.wearable.WearableFailure
import com.enjoyyourtime.globalfitness.wearable.WearableKeys
import com.enjoyyourtime.globalfitness.wearable.WearableMessage
import com.enjoyyourtime.globalfitness.wearable.WearableSummary
import com.enjoyyourtime.globalfitness.wearable.WearableTransport

/** Localized texts for the UI-free wearable layer. */

/** The vendor app named in instructions ("Mi Fitness", "Zepp"), null for devices without one. */
val WearableDriver.vendorApp: String? get() = keyHelp?.vendorApp

/**
 * Why connecting failed or the session ended. [busy]: another app holds the device. Texts that
 * mention the vendor app have a variant for devices without one.
 */
fun failureText(failure: WearableFailure, busy: Boolean, vendor: String?): UiText {
    if (busy) {
        return if (vendor != null) UiText.res(R.string.wearable_failure_busy, vendor) else UiText.res(R.string.wearable_failure_busy_plain)
    }
    return when (failure) {
        WearableFailure.BLUETOOTH_UNAVAILABLE -> UiText.res(R.string.band_failure_no_bluetooth)
        WearableFailure.BLUETOOTH_OFF -> UiText.res(R.string.band_failure_bluetooth_off)
        WearableFailure.MISSING_PERMISSION -> UiText.res(R.string.wearable_failure_permission)
        WearableFailure.NO_KEY -> UiText.res(R.string.wearable_failure_no_key)
        WearableFailure.NO_DEVICE, WearableFailure.UNKNOWN_DRIVER -> UiText.res(R.string.wearable_failure_unknown)
        WearableFailure.NOT_FOUND -> UiText.res(R.string.wearable_failure_not_found)
        WearableFailure.CONNECT_FAILED ->
            if (vendor != null) UiText.res(R.string.wearable_failure_connect, vendor) else UiText.res(R.string.wearable_failure_connect_plain)
        WearableFailure.UNSUPPORTED_DEVICE -> UiText.res(R.string.wearable_failure_unsupported)
        WearableFailure.NO_RESPONSE ->
            if (vendor != null) UiText.res(R.string.wearable_failure_no_response, vendor) else UiText.res(R.string.wearable_failure_no_response_plain)
        WearableFailure.WRONG_KEY ->
            if (vendor != null) UiText.res(R.string.wearable_failure_wrong_key, vendor) else UiText.res(R.string.wearable_failure_auth_rejected)
        WearableFailure.AUTH_REJECTED -> UiText.res(R.string.wearable_failure_auth_rejected)
        WearableFailure.LINK_LOST -> UiText.res(R.string.wearable_failure_link_lost)
        WearableFailure.REQUEST_TIMEOUT -> UiText.res(R.string.wearable_failure_timeout)
        WearableFailure.NOT_SUPPORTED -> UiText.res(R.string.wearable_failure_not_supported)
        WearableFailure.CLOSED -> UiText.res(R.string.wearable_status_not_connected)
    }
}

private fun WearableAction.label(): UiText = UiText.res(
    when (this) {
        WearableAction.FIND -> R.string.wearable_find
        WearableAction.SYNC_TIME -> R.string.band_sync_time
        WearableAction.REFRESH -> R.string.band_refresh_battery
        WearableAction.LIVE_HEART_RATE -> R.string.band_live_heart_rate
        WearableAction.ALARMS -> R.string.band_alarms_title
        WearableAction.SAVE_ALARM -> R.string.band_save_alarm
        WearableAction.DELETE_ALARM -> R.string.band_delete_alarm
        WearableAction.TEST_NOTIFICATION -> R.string.band_test_notification
    },
)

fun WearableMessage.toUiText(deviceName: String, vendor: String?): UiText = when (this) {
    WearableMessage.TimeSynced -> UiText.res(R.string.wearable_time_synced)
    WearableMessage.TestNotificationSent -> UiText.res(R.string.band_test_notification_sent)
    WearableMessage.FoundIt -> UiText.res(R.string.wearable_found)
    WearableMessage.FindPhoneRequested -> UiText.res(R.string.wearable_find_phone, deviceName)
    WearableMessage.ConnectFirst -> UiText.res(R.string.wearable_connect_first)
    is WearableMessage.ActionFailed -> UiText.res(R.string.common_error_detail, action.label(), failureText(failure, busy = false, vendor = vendor))
}

/** Format error of a typed key. */
fun KeyProblem.toUiText(kind: AuthKeyKind, length: Int): UiText = when (this) {
    KeyProblem.EMPTY -> UiText.res(if (kind == AuthKeyKind.NUMERIC_USER_ID) R.string.wearable_key_error_empty_user_id else R.string.band_key_error_empty)
    KeyProblem.NOT_HEX -> UiText.res(R.string.band_key_error_not_hex)
    KeyProblem.WRONG_LENGTH ->
        UiText.res(R.string.band_key_error_length, Fmt.count(WearableKeys.HEX_LENGTH.toLong()), Fmt.count(length.toLong()))
    KeyProblem.NOT_NUMERIC -> UiText.res(R.string.wearable_key_error_not_numeric)
    KeyProblem.TOO_LONG -> UiText.res(R.string.wearable_key_error_too_long)
}

@Composable
fun transportLabel(t: WearableTransport): String = when (t) {
    WearableTransport.BLE_GATT -> stringResource(R.string.wearable_transport_ble)
    WearableTransport.CLASSIC_SPP -> stringResource(R.string.wearable_transport_classic)
    WearableTransport.BLE_UART -> stringResource(R.string.wearable_transport_uart)
}

@Composable
fun connectionLabel(connection: WearableConnection, protocol: String?): String = when (connection) {
    WearableConnection.DISCONNECTED -> stringResource(R.string.wearable_status_not_connected)
    WearableConnection.CONNECTING -> stringResource(R.string.band_status_connecting)
    WearableConnection.READY -> protocol?.let { stringResource(R.string.wearable_connected_protocol, it) }
        ?: stringResource(R.string.wearable_status_connected)
}

/** One line about a configured wearable (Devices list and the Today chip). */
@Composable
fun wearableStatus(summary: WearableSummary): String {
    if (summary.driver == null) return stringResource(R.string.wearable_status_unknown_model)
    if (summary.needsKey) return stringResource(R.string.band_status_no_key)
    val state = summary.state
    val connection = when (state.connection) {
        WearableConnection.DISCONNECTED -> stringResource(R.string.wearable_status_not_connected)
        WearableConnection.CONNECTING -> stringResource(R.string.band_status_connecting)
        WearableConnection.READY -> stringResource(R.string.wearable_status_connected)
    }
    return state.batteryLevel?.takeIf { state.connection == WearableConnection.READY }
        ?.let { stringResource(R.string.band_status_battery, connection, Fmt.percent(it.toDouble())) } ?: connection
}
