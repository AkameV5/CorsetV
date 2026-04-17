package com.akamev.corset.presentation.auth

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
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun updateEmail(value: String) = _uiState.update { it.copy(email = value, errorMessage = null) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun updateConfirmPassword(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }
    fun updateFirstName(value: String) = _uiState.update { it.copy(firstName = value, errorMessage = null) }
    fun updateLastName(value: String) = _uiState.update { it.copy(lastName = value, errorMessage = null) }

    suspend fun resolveStartDestination(): String {
        val user = authRepository.currentUser() ?: return CorsetDestination.Login.route
        if (!user.isEmailVerified) return CorsetDestination.Verification.route
        return if (userRepository.hasCompletedProfile(user.uid)) {
            CorsetDestination.Profile.route
        } else {
            CorsetDestination.SetupProfile.route
        }
    }

    suspend fun login(): String? {
        val state = uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Заполните все поля.") }
            return null
        }

        return runAction {
            authRepository.login(state.email.trim(), state.password.trim())
            resolveStartDestination()
        }
    }

    suspend fun register(): Boolean {
        val state = uiState.value
        if (state.email.isBlank() || state.password.isBlank() || state.confirmPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Заполните все поля.") }
            return false
        }
        if (state.password != state.confirmPassword) {
            _uiState.update { it.copy(errorMessage = "Пароли не совпадают.") }
            return false
        }
        if (state.password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Минимальная длина пароля — 6 символов.") }
            return false
        }

        return runAction {
            authRepository.register(state.email.trim(), state.password.trim())
            _uiState.update {
                it.copy(infoMessage = "Аккаунт создан. Письмо для подтверждения уже отправлено.")
            }
            true
        } ?: false
    }

    suspend fun resendVerification() {
        runAction {
            val email = authRepository.resendVerification()
            _uiState.update {
                it.copy(infoMessage = "Письмо отправлено повторно на ${email.orEmpty()}.")
            }
        }
    }

    suspend fun checkVerification(): String? {
        val user = authRepository.reloadCurrentUser() ?: return CorsetDestination.Login.route
        return if (user.isEmailVerified) {
            if (userRepository.hasCompletedProfile(user.uid)) {
                CorsetDestination.Profile.route
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
            _uiState.update { it.copy(errorMessage = "Введите имя и фамилию.") }
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
            _uiState.update { it.copy(errorMessage = error.message ?: "Что-то пошло не так.") }
            null
        } finally {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    authRepository = app.container.authRepository,
                    userRepository = app.container.userRepository,
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
                title = "CorsetV",
                subtitle = "Персональный контроль осанки, подключение корсета и рекомендации в одном приложении.",
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

    AuthScaffold(title = "Вход", showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = "Добро пожаловать",
                subtitle = "Войди, чтобы открыть мониторинг, историю и персональные рекомендации.",
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
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        label = { Text("Пароль") },
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
                            Text("Войти")
                        }
                    }
                    TextButton(onClick = onOpenRegister, modifier = Modifier.fillMaxWidth()) {
                        Text("Создать аккаунт")
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

    AuthScaffold(title = "Регистрация", showBack = true, onBack = onBack) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = "Создание аккаунта",
                subtitle = "Открой доступ к профилю, устройству и мониторингу за пару шагов.",
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
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::updatePassword,
                        label = { Text("Пароль") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = state.confirmPassword,
                        onValueChange = viewModel::updateConfirmPassword,
                        label = { Text("Повтори пароль") },
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
                            Text("Создать аккаунт")
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

    AuthScaffold(title = "Подтверждение", showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = "Подтверждение почты",
                subtitle = "Подтверди ${viewModel.currentEmail()} и продолжим настройку профиля.",
            )
            Spacer(modifier = Modifier.height(20.dp))
            GlassCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Мы проверяем статус автоматически каждые пару секунд. Если письмо затерялось, отправим его еще раз.",
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
                        Text("Проверить сейчас")
                    }
                    TextButton(
                        onClick = { viewModel.viewModelScope.launch { viewModel.resendVerification() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Отправить письмо еще раз")
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

    AuthScaffold(title = "Профиль", showBack = false, onBack = {}) { contentPadding ->
        AuthFormContainer(contentPadding) {
            HeroHeader(
                title = "Заполним профиль",
                subtitle = "Укажи имя и фамилию, чтобы завершить настройку аккаунта.",
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
                        label = { Text("Имя") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.lastName,
                        onValueChange = viewModel::updateLastName,
                        label = { Text("Фамилия") },
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
                            Text("Сохранить профиль")
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
