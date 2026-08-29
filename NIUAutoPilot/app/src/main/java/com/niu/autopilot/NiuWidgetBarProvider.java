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
 * 4×1 全宽快捷栏小部件：开机 / 关机 / 坐桶 / 自动
 * 按钮状态跟随车辆状态互斥高亮
 */
public class NiuWidgetBarProvider extends AppWidgetProvider {
    public static final String ACTION_POWER_ON  = "com.niu.autopilot.BAR_POWER_ON";
    public static final String ACTION_POWER_OFF = "com.niu.autopilot.BAR_POWER_OFF";
    public static final String ACTION_SEAT      = "com.niu.autopilot.BAR_SEAT";
    public static final String ACTION_AUTO      = "com.niu.autopilot.BAR_AUTO";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) updateWidget(context, mgr, id);
    }

    private void updateWidget(Context ctx, AppWidgetManager mgr, int id) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_niu_bar);
        v.setOnClickPendingIntent(R.id.btn_bar_on,   pi(ctx, ACTION_POWER_ON));
        v.setOnClickPendingIntent(R.id.btn_bar_off,  pi(ctx, ACTION_POWER_OFF));
        v.setOnClickPendingIntent(R.id.btn_bar_seat,  pi(ctx, ACTION_SEAT));
        v.setOnClickPendingIntent(R.id.btn_bar_auto,  pi(ctx, ACTION_AUTO));

        Settings s = new Settings(ctx);
        String state = s.getVehicleState();
        boolean isOn = "已开机".equals(state);
        boolean autoRunning = s.isAutoModeRunning();

        // 开机/关机互斥高亮
        setBtnActive(v, R.id.btn_bar_on, R.id.tv_bar_on_label, isOn);
        setBtnActive(v, R.id.btn_bar_off, R.id.tv_bar_off_label, !isOn && !"未知".equals(state));
        setBtnActive(v, R.id.btn_bar_auto, R.id.tv_bar_auto_label, autoRunning);

        v.setTextViewText(R.id.tv_bar_auto_label, autoRunning ? "停止" : "自动");
        v.setTextViewText(R.id.tv_bar_state, autoRunning ? "自动模式运行中" : ("车辆: " + state));

        mgr.updateAppWidget(id, v);
    }

    private void setBtnActive(RemoteViews v, int btnId, int labelId, boolean active) {
        if (active) {
            v.setInt(btnId, "setBackgroundResource", R.drawable.bg_widget_btn_active);
            v.setTextColor(labelId, 0xFFFFFFFF);
        } else {
            v.setInt(btnId, "setBackgroundResource", R.drawable.bg_widget_btn_inactive);
            v.setTextColor(labelId, 0xB3FFFFFF);
        }
    }

    private PendingIntent pi(Context ctx, String action) {
        Intent intent = new Intent(ctx, NiuWidgetBarProvider.class);
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
            case ACTION_POWER_ON:  sendCmd(context, Settings.CMD_POWER_ON, "开机", "已开机"); break;
            case ACTION_POWER_OFF: sendCmd(context, Settings.CMD_POWER_OFF, "关机", "已关机"); break;
            case ACTION_SEAT:      sendCmd(context, Settings.CMD_SEAT, "开坐桶", null); break;
            case ACTION_AUTO:      toggleAuto(context); break;
        }
    }

    private void sendCmd(Context ctx, String type, String label, String newState) {
        Settings s = new Settings(ctx);
        String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            setState(ctx, "请先在App填账号");
            return;
        }
        setState(ctx, "发送" + label + "...");
        NiuHttpClient http = new NiuHttpClient(ctx);
        http.sendCommand(account, password, sn, type, (ok, msg) -> {
            if (ok && newState != null) {
                s.setVehicleState(newState);
            }
            setState(ctx, ok ? (label + "成功") : (label + "失败"));
            refreshAll(ctx);
        });
    }

    private void toggleAuto(Context ctx) {
        Settings s = new Settings(ctx);
        if (s.isAutoModeRunning()) {
            ctx.stopService(new Intent(ctx, BleService.class));
            s.setAutoModeRunning(false);
            setState(ctx, "自动模式已停止");
        } else {
            if (s.getAccount().isEmpty() || s.getPassword().isEmpty() || s.getSn().isEmpty()) {
                setState(ctx, "请先在App填账号"); return;
            }
            if (s.getDeviceMac().isEmpty()) { setState(ctx, "请先绑定车辆蓝牙"); return; }
            if (!s.isAutoOn() && !s.isAutoOff()) { setState(ctx, "请先勾选自动开关"); return; }
            Intent i = new Intent(ctx, BleService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            s.setAutoModeRunning(true);
            setState(ctx, "自动模式已启动");
        }
        refreshAll(ctx);
    }

    private void setState(Context ctx, String text) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_niu_bar);
        v.setTextViewText(R.id.tv_bar_state, text);
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NiuWidgetBarProvider.class));
        for (int id : ids) mgr.partiallyUpdateAppWidget(id, v);
    }

    private void refreshAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, NiuWidgetBarProvider.class));
        for (int id : ids) updateWidget(ctx, mgr, id);
    }
}
