package com.niu.autopilot;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置项集中管理：账号、SN、扫描参数、开关。
 */
public class Settings {
    private final SharedPreferences sp;

    public static final String DEF_SERVICE_UUID = "8ec94e30-f315-4f60-9fb8-838830daea50";
    // 已实测确认的 HTTP 命令 type
    public static final String CMD_POWER_ON  = "acc_on";          // 开机
    public static final String CMD_POWER_OFF = "acc_off";         // 关机
    public static final String CMD_SEAT      = "cushion_lock_on"; // 开坐桶
    public static final String CMD_LOCK      = "fortification_on"; // 设防/锁车
    public static final String CMD_UNLOCK    = "fortification_off";// 撤防/解锁

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

    /** 用户在小牛蓝牙列表里选中的车辆设备 MAC（用于自动模式测距） */
    public String getDeviceMac() { return sp.getString("device_mac", ""); }
    public void setDeviceMac(String v) { sp.edit().putString("device_mac", v).apply(); }

    /** 选中的设备显示名（仅展示用） */
    public String getDeviceName() { return sp.getString("device_name", ""); }
    public void setDeviceName(String v) { sp.edit().putString("device_name", v).apply(); }

    public String getServiceUuid() { return sp.getString("service_uuid", DEF_SERVICE_UUID); }
    public void setServiceUuid(String v) { sp.edit().putString("service_uuid", v).apply(); }

    /** 扫描间隔（毫秒），默认500ms，范围100~5000ms */
    public int getScanIntervalMs() { return sp.getInt("scan_interval_ms", 500); }
    public void setScanIntervalMs(int v) { sp.edit().putInt("scan_interval_ms", v).apply(); }

    public int getRssiThreshold() { return sp.getInt("rssi_threshold", -60); }
    public void setRssiThreshold(int v) { sp.edit().putInt("rssi_threshold", v).apply(); }

    public int getRssiLeaveThreshold() { return sp.getInt("rssi_leave", -80); }
    public void setRssiLeaveThreshold(int v) { sp.edit().putInt("rssi_leave", v).apply(); }

    public int getAutoOnCooldownSec() { return sp.getInt("auto_on_cooldown", 30); }
    public void setAutoOnCooldownSec(int v) { sp.edit().putInt("auto_on_cooldown", v).apply(); }

    public boolean isAutoOn() { return sp.getBoolean("auto_on", true); }
    public void setAutoOn(boolean v) { sp.edit().putBoolean("auto_on", v).apply(); }

    public boolean isAutoOff() { return sp.getBoolean("auto_off", false); }
    public void setAutoOff(boolean v) { sp.edit().putBoolean("auto_off", v).apply(); }

    public boolean isRememberPassword() { return sp.getBoolean("remember_pwd", false); }
    public void setRememberPassword(boolean v) { sp.edit().putBoolean("remember_pwd", v).apply(); }

    /** 主题模式：0=跟随系统，1=浅色，2=深色 */
    public int getThemeMode() { return sp.getInt("theme_mode", 0); }
    public void setThemeMode(int v) { sp.edit().putInt("theme_mode", v).apply(); }

    /** 自动模式是否运行中（widget用） */
    public boolean isAutoModeRunning() { return sp.getBoolean("auto_running", false); }
    public void setAutoModeRunning(boolean v) { sp.edit().putBoolean("auto_running", v).apply(); }

    /** 车辆当前状态（widget用）：已开机/已关机/未知 */
    public String getVehicleState() { return sp.getString("vehicle_state", "未知"); }
    public void setVehicleState(String v) { sp.edit().putString("vehicle_state", v).apply(); }
}
