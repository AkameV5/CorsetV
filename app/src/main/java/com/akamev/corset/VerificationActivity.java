package com.akamev.corset;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class VerificationActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private TextView tvEmailAddress;

    // Для автоматической проверки
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checkRunnable;
    private boolean isChecking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_verification);

        mAuth = FirebaseAuth.getInstance();

        Button btnCheck = findViewById(R.id.btn_check_verification);
        TextView tvResend = findViewById(R.id.tv_resend);
        tvEmailAddress = findViewById(R.id.tv_email_address);

        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            tvEmailAddress.setText(user.getEmail());
        }

        // Кнопку оставляем на всякий случай, если авто-проверка затупит
        btnCheck.setOnClickListener(v -> manualCheck());

        tvResend.setOnClickListener(v -> {
            if (user != null) {
                user.sendEmailVerification()
                        .addOnSuccessListener(aVoid -> Toast.makeText(this, "Письмо отправлено снова", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e -> Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Как только открыли экран или вернулись из почты - запускаем цикл проверки
        startAutoCheck();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Если свернули или ушли - останавливаем, чтобы не жрать батарею
        stopAutoCheck();
    }

    private void startAutoCheck() {
        isChecking = true;
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isChecking) return;
                checkStatusAndRedirect();
                // Повторяем через 2 секунды
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(checkRunnable);
    }

    private void stopAutoCheck() {
        isChecking = false;
        if (checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
    }

    // Метод проверки статуса
    private void checkStatusAndRedirect() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            // ВАЖНО: reload() обновляет данные с сервера
            user.reload().addOnCompleteListener(task -> {
                if (user.isEmailVerified()) {
                    // Если подтверждено - останавливаем проверку и переходим
                    stopAutoCheck();
                    goToNextScreen();
                }
            });
        }
    }

    // Ручная проверка (для кнопки)
    private void manualCheck() {
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            user.reload().addOnCompleteListener(task -> {
                if (user.isEmailVerified()) {
                    goToNextScreen();
                } else {
                    Toast.makeText(this, "Почта еще не подтверждена", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void goToNextScreen() {
        Toast.makeText(this, "Почта подтверждена!", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(VerificationActivity.this, SetupProfileActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
