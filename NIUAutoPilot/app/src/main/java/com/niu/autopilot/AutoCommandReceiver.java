package com.niu.autopilot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * v2.50 对外自动化指令接口（小爱同学/Tasker/自动化App调用）。
 * 用法：发送广播 action=com.niu.autopilot.AUTO_COMMAND，extra cmd=power_on/power_off/seat/auto/refresh
 * 例如 Tasker: 发送意图 action=com.niu.autopilot.AUTO_COMMAND extra cmd=power_on
 */
public class AutoCommandReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.niu.autopilot.AUTO_COMMAND";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) cmd = "";
        String targetAction;
        switch (cmd) {
            case "power_on":
            case "on":
            case "开机":
                targetAction = NiuWidgetProvider.ACTION_POWER_ON;
                break;
            case "power_off":
            case "off":
            case "关机":
                targetAction = NiuWidgetProvider.ACTION_POWER_OFF;
                break;
            case "seat":
            case "trunk":
            case "坐桶":
                targetAction = NiuWidgetProvider.ACTION_SEAT;
                break;
            case "auto":
            case "toggle_auto":
                targetAction = NiuWidgetProvider.ACTION_AUTO;
                break;
            case "refresh":
            case "刷新":
                targetAction = NiuWidgetProvider.ACTION_REFRESH;
                break;
            default:
                android.widget.Toast.makeText(context, "未知指令: " + cmd, android.widget.Toast.LENGTH_SHORT).show();
                return;
        }
        // 转发到小部件接收器执行
        context.sendBroadcast(new Intent(context, NiuWidgetProvider.class).setAction(targetAction));
    }
}
