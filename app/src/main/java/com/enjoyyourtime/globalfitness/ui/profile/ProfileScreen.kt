package com.enjoyyourtime.globalfitness.ui.profile

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enjoyyourtime.globalfitness.BuildConfig
import com.enjoyyourtime.globalfitness.health.HcAvailability
import com.enjoyyourtime.globalfitness.ui.Fmt
import com.enjoyyourtime.globalfitness.ui.MainViewModel
import com.enjoyyourtime.globalfitness.ui.components.AppCard
import com.enjoyyourtime.globalfitness.ui.components.AppIcons
import com.enjoyyourtime.globalfitness.ui.components.SectionTitle
import com.enjoyyourtime.globalfitness.ui.components.TabPage
import com.enjoyyourtime.globalfitness.ui.health.HealthExportCard
import com.enjoyyourtime.globalfitness.ui.health.HealthViewModel
import java.time.LocalDate

/**
 * "Perfil": the single profile, the optional cloud account, ads / premium, data export and
 * import, the Health Connect export switch and "Acerca de".
 */
@Composable
fun ProfileScreen(
    vm: MainViewModel,
    healthVm: HealthViewModel,
    onConnectHealth: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenRemoveAds: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val user by vm.accountUser.collectAsStateWithLifecycle()
    val cloud by vm.cloudStatus.collectAsStateWithLifecycle()
    val premium by vm.premium.collectAsStateWithLifecycle()
    val privacyOptions by vm.adPrivacyOptionsRequired.collectAsStateWithLifecycle()
    val health by healthVm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var editing by rememberSaveable { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(context.contentResolver, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importCsv(context.contentResolver, it) }
    }
    val profile = state.profile ?: return

    TabPage(title = "Perfil") {
        item(key = "profile") {
            AppCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                profile.name.trim().take(1).uppercase(Fmt.LOCALE),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge)
                            val count = state.entries.size
                            Text(
                                if (count == 1) "1 medición" else "$count mediciones",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    val details = buildList {
                        add(profile.sex.label)
                        add("${profile.ageAt(System.currentTimeMillis())} años")
                        add("${Fmt.num(profile.heightCm, if (profile.heightCm % 1.0 == 0.0) 0 else 1)} cm")
                        add(profile.targetWeightKg?.let { "objetivo ${Fmt.num(it, 1)} kg" } ?: "sin peso objetivo")
                    }
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { editing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Editar perfil")
                    }
                }
            }
        }

        item(key = "account-title") { SectionTitle("Cuenta y anuncios") }
        item(key = "account") {
            AppCard {
                val signedIn = user
                val paused = cloud.conflict != null || cloud.deletionPending
                SettingRow(
                    icon = if (paused) Icons.Filled.Warning else AppIcons.Cloud,
                    iconTint = if (paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    title = "Cuenta y copia en la nube",
                    subtitle = when {
                        signedIn == null -> "Sin cuenta: tus datos solo están en este teléfono"
                        paused -> "${signedIn.email ?: "Conectada"} · sincronización en pausa, toca para elegir"
                        cloud.error != null -> "${signedIn.email ?: "Conectada"} · error de sincronización"
                        cloud.lastSyncAt != null -> "${signedIn.email ?: "Conectada"} · ${Fmt.relative(cloud.lastSyncAt!!)}"
                        else -> signedIn.email ?: "Conectada"
                    },
                    onClick = onOpenAccount,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                if (premium) {
                    SettingRow(
                        icon = Icons.Filled.CheckCircle,
                        title = "Premium activo",
                        subtitle = "Sin anuncios. ¡Gracias por tu apoyo!",
                        onClick = null,
                    )
                } else {
                    SettingRow(
                        icon = Icons.Filled.Star,
                        title = "Quitar anuncios",
                        subtitle = "Pago único, sin suscripción",
                        onClick = onOpenRemoveAds,
                    )
                }
                if (privacyOptions) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    SettingRow(
                        icon = Icons.Filled.Lock,
                        title = "Opciones de privacidad de anuncios",
                        subtitle = "Revisa o cambia tu consentimiento",
                        onClick = { activity?.let(vm::showAdPrivacyOptions) },
                    )
                }
            }
        }

        item(key = "data-title") { SectionTitle("Datos") }
        item(key = "data") {
            AppCard {
                SettingRow(
                    icon = AppIcons.Upload,
                    title = "Exportar CSV",
                    subtitle = "Todas las mediciones con sus métricas (separador «;», decimales con coma)",
                    onClick = { exportLauncher.launch("globalfitness-${LocalDate.now()}.csv") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Download,
                    title = "Importar CSV",
                    subtitle = "Se omiten las mediciones duplicadas",
                    onClick = {
                        importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream"))
                    },
                )
            }
        }
        if (health.availability == HcAvailability.AVAILABLE) {
            item(key = "hc") {
                HealthExportCard(
                    state = health,
                    onToggle = healthVm::setExportEnabled,
                    onExportHistory = healthVm::exportHistory,
                    onRequestPermissions = onConnectHealth,
                )
            }
        }

        item(key = "about-title") { SectionTitle("Acerca de") }
        item(key = "about") {
            AppCard {
                SettingRow(
                    icon = Icons.Filled.Info,
                    title = "GlobalFitness ${BuildConfig.VERSION_NAME}",
                    subtitle = "Versión ${BuildConfig.VERSION_CODE}" + if (BuildConfig.DEBUG) " · depuración" else "",
                    onClick = null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = Icons.Filled.Lock,
                    title = "Política de privacidad",
                    subtitle = "Borrador pendiente de revisión legal",
                    onClick = onOpenPrivacyPolicy,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                SettingRow(
                    icon = AppIcons.Logo,
                    title = "Código abierto",
                    subtitle = "Protocolos de openScale (GPL-3.0) y Gadgetbridge (AGPL-3.0), bibliotecas",
                    onClick = onOpenLicenses,
                )
            }
        }
    }

    if (editing) {
        ProfileDialog(
            initial = profile,
            onSubmit = {
                vm.updateProfile(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = iconTint) },
        trailingContent = if (onClick != null) {
            { Icon(AppIcons.ChevronRight, contentDescription = null) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
