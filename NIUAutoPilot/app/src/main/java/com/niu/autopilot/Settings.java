package com.niu.autopilot;

import android.content.Context;
import android.content.SharedPreferences;

public class Settings {
    private final SharedPreferences sp;

    public static final String DEF_SERVICE_UUID = "8ec94e30-f315-4f60-9fb8-838830daea50";
    public static final String CMD_POWER_ON  = "acc_on";
    public static final String CMD_POWER_OFF = "acc_off";
    public static final String CMD_SEAT      = "cushion_lock_on";
    public static final String CMD_LOCK      = "fortification_on";
    public static final String CMD_UNLOCK    = "fortification_off";

    public static final int PRESET_BALANCED = 0;
    public static final int PRESET_AGGRESSIVE = 1;
    public static final int PRESET_STABLE = 2;

    public Settings(Context ctx) {
        sp = ctx.getSharedPreferences("niu_autopilot", Context.MODE_PRIVATE);
    }

    public SharedPreferences getSp() { return sp; }

    public String getAccount() { return sp.getString("account", ""); }
    public void setAccount(String v) { sp.edit().putString("account", v).apply(); }

    public String getPassword() { return sp.getString("password", ""); }
    public void setPassword(String v) { sp.edit().putString("password", v).apply(); }

    public String getSn() { return sp.getString("sn", ""); }
    public void setSn(String v) { sp.edit().putString("sn", v).apply(); }

    public String getDeviceMac() { return sp.getString("device_mac", ""); }
    public void setDeviceMac(String v) { sp.edit().putString("device_mac", v).apply(); }

    public String getDeviceName() { return sp.getString("device_name", ""); }
    public void setDeviceName(String v) { sp.edit().putString("device_name", v).apply(); }

    public String getServiceUuid() { return sp.getString("service_uuid", DEF_SERVICE_UUID); }
    public void setServiceUuid(String v) { sp.edit().putString("service_uuid", v).apply(); }

    public int getScanIntervalMs() { return sp.getInt("scan_interval_ms", 700); }
    public void setScanIntervalMs(int v) { sp.edit().putInt("scan_interval_ms", v).apply(); }

    public int getRssiThreshold() { return sp.getInt("rssi_threshold", -60); }
    public void setRssiThreshold(int v) { sp.edit().putInt("rssi_threshold", v).apply(); }

    public int getRssiLeaveThreshold() { return sp.getInt("rssi_leave", -80); }
    public void setRssiLeaveThreshold(int v) { sp.edit().putInt("rssi_leave", v).apply(); }

    public int getHysteresisDb() { return getRssiThreshold() - getRssiLeaveThreshold(); }
    public void setHysteresisDb(int v) { setRssiLeaveThreshold(getRssiThreshold() - v); }

    public int getAutoOnCooldownSec() { return sp.getInt("auto_on_cooldown", 30); }
    public void setAutoOnCooldownSec(int v) { sp.edit().putInt("auto_on_cooldown", v).apply(); }

    public boolean isAutoOn() { return sp.getBoolean("auto_on", true); }
    public void setAutoOn(boolean v) { sp.edit().putBoolean("auto_on", v).apply(); }

    public boolean isAutoOff() { return sp.getBoolean("auto_off", false); }
    public void setAutoOff(boolean v) { sp.edit().putBoolean("auto_off", v).apply(); }

    public boolean isRememberPassword() { return sp.getBoolean("remember_pwd", false); }
    public void setRememberPassword(boolean v) { sp.edit().putBoolean("remember_pwd", v).apply(); }

    public int getThemeMode() { return sp.getInt("theme_mode", 0); }
    public void setThemeMode(int v) { sp.edit().putInt("theme_mode", v).apply(); }

    public boolean isAutoModeRunning() { return sp.getBoolean("auto_running", false); }
    public void setAutoModeRunning(boolean v) { sp.edit().putBoolean("auto_running", v).apply(); }

    public String getVehicleState() { return sp.getString("vehicle_state", "未知"); }
    public void setVehicleState(String v) { sp.edit().putString("vehicle_state", v).apply(); }

    // v2.12 新增
    public int getPresetMode() { return sp.getInt("preset_mode", PRESET_BALANCED); }
    public void setPresetMode(int v) { sp.edit().putInt("preset_mode", v).apply(); }

