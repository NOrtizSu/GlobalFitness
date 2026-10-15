package com.enjoyyourtime.globalfitness.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.enjoyyourtime.globalfitness.ble.ScaleScanner

/** Actions to fix scanning preconditions (permissions, Bluetooth, location). */
class BleAccess(
    val requestPermissions: () -> Unit,
    val enableBluetooth: () -> Unit,
    val openAppSettings: () -> Unit,
    val openLocationSettings: () -> Unit,
    /** True after the user denied and Android will no longer show the system dialog. */
    val permanentlyDenied: Boolean,
)

/**
 * @param rationaleSeen persisted flag: Android asked for a rationale at least once. Only after
 * that can a silent denial (no rationale) be read as "denied permanently".
 */
@Composable
fun rememberBleAccess(
    rationaleSeen: Boolean,
    onRationaleSeen: () -> Unit,
    onChanged: () -> Unit,
): BleAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var deniedForever by rememberSaveable { mutableStateOf(false) }

    fun rationaleRequested(): Boolean =
        activity != null && ScaleScanner.requiredPermissions().any { activity.shouldShowRequestPermissionRationale(it) }

    LaunchedEffect(Unit) {
        if (rationaleRequested()) onRationaleSeen()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = ScaleScanner.hasScanPermission(context)
        val rationaleNow = rationaleRequested()
        if (rationaleNow) onRationaleSeen()
        deniedForever = !granted && !rationaleNow && rationaleSeen
        onChanged()
    }
    val resultLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ScaleScanner.hasScanPermission(context)) deniedForever = false
        onChanged()
    }

    return remember(deniedForever) {
        BleAccess(
            requestPermissions = { permissionLauncher.launch(ScaleScanner.requiredPermissions()) },
            enableBluetooth = {
                if (ScaleScanner.hasConnectPermission(context)) {
                    runCatching { resultLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { runCatching { resultLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                } else {
                    permissionLauncher.launch(ScaleScanner.requiredPermissions())
                }
            },
            openAppSettings = {
                runCatching {
                    resultLauncher.launch(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                }
            },
            openLocationSettings = {
                runCatching { resultLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            },
            permanentlyDenied = deniedForever,
        )
    }
}
