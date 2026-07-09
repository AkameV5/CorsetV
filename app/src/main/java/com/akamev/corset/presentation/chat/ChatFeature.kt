package com.akamev.corset.presentation.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.domain.model.AiChatRole
import com.akamev.corset.domain.model.AiChatTurn
import com.akamev.corset.domain.model.ChatMessage
import com.akamev.corset.domain.model.ChatSessionSummary
import com.akamev.corset.domain.model.DeviceState
import com.akamev.corset.domain.model.PosturePoint
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.GlassCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class ChatUiState(
    val sessions: List<ChatSessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
)

class ChatViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadInitialState()
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun useSuggestion(query: String) {
        _uiState.update { it.copy(input = query) }
    }

    fun openSession(sessionId: String) {
        viewModelScope.launch {
            val sessions = app.container.chatHistoryLocalDataSource.listSessions()
            val messages = app.container.chatHistoryLocalDataSource.loadMessages(sessionId)
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    activeSessionId = sessionId,
                    messages = messages,
                )
            }
        }
    }

    fun startNewChat() {
        viewModelScope.launch {
            val sessionId = app.container.chatHistoryLocalDataSource.createSession()
            val sessions = app.container.chatHistoryLocalDataSource.listSessions()
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    activeSessionId = sessionId,
                    messages = emptyList(),
                    input = "",
                    isSending = false,
                )
            }
        }
    }

    fun sendMessage() {
        val text = _uiState.value.input.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            val sessionId = ensureActiveSession()
            val userMessage = ChatMessage(
                id = System.currentTimeMillis(),
                text = text,
                isUser = true,
            )
            val draftMessages = _uiState.value.messages + userMessage
            _uiState.update {
                it.copy(
                    activeSessionId = sessionId,
                    input = "",
                    isSending = true,
                    messages = draftMessages,
                )
            }

            app.container.chatHistoryLocalDataSource.renameSessionIfNeeded(sessionId, text)

            val answer = runCatching {
                val context = buildPostureContext()
                val conversation = draftMessages
                    .takeLast(MAX_CONTEXT_MESSAGES)
                    .map { message ->
                        AiChatTurn(
                            role = if (message.isUser) AiChatRole.User else AiChatRole.Model,
                            text = message.text,
                        )
                    }

                app.container.aiRemoteDataSource.requestChat(
                    systemInstruction = buildSystemInstruction(context),
                    conversation = conversation,
                )
            }.getOrElse { error ->
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Не удалось получить ответ. Проверь интернет и попробуй ещё раз."
            }

            val assistantMessage = ChatMessage(
                id = System.currentTimeMillis() + 1,
                text = cleanupAnswer(answer),
                isUser = false,
            )

            app.container.chatHistoryLocalDataSource.saveExchange(
                sessionId = sessionId,
                query = text,
                answer = assistantMessage.text,
            )

            val sessions = app.container.chatHistoryLocalDataSource.listSessions()
            _uiState.update {
                it.copy(
                    sessions = sessions,
                    activeSessionId = sessionId,
                    isSending = false,
                    messages = draftMessages + assistantMessage,
                )
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            val sessions = app.container.chatHistoryLocalDataSource.listSessions()
            val activeSessionId = sessions.firstOrNull()?.id ?: app.container.chatHistoryLocalDataSource.createSession()
            val updatedSessions = app.container.chatHistoryLocalDataSource.listSessions()
            val messages = app.container.chatHistoryLocalDataSource.loadMessages(activeSessionId)

            _uiState.value = ChatUiState(
                sessions = updatedSessions,
                activeSessionId = activeSessionId,
                messages = messages,
                suggestions = defaultChatSuggestions(),
            )
        }
    }

    private suspend fun ensureActiveSession(): String {
        return _uiState.value.activeSessionId ?: app.container.chatHistoryLocalDataSource.createSession().also { sessionId ->
            _uiState.update { it.copy(activeSessionId = sessionId) }
        }
    }

    private suspend fun buildPostureContext(): String {
        val history = app.container.postureHistoryLocalDataSource.loadHistory()
        val deviceState = app.container.bluetoothController.deviceState.value
        val threshold = app.container.appPreferences.getResolvedAlertAngle()
        val now = System.currentTimeMillis()
        val last24h = history.filter { it.timestamp >= now - DAY_MS }
        val previous24h = history.filter { it.timestamp in (now - 2 * DAY_MS) until (now - DAY_MS) }

        return buildString {
            appendLine(deviceContext(deviceState))
            appendLine("Порог срабатывания: ${formatAngle(threshold)}.")

            if (last24h.isEmpty()) {
                appendLine("Данных за последние сутки пока мало.")
                return@buildString
            }

            val average = last24h.map { it.angle }.average().toFloat()
            val maxAngle = last24h.maxOf { it.angle }
            val aboveThresholdPercent = last24h.count { it.angle > threshold }
                .toFloat()
                .div(last24h.size.toFloat())
                .times(100f)
            val latest = last24h.lastOrNull()?.angle
            val grouped = last24h.groupBy { hourBucket(it.timestamp) }
            val worstHour = grouped.minByOrNull { (_, points) -> goodRatio(points, threshold) }?.key
            val bestHour = grouped.maxByOrNull { (_, points) -> goodRatio(points, threshold) }?.key

            appendLine("Среднее отклонение за 24 часа: ${formatAngle(average)}.")
            appendLine("Максимальное отклонение за 24 часа: ${formatAngle(maxAngle)}.")
            appendLine("Доля времени выше порога: ${aboveThresholdPercent.roundToInt()}%.")
            latest?.let { appendLine("Последнее значение: ${formatAngle(it)}.") }
            bestHour?.let { appendLine("Лучший час: ${formatHourBucket(it)}.") }
            worstHour?.let { appendLine("Самый слабый час: ${formatHourBucket(it)}.") }
            appendLine("Размер выборки: ${last24h.size} точек.")

            if (previous24h.isNotEmpty()) {
                val previousAverage = previous24h.map { it.angle }.average().toFloat()
                val diff = average - previousAverage
                val direction = when {
                    diff > 0.3f -> "хуже"
                    diff < -0.3f -> "лучше"
                    else -> "почти без изменений"
                }
                appendLine("По сравнению с предыдущими сутками: $direction (${formatSignedAngle(diff)}).")
            }
        }
    }

    private fun buildSystemInstruction(
        context: String,
    ): String {
        return """
            Ты умный AI-коуч приложения CorsetV.
            Помогаешь разбирать осанку, рабочие привычки и телеметрию корсета.
            Не пиши как бот, не повторяй одни и те же фразы, не называй себя врачом.
            Не ставь диагнозы. Если есть боль, онемение или сильный дискомфорт, мягко советуй обратиться к врачу.
            Отвечай естественным русским языком, без странных символов и канцелярита.
            Если данных мало, честно скажи это.
            Если пользователь спрашивает про прогресс, опирайся на контекст ниже.
            Предпочтительный формат ответа:
            1. Короткий вывод по данным
            2. Практический совет на сейчас
            3. Что проверить дальше

            Контекст пользователя:
            $context
        """.trimIndent()
    }

    private fun deviceContext(deviceState: DeviceState): String {
        return when {
            deviceState.isConnected -> {
                val battery = deviceState.batteryLevel?.let { " Батарея: $it%." }.orEmpty()
                "Корсет подключён.$battery"
            }

            deviceState.hasSavedDevice -> "Корсет сохранён, но сейчас не подключён."
            else -> "Корсет ещё не добавлен в приложение."
        }
    }

    private fun cleanupAnswer(answer: String): String {
        return answer
            .replace("В°", "°")
            .replace("—", "-")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_CONTEXT_MESSAGES = 10

        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(app) }
        }

        private fun defaultChatSuggestions(): List<String> = listOf(
            "Что видно по моей осанке сегодня?",
            "Почему сегодня могло стать хуже?",
            "Когда у меня чаще всего начинается просадка?",
            "Что сделать прямо сейчас, чтобы разгрузить шею?",
        )

        private fun formatAngle(value: Float): String = "${value.roundToInt()}°"

        private fun formatSignedAngle(value: Float): String {
            val rounded = (value * 10).roundToInt() / 10f
            return if (rounded > 0) "+${rounded}°" else "${rounded}°"
        }

        private fun hourBucket(timestamp: Long): Long {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = timestamp
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }

        private fun formatHourBucket(timestamp: Long): String {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = timestamp
            val startHour = calendar.get(Calendar.HOUR_OF_DAY)
            val endHour = (startHour + 1) % 24
            return String.format(Locale.getDefault(), "%02d:00-%02d:00", startHour, endHour)
        }

        private fun goodRatio(
            points: List<PosturePoint>,
            threshold: Float,
        ): Float {
            if (points.isEmpty()) return 0f
            return points.count { it.angle <= threshold }.toFloat() / points.size.toFloat()
        }

    }
}

