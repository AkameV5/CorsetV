package com.akamev.corset.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.akamev.corset.CorsetApplication
import com.akamev.corset.presentation.auth.AuthViewModel
import com.akamev.corset.presentation.auth.LoginScreen
import com.akamev.corset.presentation.auth.RegisterScreen
import com.akamev.corset.presentation.auth.SetupProfileScreen
import com.akamev.corset.presentation.auth.SplashScreen
import com.akamev.corset.presentation.auth.VerificationScreen
import com.akamev.corset.presentation.chat.ChatScreen
import com.akamev.corset.presentation.chat.ChatViewModel
import com.akamev.corset.presentation.coach.CoachScreen
import com.akamev.corset.presentation.coach.CoachViewModel
import com.akamev.corset.presentation.device.AddDeviceScreen
import com.akamev.corset.presentation.device.ConnectingScreen
import com.akamev.corset.presentation.device.DeviceConnectionViewModel
import com.akamev.corset.presentation.home.HomeScreen
import com.akamev.corset.presentation.home.HomeViewModel
import com.akamev.corset.presentation.navigation.CorsetDestination
import com.akamev.corset.presentation.profile.ProfileScreen
import com.akamev.corset.presentation.profile.ProfileViewModel

@Composable
fun CorsetApp() {
    val context = LocalContext.current
    val app = context.applicationContext as CorsetApplication
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    NavHost(
        navController = navController,
        startDestination = CorsetDestination.Splash.route,
    ) {
        composable(CorsetDestination.Splash.route) {
            val viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AuthViewModel.factory(app))
            SplashScreen(
                viewModel = viewModel,
                onResolved = { route ->
                    navController.navigate(route) {
                        popUpTo(CorsetDestination.Splash.route) { inclusive = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Login.route) {
            val viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AuthViewModel.factory(app))
            LoginScreen(
                viewModel = viewModel,
                onOpenRegister = { navController.navigate(CorsetDestination.Register.route) },
                onSuccess = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Register.route) {
            val viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AuthViewModel.factory(app))
            RegisterScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onSuccess = {
                    navController.navigate(CorsetDestination.Verification.route) {
                        popUpTo(CorsetDestination.Login.route) { inclusive = false }
                    }
                },
            )
        }
        composable(CorsetDestination.Verification.route) {
            val viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AuthViewModel.factory(app))
            VerificationScreen(
                viewModel = viewModel,
                onVerified = { route ->
                    navController.navigate(route) {
                        popUpTo(CorsetDestination.Login.route) { inclusive = true }
                    }
                },
            )
        }
        composable(CorsetDestination.SetupProfile.route) {
            val viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = AuthViewModel.factory(app))
            SetupProfileScreen(
                viewModel = viewModel,
                onProfileReady = {
                    navController.navigate(CorsetDestination.Profile.route) {
                        popUpTo(CorsetDestination.Login.route) { inclusive = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Home.route) {
            val viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = HomeViewModel.factory(app))
            HomeScreen(
                viewModel = viewModel,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Coach.route) {
            val viewModel: CoachViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = CoachViewModel.factory(app))
            CoachScreen(
                viewModel = viewModel,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Chat.route) {
            val viewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = ChatViewModel.factory(app))
            ChatScreen(
                viewModel = viewModel,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    }
                },
            )
        }
        composable(CorsetDestination.Profile.route) {
            val viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = ProfileViewModel.factory(app))
            ProfileScreen(
                viewModel = viewModel,
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    }
                },
                onOpenAddDevice = { navController.navigate(CorsetDestination.AddDevice.route) },
                onLoggedOut = {
                    navController.navigate(CorsetDestination.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }
        composable(CorsetDestination.AddDevice.route) {
            AddDeviceScreen(
                onBack = { navController.popBackStack() },
                onStartConnecting = { navController.navigate(CorsetDestination.Connecting.route) },
            )
        }
        composable(CorsetDestination.Connecting.route) {
            val viewModel: DeviceConnectionViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(factory = DeviceConnectionViewModel.factory(app))
            ConnectingScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onConnected = {
                    navController.navigate(CorsetDestination.Profile.route) {
                        popUpTo(CorsetDestination.Profile.route) { inclusive = false }
                    }
                },
            )
        }
    }
}
