package com.niu.autopilot;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * 桌面小部件：开机 / 关机 / 开坐桶 / 自动开机
 */
public class NiuWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_POWER_ON  = "com.niu.autopilot.WIDGET_POWER_ON";
    public static final String ACTION_POWER_OFF = "com.niu.autopilot.WIDGET_POWER_OFF";
    public static final String ACTION_SEAT      = "com.niu.autopilot.WIDGET_SEAT";
    public static final String ACTION_AUTO      = "com.niu.autopilot.WIDGET_AUTO";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
    }

    private void updateWidget(Context context, AppWidgetManager mgr, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_niu);
        views.setOnClickPendingIntent(R.id.btn_widget_on,   pi(context, ACTION_POWER_ON));
        views.setOnClickPendingIntent(R.id.btn_widget_off,  pi(context, ACTION_POWER_OFF));
        views.setOnClickPendingIntent(R.id.btn_widget_seat,  pi(context, ACTION_SEAT));
        views.setOnClickPendingIntent(R.id.btn_widget_auto,  pi(context, ACTION_AUTO));

        // 自动按钮文字根据运行状态切换
        Settings s = new Settings(context);
        views.setTextViewText(R.id.btn_widget_auto, s.isAutoModeRunning() ? "停止自动" : "自动开机");
        String state = s.getVehicleState();
        views.setTextViewText(R.id.tv_widget_status,
                s.isAutoModeRunning() ? "自动模式运行中" : ("车辆: " + state));

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
        }
    }

    private void sendCmd(Context ctx, String type, String label) {
        Settings s = new Settings(ctx);
        String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            setStatus(ctx, "请先在App内填账号");
            return;
        }
        setStatus(ctx, "发送" + label + "...");
        NiuHttpClient http = new NiuHttpClient(ctx);
        http.sendCommand(account, password, sn, type, (ok, msg) -> {
            if (ok) {
                if ("acc_on".equals(type)) s.setVehicleState("已开机");
                if ("acc_off".equals(type)) s.setVehicleState("已关机");
            }
            setStatus(ctx, ok ? (label + "成功") : (label + "失败"));
            refreshAll(ctx);
        });
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
