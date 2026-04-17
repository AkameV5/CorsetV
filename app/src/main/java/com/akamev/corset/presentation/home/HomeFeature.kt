package com.akamev.corset.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.akamev.corset.CorsetApplication
import com.akamev.corset.presentation.common.CorsetBackground
import com.akamev.corset.presentation.common.CorsetBottomBar
import com.akamev.corset.presentation.common.GlassCard
import com.akamev.corset.presentation.common.HeroHeader
import com.akamev.corset.presentation.common.TwoColumnStats
import com.akamev.corset.presentation.navigation.CorsetDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

data class HomeUiState(
    val firstName: String = "",
    val formattedDate: String = "",
    val dailyTip: String = "",
    val streak: Long = 0,
)

class HomeViewModel(
    private val app: CorsetApplication,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val profile = app.container.userRepository.getCurrentUserProfile()
            val locale = Locale.forLanguageTag("ru")
            val today = SimpleDateFormat("EEEE, d MMMM", locale).format(Date())
            val formattedDate = today.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            val tips = listOf(
                "Держи экран на уровне глаз, а не коленей.",
                "Раз в 30 минут выдыхай и мягко расправляй плечи.",
                "Пара минут ходьбы заметно снимает нагрузку со спины.",
                "Не зажимай шею, когда работаешь долго за столом.",
                "Короткая разминка грудного отдела быстро возвращает тонус.",
            )

            _uiState.value = HomeUiState(
                firstName = profile?.firstName.orEmpty(),
                formattedDate = formattedDate,
                dailyTip = tips[formattedDate.hashCode().absoluteValue % tips.size],
                streak = profile?.currentStreak ?: 0,
            )
        }
    }

    companion object {
        fun factory(app: CorsetApplication): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(app) }
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = { CorsetBottomBar(currentRoute = currentRoute, onNavigate = onNavigate) },
    ) { paddingValues ->
        CorsetBackground {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroHeader(
                    title = if (state.firstName.isBlank()) "Привет" else "Привет, ${state.firstName}",
                    subtitle = state.formattedDate.ifBlank { "Сегодня хороший день, чтобы держать спину ровнее." },
                )
                TwoColumnStats(
                    firstLabel = "Серия",
                    firstValue = "${state.streak} дн.",
                    secondLabel = "Фокус",
                    secondValue = "Осанка",
                )
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Совет дня", style = MaterialTheme.typography.titleLarge)
                        Text(state.dailyTip, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeActionCard(
                        title = "Мониторинг",
                        subtitle = "Текущий угол, история и оценка состояния",
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Coach.route) }
                    HomeActionCard(
                        title = "AI-чат",
                        subtitle = "Быстрые ответы по осанке и нагрузке",
                        modifier = Modifier.weight(1f),
                    ) { onNavigate(CorsetDestination.Chat.route) }
                }
                GlassCard {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Быстрый доступ", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Открой профиль, подключи устройство и запусти калибровку, чтобы мониторинг начал считать отклонение от базовой позы.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { onNavigate(CorsetDestination.Profile.route) }) {
                            Text("Открыть профиль")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassCard(modifier = modifier.clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
