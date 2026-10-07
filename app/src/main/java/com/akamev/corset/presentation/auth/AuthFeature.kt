package com.akamev.corset.presentation.auth

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.R
import com.akamev.corset.data.local.AppPreferences
import com.akamev.corset.data.repository.AuthRepository
import com.akamev.corset.data.repository.UserRepository
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import com.akamev.corset.presentation.navigation.CorsetDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

class AuthViewModel(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun updateEmail(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun updateConfirmPassword(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }
    fun updateFirstName(value: String) = _uiState.update { it.copy(firstName = value, errorMessage = null) }
    fun updateLastName(value: String) = _uiState.update { it.copy(lastName = value, errorMessage = null) }

    suspend fun resolveStartDestination(): String {
        if (appPreferences.isGuestMode()) return CorsetDestination.Home.route
        val user = authRepository.currentUser() ?: return CorsetDestination.Login.route
        if (!user.isEmailVerified) return CorsetDestination.Verification.route
        return if (userRepository.hasCompletedProfile(user.uid)) {
            CorsetDestination.Home.route
        } else {
            CorsetDestination.SetupProfile.route
        }
    }

    fun continueAsGuest(): String {
        appPreferences.setGuestMode(true)
        return CorsetDestination.Home.route
    }

    suspend fun login(): String? {
        val state = uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.auth_fill_all_fields)) }
            return null
        }

        return runAction {
            appPreferences.setGuestMode(false)
            authRepository.login(state.email.trim(), state.password.trim())
            resolveStartDestination()
        }
    }

    suspend fun register(): Boolean {
        val state = uiState.value
        if (state.email.isBlank() || state.password.isBlank() || state.confirmPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.auth_fill_all_fields)) }
            return false
        }
        if (state.password != state.confirmPassword) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.auth_passwords_mismatch)) }
            return false
        }
        if (state.password.length < 6) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.auth_password_too_short)) }
            return false
        }

        return runAction {
            appPreferences.setGuestMode(false)
            authRepository.register(state.email.trim(), state.password.trim())
            _uiState.update {
                it.copy(infoMessage = context.getString(R.string.auth_account_created_check_email))
            }
            true
        } ?: false
    }

    suspend fun resendVerification() {
        runAction {
            val email = authRepository.resendVerification()
            _uiState.update {
                it.copy(infoMessage = context.getString(R.string.auth_resend_verification_success, email.orEmpty()))
            }
        }
    }

    suspend fun checkVerification(): String? {
        val user = authRepository.reloadCurrentUser() ?: return CorsetDestination.Login.route
        return if (user.isEmailVerified) {
            if (userRepository.hasCompletedProfile(user.uid)) {
                CorsetDestination.Home.route
            } else {
                CorsetDestination.SetupProfile.route
            }
        } else {
            null
        }
    }

    suspend fun saveProfile(): Boolean {
        val state = uiState.value
        if (state.firstName.isBlank() || state.lastName.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.auth_fill_all_fields)) }
            return false
        }

        return runAction {
            userRepository.saveProfile(
                firstName = state.firstName.trim(),
                lastName = state.lastName.trim(),
            )
            true
        } ?: false
    }

    fun currentEmail(): String = authRepository.currentUser()?.email.orEmpty()

    private suspend fun <T> runAction(block: suspend () -> T): T? {
        return try {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            block()
        } catch (error: Exception) {
            _uiState.update { it.copy(errorMessage = error.message ?: context.getString(R.string.auth_default_error)) }
            null
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    context = app,
                    authRepository = app.container.authRepository,
                    userRepository = app.container.userRepository,
                    appPreferences = app.container.appPreferences,
                )
            }
        }
    }
}