    public int getConsecutiveRequired() { return sp.getInt("consecutive_required", 3); }
    public void setConsecutiveRequired(int v) { sp.edit().putInt("consecutive_required", v).apply(); }

    public boolean isVibrationEnabled() { return sp.getBoolean("vibration_enabled", true); }
    public void setVibrationEnabled(boolean v) { sp.edit().putBoolean("vibration_enabled", v).apply(); }

    // v2.66 触发声音反馈（R3：多通道触发反馈，声音+震动+通知）
    public boolean isSoundEnabled() { return sp.getBoolean("sound_enabled", true); }
    public void setSoundEnabled(boolean v) { sp.edit().putBoolean("sound_enabled", v).apply(); }

    public float getCurrentRssi() { return sp.getFloat("current_rssi", 0f); }
    public void setCurrentRssi(float v) { sp.edit().putFloat("current_rssi", v).apply(); }

    public long getLastTriggerTime() { return sp.getLong("last_trigger_time", 0); }
    public void setLastTriggerTime(long v) { sp.edit().putLong("last_trigger_time", v).apply(); }

    public String getLastTriggerAction() { return sp.getString("last_trigger_action", ""); }
    public void setLastTriggerAction(String v) { sp.edit().putString("last_trigger_action", v).apply(); }

    // v2.13 车辆电量
    public int getBatteryLevel() { return sp.getInt("battery_level", -1); }
    public void setBatteryLevel(int v) { sp.edit().putInt("battery_level", v).apply(); }
    public long getBatteryUpdateTime() { return sp.getLong("battery_update_time", 0); }
    public void setBatteryUpdateTime(long v) { sp.edit().putLong("battery_update_time", v).apply(); }

    public void applyPreset(int mode) {
        switch (mode) {
            case PRESET_AGGRESSIVE:
                setRssiThreshold(-65); setHysteresisDb(15);
                setConsecutiveRequired(1); setAutoOnCooldownSec(10); setScanIntervalMs(500);
                break;
            case PRESET_STABLE:
                setRssiThreshold(-55); setHysteresisDb(25);
                setConsecutiveRequired(3); setAutoOnCooldownSec(60); setScanIntervalMs(1000);
                break;
            case PRESET_BALANCED:
            default:
                setRssiThreshold(-60); setHysteresisDb(20);
                setConsecutiveRequired(2); setAutoOnCooldownSec(30); setScanIntervalMs(700);
                break;
        }
        setPresetMode(mode);
    }

    // v2.32 AI 智能调参配置
    public String getAiApiBase() { return sp.getString("ai_api_base", "https://api.siliconflow.cn/v1"); }
    public void setAiApiBase(String v) { sp.edit().putString("ai_api_base", v).apply(); }
    public String getAiApiKey() { return sp.getString("ai_api_key", ""); }
    public void setAiApiKey(String v) { sp.edit().putString("ai_api_key", v).apply(); }
    public String getAiModel() { return sp.getString("ai_model", ""); }
    public void setAiModel(String v) { sp.edit().putString("ai_model", v).apply(); }

    // v2.30 GitHub Token（用于上传诊断日志）
    public String getGithubToken() { return sp.getString("github_token", ""); }
    public void setGithubToken(String v) { sp.edit().putString("github_token", v).apply(); }

    // ========== v2.13 电量（极驰锐动租电） ==========
    /** 电量百分比，-1 = 未同步 */
    public int getBatteryPercent() { return sp.getInt("battery_percent", -1); }
    public void setBatteryPercent(int v) { sp.edit().putInt("battery_percent", Math.max(-1, Math.min(100, v))).apply(); }

    /** 电量同步时间戳（毫秒），0 = 从未同步 */
    public long getBatterySyncedAt() { return sp.getLong("battery_synced_at", 0); }
    public void setBatterySyncedAt(long v) { sp.edit().putLong("battery_synced_at", v).apply(); }

    /** 电量来源："" 未同步 / "manual" 手动 / "api" 极驰锐动接口 */
    public String getBatterySource() { return sp.getString("battery_source", ""); }
    public void setBatterySource(String v) { sp.edit().putString("battery_source", v).apply(); }

