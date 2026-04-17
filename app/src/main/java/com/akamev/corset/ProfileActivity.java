package com.akamev.corset;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

public class ProfileActivity extends AppCompatActivity {

    private static final String APP_PREFS = "AppPrefs";
    private static final String KEY_LAST_BATTERY = "last_battery";

    private CardView connectedDeviceCard;
    private CardView addDeviceCard;
    private TextView deviceStatusText;
    private ExtendedFloatingActionButton fabCalibrate;
    private LinearLayout btnAddDevice;
    private BottomNavigationView bottomNavigationView;
    private TextView profileNameText;
    private TextView profileAvatarLetter;
    private TextView tvBatteryLevel;
    private LinearLayout batteryLayout;
    private ImageView batteryIcon;

    private float dX;
    private float dY;
    private float startX;
    private float startY;
    private boolean isDraggingButton = false;
    private GestureDetector gestureDetector;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;
    @Nullable private Integer lastBatteryLevel;

    private final BroadcastReceiver gattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;

            String action = intent.getAction();
            if (BluetoothManager.ACTION_GATT_CONNECTED.equals(action)) {
                updateDeviceUi();
            } else if (BluetoothManager.ACTION_GATT_DISCONNECTED.equals(action)) {
                updateDeviceUi();
            } else if (BluetoothManager.ACTION_DATA_AVAILABLE.equals(action)) {
                CorsetTelemetry telemetry = CorsetTelemetry.parse(
                        intent.getStringExtra(BluetoothManager.EXTRA_DATA)
                );
                if (telemetry != null && telemetry.batteryLevel != null) {
                    lastBatteryLevel = telemetry.batteryLevel;
                    persistBatteryLevel(telemetry.batteryLevel);
                }
                updateDeviceUi();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_profile);

