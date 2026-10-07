package com.akamev.corset.presentation.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.akamev.corset.R

sealed class CorsetDestination(
    val route: String,
    @StringRes val titleRes: Int,
) {
    data object Splash : CorsetDestination("splash", R.string.app_name)
    data object Login : CorsetDestination("login", R.string.nav_login)
    data object Register : CorsetDestination("register", R.string.nav_register)
    data object Verification : CorsetDestination("verification", R.string.nav_verification)
    data object SetupProfile : CorsetDestination("setup_profile", R.string.nav_profile)
    data object Home : CorsetDestination("home", R.string.nav_home)
    data object Coach : CorsetDestination("coach", R.string.nav_coach)
    data object Chat : CorsetDestination("chat", R.string.nav_chat)
    data object Profile : CorsetDestination("profile", R.string.nav_profile)
    data object AddDevice : CorsetDestination("add_device", R.string.nav_device)
    data object Connecting : CorsetDestination("connecting?deviceAddress={deviceAddress}", R.string.nav_connecting) {
        const val baseRoute = "connecting"
        const val deviceAddressArg = "deviceAddress"

        fun createRoute(deviceAddress: String? = null): String {
            return if (deviceAddress.isNullOrBlank()) {
                baseRoute
            } else {
                "$baseRoute?$deviceAddressArg=${Uri.encode(deviceAddress)}"
            }
        }
    }
}

data class BottomDestination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
)

val bottomDestinations = listOf(
    BottomDestination(CorsetDestination.Home.route, R.string.nav_home, Icons.Filled.Home),
    BottomDestination(CorsetDestination.Coach.route, R.string.nav_coach, Icons.Filled.AutoGraph),
    BottomDestination(CorsetDestination.Chat.route, R.string.nav_chat, Icons.AutoMirrored.Filled.Chat),
    BottomDestination(CorsetDestination.Profile.route, R.string.nav_profile, Icons.Filled.Person),
)
