package com.akamev.corset;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PostureDataLogger {

    private File logFile;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    public PostureDataLogger(Context context) {
        File storageDir = context.getFilesDir();
        logFile = new File(storageDir, "posture_log.csv");

        if (!logFile.exists()) {
            try (FileOutputStream fos = new FileOutputStream(logFile)) {
                String header = "timestamp,angle,motor_on\n";
                fos.write(header.getBytes());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void logData(float angle, boolean isMotorOn) {
        String timestamp = dateFormat.format(new Date());
        // Формат: Время,Угол,Мотор(1 или 0)
        String logEntry = String.format(Locale.US, "%s,%.2f,%d\n", timestamp, angle, isMotorOn ? 1 : 0);

        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write(logEntry.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}