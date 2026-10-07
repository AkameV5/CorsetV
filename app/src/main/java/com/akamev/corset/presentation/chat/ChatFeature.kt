package com.akamev.corset.presentation.chat

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akamev.corset.CorsetApplication
import com.akamev.corset.domain.model.ChatHistoryItem
import com.akamev.corset.domain.model.ChatMessage
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.GlassCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val history: List<ChatHistoryItem> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
)

class ChatViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun useHistoryQuery(query: String) {
        _uiState.update { it.copy(input = query) }
    }

    fun sendMessage() {
        val text = _uiState.value.input.trim()
        if (text.isBlank()) return

        val updatedMessages = _uiState.value.messages + ChatMessage(
            id = System.currentTimeMillis(),
            text = text,
            isUser = true,
        )
        _uiState.update { it.copy(messages = updatedMessages, input = "", isSending = true) }

        viewModelScope.launch {
            val answer = runCatching {
                val postureContext = postureContext()
                val prompt = "Ты врач HealFlow. Данные пациента: $postureContext. Вопрос: $text. Ответь кратко на русском."
                app.container.aiRemoteDataSource.requestText(prompt)
            }.getOrElse {
                "Не удалось получить ответ. Проверь подключение к интернету и попробуй еще раз."
            }

            app.container.chatHistoryLocalDataSource.saveRequest(text, answer)
            _uiState.update {
                it.copy(
                    isSending = false,
                    messages = it.messages + ChatMessage(
                        id = System.currentTimeMillis() + 1,
                        text = answer,
                        isUser = false,
                    ),
                )
            }
            loadHistoryOnly()
        }
    }

    private fun load() {
        viewModelScope.launch {
            val history = app.container.chatHistoryLocalDataSource.loadHistory()
            val todayMessages = buildList {
                history.filter { DateUtils.isToday(it.timestamp) }
                    .reversed()
                    .forEach { item ->
                        add(ChatMessage(id = item.timestamp, text = item.query, isUser = true))
                        add(ChatMessage(id = item.timestamp + 1, text = item.answer, isUser = false))
                    }
            }

            _uiState.value = ChatUiState(
                messages = if (todayMessages.isEmpty()) {
                    listOf(
                        ChatMessage(
                            id = 0L,
                            text = "Привет! Я AI-помощник CorsetV. Спрашивай про спину, осанку и текущую статистику.",
                            isUser = false,
                        )
                    )
                } else {
                    todayMessages
                },
                history = history,
            )
        }
    }

    private fun loadHistoryOnly() {
        viewModelScope.launch {
            _uiState.update { it.copy(history = app.container.chatHistoryLocalDataSource.loadHistory()) }
        }
    }

    private suspend fun postureContext(): String {
        val history = app.container.postureHistoryLocalDataSource.loadHistory()
        if (history.isEmpty()) return "Данных мало."
        val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val slice = history.filter { it.timestamp >= oneDayAgo }
        if (slice.isEmpty()) return "За сегодня данных нет."
        val average = slice.sumOf { it.angle.toDouble() } / slice.size.toDouble()
        return "Средний угол за сутки: ${String.format("%.1f", average)}"
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChatViewModel(app) }
        }
    }
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
                    Text("История запросов", style = MaterialTheme.typography.titleLarge)
                    state.history.forEach { item ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(item.query, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    item.answer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(
                                    onClick = {
                                        viewModel.useHistoryQuery(item.query)
                                        scope.launch { drawerState.close() }
                                    },
                                ) {
                                    Text("Подставить вопрос")
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
                            Icon(Icons.Filled.Menu, contentDescription = "История")
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            ChatBubble(message = message)
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
