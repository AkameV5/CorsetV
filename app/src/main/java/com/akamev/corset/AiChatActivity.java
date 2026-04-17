package com.akamev.corset;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiChatActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private EditText inputMessage;
    private FloatingActionButton btnSend;
    private BottomNavigationView bottomNavigationView;

    private DrawerLayout drawerLayout;
    private ImageView btnMenu;
    private RecyclerView recyclerHistory;

    private ChatHistoryManager chatHistoryManager;
    private PostureHistoryManager postureManager;
    private final OkHttpClient httpClient = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        postureManager = new PostureHistoryManager(this);
        chatHistoryManager = new ChatHistoryManager(this);

        initViews();
        setupNavigation();
        setupHistoryMenu();
        loadTodayMessages();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recycler_chat);
        inputMessage = findViewById(R.id.input_message);
        btnSend = findViewById(R.id.btn_send);
        bottomNavigationView = findViewById(R.id.bottom_navigation_view);

        drawerLayout = findViewById(R.id.drawer_layout);
        btnMenu = findViewById(R.id.btn_menu);
        recyclerHistory = findViewById(R.id.recycler_history);

        chatAdapter = new ChatAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);

        btnSend.setOnClickListener(v -> sendMessage());

        btnMenu.setOnClickListener(v -> {
            loadHistoryList();
            drawerLayout.openDrawer(Gravity.LEFT);
        });
    }

    private void loadTodayMessages() {
        List<ChatHistoryManager.HistoryItem> allHistory = chatHistoryManager.loadHistory();
        List<ChatHistoryManager.HistoryItem> todayMessages = new ArrayList<>();

        for (ChatHistoryManager.HistoryItem item : allHistory) {
            if (DateUtils.isToday(item.timestamp)) {
                todayMessages.add(item);
            }
        }

        if (todayMessages.isEmpty()) {
            chatAdapter.addMessage("Привет! Я ИИ-помощник HealFlow. Чем помочь вашей спине?", false);
        } else {
            // История хранится «новые сверху», в чате нужно «старые сверху»
            Collections.reverse(todayMessages);
            for (ChatHistoryManager.HistoryItem item : todayMessages) {
                chatAdapter.addMessage(item.query, true);
                chatAdapter.addMessage(item.answer, false);
            }
            scrollToBottom();
        }
    }

    private void setupHistoryMenu() {
        recyclerHistory.setLayoutManager(new LinearLayoutManager(this));
        loadHistoryList();
    }

    private void loadHistoryList() {
        List<ChatHistoryManager.HistoryItem> history = chatHistoryManager.loadHistory();

        HistoryAdapter historyAdapter = new HistoryAdapter(history, (query, answer) -> {
            drawerLayout.closeDrawer(Gravity.LEFT);
            inputMessage.setText(query);
        });
        recyclerHistory.setAdapter(historyAdapter);
    }

    private void sendMessage() {
        String text = inputMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        chatAdapter.addMessage(text, true);
        inputMessage.setText("");
        scrollToBottom();

        String statsContext = getPostureStats();
        askAi(text, statsContext);
    }

    private String getPostureStats() {
        List<AiCoachActivity.PosturePoint> history = postureManager.loadHistory();
        if (history.isEmpty()) return "Данных мало.";
        float sum = 0;
        int count = 0;
        long oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
        for (AiCoachActivity.PosturePoint p : history) {
            if (p.timestamp >= oneDayAgo) { sum += p.angle; count++; }
        }
        if (count == 0) return "За сегодня данных нет.";
        return "Средний угол за сутки: " + String.format("%.1f", sum / count);
    }

    private void askAi(String userQuery, String contextData) {
        String prompt = "Ты врач HealFlow. Данные пациента: " + contextData
                + ". Вопрос: " + userQuery + ". Ответь кратко на русском.";

        try {
            String url = "https://text.pollinations.ai/" + URLEncoder.encode(prompt, "UTF-8");
            Request request = new Request.Builder().url(url).get().build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() ->
                            chatAdapter.addMessage("Ошибка сети. Проверьте подключение к интернету.", false));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    // ИСПРАВЛЕНИЕ 2: закрываем ResponseBody через try-with-resources
                    try (ResponseBody body = response.body()) {
                        if (!response.isSuccessful() || body == null) {
                            runOnUiThread(() ->
                                    chatAdapter.addMessage("Не удалось получить ответ. Попробуйте позже.", false));
                            return;
                        }
                        final String answer = body.string().trim();
                        runOnUiThread(() -> {
                            chatAdapter.addMessage(answer, false);
                            scrollToBottom();
                            chatHistoryManager.saveRequest(userQuery, answer);
                        });
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scrollToBottom() {
        if (chatAdapter.getItemCount() > 0)
            recyclerView.smoothScrollToPosition(chatAdapter.getItemCount() - 1);
    }

    // ИСПРАВЛЕНИЕ 4: добавлены все пункты навигации (nav_home, nav_training)
    private void setupNavigation() {
        bottomNavigationView.setSelectedItemId(R.id.nav_chat);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_chat) return true;
            if (id == R.id.nav_profile) startActivity(new Intent(this, ProfileActivity.class));
            else if (id == R.id.nav_ai_coach) startActivity(new Intent(this, AiCoachActivity.class));
            else if (id == R.id.nav_home) startActivity(new Intent(this, HomeActivity.class));
            else if (id == R.id.nav_training) Toast.makeText(this, "В разработке", Toast.LENGTH_SHORT).show();
            overridePendingTransition(0, 0);
            return false;
        });
    }
}