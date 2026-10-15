import androidx.compose.ui.Modifier
-----
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
=====
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
-----
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ble.GattSessionInfo
=====
import com.enjoyyourtime.globalfitness.ui.components.AppCard
-----
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.devices.scanErrorRes
import com.enjoyyourtime.globalfitness.ui.devices.text
=====
/** How long a failed GATT session stays on Hoy. */
-----
/** How long a failed GATT session stays on Today. */
=====
/** The GATT session worth showing on Hoy: one in progress, else a recent failure. */
-----
/** The GATT session worth showing on Today: one in progress, else a recent failure. */
=====
                ScanStatus.NoBluetoothLe -> Problem(
                    title = "Bluetooth LE no disponible",
                    text = "Este teléfono no tiene Bluetooth de baja energía, necesario para leer la báscula.",
                )
                ScanStatus.MissingPermission -> Problem(
                    title = "Falta el permiso de Bluetooth",
                    text = "Para leer tu báscula y tu pulsera, GlobalFitness necesita el permiso de dispositivos cercanos " +
                        "(en Android 11 o anterior, el de ubicación). No se usa ni guarda tu ubicación.",
                    actionLabel = if (access.permanentlyDenied) "Abrir ajustes" else "Conceder permiso",
                    onAction = if (access.permanentlyDenied) access.openAppSettings else access.requestPermissions,
                )
                ScanStatus.BluetoothOff -> Problem(
                    title = "Bluetooth desactivado",
                    text = "Activa el Bluetooth para detectar la báscula.",
                    actionLabel = "Activar Bluetooth",
                    onAction = access.enableBluetooth,
                )
                is ScanStatus.Failed -> Problem(
                    title = "Error al buscar la báscula",
                    text = status.message,
                    actionLabel = "Reintentar",
-----
                ScanStatus.NoBluetoothLe -> Problem(
                    title = stringResource(R.string.live_no_ble_title),
                    text = stringResource(R.string.live_no_ble_text),
                )
                ScanStatus.MissingPermission -> Problem(
                    title = stringResource(R.string.live_permission_title),
                    text = stringResource(R.string.live_permission_text),
                    actionLabel = stringResource(if (access.permanentlyDenied) R.string.action_open_settings else R.string.action_grant_permission),
                    onAction = if (access.permanentlyDenied) access.openAppSettings else access.requestPermissions,
                )
                ScanStatus.BluetoothOff -> Problem(
                    title = stringResource(R.string.live_bluetooth_off_title),
                    text = stringResource(R.string.live_bluetooth_off_text),
                    actionLabel = stringResource(R.string.action_turn_on_bluetooth),
                    onAction = access.enableBluetooth,
                )
                is ScanStatus.Failed -> Problem(
                    title = stringResource(R.string.live_scan_error_title),
                    text = stringResource(scanErrorRes(status.code), status.code),
                    actionLabel = stringResource(R.string.action_retry),
=====
                        Problem(
                            title = "Ubicación desactivada",
                            text = "En Android 11 o anterior el sistema no entrega anuncios Bluetooth si la ubicación está apagada.",
                            actionLabel = "Activar ubicación",
-----
                        Problem(
                            title = stringResource(R.string.live_location_off_title),
                            text = stringResource(R.string.live_location_off_text),
                            actionLabel = stringResource(R.string.action_turn_on_location),
=====
            is LiveScale.Connecting -> Busy("Conectando con ${state.scaleName}…", "No te bajes de la báscula.")
            is LiveScale.Measuring -> WeightLine("Midiendo", state.weightKg, "Quédate quieto.")
            is LiveScale.Stabilized -> WeightLine("Estable · midiendo la composición…", state.weightKg, "No te bajes todavía.")
            is LiveScale.SaveFailed -> Problem(
                title = "No se pudo guardar ${Fmt.kg(state.weightKg)}",
                text = state.message,
            )
            is LiveScale.Saved -> {
                val title = if (state.alreadyStored) "Medición ya guardada" else "Medición guardada"
                val sub = if (state.impedance != null) "con impedancia (${state.impedance} Ω)" else "sin impedancia (¿calcetines o pies secos?)"
-----
            is LiveScale.Connecting -> Busy(stringResource(R.string.live_connecting, state.scaleName), stringResource(R.string.live_stay_on))
            is LiveScale.Measuring -> WeightLine(stringResource(R.string.live_measuring), state.weightKg, stringResource(R.string.live_stand_still))
            is LiveScale.Stabilized -> WeightLine(
                stringResource(R.string.live_stable_measuring),
                state.weightKg,
                stringResource(R.string.live_dont_step_off_yet),
            )
            is LiveScale.SaveFailed -> Problem(
                title = stringResource(R.string.live_save_failed, Fmt.kg(state.weightKg)),
                text = state.notice.text(),
            )
            is LiveScale.Saved -> {
                val title = stringResource(if (state.alreadyStored) R.string.live_already_saved else R.string.msg_measurement_saved)
                val sub = if (state.impedance != null) {
                    stringResource(R.string.live_with_impedance, stringResource(R.string.value_ohm, Fmt.count(state.impedance.toLong())))
                } else {
                    stringResource(R.string.live_without_impedance)
                }
=====
        SessionStatus.CONNECTING -> Busy("Conectando con ${session.scaleName}…", "No te bajes de la báscula.")
        SessionStatus.CONNECTED -> Busy("${session.scaleName}: conectada", "Esperando la medición…")
        SessionStatus.MEASURING -> session.weightKg?.let { WeightLine("Midiendo · ${session.scaleName}", it, "Quédate quieto.") }
            ?: Busy("${session.scaleName}: midiendo…", null)
        SessionStatus.FAILED -> Problem(
            title = "No se pudo leer ${session.scaleName}",
            text = session.detail ?: "Vuelve a subirte a la báscula. Si se repite, revisa su configuración en Dispositivos.",
        )
-----
        SessionStatus.CONNECTING -> Busy(stringResource(R.string.live_connecting, session.scaleName), stringResource(R.string.live_stay_on))
        SessionStatus.CONNECTED -> Busy(
            stringResource(R.string.live_session_connected, session.scaleName),
            stringResource(R.string.live_waiting_measurement),
        )
        SessionStatus.MEASURING -> session.weightKg?.let {
            WeightLine(stringResource(R.string.live_measuring_scale, session.scaleName), it, stringResource(R.string.live_stand_still))
        } ?: Busy(stringResource(R.string.live_session_measuring, session.scaleName), null)
        SessionStatus.FAILED -> Problem(
            title = stringResource(R.string.live_read_failed, session.scaleName),
            text = session.detail?.text() ?: stringResource(R.string.live_read_failed_hint),
        )
