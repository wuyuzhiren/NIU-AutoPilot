package com.niu.autopilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 看门狗：AlarmManager 定时触发 + 开机启动，检查并重启 BleService。
 * 即使进程被 MIUI/系统杀掉，AlarmManager 仍会触发，从而拉起服务。
 */
public class WatchdogReceiver extends BroadcastReceiver {
    public static final String ACTION_WATCHDOG = "com.niu.autopilot.WATCHDOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        // 看门狗闹钟 或 开机完成
        if (ACTION_WATCHDOG.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Settings s = new Settings(context);
            if (s.isAutoModeRunning()) {
                // 应该运行但可能被杀了，重新启动服务（幂等，已在运行则不重复）
                Intent serviceIntent = new Intent(context, BleService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Exception e) {
                    // 忽略，下次看门狗再试
                }
            }
        }
    }
}
