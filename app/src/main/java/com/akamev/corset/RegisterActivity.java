package com.akamev.corset;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;

public class RegisterActivity extends AppCompatActivity {

    private EditText etEmail, etPassword, etConfirmPassword;
    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        mAuth = FirebaseAuth.getInstance();

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        etConfirmPassword = findViewById(R.id.et_confirm_password);
        Button btnRegister = findViewById(R.id.btn_register);
        TextView tvLogin = findViewById(R.id.tv_login);

        btnRegister.setOnClickListener(v -> registerUser());
        tvLogin.setOnClickListener(v -> finish());
    }

    private void registerUser() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirmPass = etConfirmPassword.getText().toString().trim();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) return;
        if (!password.equals(confirmPass)) {
            Toast.makeText(this, "Пароли не совпадают", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Toast.makeText(this, "Пароль слишком короткий (мин 6)", Toast.LENGTH_SHORT).show();
            return;
        }

        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Отправляем письмо подтверждения
                        if (mAuth.getCurrentUser() != null) {
                            mAuth.getCurrentUser().sendEmailVerification();
                        }

                        Toast.makeText(this, "Аккаунт создан! Подтвердите почту.", Toast.LENGTH_LONG).show();

                        Intent intent = new Intent(RegisterActivity.this, VerificationActivity.class);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    } else {
                        Toast.makeText(RegisterActivity.this, "Ошибка: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }
}