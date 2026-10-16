// Protocol ported from Gadgetbridge (AGPL-3.0), https://codeberg.org/Freeyourgadget/Gadgetbridge
package com.enjoyyourtime.globalfitness.wearable.xiaomi

import com.enjoyyourtime.globalfitness.scale.MatchConfidence
import com.enjoyyourtime.globalfitness.wearable.AuthKeyKind
import com.enjoyyourtime.globalfitness.wearable.KeyHelp
import com.enjoyyourtime.globalfitness.wearable.NamePatternDriver
import com.enjoyyourtime.globalfitness.wearable.WearableFamily
import com.enjoyyourtime.globalfitness.wearable.WearableFeature
import com.enjoyyourtime.globalfitness.wearable.WearableSession
import com.enjoyyourtime.globalfitness.wearable.WearableSessionEnv
import com.enjoyyourtime.globalfitness.wearable.WearableTransport

/**
 * One Xiaomi / Redmi protobuf device, from Gadgetbridge's XiaomiCoordinator subclasses
 * (devices/xiaomi/watches): name pattern, transport (getConnectionType; BOTH defaults to BLE as
 * XiaomiSupport does) and key kind (plaintext BLE devices authenticate with the numeric user id).
 */
data class XiaomiModel(
    val id: String,
    val displayName: String,
    val brand: String,
    val pattern: Regex,
    val transport: WearableTransport,
    val keyKind: AuthKeyKind = AuthKeyKind.HEX_32,
    /** Anchor of the device on gadgetbridge.org/gadgets/wearables/xiaomi/. */
    val docDevice: String,
    val confidence: MatchConfidence = MatchConfidence.HIGH,
    /** False on devices whose CCM tag sometimes fails (Gadgetbridge checkDecryptionMac). */
    val checkDecryptionMac: Boolean = true,
    /** Wear OS watches need the companion device id in the auth nonce. */
    val usesDeviceId: Boolean = false,
    val keywords: List<String> = emptyList(),
)

object XiaomiModels {
    const val SERVER_DOC_URL = "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/"
    private const val DOC_PAGE = "https://gadgetbridge.org/gadgets/wearables/xiaomi/"
    const val VENDOR_APP = "Mi Fitness"

    fun docUrl(model: XiaomiModel): String = DOC_PAGE + "#device__" + model.docDevice

    private val SPP = WearableTransport.CLASSIC_SPP
    private val BLE = WearableTransport.BLE_GATT

