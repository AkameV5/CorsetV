package com.akamev.corset.presentation.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
            val sessions = app.container.chatRepository.listSessions()
            val messages = app.container.chatRepository.loadMessages(sessionId)
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
            val sessionId = app.container.chatRepository.createSession()
            val sessions = app.container.chatRepository.listSessions()
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

            app.container.chatRepository.renameSessionIfNeeded(sessionId, text)

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
                val isRu = Locale.getDefault().language == "ru"
                error.message?.takeIf { it.isNotBlank() }
                    ?: if (isRu) "Не удалось получить ответ. Проверь интернет и попробуй ещё раз."
                    else "Could not retrieve answer. Check internet connection and try again."
            }

            val assistantMessage = ChatMessage(
                id = System.currentTimeMillis() + 1,
                text = cleanupAnswer(answer),
                isUser = false,
            )

            app.container.chatRepository.saveExchange(
                sessionId = sessionId,
                query = text,
                answer = assistantMessage.text,
            )

            val sessions = app.container.chatRepository.listSessions()
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
            val sessions = app.container.chatRepository.listSessions()
            val activeSessionId = sessions.firstOrNull()?.id ?: app.container.chatRepository.createSession()
            val updatedSessions = app.container.chatRepository.listSessions()
            val messages = app.container.chatRepository.loadMessages(activeSessionId)

            _uiState.value = ChatUiState(
                sessions = updatedSessions,
                activeSessionId = activeSessionId,
                messages = messages,
                suggestions = defaultChatSuggestions(),
            )
        }
    }

    private suspend fun ensureActiveSession(): String {
        return _uiState.value.activeSessionId ?: app.container.chatRepository.createSession().also { sessionId ->
            _uiState.update { it.copy(activeSessionId = sessionId) }
        }
    }

    private suspend fun buildPostureContext(): String {
        val history = app.container.postureRepository.loadHistory()
        val deviceState = app.container.bluetoothController.deviceState.value
        val threshold = app.container.appPreferences.getResolvedAlertAngle()
        val now = System.currentTimeMillis()
        val last24h = history.filter { it.timestamp >= now - DAY_MS }
        val previous24h = history.filter { it.timestamp in (now - 2 * DAY_MS) until (now - DAY_MS) }
        val isRu = Locale.getDefault().language == "ru"

        return buildString {
            appendLine(deviceContext(deviceState, isRu))
            appendLine(if (isRu) "Порог срабатывания: ${formatAngle(threshold)}." else "Alert threshold: ${formatAngle(threshold)}.")

            if (last24h.isEmpty()) {
                appendLine(if (isRu) "Данных за последние сутки пока мало." else "Limited telemetry data recorded in the last 24h.")
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

            if (isRu) {
                appendLine("Среднее отклонение за 24 часа: ${formatAngle(average)}.")
                appendLine("Максимальное отклонение за 24 часа: ${formatAngle(maxAngle)}.")
                appendLine("Доля времени выше порога: ${aboveThresholdPercent.roundToInt()}%.")
                latest?.let { appendLine("Последнее значение: ${formatAngle(it)}.") }
                bestHour?.let { appendLine("Лучший час: ${formatHourBucket(it)}.") }
                worstHour?.let { appendLine("Самый слабый час: ${formatHourBucket(it)}.") }
                appendLine("Размер выборки: ${last24h.size} точек.")
            } else {
                appendLine("24h average deviation: ${formatAngle(average)}.")
                appendLine("24h maximum deviation: ${formatAngle(maxAngle)}.")
                appendLine("Percentage of time above threshold: ${aboveThresholdPercent.roundToInt()}%.")
                latest?.let { appendLine("Latest angle: ${formatAngle(it)}.") }
                bestHour?.let { appendLine("Best hour: ${formatHourBucket(it)}.") }
                worstHour?.let { appendLine("Weakest hour: ${formatHourBucket(it)}.") }
                appendLine("Sample points: ${last24h.size}.")
            }

            if (previous24h.isNotEmpty()) {
                val previousAverage = previous24h.map { it.angle }.average().toFloat()
                val diff = average - previousAverage
                val direction = when {
                    diff > 0.3f -> if (isRu) "хуже" else "worse"
                    diff < -0.3f -> if (isRu) "лучше" else "better"
                    else -> if (isRu) "почти без изменений" else "unchanged"
                }
                if (isRu) {
                    appendLine("По сравнению с предыдущими сутками: $direction (${formatSignedAngle(diff)}).")
                } else {
                    appendLine("Compared to previous 24h: $direction (${formatSignedAngle(diff)}).")
                }
            }
        }
    }

    private fun buildSystemInstruction(
        context: String,
    ): String {
        val isRu = Locale.getDefault().language == "ru"
        if (!isRu) {
            return """
                You are a personal AI biomechanics and posture expert for the CorsetV smart tracker.
                Your goal: help the user maintain spinal health, interpret deviation angles, and provide precise, actionable 30-60 second micro-interventions.

                Telemetry criteria:
                - 0°-4°: Ideal physiological posture, minimal spine load.
                - 5°-7°: Growing extensor fatigue, shoulders beginning to roll forward.
                - >8°-10°+: Pronounced slouch (slumping in chair, forward head posture).

                Rules for answers:
                1. Be concise, actionable, and direct. No fluff or generic clichés like "just sit straight".
                2. Offer micro-actions for the desk:
                   - Chin tuck (subtle retraction of chin without tilting head back);
                   - Shoulder blades in back pockets (retracting and depressing scapulae);
                   - Thoracic expansion via deep ribcage breathing;
                   - Screen height check (top third of monitor at eye level).
                3. Do NOT provide medical diagnoses. For severe or persistent pain, recommend consulting a doctor.
                4. When telemetry data is provided in context, connect your advice to the numbers (average angle, worst interval, threshold).

                STRICT FORMATTING RULES:
                - NEVER use Markdown symbols: no asterisks (**bold**), no hash headers (#), no tables (|---|), no underscores (__).
                - For lists, use simple hyphens (- ) or numbered lists (1., 2.).
                - Put an empty line between paragraphs for readability.

                Response structure:
                • Analysis: brief summary of current angle or daily trend (1-2 sentences).
                • 30-sec action: specific quick stretch or micro-posture correction.
                • Ergonomics tip: adjustment for desk setup or break timing.

                User and Corset Context:
                $context
            """.trimIndent()
        }

        return """
            Ты персональный AI-эксперт по осанке и эргономике умного корсета CorsetV.
            Твоя цель: помогать пользователю держать здоровую спину, анализировать углы наклона и давать точные, практичные микро-рекомендации на 30-60 секунд.

            Критерии телеметрии:
            - Отклонение 0°-4°: отличная физиологическая поза, минимальная нагрузка на позвоночник.
            - Отклонение 5°-7°: нарастающая усталость разгибателей спины, плечи смещаются вперёд.
            - Отклонение >8°-10°+: выраженная сутулость (сползание в кресле, сильный вынос шеи вперёд).

            Правила ответов:
            1. Будь конкретным и лаконичным, без "воды" и общих фраз вроде "просто держите спину прямо".
            2. Предлагай микро-действия прямо на рабочем месте:
               - Chin tuck (мягкое смещение подбородка назад без запрокидывания головы);
               - Лопатки в задние карманы (сведение и опускание лопаток для включения ромбовидных мышц);
               - Раскрытие грудной клетки через глубокий вдох в ребра;
               - Проверка высоты экрана (верхняя треть монитора строго на уровне глаз).
            3. Не ставь медицинских диагнозов. При жалобах на острую боль или онемение — мягко рекомендуй консультацию врача.
            4. Если в контексте есть данные телеметрии, обязательно связывай свой ответ с цифрами (средний угол, худший час, порог).

            СТРОГИЕ ПРАВИЛА ОФОРМЛЕНИЯ ТЕКСТА:
            - КАТЕГОРИЧЕСКИ НЕ ИСПОЛЬЗУЙ Markdown-символы: никаких звёздочек (**жирный текст**), решёток (#), таблиц (|---|) и подчёркиваний (__).
            - Для списков используй только простой дефис (- ) или цифры (1., 2.).
            - Обязательно делай пустую строку между абзацами, чтобы текст легко читался и не слипался.

            Структура ответа:
            • Анализ: краткий вывод по текущему углу или тренду (1-2 емких предложения).
            • Действие на 30 сек: конкретное быстрое упражнение или микро-коррекция позы.
            • Что настроить: совет по рабочему месту или времени отдыха.

            Контекст пользователя и корсета:
            $context
        """.trimIndent()
    }

    private fun deviceContext(deviceState: DeviceState, isRu: Boolean = true): String {
        return when {
            deviceState.isConnected -> {
                val battery = deviceState.batteryLevel?.let {
                    if (isRu) " Батарея: $it%." else " Battery: $it%."
                }.orEmpty()
                if (isRu) "Корсет подключён.$battery" else "Corset is connected.$battery"
            }

            deviceState.hasSavedDevice -> if (isRu) "Корсет сохранён, но сейчас не подключён." else "Corset is saved, but currently disconnected."
            else -> if (isRu) "Корсет ещё не добавлен в приложение." else "Corset has not been paired yet."
        }
    }

    private fun cleanupAnswer(answer: String): String {
        return answer
            .replace("В°", "°")
            .replace("—", " — ")
            // Убираем маркеры жирного шрифта и курсива (**текст** -> текст)
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace("**", "")
            .replace(Regex("__(.*?)__"), "$1")
            .replace("__", "")
            // Убираем решетки заголовков
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            // Убираем разделительные линии таблиц вида |---|---|
            .replace(Regex("(?m)^\\|?\\s*[-:]{2,}\\s*\\|.*$"), "")
            // Преобразуем строки таблиц в читаемый текст с тире
            .lines()
            .joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                    trimmed.split("|")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                } else {
                    line
                }
            }
            // Звездочки списков заменяем на аккуратную точку
            .replace(Regex("(?m)^\\s*\\*\\s+"), "• ")
            .replace(Regex("(?m)^\\s*-\\s+"), "• ")
            // Разделяем слипшийся текст после знаков препинания: "шеи.Попробуйте" -> "шеи. Попробуйте"
            .replace(Regex("([.!?:])(?=[А-ЯA-Z])"), "$1 ")
            // Убираем множественные переносы строк, оставляя максимум 2
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MAX_CONTEXT_MESSAGES = 10

        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(app) }
        }

        private fun defaultChatSuggestions(): List<String> {
            val isRu = Locale.getDefault().language == "ru"
            return if (isRu) {
                listOf(
                    "Что видно по моей осанке сегодня?",
                    "Почему сегодня могло стать хуже?",
                    "Когда у меня чаще всего начинается просадка?",
                    "Что сделать прямо сейчас, чтобы разгрузить шею?",
                )
            } else {
                listOf(
                    "How does my posture look today?",
                    "Why did my posture decline earlier?",
                    "When does my posture drop most often?",
                    "What 30-sec stretch helps neck fatigue right now?",
                )
            }
        }

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
    return SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(updatedAt))
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
                        Text(
                            text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_drawer_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        OutlinedButton(
                            onClick = {
                                viewModel.startNewChat()
                                scope.launch { drawerState.close() }
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_new_dialog))
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
                                        Text(
                                            if (isActive)
                                                androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_opened_btn)
                                            else
                                                androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_open_btn)
                                        )
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
                    title = { Text(androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_title)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_menu_desc),
                            )
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
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (state.messages.isEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_empty_title),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_empty_subtitle),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        Text(
                                            text = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_frequent_questions),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            state.suggestions.forEach { suggestion ->
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                        .clickable { viewModel.useSuggestion(suggestion) }
                                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                                ) {
                                                    Text(
                                                        text = suggestion,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            items(state.messages, key = { it.id }) { message ->
                                ChatBubble(message = message)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.input,
                            onValueChange = viewModel::updateInput,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(26.dp),
                            placeholder = {
                                Text(
                                    androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_input_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            maxLines = 4,
                        )
                        IconButton(
                            onClick = viewModel::sendMessage,
                            enabled = !state.isSending && state.input.isNotBlank(),
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    if (!state.isSending && state.input.isNotBlank())
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                ),
                        ) {
                            if (state.isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.akamev.corset.R.string.chat_send_btn),
                                    tint = if (state.input.isNotBlank())
                                        MaterialTheme.colorScheme.onPrimary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
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
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        } else {
            Card(
                modifier = Modifier.widthIn(max = 320.dp),
                shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                        )
                        Text(
                            text = "Corset Coach",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
