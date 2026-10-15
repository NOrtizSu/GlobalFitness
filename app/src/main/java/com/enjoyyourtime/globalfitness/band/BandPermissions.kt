package com.enjoyyourtime.globalfitness.band

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions for the direct band mode (declared in the manifest by the scale feature). */
object BandPermissions {
    /** Scanning plus connecting; on Android 11 and lower only location is a runtime permission. */
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Needed for bonded devices and RFCOMM (Android 12+; BLUETOOTH is a normal permission before). */
    fun hasConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