    val ALL: List<XiaomiModel> = listOf(
        XiaomiModel("xiaomi_smart_band_10_pro", "Xiaomi Smart Band 10 Pro", "Xiaomi", Regex("^Xiaomi Smart Band 10 Pro [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_10_pro"),
        XiaomiModel("xiaomi_smart_band_10", "Xiaomi Smart Band 10", "Xiaomi", Regex("^Xiaomi Smart Band 10 [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_10"),
        XiaomiModel("xiaomi_smart_band_11", "Xiaomi Smart Band 11", "Xiaomi", Regex("^Xiaomi Smart Band 11 [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_11"),
        XiaomiModel("xiaomi_smart_band_9_pro", "Xiaomi Smart Band 9 Pro", "Xiaomi", Regex("^Xiaomi Smart Band 9 Pro [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_9_pro"),
        XiaomiModel("xiaomi_smart_band_9_active", "Xiaomi Smart Band 9 Active", "Xiaomi", Regex("^Xiaomi( Smart)? Band 9 Active [0-9A-F]{4}$"), BLE, docDevice = "xiaomi_mi_band_9_active"),
        XiaomiModel("xiaomi_smart_band_9", "Xiaomi Smart Band 9", "Xiaomi", Regex("^Xiaomi Smart Band 9 [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_9", keywords = listOf("mi band 9")),
        XiaomiModel("xiaomi_smart_band_8_pro", "Xiaomi Smart Band 8 Pro", "Xiaomi", Regex("^Xiaomi Smart Band 8 Pro [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_mi_band_8_pro"),
        XiaomiModel("xiaomi_smart_band_8_active", "Xiaomi Smart Band 8 Active", "Xiaomi", Regex("^Xiaomi( Smart)? Band 8 Active [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_mi_band_8_active"),
        XiaomiModel("xiaomi_smart_band_8", "Xiaomi Smart Band 8", "Xiaomi", Regex("^Xiaomi Smart Band 8 [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_mi_band_8", keywords = listOf("mi band 8")),
        XiaomiModel("xiaomi_smart_band_7_pro", "Xiaomi Smart Band 7 Pro", "Xiaomi", Regex("^Xiaomi Smart Band 7 Pro.*$"), BLE, docDevice = "xiaomi_mi_band_7_pro", keywords = listOf("mi band 7 pro")),
        XiaomiModel("xiaomi_mi_band_4c", "Mi Band 4C", "Xiaomi", Regex("^Mi Smart Band 4C_[0-9A-F]{4}$"), BLE, keyKind = AuthKeyKind.NUMERIC_USER_ID, docDevice = "xiaomi_mi_band_4c", keywords = listOf("redmi band")),
        XiaomiModel("xiaomi_mi_watch_color_sport", "Mi Watch Color Sport", "Xiaomi", Regex("^Mi ColorS [0-9A-Z]{4}$"), BLE, keyKind = AuthKeyKind.NUMERIC_USER_ID, docDevice = "xiaomi_mi_watch_color_sport"),
        XiaomiModel("xiaomi_mi_watch_lite", "Xiaomi Watch Lite", "Xiaomi", Regex("^Mi Watch Lite_[A-Z0-9]{4}$"), BLE, keyKind = AuthKeyKind.NUMERIC_USER_ID, docDevice = "xiaomi_mi_watch_lite", keywords = listOf("mi watch lite", "redmi watch")),
        XiaomiModel("redmi_smart_band_2", "Redmi Smart Band 2", "Redmi", Regex("^Redmi Smart Band 2 [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_smart_band_2"),
        XiaomiModel("redmi_smart_band_3", "Redmi Smart Band 3", "Redmi", Regex("^Redmi Smart Band 3 [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_smart_band_3"),
        XiaomiModel("redmi_smart_band_pro", "Redmi Smart Band Pro", "Redmi", Regex("^Redmi Band Pro [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_smart_band_pro", checkDecryptionMac = false),
        XiaomiModel("redmi_watch_2_lite", "Redmi Watch 2 Lite", "Redmi", Regex("^Redmi Watch 2 Lite [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_watch_2_lite"),
        XiaomiModel("redmi_watch_2", "Redmi Watch 2", "Redmi", Regex("^Redmi Watch 2 [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_watch_2"),
        XiaomiModel("redmi_watch_3_active", "Redmi Watch 3 Active", "Redmi", Regex("^Redmi Watch 3 Active [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_watch_3_active"),
        XiaomiModel("redmi_watch_3", "Redmi Watch 3", "Redmi", Regex("^Redmi Watch 3 [A-Z0-9]{4}$"), BLE, docDevice = "xiaomi_redmi_watch_3"),
        XiaomiModel("redmi_watch_4", "Redmi Watch 4", "Redmi", Regex("^Redmi Watch 4 [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_redmi_watch_4"),
        XiaomiModel("redmi_watch_5_active", "Redmi Watch 5 Active", "Redmi", Regex("^Redmi Watch 5 Active [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_redmi_watch_5_active"),
        XiaomiModel("redmi_watch_5_lite", "Redmi Watch 5 Lite", "Redmi", Regex("^Redmi Watch 5 Lite [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_redmi_watch_5_lite"),
        XiaomiModel("redmi_watch_5", "Redmi Watch 5", "Redmi", Regex("^Redmi Watch 5 [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_redmi_watch_5"),
        XiaomiModel("redmi_watch_6", "Redmi Watch 6", "Redmi", Regex("^Redmi Watch 6( NFC)? [0-9A-F]{4}$", RegexOption.IGNORE_CASE), SPP, docDevice = "xiaomi_redmi_watch_6"),
        // "Move" alone is too generic to be offered automatically: listed, but only suggested by hand.
        XiaomiModel("redmi_watch_move", "Redmi Watch Move", "Redmi", Regex("^Move$"), SPP, docDevice = "xiaomi_redmi_watch_move", confidence = MatchConfidence.MEDIUM),
        XiaomiModel("xiaomi_watch_5", "Xiaomi Watch 5", "Xiaomi", Regex("^Xiaomi Watch 5(?! (Pro|Lite))( .*)?$"), SPP, docDevice = "xiaomi_watch_5", usesDeviceId = true, keywords = listOf("wear os")),
        XiaomiModel("xiaomi_watch_s1_active", "Xiaomi Watch S1 Active", "Xiaomi", Regex("^XiaomiWatchS1Active [0-9A-Z]{4}$"), BLE, docDevice = "xiaomi_watch_s1_active"),
        XiaomiModel("xiaomi_watch_s1_pro", "Xiaomi Watch S1 Pro", "Xiaomi", Regex("^(Xiaomi Watch S1 Pro [0-9A-F]{4}|[Ll]61.*[0-9A-F]{4})$"), SPP, docDevice = "xiaomi_watch_s1_pro"),
        XiaomiModel("xiaomi_watch_s1", "Xiaomi Watch S1", "Xiaomi", Regex("^Xiaomi Watch S1 [0-9A-F]{4}$"), BLE, docDevice = "xiaomi_watch_s1"),
        XiaomiModel("xiaomi_watch_s3", "Xiaomi Watch S3", "Xiaomi", Regex("^Xiaomi Watch S3( eSIM)? [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_watch_s3"),
        XiaomiModel("xiaomi_watch_s4", "Xiaomi Watch S4", "Xiaomi", Regex("^Xiaomi Watch S4( eSIM)? [0-9A-F]{4}$"), SPP, docDevice = "xiaomi_watch_s4"),
    )

    /** Driver id of the Smart Band 9, the model of the original direct band mode. */
    const val BAND_9_ID = "xiaomi_smart_band_9"

    /** Every Xiaomi protobuf function Gadgetbridge implements on all of these models. */
    val FEATURES: Set<WearableFeature> = setOf(
        WearableFeature.BATTERY,
        WearableFeature.DEVICE_INFO,
        WearableFeature.DEVICE_STATE,
        WearableFeature.TIME_SYNC,
        WearableFeature.FIND_DEVICE,
        WearableFeature.LIVE_HEART_RATE,
        WearableFeature.LIVE_STEPS,
        WearableFeature.NOTIFICATIONS,
        WearableFeature.ALARMS,
    )

    fun drivers(): List<XiaomiDriver> = ALL.map { XiaomiDriver(it) }
}

/** Xiaomi protobuf driver for one [model]; SPP or BLE session by the model's transport. */
class XiaomiDriver(val model: XiaomiModel) : NamePatternDriver() {
    override val id: String = model.id
    override val displayName: String = model.displayName
    override val brand: String = model.brand
    override val family = WearableFamily.XIAOMI
    override val features = XiaomiModels.FEATURES
    override val transport: WearableTransport = model.transport
    override val authKey: AuthKeyKind = model.keyKind
    override val namePatterns = listOf(model.pattern)
    override val nameConfidence: MatchConfidence = model.confidence
    override val keywords: List<String> = model.keywords + listOf("xiaomi", "mi fitness")
    override val keyHelp = KeyHelp(model.keyKind, XiaomiModels.SERVER_DOC_URL, XiaomiModels.docUrl(model), XiaomiModels.VENDOR_APP)

    override fun createSession(env: WearableSessionEnv): WearableSession = when (model.transport) {
        WearableTransport.CLASSIC_SPP -> XiaomiSppWearableSession(env, model)
        else -> XiaomiBleWearableSession(env, model)
    }
}
