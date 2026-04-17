package com.akamev.corset;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class HomeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNavigationView;
    private TextView tvGreeting, tvDate, tvDailyTip, tvStreak;
    private CardView cardMonitor, cardChat;
    private FirebaseFirestore db;

    private final String[] TIPS = {
            "Держите телефон на уровне глаз. 📱",
            "Делайте перерывы каждые 30 минут. 🧘",
            "Пейте больше воды. 💧",
            "Спите на ортопедической подушке. 🛌",
            "Упражнение «Кошка» полезно для спины. 🐈"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_home);

        db = FirebaseFirestore.getInstance();

        initViews();
        setupNavigation();
        setupDailyInfo();
        updateUserStats();
    }

    private void initViews() {
        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        tvGreeting = findViewById(R.id.tv_greeting);
        tvDate = findViewById(R.id.tv_date);
        tvDailyTip = findViewById(R.id.tv_daily_tip);
        tvStreak = findViewById(R.id.tv_streak);

        cardMonitor = findViewById(R.id.card_goto_monitor);
        cardChat = findViewById(R.id.card_goto_chat);

        cardMonitor.setOnClickListener(v -> {
            startActivity(new Intent(this, AiCoachActivity.class));
            overridePendingTransition(0, 0);
        });

        cardChat.setOnClickListener(v -> {
            startActivity(new Intent(this, AiChatActivity.class));
            overridePendingTransition(0, 0);
        });
    }

    private void updateUserStats() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        db.collection("users").document(uid).get()
                .addOnSuccessListener(document -> {
                    if (document.exists()) {
                        String name = document.getString("firstName");
                        if (name != null) tvGreeting.setText("Привет, " + name + "! 👋");

                        // ИСПРАВЛЕНИЕ 5: null-безопасное получение currentStreak
                        Long streakVal = document.getLong("currentStreak");
                        long currentStreak = streakVal != null ? streakVal : 0L;
                        tvStreak.setText(currentStreak + " дня подряд 🔥");
                    }
                });
    }

    private void setupDailyInfo() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));
        String currentDate = sdf.format(new Date());
        tvDate.setText(currentDate.substring(0, 1).toUpperCase() + currentDate.substring(1));
        tvDailyTip.setText(TIPS[new Random().nextInt(TIPS.length)]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNavigationView.setSelectedItemId(R.id.nav_home);
    }

    private void setupNavigation() {
        bottomNavigationView.setSelectedItemId(R.id.nav_home);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) return true;
            if (id == R.id.nav_profile) startActivity(new Intent(this, ProfileActivity.class));
            else if (id == R.id.nav_ai_coach) startActivity(new Intent(this, AiCoachActivity.class));
            else if (id == R.id.nav_chat) startActivity(new Intent(this, AiChatActivity.class));
            else if (id == R.id.nav_training) Toast.makeText(this, "В разработке", Toast.LENGTH_SHORT).show();

            overridePendingTransition(0, 0);
            return false;
        });
    }
}
