package com.enjoyyourtime.globalfitness.ui.devices

import android.bluetooth.le.ScanCallback
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.UiText
import com.enjoyyourtime.globalfitness.scale.ConfigError
import com.enjoyyourtime.globalfitness.scale.ConfigField
import com.enjoyyourtime.globalfitness.scale.DriverNameNote
import com.enjoyyourtime.globalfitness.scale.FormulaLib
import com.enjoyyourtime.globalfitness.scale.GENERIC_BRAND
import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import com.enjoyyourtime.globalfitness.scale.ScaleNotice
import com.enjoyyourtime.globalfitness.scale.ScaleNotice.Kind
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.asString

/** Localized texts for the pure-Kotlin scale layer (messages, driver names, settings). */

fun ScaleNotice.toUiText(): UiText {
    if (kind == Kind.OMRON_MEASUREMENTS_READ) {
        val count = (args.firstOrNull() as? Int) ?: 0
        return UiText.plural(R.plurals.scale_omron_measurements_read, count)
    }
    return UiText.Res(noticeRes(kind), args)
}

@StringRes
private fun noticeRes(kind: Kind): Int = when (kind) {
    Kind.STEP_ON -> R.string.scale_step_on
    Kind.STEP_ON_FOR_REFERENCE -> R.string.scale_step_on_for_reference
    Kind.LOW_BATTERY -> R.string.scale_low_battery
    Kind.MEASUREMENT_FAILED_FEET -> R.string.scale_measurement_failed_feet
    Kind.FAT_MEASUREMENT_FAILED -> R.string.scale_fat_measurement_failed
    Kind.INVALID_RECORD -> R.string.scale_invalid_record
    Kind.USER_CREATED_STEP_ON -> R.string.scale_user_created_step_on
    Kind.SCALE_FULL -> R.string.scale_full
    Kind.USER_CREATE_FAILED -> R.string.scale_user_create_failed
    Kind.USER_REJECTED_RETRY_LATER -> R.string.scale_user_rejected
    Kind.NO_FREE_USERS_CHOOSE_SLOT -> R.string.scale_no_free_users_choose_slot
    Kind.NO_FREE_USERS_SET_PIN -> R.string.scale_no_free_users_set_pin
    Kind.USER_REGISTERED -> R.string.scale_user_registered
    Kind.USER_REGISTRATION_REFUSED -> R.string.scale_user_registration_refused
    Kind.PIN_REJECTED -> R.string.scale_pin_rejected
    Kind.PIN_MISSING -> R.string.scale_pin_missing
    Kind.PIN_SHOWN_ON_SCALE -> R.string.scale_pin_shown
    Kind.PAIRED -> R.string.scale_paired
    Kind.NOT_PAIRED_PAIRING_MODE -> R.string.scale_not_paired
    Kind.TRISA_PAIRED_STEP_ON_AGAIN -> R.string.scale_trisa_paired
    Kind.TRISA_NOT_PAIRED -> R.string.scale_trisa_not_paired
    Kind.OMRON_UNKNOWN_MODEL -> R.string.scale_omron_unknown_model
    Kind.OMRON_HOLD_PAIRING_BUTTON -> R.string.scale_omron_hold_pairing
    Kind.OMRON_NOT_IN_PAIRING_MODE -> R.string.scale_omron_not_pairing
    Kind.OMRON_PAIRED -> R.string.scale_omron_paired
    Kind.OMRON_PAIRING_FAILED -> R.string.scale_omron_pairing_failed
    Kind.OMRON_SESSION_REJECTED -> R.string.scale_omron_session_rejected
    Kind.OMRON_MEMORY_READ_ERROR -> R.string.scale_omron_read_error
    Kind.OMRON_NO_NEW_MEASUREMENTS -> R.string.scale_omron_no_new
    Kind.OMRON_MEASUREMENTS_READ -> error("A plural, resolved by toUiText()")
    Kind.OMRON_STOPPED_RESPONDING -> R.string.scale_omron_stopped
    Kind.EUFY_AUTH_REJECTED -> R.string.scale_eufy_auth_rejected
    Kind.BIND_KEY_NEEDED -> R.string.scale_bind_key_needed
    Kind.BIND_KEY_WRONG -> R.string.scale_bind_key_wrong
    Kind.MISSING_NEARBY_PERMISSION -> R.string.scale_missing_nearby_permission
    Kind.BLUETOOTH_UNAVAILABLE -> R.string.scale_bluetooth_unavailable
    Kind.BLUETOOTH_OFF -> R.string.scale_bluetooth_off
    Kind.INVALID_ADDRESS -> R.string.scale_invalid_address
    Kind.CONNECT_NOT_STARTED -> R.string.scale_connect_not_started
    Kind.NOT_CONNECTED -> R.string.scale_not_connected
    Kind.SERVICE_DISCOVERY_FAILED -> R.string.scale_service_discovery_failed
    Kind.SERVICES_READ_FAILED -> R.string.scale_services_read_failed
    Kind.SCALE_STOPPED_RESPONDING -> R.string.scale_stopped_responding
    Kind.CONNECTION_CLOSED -> R.string.scale_connection_closed
    Kind.SCALE_DISCONNECTED -> R.string.scale_disconnected
    Kind.CONNECTION_LOST_OUT_OF_RANGE -> R.string.scale_connection_lost
    Kind.SCALE_CLOSED_CONNECTION -> R.string.scale_closed_connection
    Kind.PHONE_CANCELLED_CONNECTION -> R.string.scale_phone_cancelled
    Kind.CONNECTION_NOT_ESTABLISHED -> R.string.scale_connection_not_established
    Kind.GATT_ERROR_133 -> R.string.scale_gatt_error_133
    Kind.GATT_ERROR -> R.string.scale_gatt_error
    Kind.CONNECT_TIMEOUT -> R.string.scale_connect_timeout
    Kind.CONNECT_FAILED -> R.string.scale_connect_failed
    Kind.CONNECT_FAILED_DETAIL -> R.string.scale_connect_failed_detail
    Kind.PROTOCOL_ERROR -> R.string.scale_protocol_error
    Kind.NO_NEW_DATA -> R.string.scale_no_new_data
    Kind.AUTO_CONFIGURED -> R.string.scale_auto_configured
    Kind.AUTO_CONFIGURED_NEEDS_SETUP -> R.string.scale_auto_configured_needs_setup
    Kind.ADDED_STEP_ON -> R.string.scale_added_step_on
    Kind.ADDED_NEEDS_SETUP -> R.string.scale_added_needs_setup
    Kind.SAVE_FAILED -> R.string.scale_save_failed
}