@Composable
fun SplashScreen(
    viewModel: AuthViewModel,
    onResolved: (String) -> Unit,
) {
    CorsetBackground {
        LaunchedEffect(Unit) {
            onResolved(viewModel.resolveStartDestination())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            HeroHeader(
                title = stringResource(R.string.app_tagline),
                subtitle = stringResource(R.string.app_subtitle),
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onOpenRegister: () -> Unit,
    onSuccess: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    AuthScaffold(title = stringResource(R.string.nav_login), showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = stringResource(R.string.auth_login_title),
                subtitle = stringResource(R.string.auth_login_subtitle),
            )
            Spacer(modifier = Modifier.height(20.dp))
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::updateEmail,
                        label = { Text(stringResource(R.string.auth_email)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        label = { Text(stringResource(R.string.auth_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    )
                    AuthMessage(state)
                    Button(
                        onClick = {
                            keyboard?.hide()
                            viewModel.viewModelScope.launch {
                                viewModel.login()?.let(onSuccess)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        enabled = !state.isLoading,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.auth_sign_in_btn))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            keyboard?.hide()
                            val route = viewModel.continueAsGuest()
                            onSuccess(route)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = !state.isLoading,
                    ) {
                        Text(stringResource(R.string.auth_continue_guest_btn))
                    }
                    TextButton(onClick = onOpenRegister, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auth_create_account_btn))
                    }
                }
            }
        }
    }
}

@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuthScaffold(title = stringResource(R.string.nav_register), showBack = true, onBack = onBack) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = stringResource(R.string.auth_register_title),
                subtitle = stringResource(R.string.auth_register_subtitle),
            )
            Spacer(modifier = Modifier.height(20.dp))
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::updateEmail,
                        label = { Text(stringResource(R.string.auth_email)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        label = { Text(stringResource(R.string.auth_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::updateConfirmPassword,
                        label = { Text(stringResource(R.string.auth_confirm_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    AuthMessage(state)
                    Button(
                        onClick = {
                            viewModel.viewModelScope.launch {
                                if (viewModel.register()) onSuccess()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        enabled = !state.isLoading,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.auth_register_btn))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VerificationScreen(
    viewModel: AuthViewModel,
    onVerified: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            viewModel.checkVerification()?.let {
                onVerified(it)
                break
            }
        }
    }

    AuthScaffold(title = stringResource(R.string.nav_verification), showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = stringResource(R.string.auth_email_verification_title),
                subtitle = stringResource(R.string.auth_email_verification_subtitle, viewModel.currentEmail()),
            )
            Spacer(modifier = Modifier.height(20.dp))
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.auth_email_not_verified),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    AuthMessage(state)
                    Button(
                        onClick = {
                            viewModel.viewModelScope.launch {
                                viewModel.checkVerification()?.let(onVerified)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_check_verification_btn))
                    }
                    TextButton(
                        onClick = { viewModel.viewModelScope.launch { viewModel.resendVerification() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_resend_verification_btn))
                    }
                }
            }
        }
    }
}

@Composable
fun SetupProfileScreen(
    viewModel: AuthViewModel,
    onProfileReady: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuthScaffold(title = stringResource(R.string.nav_profile), showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = stringResource(R.string.auth_setup_profile_title),
                subtitle = stringResource(R.string.auth_setup_profile_subtitle),
            )
            Spacer(modifier = Modifier.height(20.dp))
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = state.firstName,
                        onValueChange = viewModel::updateFirstName,
                        label = { Text(stringResource(R.string.auth_first_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.lastName,
                        onValueChange = viewModel::updateLastName,
                        label = { Text(stringResource(R.string.auth_last_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AuthMessage(state)
                    Button(
                        onClick = {
                            viewModel.viewModelScope.launch {
                                if (viewModel.saveProfile()) onProfileReady()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        enabled = !state.isLoading,
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.auth_save_profile_btn))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScaffold(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        CorsetBackground {
            content(paddingValues)
        }
    }
}

@Composable
private fun AuthFormContainer(
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        content()
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AuthMessage(state: AuthUiState) {
    val errorMessage = state.errorMessage
    val infoMessage = state.infoMessage
    when {
        !errorMessage.isNullOrBlank() -> Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )

        !infoMessage.isNullOrBlank() -> Text(
            text = infoMessage,
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
