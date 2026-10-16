package com.enjoyyourtime.globalfitness.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.enjoyyourtime.globalfitness.R
import com.enjoyyourtime.globalfitness.ui.components.AppIcons

/** The five sections of the bottom navigation bar. */
enum class TopLevel(val route: String, @param:StringRes val label: Int, val selectedIcon: ImageVector, val icon: ImageVector) {
    TODAY("hoy", R.string.nav_today, Icons.Filled.Home, Icons.Outlined.Home),
    BODY("cuerpo", R.string.nav_body, AppIcons.Scale, AppIcons.Scale),
    ACTIVITY("actividad", R.string.nav_activity, Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
    DEVICES("dispositivos", R.string.nav_devices, AppIcons.Bluetooth, AppIcons.Bluetooth),
    PROFILE("perfil", R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person),
    ;

    companion object {
        fun of(route: String?): TopLevel? = entries.firstOrNull { it.route == route }
    }
}

/** Full-screen pages opened from a section (no bottom bar, no ads). */
object Routes {
    const val ACCOUNT = "cuenta"
    const val REMOVE_ADS = "quitar-anuncios"
    const val PRIVACY_POLICY = "privacidad"
    const val LICENSES = "codigo-abierto"
}