        initViews();
        setupListeners();
        setupSwipeToDelete();
        setupStatusChecker();
        loadUserData();
        restoreBatteryLevel();
        updateDeviceUi();
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothManager.ACTION_GATT_CONNECTED);
        filter.addAction(BluetoothManager.ACTION_GATT_DISCONNECTED);
        filter.addAction(BluetoothManager.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(gattUpdateReceiver, filter);

        BluetoothManager manager = BluetoothManager.getInstance();
        if (manager.hasSavedDevice()) {
            manager.connectToSavedDevice();
        }

        updateDeviceUi();
        statusHandler.post(statusRunnable);

        if (bottomNavigationView != null) {
            bottomNavigationView.setSelectedItemId(R.id.nav_profile);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(gattUpdateReceiver);
        statusHandler.removeCallbacks(statusRunnable);
    }

    private void initViews() {
        connectedDeviceCard = findViewById(R.id.connected_device_card);
        addDeviceCard = findViewById(R.id.add_device_card);
        deviceStatusText = findViewById(R.id.device_status_text);
        fabCalibrate = findViewById(R.id.fab_calibrate);
        btnAddDevice = findViewById(R.id.add_device_button);
        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        profileNameText = findViewById(R.id.profile_name_text);
        profileAvatarLetter = findViewById(R.id.profile_avatar_letter);
        tvBatteryLevel = findViewById(R.id.tv_battery_level);
        batteryLayout = findViewById(R.id.battery_layout);
        batteryIcon = findViewById(R.id.iv_battery_icon);
    }

    private void loadUserData() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore.getInstance().collection("users").document(uid)
                .get()
                .addOnSuccessListener(document -> {
                    if (!document.exists()) return;

                    String name = document.getString("firstName");
                    String surname = document.getString("lastName");
                    if (name == null) return;

                    profileNameText.setText(name + " " + (surname != null ? surname : ""));
                    if (!name.isEmpty()) {
                        profileAvatarLetter.setText(String.valueOf(name.charAt(0)).toUpperCase());
                    }
                });
    }

    private void restoreBatteryLevel() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (prefs.contains(KEY_LAST_BATTERY)) {
            int storedValue = prefs.getInt(KEY_LAST_BATTERY, -1);
            lastBatteryLevel = storedValue >= 0 ? storedValue : null;
        }
    }

    private void persistBatteryLevel(int batteryLevel) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_BATTERY, batteryLevel)
                .apply();
    }

    private void updateDeviceUi() {
        BluetoothManager manager = BluetoothManager.getInstance();
        boolean hasSavedDevice = manager.hasSavedDevice();
        boolean isConnected = manager.isConnected();

        if (!hasSavedDevice && !isConnected) {
            addDeviceCard.setVisibility(View.VISIBLE);
            connectedDeviceCard.setVisibility(View.GONE);
            fabCalibrate.setVisibility(View.GONE);
            return;
        }

        addDeviceCard.setVisibility(View.GONE);
        connectedDeviceCard.setVisibility(View.VISIBLE);
        fabCalibrate.setVisibility(View.VISIBLE);

        setConnectionStatus(isConnected);
        updateBatteryUi(lastBatteryLevel, !isConnected);
    }

    private void setConnectionStatus(boolean isConnected) {
        if (isConnected) {
            deviceStatusText.setText("\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e");
            deviceStatusText.setTextColor(getColor(R.color.orange_accent_color));
        } else {
            deviceStatusText.setText("\u041d\u0435 \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e");
            deviceStatusText.setTextColor(Color.parseColor("#757575"));
        }
    }

    private void updateBatteryUi(@Nullable Integer batteryLevel, boolean stale) {
        batteryLayout.setVisibility(View.VISIBLE);

        if (batteryLevel == null) {
            int neutralColor = Color.parseColor("#9E9E9E");
            tvBatteryLevel.setText("--");
            tvBatteryLevel.setTextColor(neutralColor);
            batteryIcon.setColorFilter(neutralColor);
            return;
        }

        int color;
        if (stale) {
            color = Color.parseColor("#9E9E9E");
        } else if (batteryLevel <= 20) {
            color = Color.parseColor("#E53935");
        } else if (batteryLevel <= 50) {
            color = getColor(R.color.orange_accent_color);
        } else {
            color = getColor(R.color.green_accent_color);
        }

        tvBatteryLevel.setText(batteryLevel + "%");
        tvBatteryLevel.setTextColor(color);
        batteryIcon.setColorFilter(color);
    }

    private void setupStatusChecker() {
        statusRunnable = new Runnable() {
            @Override
            public void run() {
                BluetoothManager manager = BluetoothManager.getInstance();
                if (manager.hasSavedDevice() && !manager.isConnected()) {
                    manager.connectToSavedDevice();
                }
                updateDeviceUi();
                statusHandler.postDelayed(this, 3000);
            }
        };
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupSwipeToDelete() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float diffX = e2.getX() - e1.getX();
                if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100 && diffX < 0) {
                    deleteDevice();
                    return true;
                }
                return false;
            }
        });

        connectedDeviceCard.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void deleteDevice() {
        BluetoothManager manager = BluetoothManager.getInstance();
        manager.disconnect();
        manager.clearSavedDevice();
        lastBatteryLevel = null;
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit().remove(KEY_LAST_BATTERY).apply();
        updateDeviceUi();
        Toast.makeText(this, "\u0423\u0441\u0442\u0440\u043e\u0439\u0441\u0442\u0432\u043e \u0443\u0434\u0430\u043b\u0435\u043d\u043e", Toast.LENGTH_SHORT).show();
    }

    private void setupListeners() {
        View.OnClickListener addDeviceListener =
                v -> startActivity(new Intent(ProfileActivity.this, AddDeviceActivity.class));

        if (btnAddDevice != null) btnAddDevice.setOnClickListener(addDeviceListener);
        if (addDeviceCard != null) addDeviceCard.setOnClickListener(addDeviceListener);

        if (fabCalibrate != null) {
            fabCalibrate.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        startX = event.getRawX();
                        startY = event.getRawY();
                        isDraggingButton = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        view.animate().x(event.getRawX() + dX).y(event.getRawY() + dY).setDuration(0).start();
                        if (Math.abs(event.getRawX() - startX) > 10
                                || Math.abs(event.getRawY() - startY) > 10) {
                            isDraggingButton = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDraggingButton) performCalibration();
                        return true;
                    default:
                        return false;
                }
            });
        }

        if (bottomNavigationView != null) {
            bottomNavigationView.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_profile) return true;
                if (id == R.id.nav_home) startActivity(new Intent(this, HomeActivity.class));
                else if (id == R.id.nav_ai_coach) startActivity(new Intent(this, AiCoachActivity.class));
                else if (id == R.id.nav_chat) startActivity(new Intent(this, AiChatActivity.class));
                else if (id == R.id.nav_training) {
                    Toast.makeText(this, "\u0412 \u0440\u0430\u0437\u0440\u0430\u0431\u043e\u0442\u043a\u0435", Toast.LENGTH_SHORT).show();
                }
                overridePendingTransition(0, 0);
                return false;
            });
        }
    }

    private void performCalibration() {
        BluetoothManager.getInstance().writeCharacteristic("SET");

        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        prefs.edit()
                .putBoolean("calibration_done", true)
                .putFloat("saved_baseline", Float.NaN)
                .apply();

        Toast.makeText(this, "\u041e\u0441\u0430\u043d\u043a\u0430 \u0437\u0430\u0444\u0438\u043a\u0441\u0438\u0440\u043e\u0432\u0430\u043d\u0430!", Toast.LENGTH_SHORT).show();
    }
}
