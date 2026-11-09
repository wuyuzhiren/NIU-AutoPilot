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

public class NiuWidgetBarProvider extends AppWidgetProvider {
    public static final String ACTION_POWER_ON  = "com.niu.autopilot.BAR_POWER_ON";
    public static final String ACTION_POWER_OFF = "com.niu.autopilot.BAR_POWER_OFF";
    public static final String ACTION_SEAT      = "com.niu.autopilot.BAR_SEAT";
    public static final String ACTION_AUTO      = "com.niu.autopilot.BAR_AUTO";
    public static final String ACTION_REFRESH   = "com.niu.autopilot.BAR_REFRESH";
    public static final String ACTION_DIAGNOSTIC = "com.niu.autopilot.BAR_DIAGNOSTIC";
    /** v2.26: 状态同步广播 */
    public static final String ACTION_STATE_SYNC = "com.niu.autopilot.ACTION_STATE_SYNC";

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
        v.setOnClickPendingIntent(R.id.btn_bar_refresh, pi(ctx, ACTION_REFRESH));
        v.setOnClickPendingIntent(R.id.btn_bar_diagnostic, pi(ctx, ACTION_DIAGNOSTIC));

        Settings s = new Settings(ctx);
        String state = s.getVehicleState();
        boolean isOn = "已开机".equals(state);
        boolean autoRunning = s.isAutoModeRunning();

        setBtnActive(v, R.id.btn_bar_on, R.id.tv_bar_on_label, isOn);
        setBtnActive(v, R.id.btn_bar_off, R.id.tv_bar_off_label, !isOn && !"未知".equals(state));
        setBtnActive(v, R.id.btn_bar_auto, R.id.tv_bar_auto_label, autoRunning);

        v.setTextViewText(R.id.tv_bar_auto_label, autoRunning ? "停止" : "自动");
        v.setTextViewText(R.id.tv_bar_state, autoRunning ? "自动模式运行中" : ("车辆: " + state));

        // v2.20 车辆静态图（根据状态切换）
        int carState = isOn ? 1 : 0;
        Bitmap carBmp = VehicleAnimView.generateStaticBitmap(ctx, carState, 96, 72);
        if (carBmp != null) {
            v.setImageViewBitmap(R.id.iv_bar_car, carBmp);
        }

        // 电量显示
        int battery = s.getBatteryPercent();
        if (battery >= 0) {
            v.setTextViewText(R.id.tv_bar_battery, battery + "%");
            v.setProgressBar(R.id.pb_bar_battery, 100, battery, false);
            int color = 0xFF4CAF50;
            if (battery < 20) color = 0xFFF44336;
            else if (battery < 50) color = 0xFFFF9800;
            v.setTextColor(R.id.tv_bar_battery, color);
        } else {
            v.setTextViewText(R.id.tv_bar_battery, "--%");
            v.setProgressBar(R.id.pb_bar_battery, 100, 0, false);
            v.setTextColor(R.id.tv_bar_battery, 0x80FFFFFF);
        }

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
            case ACTION_REFRESH:   refreshAllData(context); break;
            case ACTION_DIAGNOSTIC: startDiagnostic(context); break;
            case ACTION_STATE_SYNC: refreshAll(context); break;
        }
    }

    private void sendCmd(Context ctx, String type, String label, String newState) {
        Settings s = new Settings(ctx);
        String account = s.getAccount(), password = s.getPassword(), sn = s.getSn();
        if (account.isEmpty() || password.isEmpty() || sn.isEmpty()) {
            setState(ctx, "请先在App填账号");
            return;
        }
        setState(ctx, "⏳ " + label + "中...");
        NiuHttpClient http = new NiuHttpClient(ctx);
        http.sendCommand(account, password, sn, type, (ok, msg) -> {
            if (ok && newState != null) s.setVehicleState(newState);
            setState(ctx, ok ? ("✅ " + label + "成功") : ("❌ " + label + "失败"));
            refreshAll(ctx);
            // v2.26: 命令发送后回查状态+同步电量
            if (ok) NiuWidgetProvider.syncStateAndBattery(ctx, label + "后同步");
        });
    }

    /** v2.26: 同时刷新极驰锐动电量 + 车辆开关机状态 */
    private void refreshAllData(final Context ctx) {
        final Settings s = new Settings(ctx);
        setState(ctx, "刷新电量和状态...");

        final int[] done = {0};
        final int total = 2;
        final String[] batteryResult = {null};
        final String[] stateResult = {null};

        // 1. 极驰锐动电量
        String token = s.getBatteryToken();
        if (token != null && !token.trim().isEmpty()) {
            new NiuBatteryClient(ctx).queryBattery((ok, percent, msg) -> {
                batteryResult[0] = ok ? ("电量" + percent + "%") : "电量失败";
                checkAndRefresh(ctx, done, total, batteryResult, stateResult);
            });
        } else {
            batteryResult[0] = "未配置电量";
            checkAndRefresh(ctx, done, total, batteryResult, stateResult);
        }

        // 2. 车辆开关机状态
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
            setState(ctx, status);
            refreshAll(ctx);
        }
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
