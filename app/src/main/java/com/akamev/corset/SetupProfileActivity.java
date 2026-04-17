package com.akamev.corset;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class SetupProfileActivity extends AppCompatActivity {

    private EditText etName, etSurname;
    private FirebaseFirestore db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_setup_profile);

        db = FirebaseFirestore.getInstance();
        etName = findViewById(R.id.et_name);
        etSurname = findViewById(R.id.et_surname);
        Button btnComplete = findViewById(R.id.btn_complete_setup);

        btnComplete.setOnClickListener(v -> saveProfile());
    }

    private void saveProfile() {
        String name = etName.getText().toString().trim();
        String surname = etSurname.getText().toString().trim();

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(surname)) {
            Toast.makeText(this, "Пожалуйста, введите имя и фамилию", Toast.LENGTH_SHORT).show();
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            Map<String, Object> userData = new HashMap<>();
            userData.put("firstName", name);
            userData.put("lastName", surname);
            userData.put("email", user.getEmail());

            db.collection("users").document(user.getUid())
                    .set(userData)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Профиль готов!", Toast.LENGTH_SHORT).show();
                        Intent intent = new Intent(SetupProfileActivity.this, ProfileActivity.class);
                        // Очищаем историю переходов, чтобы нельзя было вернуться на регистрацию
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        }
    }
}