@Composable
fun ScaleNotice.text(): String = toUiText().asString()

/** Android's BLE scan error codes. */
@StringRes
fun scanErrorRes(code: Int): Int = when (code) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> R.string.scan_error_already_started
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> R.string.scan_error_registration
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> R.string.scan_error_internal
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> R.string.scan_error_unsupported
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> R.string.scan_error_resources
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> R.string.scan_error_too_frequent
    else -> R.string.scan_error_code
}

/** "OKOK / Chipsea (broadcast)": the model name with its generic words translated. */
@Composable
fun ScaleDriver.title(): String = driverTitle(displayName, nameNote)

@Composable
fun driverTitle(displayName: String, note: DriverNameNote?): String =
    if (note == null) displayName else stringResource(noteRes(note), displayName)

@StringRes
private fun noteRes(note: DriverNameNote): Int = when (note) {
    DriverNameNote.STANDARD_PROFILE -> R.string.driver_name_standard_profile
    DriverNameNote.SCALE -> R.string.driver_name_scale
    DriverNameNote.BODY_FAT_BROADCAST -> R.string.driver_name_body_fat_broadcast
    DriverNameNote.BROADCAST -> R.string.driver_name_broadcast
    DriverNameNote.AABB_BROADCAST -> R.string.driver_name_aabb_broadcast
    DriverNameNote.D0_E0_BROADCAST -> R.string.driver_name_d0_e0_broadcast
    DriverNameNote.EIGHT_ELECTRODES -> R.string.driver_name_eight_electrodes
    DriverNameNote.MGB_PROTOCOL -> R.string.driver_name_mgb_protocol
    DriverNameNote.CONNECTION -> R.string.driver_name_connection
    DriverNameNote.NEW_MODEL -> R.string.driver_name_new_model
    DriverNameNote.FIRST_GENERATION -> R.string.driver_name_first_generation
}

/** The brand, with "Generic" translated. */
@Composable
fun brandLabel(brand: String): String = if (brand == GENERIC_BRAND) stringResource(R.string.scale_brand_generic) else brand

@Composable
fun ScaleDriver.brandLabel(): String = brandLabel(brand)

/** The formula library name, with its generic words translated. */
@Composable
fun FormulaLib.title(): String = when (this) {
    FormulaLib.STANDARD_IMPEDANCE -> stringResource(R.string.formula_standard_impedance)
    FormulaLib.TRISA_RESISTANCE -> stringResource(R.string.formula_trisa_resistance)
    FormulaLib.WLA25 -> stringResource(R.string.formula_wla25)
    FormulaLib.ONE_BYONE_NEW -> stringResource(R.string.driver_name_new_model, "1byone")
    FormulaLib.KEEP_S3 -> stringResource(R.string.formula_keep_s3)
    else -> label
}

@StringRes
fun ConfigField.labelRes(): Int = when (key) {
    ConfigField.KEY_BINDKEY -> R.string.config_bind_key
    ConfigField.KEY_PIN -> R.string.config_pin
    ConfigField.KEY_SCALE_PROFILE -> R.string.config_scale_profile
    else -> R.string.config_user_slot
}

@Composable
fun ConfigField.help(): String = when (key) {
    ConfigField.KEY_BINDKEY -> stringResource(R.string.config_bind_key_help)
    ConfigField.KEY_PIN -> stringResource(R.string.config_pin_help)
    ConfigField.KEY_SCALE_PROFILE -> stringResource(R.string.config_scale_profile_help)
    else -> stringResource(R.string.config_user_slot_help, minSlot)
}

fun ConfigField.errorText(error: ConfigError): UiText = when (error) {
    ConfigError.REQUIRED -> UiText.res(R.string.config_error_required)
    ConfigError.HEX_KEY_32 -> UiText.res(R.string.config_error_hex_key)
    ConfigError.USER_SLOT_RANGE -> UiText.res(R.string.config_error_user_slot, Fmt.count(minSlot.toLong()), Fmt.count(maxSlot.toLong()))
    ConfigError.PIN_DIGITS -> UiText.res(R.string.config_error_pin)
}
