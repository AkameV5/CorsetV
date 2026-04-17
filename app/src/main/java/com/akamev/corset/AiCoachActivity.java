package com.akamev.corset;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.ChipGroup;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiCoachActivity extends AppCompatActivity {

    private static final String APP_PREFS = "AppPrefs";
    private static final String PREFS_STATS = "DailyStats";
    private static final float MOTOR_THRESHOLD = 20f;

    private LineChart chart;
    private TextView scoreText;
    private TextView currentAngleText;
    private TextView aiAdviceText;
    private ProgressBar aiLoading;
    private BottomNavigationView bottomNavigationView;
    private ChipGroup timeFiltersGroup;

    public static class PosturePoint {
        long timestamp;
        float angle;

        public PosturePoint(long timestamp, float angle) {
            this.timestamp = timestamp;
            this.angle = angle;
        }
    }

    private final List<PosturePoint> historyPoints = new ArrayList<>();

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ExecutorService historyIoExecutor = Executors.newSingleThreadExecutor();

    private PostureHistoryManager historyManager;
    private long dailyGoodFrames = 0;
    private long dailyTotalFrames = 0;
    private boolean isMonitoringStarted = false;
    private float baselineAngle = Float.NaN;
    private boolean isAiBusy = false;
    private long lastChartRenderTimeMs = 0L;
    private float liveCounter = 0f;

    private enum ChartMode { LIVE, HISTORY }
    private ChartMode currentMode = ChartMode.LIVE;

    private final BroadcastReceiver dataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (BluetoothManager.ACTION_DATA_AVAILABLE.equals(intent.getAction())) {
                processIncomingData(intent.getStringExtra(BluetoothManager.EXTRA_DATA));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_coach);

        historyManager = new PostureHistoryManager(this);
        initViews();

        new Thread(() -> {
            List<PosturePoint> loaded = historyManager.loadHistory();
            runOnUiThread(() -> {
                synchronized (historyPoints) {
                    historyPoints.addAll(loaded);
                }
            });
        }).start();

        setupLiveChartConfig();
        setupTimeFilters();
        setupNavigation();
        checkCalibrationStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(BluetoothManager.ACTION_DATA_AVAILABLE);
        LocalBroadcastManager.getInstance(this).registerReceiver(dataReceiver, filter);
        bottomNavigationView.setSelectedItemId(R.id.nav_ai_coach);

        checkCalibrationStatus();
        checkNewDay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver);
        if (isMonitoringStarted) saveDailyStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        historyIoExecutor.shutdown();
    }

    private void initViews() {
        chart = findViewById(R.id.postureChart);
        scoreText = findViewById(R.id.score_text);
        currentAngleText = findViewById(R.id.current_angle_text);
        aiAdviceText = findViewById(R.id.ai_advice_text);
        aiLoading = findViewById(R.id.ai_loading);
        bottomNavigationView = findViewById(R.id.bottom_navigation_view);
        timeFiltersGroup = findViewById(R.id.time_filters_group);
    }

    private void checkCalibrationStatus() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        isMonitoringStarted = prefs.getBoolean("calibration_done", false);
        baselineAngle = prefs.getFloat("saved_baseline", Float.NaN);

        if (isMonitoringStarted) {
            loadDailyStats();
            updateScoreUI();
            if (!Float.isNaN(baselineAngle)) {
                aiAdviceText.setText("Мониторинг активен. База: "
                        + String.format(Locale.getDefault(), "%.1f°", baselineAngle));
            } else {
                aiAdviceText.setText("Мониторинг активен, ожидание первых данных...");
            }
        } else {
            aiAdviceText.setText("Нажмите «Откалибровать» в профиле для начала мониторинга.");
            scoreText.setText("--");
            currentAngleText.setText("--");
        }
    }

    private void setupTimeFilters() {
        timeFiltersGroup.setOnCheckedChangeListener((group, checkedId) -> {
            try {
                if (checkedId == R.id.chip_live) {
                    if (!isMonitoringStarted) {
                        Toast.makeText(this, "Сначала выполните калибровку в профиле", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    switchToLiveMode();
                } else {
                    long period = 10 * 60 * 1000L;
                    if (checkedId == R.id.chip_1h) period = 60 * 60 * 1000L;
                    else if (checkedId == R.id.chip_10h) period = 10 * 60 * 60 * 1000L;
                    else if (checkedId == R.id.chip_24h) period = 24 * 60 * 60 * 1000L;

                    switchToHistoryMode(period);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void switchToLiveMode() {
        currentMode = ChartMode.LIVE;
        chart.clear();
        liveCounter = 0f;
        lastChartRenderTimeMs = 0L;
        chart.getXAxis().resetAxisMinimum();
        chart.getXAxis().resetAxisMaximum();
        chart.fitScreen();
        setupLiveChartConfig();
        chart.setData(new LineData());
        chart.invalidate();
    }

    private void switchToHistoryMode(long periodMs) {
        currentMode = ChartMode.HISTORY;
        chart.clear();
        setupHistoryChartConfig();

        long now = System.currentTimeMillis();
        long start = now - periodMs;
        List<Entry> entries = new ArrayList<>();

        // ИСПРАВЛЕНИЕ 1: synchronized при чтении historyPoints
        List<PosturePoint> snapshot;
        synchronized (historyPoints) {
            snapshot = new ArrayList<>(historyPoints);
        }

        for (PosturePoint point : snapshot) {
            if (point.timestamp >= start) {
                entries.add(new Entry((float) point.timestamp, point.angle));
            }
        }

        if (entries.isEmpty()) {
            Toast.makeText(this, "Нет данных за выбранный период", Toast.LENGTH_SHORT).show();
            chart.setData(new LineData());
            chart.invalidate();
            return;
        }

        LineDataSet set = createSet("История", false);
        set.setValues(entries);
        set.setDrawCircles(false);
        set.setMode(LineDataSet.Mode.LINEAR);

        LineData data = new LineData(set);
        chart.setData(data);
        chart.getXAxis().setAxisMinimum((float) start);
        chart.getXAxis().setAxisMaximum((float) now);
        chart.fitScreen();
        chart.invalidate();
    }

    private void setupLiveChartConfig() {
        chart.setDrawGridBackground(true);
        chart.setGridBackgroundColor(Color.parseColor("#FAFAFA"));
        chart.getDescription().setEnabled(false);
        chart.setTouchEnabled(false);
        chart.setDragEnabled(false);
        chart.setScaleEnabled(false);
        chart.setPinchZoom(false);
        chart.setAutoScaleMinMaxEnabled(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setDrawGridLines(true);
        xAxis.setGridColor(Color.parseColor("#EEEEEE"));
        xAxis.setValueFormatter(null);
        xAxis.setEnabled(false);

        setupYAxis();
    }

    private void setupHistoryChartConfig() {
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setPinchZoom(true);

        XAxis xAxis = chart.getXAxis();
        xAxis.setEnabled(true);
        xAxis.setValueFormatter(new ValueFormatter() {
            private final SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());

            @Override
            public String getFormattedValue(float value) {
                return format.format(new Date((long) value));
            }
        });

        setupYAxis();
    }

    private void setupYAxis() {
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(90f);
        leftAxis.removeAllLimitLines();

        LimitLine limitLine = new LimitLine(15f, "Норма");
        limitLine.setLineColor(Color.parseColor("#4CAF50"));
        limitLine.setLineWidth(2f);
        limitLine.enableDashedLine(10f, 10f, 0f);
        leftAxis.addLimitLine(limitLine);

        chart.getAxisRight().setEnabled(false);
    }

    private void processIncomingData(String data) {
        if (!isMonitoringStarted) return;

        try {
            CorsetTelemetry telemetry = CorsetTelemetry.parse(data);
            if (telemetry == null) return;

            float rawAngle = telemetry.angle;
            if (Float.isNaN(baselineAngle)) {
                baselineAngle = rawAngle;
                getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                        .edit()
                        .putFloat("saved_baseline", baselineAngle)
                        .apply();
            }

            float deviation = Math.abs(rawAngle - baselineAngle);
            long now = System.currentTimeMillis();

            historyIoExecutor.execute(() -> historyManager.savePoint(now, deviation));

            // ИСПРАВЛЕНИЕ 1: synchronized вместо CopyOnWriteArrayList
            synchronized (historyPoints) {
                historyPoints.add(new PosturePoint(now, deviation));
                if (historyPoints.size() > 10000) {
                    historyPoints.remove(0);
                }
            }

            currentAngleText.setText(String.format(Locale.getDefault(), "%.0f°", deviation));
            currentAngleText.setTextColor(deviation >= MOTOR_THRESHOLD
                    ? Color.RED
                    : Color.parseColor("#4CAF50"));

            dailyTotalFrames++;
            if (deviation < MOTOR_THRESHOLD) {
                dailyGoodFrames++;
            }

            if (dailyTotalFrames % 20 == 0) {
                saveDailyStats();
                updateScoreUI();
            }

            if (!isAiBusy && (dailyTotalFrames == 10 || (dailyTotalFrames > 10 && dailyTotalFrames % 200 == 0))) {
                int score = (int) (((float) dailyGoodFrames / dailyTotalFrames) * 100);
                float avg = calculateAverage(20 * 60 * 1000L);
                askSmartAi(score, avg);
            }

            if (currentMode == ChartMode.LIVE && now - lastChartRenderTimeMs >= 80L) {
                lastChartRenderTimeMs = now;
                addEntryToLiveChart(deviation);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addEntryToLiveChart(float value) {
        try {
            LineData data = chart.getData();
            if (data == null) {
                data = new LineData();
                chart.setData(data);
            }

            LineDataSet set = (LineDataSet) data.getDataSetByIndex(0);
            if (set == null) {
                set = createSet("Live", true);
                data.addDataSet(set);
            }

            data.addEntry(new Entry(liveCounter, value), 0);
            liveCounter++;

            data.notifyDataChanged();
            chart.notifyDataSetChanged();
            chart.setVisibleXRangeMaximum(60f);
            chart.moveViewToX(data.getEntryCount());
        } catch (Exception ignored) {
        }
    }

    private LineDataSet createSet(String label, boolean isLive) {
        LineDataSet set = new LineDataSet(null, label);
        set.setAxisDependency(YAxis.AxisDependency.LEFT);
        set.setColor(Color.parseColor("#FF9800"));
        set.setLineWidth(2f);
        set.setDrawFilled(true);
        set.setDrawCircles(false);
        set.setDrawValues(false);

        try {
            set.setFillDrawable(ContextCompat.getDrawable(this, R.drawable.fade_orange));
        } catch (Exception e) {
            set.setFillColor(Color.parseColor("#FF9800"));
        }
        set.setFillAlpha(30);
        if (isLive) {
            set.setMode(LineDataSet.Mode.LINEAR);
        }
        return set;
    }

    private float calculateAverage(long periodMs) {
        long now = System.currentTimeMillis();
        long start = now - periodMs;
        float sum = 0f;
        int count = 0;

        // ИСПРАВЛЕНИЕ 1: synchronized при чтении
        List<PosturePoint> snapshot;
        synchronized (historyPoints) {
            snapshot = new ArrayList<>(historyPoints);
        }

        for (int i = snapshot.size() - 1; i >= 0; i--) {
            PosturePoint point = snapshot.get(i);
            if (point.timestamp < start) break;
            sum += point.angle;
            count++;
        }
        return count > 0 ? sum / count : 0f;
    }

    private void askSmartAi(int score, float avgAngle) {
        isAiBusy = true;
        aiLoading.setVisibility(View.VISIBLE);
        try {
            String prompt = "Анализ осанки. Среднее отклонение за 20 минут: "
                    + String.format(Locale.US, "%.1f", avgAngle)
                    + " градусов. Оценка: "
                    + score
                    + "%. Дай один короткий совет на русском языке.";
            String url = "https://text.pollinations.ai/" + URLEncoder.encode(prompt, "UTF-8");
            Request request = new Request.Builder().url(url).get().build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    generateFallbackAdvice(avgAngle);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    // ИСПРАВЛЕНИЕ 2: закрываем ResponseBody через try-with-resources
                    try (ResponseBody body = response.body()) {
                        if (!response.isSuccessful() || body == null) {
                            generateFallbackAdvice(avgAngle);
                            return;
                        }
                        final String responseData = body.string();
                        runOnUiThread(() -> {
                            aiAdviceText.setText(responseData.trim());
                            aiLoading.setVisibility(View.GONE);
                            isAiBusy = false;
                        });
                    }
                }
            });
        } catch (Exception e) {
            generateFallbackAdvice(avgAngle);
        }
    }

    // ИСПРАВЛЕНИЕ 3: резервные тексты на русском вместо транслита
    private void generateFallbackAdvice(float avgAngle) {
        runOnUiThread(() -> {
            String advice;
            if (avgAngle < 10f) {
                advice = "Осанка в норме. Так держать! 👍";
            } else if (avgAngle < 25f) {
                advice = "Есть лёгкая сутулость. Расправьте плечи и выпрямите спину.";
            } else {
                advice = "Сильное отклонение. Выпрямитесь и сделайте перерыв. 🧘";
            }
            aiAdviceText.setText(advice);
            aiLoading.setVisibility(View.GONE);
            isAiBusy = false;
        });
    }

    private void loadDailyStats() {
        SharedPreferences prefs = getSharedPreferences(PREFS_STATS, MODE_PRIVATE);
        String savedDate = prefs.getString("last_date", "");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        if (!savedDate.equals(today)) {
            dailyGoodFrames = 0;
            dailyTotalFrames = 0;
            saveDailyStats();
        } else {
            dailyGoodFrames = prefs.getLong("good_frames", 0);
            dailyTotalFrames = prefs.getLong("total_frames", 0);
        }
    }

    private void saveDailyStats() {
        SharedPreferences prefs = getSharedPreferences(PREFS_STATS, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        editor.putString("last_date", today);
        editor.putLong("good_frames", dailyGoodFrames);
        editor.putLong("total_frames", dailyTotalFrames);
        editor.apply();
    }

    private void checkNewDay() {
        loadDailyStats();
    }

    private void updateScoreUI() {
        if (dailyTotalFrames == 0) {
            scoreText.setText("100%");
            return;
        }
        int score = (int) (((float) dailyGoodFrames / dailyTotalFrames) * 100);
        scoreText.setText(score + "%");
    }

    private void setupNavigation() {
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_ai_coach) return true;
            if (id == R.id.nav_profile) startActivity(new Intent(this, ProfileActivity.class));
            else if (id == R.id.nav_home) startActivity(new Intent(this, HomeActivity.class));
            else if (id == R.id.nav_chat) startActivity(new Intent(this, AiChatActivity.class));
            else if (id == R.id.nav_training) Toast.makeText(this, "В разработке", Toast.LENGTH_SHORT).show();
            overridePendingTransition(0, 0);
            return false;
        });
    }
}