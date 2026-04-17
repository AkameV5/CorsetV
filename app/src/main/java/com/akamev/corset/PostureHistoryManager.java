package com.akamev.corset;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PostureHistoryManager {

    private static final String FILE_NAME = "posture_history_v2.csv";
    private final Context context;

    public PostureHistoryManager(Context context) {
        this.context = context;
    }

    // Сохранить одну точку (дописать в конец файла)
    public void savePoint(long timestamp, float angle) {
        String entry = timestamp + "," + angle + "\n";
        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_APPEND)) {
            fos.write(entry.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Загрузить всю историю (при запуске приложения)
    public List<AiCoachActivity.PosturePoint> loadHistory() {
        List<AiCoachActivity.PosturePoint> points = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);

        if (!file.exists()) return points;

        // Ограничение: загружаем только данные за последние 24 часа,
        // чтобы график не тормозил от данных за год.
        long twentyFourHoursAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000);

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    try {
                        long timestamp = Long.parseLong(parts[0]);
                        float angle = Float.parseFloat(parts[1]);

                        // Добавляем только если данные свежие (не старше 24 часов)
                        if (timestamp >= twentyFourHoursAgo) {
                            points.add(new AiCoachActivity.PosturePoint(timestamp, angle));
                        }
                    } catch (NumberFormatException e) {
                        // Игнорируем битые строки
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return points;
    }

    // Очистить историю (если нужно будет)
    public void clearHistory() {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            file.delete();
        }
    }
}