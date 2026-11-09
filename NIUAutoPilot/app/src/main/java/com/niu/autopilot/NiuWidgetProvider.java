package com.niu.autopilot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

import com.niu.autopilot.ui.VehicleAnimView;

public class NiuWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_POWER_ON  = "com.niu.autopilot.WIDGET_POWER_ON";
    public static final String ACTION_POWER_OFF = "com.niu.autopilot.WIDGET_POWER_OFF";
    public static final String ACTION_SEAT      = "com.niu.autopilot.WIDGET_SEAT";
    public static final String ACTION_AUTO      = "com.niu.autopilot.WIDGET_AUTO";
    public static final String ACTION_REFRESH   = "com.niu.autopilot.WIDGET_REFRESH";
    public static final String ACTION_DIAGNOSTIC = "com.niu.autopilot.WIDGET_DIAGNOSTIC";
    /** v2.26: 状态同步广播，BleService自动触发后通知小部件刷新 */
    public static final String ACTION_STATE_SYNC = "com.niu.autopilot.ACTION_STATE_SYNC";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateWidget(context, appWidgetManager, id);
    }

    private void updateWidget(Context context, AppWidgetManager mgr, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_niu);
        views.setOnClickPendingIntent(R.id.btn_widget_on,   pi(context, ACTION_POWER_ON));
        views.setOnClickPendingIntent(R.id.btn_widget_off,  pi(context, ACTION_POWER_OFF));
        views.setOnClickPendingIntent(R.id.btn_widget_seat,  pi(context, ACTION_SEAT));
        views.setOnClickPendingIntent(R.id.btn_widget_auto,  pi(context, ACTION_AUTO));
        views.setOnClickPendingIntent(R.id.btn_widget_refresh, pi(context, ACTION_REFRESH));
        views.setOnClickPendingIntent(R.id.btn_widget_diagnostic, pi(context, ACTION_DIAGNOSTIC));

        Settings s = new Settings(context);
        views.setTextViewText(R.id.btn_widget_auto, s.isAutoModeRunning() ? "停止自动" : "自动开机");
        String state = s.getVehicleState();
        views.setTextViewText(R.id.tv_widget_status,
                s.isAutoModeRunning() ? "自动模式运行中" : ("车辆: " + state));

        // v2.20 车辆静态图（根据状态切换）
        boolean isOn = "已开机".equals(state);
        int carState = isOn ? 1 : 0;
        Bitmap carBmp = VehicleAnimView.generateStaticBitmap(context, carState, 112, 80);
        if (carBmp != null) {
            views.setImageViewBitmap(R.id.iv_widget_car, carBmp);
        }

        // 电量显示
        int battery = s.getBatteryPercent();
        if (battery >= 0) {
            views.setTextViewText(R.id.tv_widget_battery, battery + "%");
            views.setProgressBar(R.id.pb_widget_battery, 100, battery, false);
            int color = 0xFF4CAF50;
            if (battery < 20) color = 0xFFF44336;
            else if (battery < 50) color = 0xFFFF9800;
            views.setTextColor(R.id.tv_widget_battery, color);
        } else {
            views.setTextViewText(R.id.tv_widget_battery, "--%");
            views.setProgressBar(R.id.pb_widget_battery, 100, 0, false);
            views.setTextColor(R.id.tv_widget_battery, 0x80FFFFFF);
        }

        mgr.updateAppWidget(id, views);
    }

    private PendingIntent pi(Context ctx, String action) {
        Intent intent = new Intent(ctx, NiuWidgetProvider.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, action.hashCode(), intent, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case ACTION_POWER_ON:  sendCmd(context, Settings.CMD_POWER_ON, "开机"); break;
            case ACTION_POWER_OFF: sendCmd(context, Settings.CMD_POWER_OFF, "关机"); break;
            case ACTION_SEAT:      sendCmd(context, Settings.CMD_SEAT, "开坐桶"); break;
            case ACTION_AUTO:      toggleAuto(context); break;
            case ACTION_REFRESH:   refreshAllData(context); break;
            case ACTION_DIAGNOSTIC: startDiagnostic(context); break;
            case ACTION_STATE_SYNC: refreshAll(context); break; // v2.26: 收到状态同步广播直接刷新
        }
    }

    private void sendCmd(Context ctx, String type, String label) {
        Settings s = new Settings(ctx);
        String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            setStatus(ctx, "请先在App内填账号");
            return;
        }
        setStatus(ctx, "⏳ " + label + "中...");
        NiuHttpClient http = new NiuHttpClient(ctx);
        http.sendCommand(account, password, sn, type, (ok, msg) -> {
            if (ok) {
                if ("acc_on".equals(type)) s.setVehicleState("已开机");
                if ("acc_off".equals(type)) s.setVehicleState("已关机");
            }
            setStatus(ctx, ok ? ("✅ " + label + "成功") : ("❌ " + label + "失败"));
            refreshAll(ctx);
            // v2.26: 命令发送后延迟4秒回查状态并同步电量
            if (ok) syncStateAndBattery(ctx, label + "后同步");
        });
    }

    /** v2.26: 同时刷新极驰锐动电量 + 车辆开关机状态 */
    private void refreshAllData(final Context ctx) {
        final Settings s = new Settings(ctx);
        setStatus(ctx, "刷新电量和状态...");

        final int[] done = {0};
        final int total = 2;
        final String[] batteryResult = {null};
        final String[] stateResult = {null};

        // 1. 换电电量（v3.9: 云貂用 Cookie，其余用 token）
        if (NiuBatteryClient.isConfigured(s)) {
            new NiuBatteryClient(ctx).queryBattery((ok, percent, msg) -> {
                batteryResult[0] = ok ? ("电量" + percent + "%") : "电量失败";
                checkAndRefresh(ctx, done, total, batteryResult, stateResult);
            });
        } else {
            batteryResult[0] = "未配置电量";
            checkAndRefresh(ctx, done, total, batteryResult, stateResult);
        }

        // 2. 车辆开关机状态（同时也会刷新小牛电量）
        String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
        if (!account.isEmpty() && !password.isEmpty() && !sn.isEmpty()) {
            new NiuHttpClient(ctx).queryAccState(account, password, sn, (ok, msg) -> {
                if (ok) {
                    String state = "0".equals(msg) ? "已关机" : ("1".equals(msg) ? "已开机" : msg);
                    s.setVehicleState(state);
                    stateResult[0] = state;
                } else {
                    stateResult[0] = "状态失败";
                }
                checkAndRefresh(ctx, done, total, batteryResult, stateResult);
            });
        } else {
            stateResult[0] = "未配置账号";
            checkAndRefresh(ctx, done, total, batteryResult, stateResult);
        }
    }

    private void checkAndRefresh(Context ctx, int[] done, int total,
                                 String[] batteryResult, String[] stateResult) {
        done[0]++;
        if (done[0] >= total) {
            String status = "刷新完成";
            if (batteryResult[0] != null) status += " " + batteryResult[0];
            if (stateResult[0] != null) status += " " + stateResult[0];
            setStatus(ctx, status);
            refreshAll(ctx);
        }
    }

    /** v2.26: 命令发送后回查状态+同步电量，供 BleService 和小部件共用 */
    public static void syncStateAndBattery(final Context ctx, final String reason) {
        final Settings s = new Settings(ctx);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // v3.9: 电量刷新独立于车辆账号配置——每次自动开机都强制刷新换电电量
            //（云貂只需 Cookie，账号未配置也不影响电量）
            if (NiuBatteryClient.isConfigured(s)) {
                new NiuBatteryClient(ctx).queryBattery((ok2, percent, msg2) ->
                        ctx.sendBroadcast(new Intent(ACTION_STATE_SYNC)));
            } else {
                ctx.sendBroadcast(new Intent(ACTION_STATE_SYNC));
            }

            // 车辆开关机状态：仅有小牛账号时才回查
            String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
            if (!account.isEmpty() && !password.isEmpty() && !sn.isEmpty()) {
                new NiuHttpClient(ctx).queryAccState(account, password, sn, (ok, msg) -> {
                    if (ok) {
                        String state = "0".equals(msg) ? "已关机" : ("1".equals(msg) ? "已开机" : msg);
                        s.setVehicleState(state);
                    }
                });
            }
        }, 4000);
    }

    /** v2.38: 小部件一键诊断记录60秒 */
    private void startDiagnostic(Context ctx) {
        try {
            AppLogger logger = AppLogger.get(ctx);
            if (logger.isDiagnosticActive()) {
                android.widget.Toast.makeText(ctx, "诊断记录进行中，剩余 " + logger.getDiagnosticRemaining() + " 秒", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            logger.startDiagnostic(60);
            android.widget.Toast.makeText(ctx, "开始诊断记录60秒，请走到车旁模拟开机场景", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, "诊断启动失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleAuto(Context ctx) {
        Settings s = new Settings(ctx);
        if (s.isAutoModeRunning()) {
            ctx.stopService(new Intent(ctx, BleService.class));
            s.setAutoModeRunning(false);
            setStatus(ctx, "自动模式已停止");
        } else {
            String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
            if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
                setStatus(ctx, "请先在App内填账号");
                return;
            }
            if (s.getDeviceMac().isEmpty()) {
                setStatus(ctx, "请先在App绑定车辆蓝牙");
                return;
            }
            if (!s.isAutoOn() && !s.isAutoOff()) {
                setStatus(ctx, "请先勾选自动开机/关机");
                return;
            }
            Intent i = new Intent(ctx, BleService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            s.setAutoModeRunning(true);
            setStatus(ctx, "自动模式已启动");
        }
        refreshAll(ctx);
    }

    private void setStatus(Context ctx, String text) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_niu);
        views.setTextViewText(R.id.tv_widget_status, text);
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NiuWidgetProvider.class));
        for (int id : ids) mgr.partiallyUpdateAppWidget(id, views);
    }

    private void refreshAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NiuWidgetProvider.class));
        for (int id : ids) updateWidget(ctx, mgr, id);
    }
}