    // ========== v2.15 极驰锐动电量 token ==========
    /** 极驰锐动 JWT token（Charles 抓包 Authorization 请求头） */
    public String getBatteryToken() { return sp.getString("battery_token", ""); }
    public void setBatteryToken(String v) { sp.edit().putString("battery_token", v).apply(); }

    // ========== v2.63 多平台电池支持 ==========
    /** 电池平台：jichi=极驰锐动 / custom=自定义API */
    public String getBatteryPlatform() { return sp.getString("battery_platform", "jichi"); }
    public void setBatteryPlatform(String v) { sp.edit().putString("battery_platform", v).apply(); }

    /** 自定义平台接口URL（GET，Authorization带token） */
    public String getBatteryApiUrl() { return sp.getString("battery_api_url", ""); }
    public void setBatteryApiUrl(String v) { sp.edit().putString("battery_api_url", v).apply(); }

    // ========== v2.68 鑫能出行电池ID ==========
    /** 鑫能出行电池ID（device/detail?idDevice=xxx，换电池后重新抓包获取） */
    public String getBatteryDeviceId() { return sp.getString("battery_device_id", ""); }
    public void setBatteryDeviceId(String v) { sp.edit().putString("battery_device_id", v).apply(); }

    // ========== v2.77 鑫能出行请求头可配置（默认保留抓包原值，接口变更时可改） ==========
    /** 鑫能 appId（默认微信小程序ID wxe6f87fdd010fce3e） */
    public String getBatteryAppId() { return sp.getString("battery_app_id", "wxe6f87fdd010fce3e"); }
    public void setBatteryAppId(String v) { sp.edit().putString("battery_app_id", v).apply(); }
    /** 鑫能 sign/identify（默认 GYXNKJHD，可留空表示不发送） */
    public String getBatterySign() { return sp.getString("battery_sign", "GYXNKJHD"); }
    public void setBatterySign(String v) { sp.edit().putString("battery_sign", v).apply(); }

    // ========== v2.13 信号滤波窗口（1~10，默认3） ==========
    public int getFilterWindow() { return Math.max(1, Math.min(10, sp.getInt("filter_window", 3))); }
    public void setFilterWindow(int v) { sp.edit().putInt("filter_window", Math.max(1, Math.min(10, v))).apply(); }

    // ========== v2.22 自动更新 ==========
    /** 版本检查JSON地址，默认内置GitHub Raw地址 */
    private static final String DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/wuyuzhiren/NIU-AutoPilot/main/version.json";
    public String getUpdateUrl() {
        String v = sp.getString("update_url", "");
        return (v == null || v.trim().isEmpty()) ? DEFAULT_UPDATE_URL : v;
    }
    public void setUpdateUrl(String v) { sp.edit().putString("update_url", v).apply(); }
    /** 启动时自动检查更新，默认true */
    public boolean isAutoCheckUpdate() { return sp.getBoolean("auto_check_update", true); }
    public void setAutoCheckUpdate(boolean v) { sp.edit().putBoolean("auto_check_update", v).apply(); }
    /** 上次检查更新时间 */
    public long getLastUpdateCheck() { return sp.getLong("last_update_check", 0); }
    public void setLastUpdateCheck(long v) { sp.edit().putLong("last_update_check", v).apply(); }

    // ========== v2.42 校准历史（最近3次，可回退） ==========
    private static final String KEY_CALIB_HISTORY = "calib_history_json";

    /** 读取校准历史 JSON 数组字符串，空则返回 "[]" */
    public String getCalibrationHistory() {
        String v = sp.getString(KEY_CALIB_HISTORY, "");
        return (v == null || v.trim().isEmpty()) ? "[]" : v;
    }