private fun formatSessionTime(updatedAt: Long): String {
    return SimpleDateFormat("d MMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(updatedAt))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Чаты", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(
                            onClick = {
                                viewModel.startNewChat()
                                scope.launch { drawerState.close() }
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("Новый")
                        }
                    }

                    state.sessions.forEach { session ->
                        val isActive = session.id == state.activeSessionId
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = session.preview,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = formatSessionTime(session.updatedAt),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Button(
                                        onClick = {
                                            viewModel.openSession(session.id)
                                            scope.launch { drawerState.close() }
                                        },
                                    ) {
                                        Text(if (isActive) "Открыт" else "Открыть")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI-чат") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Чаты")
                        }
                    },
                )
            },
            bottomBar = { CorsetBottomBar(currentRoute = currentRoute, onNavigate = onNavigate) },
        ) { paddingValues ->
            CorsetBackground {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.suggestions.take(2).forEach { suggestion ->
                            OutlinedButton(
                                onClick = { viewModel.useSuggestion(suggestion) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(suggestion)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.suggestions.drop(2).take(2).forEach { suggestion ->
                            OutlinedButton(
                                onClick = { viewModel.useSuggestion(suggestion) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(suggestion)
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (state.messages.isEmpty()) {
                            item {
                                ChatBubble(
                                    message = ChatMessage(
                                        id = 0L,
                                        text = "Здесь можно вести отдельные диалоги. Начни новый вопрос, и я разберу статистику, найду слабые часы и подскажу, что делать дальше.",
                                        isUser = false,
                                    ),
                                )
                            }
                        } else {
                            items(state.messages, key = { it.id }) { message ->
                                ChatBubble(message = message)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.input,
                            onValueChange = viewModel::updateInput,
                            modifier = Modifier.weight(1f),
                            label = { Text("Сообщение") },
                            placeholder = { Text("Например: что сегодня было самым слабым местом?") },
                        )
                        Button(
                            onClick = viewModel::sendMessage,
                            enabled = !state.isSending,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            Text(if (state.isSending) "..." else "Отпр.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.84f else 0.9f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (message.isUser) "Ты" else "Corset AI",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
