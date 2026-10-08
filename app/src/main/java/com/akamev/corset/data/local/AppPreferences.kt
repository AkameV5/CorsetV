package com.akamev.corset.data.local

import android.content.Context
import com.akamev.corset.domain.model.PostureAlertMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppPreferences(context: Context) {

    private val appPrefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
    private val bluetoothPrefs = context.getSharedPreferences(BLUETOOTH_PREFS, Context.MODE_PRIVATE)

    fun isCalibrationDone(): Boolean = appPrefs.getBoolean(KEY_CALIBRATION_DONE, false)

    fun setCalibrationDone(isDone: Boolean) {
        appPrefs.edit().putBoolean(KEY_CALIBRATION_DONE, isDone).apply()
    }

    fun getBaselineAngle(): Float? {
        val value = appPrefs.getFloat(KEY_BASELINE_ANGLE, Float.NaN)
        return value.takeUnless { it.isNaN() }
    }

    fun saveBaselineAngle(value: Float) {
        appPrefs.edit().putFloat(KEY_BASELINE_ANGLE, value).apply()
    }

    fun clearBaselineAngle() {
        appPrefs.edit().putFloat(KEY_BASELINE_ANGLE, Float.NaN).apply()
    }

    fun getLastBatteryLevel(): Int? {
        return if (appPrefs.contains(KEY_LAST_BATTERY)) {
            appPrefs.getInt(KEY_LAST_BATTERY, -1).takeIf { it >= 0 }
        } else {
            null
        }
    }

    fun saveLastBatteryLevel(level: Int) {
        appPrefs.edit().putInt(KEY_LAST_BATTERY, level).apply()
    }

    fun clearLastBatteryLevel() {
        appPrefs.edit().remove(KEY_LAST_BATTERY).apply()
    }

    fun getAlertMode(): PostureAlertMode {
        val savedName = appPrefs.getString(KEY_ALERT_MODE, null)
        return PostureAlertMode.entries.firstOrNull { it.name == savedName } ?: PostureAlertMode.Precise
    }

    fun saveAlertMode(mode: PostureAlertMode) {
        appPrefs.edit().putString(KEY_ALERT_MODE, mode.name).apply()
    }

    fun getCustomAlertAngle(): Float = appPrefs.getFloat(KEY_CUSTOM_ALERT_ANGLE, DEFAULT_CUSTOM_ALERT_ANGLE)

    fun saveCustomAlertAngle(value: Float) {
        appPrefs.edit().putFloat(KEY_CUSTOM_ALERT_ANGLE, value).apply()
    }

    fun getResolvedAlertAngle(): Float {
        val mode = getAlertMode()
        return mode.resolveAngle(getCustomAlertAngle())
    }


    fun getPreferredFocusSessionDurationMinutes(): Int {
        val stored = appPrefs.getInt(KEY_PREFERRED_FOCUS_SESSION_MINUTES, DEFAULT_FOCUS_SESSION_MINUTES)
        return stored.coerceIn(MIN_FOCUS_SESSION_MINUTES, MAX_FOCUS_SESSION_MINUTES)
    }

    fun getPreferredFocusSessionDurationMs(): Long {
        return getPreferredFocusSessionDurationMinutes() * 60_000L
    }

    fun savePreferredFocusSessionDurationMinutes(minutes: Int) {
        appPrefs.edit()
            .putInt(
                KEY_PREFERRED_FOCUS_SESSION_MINUTES,
                minutes.coerceIn(MIN_FOCUS_SESSION_MINUTES, MAX_FOCUS_SESSION_MINUTES),
            )
            .apply()
    }

    fun startFocusSession(durationMs: Long) {
        val now = System.currentTimeMillis()
        appPrefs.edit()
            .putLong(KEY_FOCUS_SESSION_START_AT, now)
            .putLong(KEY_FOCUS_SESSION_END_AT, now + durationMs)
            .apply()
    }

    fun getFocusSessionStartAt(): Long? {
        val value = appPrefs.getLong(KEY_FOCUS_SESSION_START_AT, -1L)
        return value.takeIf { it > 0L }
    }

    fun getFocusSessionEndAt(): Long? {
        val value = appPrefs.getLong(KEY_FOCUS_SESSION_END_AT, -1L)
        return value.takeIf { it > 0L }
    }

    fun isFocusSessionActive(now: Long = System.currentTimeMillis()): Boolean {
        val endAt = getFocusSessionEndAt() ?: return false
        return endAt > now
    }

    fun getFocusSessionRemainingMs(now: Long = System.currentTimeMillis()): Long {
        val endAt = getFocusSessionEndAt() ?: return 0L
        return (endAt - now).coerceAtLeast(0L)
    }

    fun clearFocusSession() {
        appPrefs.edit()
            .remove(KEY_FOCUS_SESSION_START_AT)
            .remove(KEY_FOCUS_SESSION_END_AT)
            .apply()
    }

    fun getSavedDeviceAddress(): String? = bluetoothPrefs.getString(KEY_SAVED_DEVICE_ADDRESS, null)

    fun saveDeviceAddress(address: String) {
        bluetoothPrefs.edit().putString(KEY_SAVED_DEVICE_ADDRESS, address).apply()
    }

    fun clearSavedDeviceAddress() {
        bluetoothPrefs.edit().remove(KEY_SAVED_DEVICE_ADDRESS).apply()
    }

    fun isGuestMode(): Boolean = appPrefs.getBoolean(KEY_GUEST_MODE, false)

    fun setGuestMode(isGuest: Boolean) {
        appPrefs.edit().putBoolean(KEY_GUEST_MODE, isGuest).apply()
    }

    fun getGuestFirstName(): String = appPrefs.getString(KEY_GUEST_FIRST_NAME, "Пользователь") ?: "Пользователь"

    fun getGuestLastName(): String = appPrefs.getString(KEY_GUEST_LAST_NAME, "") ?: ""

    fun saveGuestName(firstName: String, lastName: String) {
        appPrefs.edit()
            .putString(KEY_GUEST_FIRST_NAME, firstName)
            .putString(KEY_GUEST_LAST_NAME, lastName)
            .apply()
    }

    fun getGuestStreak(): Long = appPrefs.getLong(KEY_GUEST_STREAK, 0L)

    fun saveGuestStreak(streak: Long) {
        appPrefs.edit().putLong(KEY_GUEST_STREAK, streak).apply()
    }

    fun todayKey(): String = DATE_FORMAT.format(Date())

    private companion object {
        const val APP_PREFS = "AppPrefs"
        const val BLUETOOTH_PREFS = "BluetoothPrefs"

        const val KEY_CALIBRATION_DONE = "calibration_done"
        const val KEY_BASELINE_ANGLE = "saved_baseline"
        const val KEY_LAST_BATTERY = "last_battery"
        const val KEY_ALERT_MODE = "alert_mode"
        const val KEY_CUSTOM_ALERT_ANGLE = "custom_alert_angle"
        const val KEY_PREFERRED_FOCUS_SESSION_MINUTES = "preferred_focus_session_minutes"
        const val KEY_FOCUS_SESSION_START_AT = "focus_session_start_at"
        const val KEY_FOCUS_SESSION_END_AT = "focus_session_end_at"
        const val KEY_SAVED_DEVICE_ADDRESS = "saved_device_address"
        const val KEY_GUEST_MODE = "guest_mode"
        const val KEY_GUEST_FIRST_NAME = "guest_first_name"
        const val KEY_GUEST_LAST_NAME = "guest_last_name"
        const val KEY_GUEST_STREAK = "guest_streak"
        const val DEFAULT_CUSTOM_ALERT_ANGLE = 7f
        const val MIN_FOCUS_SESSION_MINUTES = 5
        const val MAX_FOCUS_SESSION_MINUTES = 180
        const val DEFAULT_FOCUS_SESSION_MINUTES = 45

        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
}
