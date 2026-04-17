package com.akamev.corset;

import androidx.annotation.Nullable;

public final class CorsetTelemetry {

    public final float angle;
    @Nullable public final Boolean motorOn;
    @Nullable public final Integer batteryLevel;

    private CorsetTelemetry(float angle, @Nullable Boolean motorOn, @Nullable Integer batteryLevel) {
        this.angle = angle;
        this.motorOn = motorOn;
        this.batteryLevel = batteryLevel;
    }

    @Nullable
    public static CorsetTelemetry parse(@Nullable String payload) {
        if (payload == null) return null;

        String trimmedPayload = payload.trim();
        if (trimmedPayload.isEmpty()) return null;

        String[] parts = trimmedPayload.split(";");
        if (parts.length == 0) return null;

        try {
            float angle = Float.parseFloat(parts[0].trim().replace(',', '.'));
            Boolean motorOn = parseMotor(parts, 1);
            Integer batteryLevel = parseBattery(parts, 2);
            return new CorsetTelemetry(angle, motorOn, batteryLevel);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private static Boolean parseMotor(String[] parts, int index) {
        if (parts.length <= index) return null;

        String value = parts[index].trim();
        if (value.isEmpty()) return null;
        if ("1".equals(value) || "true".equalsIgnoreCase(value)) return Boolean.TRUE;
        if ("0".equals(value) || "false".equalsIgnoreCase(value)) return Boolean.FALSE;
        return null;
    }

    @Nullable
    private static Integer parseBattery(String[] parts, int index) {
        if (parts.length <= index) return null;

        String value = parts[index].trim();
        if (value.isEmpty()) return null;

        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 100) return null;
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
