package com.enjoyyourtime.globalfitness.scale

/**
 * A user-facing message from the scale layer (drivers, GATT sessions, the monitor). Pure data: the
 * UI turns [kind] into a localized string formatted with [args] (numbers, model or scale names, or
 * a technical detail that is shown as is).
 */
data class ScaleNotice(val kind: Kind, val args: List<Any> = emptyList()) {
    enum class Kind {
        // Weighing and scale state
        STEP_ON,
        STEP_ON_FOR_REFERENCE,
        LOW_BATTERY,
        MEASUREMENT_FAILED_FEET,
        FAT_MEASUREMENT_FAILED,
        INVALID_RECORD,

        // Users on the scale
        USER_CREATED_STEP_ON,
        SCALE_FULL,
        USER_CREATE_FAILED,
        USER_REJECTED_RETRY_LATER,
        NO_FREE_USERS_CHOOSE_SLOT,
        NO_FREE_USERS_SET_PIN,

        /** args: user index, PIN. */
        USER_REGISTERED,

        /** args: result code. */
        USER_REGISTRATION_REFUSED,
        PIN_REJECTED,

        /** args: user index. */
        PIN_MISSING,

        /** args: user index. */
        PIN_SHOWN_ON_SCALE,

        // Pairing
        PAIRED,
        NOT_PAIRED_PAIRING_MODE,
        TRISA_PAIRED_STEP_ON_AGAIN,
        TRISA_NOT_PAIRED,
        OMRON_UNKNOWN_MODEL,
        OMRON_HOLD_PAIRING_BUTTON,
        OMRON_NOT_IN_PAIRING_MODE,
        OMRON_PAIRED,
        OMRON_PAIRING_FAILED,
        OMRON_SESSION_REJECTED,
        OMRON_MEMORY_READ_ERROR,
        OMRON_NO_NEW_MEASUREMENTS,

        /** args: count (Int). */
        OMRON_MEASUREMENTS_READ,
        OMRON_STOPPED_RESPONDING,
        EUFY_AUTH_REJECTED,

        // Bind keys. args: model name.
        BIND_KEY_NEEDED,
        BIND_KEY_WRONG,

        // Connection
        MISSING_NEARBY_PERMISSION,
        BLUETOOTH_UNAVAILABLE,
        BLUETOOTH_OFF,

        /** args: address. */
        INVALID_ADDRESS,
        CONNECT_NOT_STARTED,
        NOT_CONNECTED,
        SERVICE_DISCOVERY_FAILED,

        /** args: GATT status. */
        SERVICES_READ_FAILED,
        SCALE_STOPPED_RESPONDING,
        CONNECTION_CLOSED,
        SCALE_DISCONNECTED,
        CONNECTION_LOST_OUT_OF_RANGE,
        SCALE_CLOSED_CONNECTION,
        PHONE_CANCELLED_CONNECTION,
        CONNECTION_NOT_ESTABLISHED,
        GATT_ERROR_133,

        /** args: GATT status. */
        GATT_ERROR,
        CONNECT_TIMEOUT,
        CONNECT_FAILED,

        /** args: technical detail. */
        CONNECT_FAILED_DETAIL,

        /** args: technical detail. */
        PROTOCOL_ERROR,
        NO_NEW_DATA,

        // Monitor. args: scale name.
        AUTO_CONFIGURED,
        AUTO_CONFIGURED_NEEDS_SETUP,
        ADDED_STEP_ON,
        ADDED_NEEDS_SETUP,

        /** args: technical detail. */
        SAVE_FAILED,
    }
}
