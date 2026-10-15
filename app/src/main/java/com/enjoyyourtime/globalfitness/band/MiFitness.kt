package com.enjoyyourtime.globalfitness.band

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The vendor app that normally owns the band. Only used to point the user at its system settings page
 * (to force-stop it themselves); this app never stops or changes it.
 */
object MiFitness {
    /** Mi Fitness (formerly Xiaomi Wear). */
    const val PACKAGE = "com.xiaomi.wearable"

    /** Mi Health, the Chinese-market variant (listed by Gadgetbridge's docs). */
    const val PACKAGE_MI_HEALTH = "com.mi.health"

    private val PACKAGES = listOf(PACKAGE, PACKAGE_MI_HEALTH)

    /** The installed variant, preferring Mi Fitness (visible through the manifest's `<queries>`). */
    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { isInstalled(context, it) }

    fun settingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

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
