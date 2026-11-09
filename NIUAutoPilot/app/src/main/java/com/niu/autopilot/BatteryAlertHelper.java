package com.niu.autopilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import java.lang.ref.WeakReference;

/**
 * v3.9 低电量分级提醒。
 *
 * 阈值 30% / 20% / 15% / 10%，每个档位只提醒一次：
 *  - 电量从高处降入某档时提醒；
 *  - 停留在同档或继续小幅下降不重复提醒；
 *  - 电量回升到 30% 以上后重置，下次再降会重新提醒。
 *
 * App 在前台弹 AlertDialog，在后台（自动开机由服务触发）发高优先级通知。
 */
public class BatteryAlertHelper {

    private static final String CHANNEL_ID = "battery_alert";
    private static final int NOTIF_ID = 3001;

    private static WeakReference<MainActivity> sActivity = new WeakReference<>(null);
    private static boolean sResumed = false;
    private static boolean sDialogShowing = false;

    /** MainActivity.onResume 调用 */
    public static void onResume(MainActivity activity) {
        sActivity = new WeakReference<>(activity);
        sResumed = true;
        // 打开 App 时补检查一次当前缓存电量（已提醒过的档位不会重复弹）
        Settings s = new Settings(activity);
        int p = s.getBatteryPercent();
        if (p >= 0) check(activity, p, NiuBatteryClient.platformName(s));
    }

    /** MainActivity.onPause 调用 */
    public static void onPause() {
        sResumed = false;
    }

    /** MainActivity.onDestroy 调用 */
    public static void detach(MainActivity activity) {
        if (sActivity.get() == activity) {
            sActivity = new WeakReference<>(null);
            sResumed = false;
            sDialogShowing = false;
        }
    }

    /**
     * 电量更新后调用（可在任意线程）。
     * @param percent 0~100，<0 表示未同步
     * @param platform 平台显示名，用于提示文案
     */
    public static void check(final Context ctx, final int percent, final String platform) {
        if (percent < 0 || percent > 100) return;
        final Context app = ctx.getApplicationContext();
        final Settings s = new Settings(app);

        // 回升到 30% 以上，重置提醒档位
        if (percent > 30) {
            if (s.getBatteryAlertStage() != 0) s.setBatteryAlertStage(0);
            return;
        }

        final int want = percent <= 10 ? 10 : (percent <= 15 ? 15 : (percent <= 20 ? 20 : 30));
        int stage = s.getBatteryAlertStage();
        // 电量回升（例如换了一块 25% 的电池）：把档位重置为当前档，不重复提醒当前档，
        // 但保证之后降到 20/15/10 时仍会提醒
        if (stage > 0 && percent > stage) {
            s.setBatteryAlertStage(want);
            return;
        }
        // stage 是已提醒过的最低档；want >= stage 说明这一档已经提醒过
        if (stage > 0 && want >= stage) return;
        s.setBatteryAlertStage(want);

        final String title = "电量不足提醒";
        final String msg = buildMessage(percent, want, platform);

        new Handler(Looper.getMainLooper()).post(() -> {
            MainActivity a = sActivity.get();
            if (sResumed && a != null && !a.isFinishing() && !a.isDestroyed() && !sDialogShowing) {
                try {
                    sDialogShowing = true;
                    new AlertDialog.Builder(a)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton("知道了", (d, w) -> sDialogShowing = false)
                            .setOnDismissListener(d -> sDialogShowing = false)
                            .show();
                    return;
                } catch (Exception ignored) {
                    sDialogShowing = false;
                }
            }
            // 后台（例如自动开机时）发通知
            showNotification(app, title, msg);
        });
    }

    private static String buildMessage(int percent, int level, String platform) {
        String src = (platform == null || platform.isEmpty()) ? "" : "（" + platform + "）";
        switch (level) {
            case 10:
                return "当前电量仅剩 " + percent + "%" + src + "，随时可能断电，请立即前往换电柜换电！";
            case 15:
                return "当前电量仅剩 " + percent + "%" + src + "，请立即换电，避免半路断电。";
            case 20:
                return "当前电量 " + percent + "%" + src + "，已低于 20%，请尽快换电。";
            default:
                return "当前电量 " + percent + "%" + src + "，已低于 30%，建议顺路前往换电柜换电。";
        }
    }

    private static void showNotification(Context ctx, String title, String msg) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "低电量提醒",
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("电池电量低于 30% 时提醒换电");
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(ctx, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHANNEL_ID);
            else b = new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(pi);
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception ignored) {
        }
    }
}
