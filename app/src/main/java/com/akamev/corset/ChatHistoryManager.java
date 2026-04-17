package com.akamev.corset;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ChatHistoryManager {

    private static final String FILE_NAME = "chat_history_v2.txt"; // Новый файл, чтобы не было ошибок со старым форматом
    private final Context context;
    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;

    public static class HistoryItem {
        long timestamp;
        String query;
        String answer; // Добавили ответ

        public HistoryItem(long timestamp, String query, String answer) {
            this.timestamp = timestamp;
            this.query = query;
            this.answer = answer;
        }
    }

    public ChatHistoryManager(Context context) {
        this.context = context;
    }

    // Сохраняем Вопрос + Ответ
    public void saveRequest(String query, String answer) {
        long now = System.currentTimeMillis();
        // Убираем переносы строк, чтобы не ломать файл
        String safeQuery = query.replace("\n", " ");
        String safeAnswer = answer.replace("\n", " ");

        // Разделитель |||
        String entry = now + "|||" + safeQuery + "|||" + safeAnswer + "\n";

        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_APPEND)) {
            fos.write(entry.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public List<HistoryItem> loadHistory() {
        List<HistoryItem> items = new ArrayList<>();
        List<String> validLines = new ArrayList<>();
        File file = new File(context.getFilesDir(), FILE_NAME);
        long now = System.currentTimeMillis();
        boolean needsRewrite = false;

        if (!file.exists()) return items;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|\\|\\|"); // Делим по |||
                if (parts.length >= 3) {
                    try {
                        long timestamp = Long.parseLong(parts[0]);
                        if (now - timestamp < THIRTY_DAYS_MS) {
                            // Добавляем в начало (новые сверху)
                            items.add(0, new HistoryItem(timestamp, parts[1], parts[2]));
                            validLines.add(line);
                        } else {
                            needsRewrite = true;
                        }
                    } catch (Exception e) {}
                }
            }
        } catch (IOException e) { e.printStackTrace(); }

        if (needsRewrite) {
            rewriteFile(validLines);
        }
        return items;
    }

    private void rewriteFile(List<String> lines) {
        try (FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)) {
            for (String line : lines) {
                fos.write((line + "\n").getBytes());
            }
        } catch (IOException e) {}
    }
}