    /** 保存一次校准结果到历史（最多保留3条，新在前） */
    public void saveCalibrationHistory(com.niu.autopilot.calibration.CalibrationResult r) {
        if (r == null) return;
        String current = getCalibrationHistory();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(current);
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("time", System.currentTimeMillis());
            item.put("near", r.nearThreshold);
            item.put("far", r.farThreshold);
            item.put("hysteresis", r.hysteresis);
            item.put("confidence", r.confidence);
            item.put("nearDist", r.nearDistanceM);
            item.put("farDist", r.farDistanceM);
            item.put("environment", r.environment == null ? "" : r.environment);            item.put("error", r.verifyErrorM >= 0 ? (Math.round(r.verifyErrorM * 10) / 10.0) : "");
            next.put(item);
            for (int i = 0; i < arr.length() && next.length() < 5; i++) {
                next.put(arr.get(i));
            }
            sp.edit().putString(KEY_CALIB_HISTORY, next.toString()).apply();
        } catch (Exception e) {
            sp.edit().putString(KEY_CALIB_HISTORY, current).apply();
        }
    }

    /** 清空校准历史 */
    public void clearCalibrationHistory() {
        sp.edit().putString(KEY_CALIB_HISTORY, "[]").apply();
    }

    // ========== v2.43 校准增强：快速模式/自动应用/地点/历史摘要 ==========
    public boolean isQuickCalibration() { return sp.getBoolean("quick_calib", false); }
    public void setQuickCalibration(boolean v) { sp.edit().putBoolean("quick_calib", v).apply(); }

    public boolean isAutoApplyAfterVerify() { return sp.getBoolean("auto_apply_after_verify", false); }
    public void setAutoApplyAfterVerify(boolean v) { sp.edit().putBoolean("auto_apply_after_verify", v).apply(); }

    /** 最近校准摘要："今天 12:30・精度优秀"，无则返回空串 */
    public String getLastCalibSummary() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getCalibrationHistory());
            if (arr.length() == 0) return "";
            org.json.JSONObject o = arr.getJSONObject(0);
            long t = o.optLong("time", 0);
            String err = o.optString("error", "");
            String day = formatCalibDay(t);
            String qual = err.isEmpty() ? "已校准" : err;
            return day + "・" + qual;
        } catch (Exception e) { return ""; }
    }

    /** 当前参数来源："家地点校准・3天前"，无则返回空串 */
    public String getParamSource() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getCalibrationHistory());
            if (arr.length() == 0) return "";
            org.json.JSONObject o = arr.getJSONObject(0);
            String place = o.optString("place", "");
            long t = o.optLong("time", 0);
            String source = place.isEmpty() ? "标准校准" : place + "校准";
            return source + "・" + formatCalibDay(t);
        } catch (Exception e) { return ""; }
    }

    // ========== v2.49 场景模式：校准结果沉淀为可切换场景 ==========
    public String getScenes() {
        String v = sp.getString("scenes_json", "");
        return (v == null || v.trim().isEmpty()) ? "[]" : v;
    }

    /** 保存场景（同名覆盖），并设为当前激活 */
    public void saveScene(String name, com.niu.autopilot.calibration.CalibrationResult r, double lat, double lng) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getScenes());
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("name", name);
            item.put("near", r.nearThreshold);
            item.put("far", r.farThreshold);
            item.put("hysteresis", r.hysteresis);
            item.put("env", r.environment == null ? "" : r.environment);
            item.put("a", r.modelA);
            item.put("n", r.modelN);
            item.put("lat", lat);
            item.put("lng", lng);
            item.put("time", System.currentTimeMillis());
            boolean replaced = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                if (name.equals(o.optString("name"))) { next.put(item); replaced = true; }
                else { next.put(o); }
            }
            if (!replaced) next.put(item);
            sp.edit().putString("scenes_json", next.toString()).putString("active_scene", name).apply();
        } catch (Exception ignored) {}
    }

    /** 应用第 idx 个场景参数到全局 */
    public boolean applyScene(int idx) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getScenes());
            if (idx < 0 || idx >= arr.length()) return false;
            org.json.JSONObject o = arr.getJSONObject(idx);
            sp.edit()
                .putInt("rssi_threshold", o.optInt("near", -60))
                .putInt("rssi_leave", o.optInt("far", -80))
                .putString("active_scene", o.optString("name", "")).apply();
            return true;
        } catch (Exception e) { return false; }
    }

    public void deleteScene(int idx) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getScenes());
            if (idx < 0 || idx >= arr.length()) return;
            arr.remove(idx);
            sp.edit().putString("scenes_json", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public String getActiveSceneName() { return sp.getString("active_scene", ""); }

    private static String formatCalibDay(long t) {
        if (t <= 0) return "长期";
        long days = (System.currentTimeMillis() - t) / (24L * 3600L * 1000L);
        if (days <= 0) return "今天";
        if (days == 1) return "昨天";
        if (days < 7) return days + "天前";
        if (days < 30) return (days / 7) + "周前";
        return (days / 30) + "个月前";
    }
}
