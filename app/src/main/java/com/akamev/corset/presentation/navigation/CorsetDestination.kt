package com.akamev.corset.presentation.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

sealed class CorsetDestination(
    val route: String,
    val title: String,
) {
    data object Splash : CorsetDestination("splash", "Splash")
    data object Login : CorsetDestination("login", "Вход")
    data object Register : CorsetDestination("register", "Регистрация")
    data object Verification : CorsetDestination("verification", "Подтверждение")
    data object SetupProfile : CorsetDestination("setup_profile", "Профиль")
    data object Home : CorsetDestination("home", "Сегодня")
    data object Coach : CorsetDestination("coach", "Мониторинг")
    data object Chat : CorsetDestination("chat", "AI-чат")
    data object Profile : CorsetDestination("profile", "Профиль")
    data object AddDevice : CorsetDestination("add_device", "Устройство")
    data object Connecting : CorsetDestination("connecting?deviceAddress={deviceAddress}", "Подключение") {
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
    val title: String,
    val icon: ImageVector,
)

val bottomDestinations = listOf(
    BottomDestination(CorsetDestination.Home.route, "Сегодня", Icons.Filled.Home),
    BottomDestination(CorsetDestination.Coach.route, "Мониторинг", Icons.Filled.AutoGraph),
    BottomDestination(CorsetDestination.Chat.route, "AI-чат", Icons.AutoMirrored.Filled.Chat),
    BottomDestination(CorsetDestination.Profile.route, "Профиль", Icons.Filled.Person),
)
