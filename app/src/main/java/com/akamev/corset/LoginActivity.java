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
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

public class LoginActivity extends AppCompatActivity {

    private EditText etEmail;
    private EditText etPassword;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        if (mAuth.getCurrentUser() != null) {
            checkUserStatus(mAuth.getCurrentUser());
        }

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        Button btnLogin = findViewById(R.id.btn_login);
        TextView tvRegister = findViewById(R.id.tv_register);

        btnLogin.setOnClickListener(v -> loginUser());
        tvRegister.setOnClickListener(v -> startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
    }

    private void loginUser() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) {
            Toast.makeText(this, "Заполните все поля", Toast.LENGTH_SHORT).show();
            return;
        }

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        checkUserStatus(mAuth.getCurrentUser());
                    } else {
                        String message = task.getException() != null
                                ? task.getException().getMessage()
                                : "Не удалось войти";
                        Toast.makeText(LoginActivity.this, "Ошибка: " + message, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void checkUserStatus(FirebaseUser user) {
        if (user == null) return;

        if (!user.isEmailVerified()) {
            startActivity(new Intent(this, VerificationActivity.class));
            finish();
            return;
        }

        db.collection("users").document(user.getUid()).get()
                .addOnSuccessListener(document -> {
                    Intent nextIntent;
                    if (document.exists()
                            && document.contains("firstName")
                            && document.contains("lastName")) {
                        nextIntent = new Intent(this, ProfileActivity.class);
                    } else {
                        nextIntent = new Intent(this, SetupProfileActivity.class);
                    }
                    startActivity(nextIntent);
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Ошибка проверки профиля: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }
}