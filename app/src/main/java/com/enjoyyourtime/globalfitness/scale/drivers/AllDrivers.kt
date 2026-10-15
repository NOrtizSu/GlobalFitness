package com.enjoyyourtime.globalfitness.scale.drivers

import com.enjoyyourtime.globalfitness.scale.ScaleDriver
import com.enjoyyourtime.globalfitness.scale.ScaleRegistry

/**
 * Every supported driver. Order is the tie-breaker between equally confident matches, so
 * specific drivers come before generic ones (same idea as openScale's handler factory).
 */
object AllDrivers {
    fun create(): List<ScaleDriver> = listOf(
        MiScale2Driver(),
        XiaomiS400Driver(),
        MiScale1Driver(),
        AndUC352Driver(),
        BeurerBF450Driver(),
        TrisaBodyAnalyzeDriver(),
        SanitasSbf72Driver(),
        StandardBeurerSanitasDriver(),
        SoehnleDriver(),
        MedisanaBs44xDriver(),
        BeurerSanitasDriver(),
        HealthKeep280Driver(),
        TaylorBIADriver(),
        RyFitDriver(),
        CultSmartScaleProDriver(),
        RealmeSmartScaleDriver(),
        RunstarR6Driver(),
        RunstarR5Driver(),
        RelaxmedicDriver(),
        RobiS9Driver(),
        VitafitVT701Driver(),
        EEBBLDriver(),
        FitTrackDaraDriver(),
        DrTrustSSW532Driver(),
        MgbDriver(),
        InlifeDriver(),
        HesleyDriver(),
        ExingtechY1Driver(),
        EbelterBodyFatB2Driver(),
        HumeDara2Driver(),
        ExcelvanCF36xDriver(),
        DigooDGSO38HDriver(),
        AiLinkBroadcastDriver(),
        QnBroadcastDriver(),
        OkOkDriver(),
        XiaomiS800Driver(),
        YunmaiXDriver(),
        EtekcityFit8SDriver(),
        EufyC20Driver(),
        AaaBroadcastDriver(),
        PicoocMiniLiteDriver(),
        SinocareDriver(),
        // Generic fallbacks last.
        ScaleupDriver(),
        StandardWeightProfileDriver(),
    )

    fun registry(): ScaleRegistry = ScaleRegistry(create())
}
