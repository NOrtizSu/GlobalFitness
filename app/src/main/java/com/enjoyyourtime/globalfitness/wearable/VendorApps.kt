package com.enjoyyourtime.globalfitness.wearable

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** A vendor companion app (only used to point the user at its settings page; never changed by this app). */
data class VendorApp(val label: String, val packageName: String)

/** The vendor apps that normally own each family's devices (visible through the manifest's `<queries>`). */
object VendorApps {
    private val MI_FITNESS = VendorApp("Mi Fitness", "com.xiaomi.wearable")
    private val MI_HEALTH = VendorApp("Mi Health", "com.mi.health")
    private val ZEPP_LIFE = VendorApp("Zepp Life", "com.xiaomi.hm.health")
    private val ZEPP = VendorApp("Zepp", "com.huami.watch.hmwatchmanager")

    fun candidates(family: WearableFamily): List<VendorApp> = when (family) {
        WearableFamily.XIAOMI -> listOf(MI_FITNESS, MI_HEALTH)
        WearableFamily.HUAMI -> listOf(ZEPP_LIFE, ZEPP)
        WearableFamily.ZEPP_OS -> listOf(ZEPP, ZEPP_LIFE)
        else -> emptyList()
    }

    /** The first installed vendor app of [family], or null. */
    fun installed(context: Context, family: WearableFamily): VendorApp? =
        candidates(family).firstOrNull { isInstalled(context, it.packageName) }

    fun settingsIntent(app: VendorApp): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))

    private fun isInstalled(context: Context, packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
