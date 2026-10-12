package com.niu.autopilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v2.38 诊断日志记录器（支持一键诊断记录）
 * 普通日志按天存储，诊断日志单独存为 diagnostic_*.txt，供定时上传分析
 */
public class AppLogger {
    private static final String TAG = "AppLogger";
    private static final int KEEP_DAYS = 14;
    private static AppLogger instance;

    private final Context ctx;
    private final File logDir;
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private final SimpleDateFormat diagFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    // v2.38: 诊断记录状态
    private File diagnosticFile;
    private boolean diagnosticActive;
    private int diagnosticDuration;
    private long diagnosticStartTime;
    private final Handler diagHandler = new Handler(Looper.getMainLooper());

    public static synchronized AppLogger get(Context ctx) {
        if (instance == null) {
            instance = new AppLogger(ctx.getApplicationContext());
        }
        return instance;
    }

    private AppLogger(Context ctx) {
        this.ctx = ctx;
        this.logDir = new File(ctx.getExternalFilesDir(null), "logs");
        if (!logDir.exists()) logDir.mkdirs();
        cleanupOldLogs();
    }

    /** 记录一条日志（诊断模式下同时写入诊断文件） */
    public synchronized void log(String type, String message) {
        try {
            String day = dayFmt.format(new Date());
            String time = timeFmt.format(new Date());
            String line = time + " | " + type + " | " + message;

            // 普通日志
            File logFile = new File(logDir, day + ".log");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            pw.println(line);
            pw.close();

            // v2.38: 诊断模式下同时写入诊断文件
            if (diagnosticActive && diagnosticFile != null) {
                PrintWriter dpw = new PrintWriter(new FileWriter(diagnosticFile, true));
                dpw.println(line);
                dpw.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "write log failed", e);
        }
    }

    /** 记录RSSI采样 */
    public void logRssi(int raw, float smoothed, int nearThreshold, int farThreshold,
                        int consecutiveNear, boolean nearConfirmed, boolean farConfirmed) {
        log("RSSI", String.format("raw=%d smooth=%.1f near=%d far=%d consec=%d nearOk=%s farOk=%s",
                raw, smoothed, nearThreshold, farThreshold, consecutiveNear,
                nearConfirmed ? "Y" : "N", farConfirmed ? "Y" : "N"));
    }

    /** 记录触发事件 */
    public void logTrigger(String action, String detail) {
        log("TRIGGER", action + " | " + detail);
    }

    /** 记录状态变化 */
    public void logState(String state, String detail) {
        log("STATE", state + " | " + detail);
    }

    /** 记录命令发送 */
    public void logCommand(String cmd, boolean success, String msg) {
        log("CMD", cmd + " | " + (success ? "OK" : "FAIL") + " | " + msg);
    }

    /** 记录错误 */
    public void logError(String module, String error) {
        log("ERROR", module + " | " + error);
    }

    // ========== v2.38: 一键诊断记录 ==========

    /** 开始诊断记录，duration秒后自动结束 */
    public synchronized void startDiagnostic(int durationSec) {
        if (diagnosticActive) {
            log("DIAG", "诊断记录已在进行中，忽略重复请求");
            return;
        }
        try {
            diagnosticActive = true;
            diagnosticDuration = durationSec;
            diagnosticStartTime = System.currentTimeMillis();

            String fileName = "diagnostic_" + diagFmt.format(new Date()) + ".txt";
            diagnosticFile = new File(logDir, fileName);

            // 写入诊断头部
            Settings s = new Settings(ctx);
            PrintWriter pw = new PrintWriter(new FileWriter(diagnosticFile, true));
            pw.println("=== 小牛靠近开机助手 一键诊断日志 ===");
            pw.println("诊断开始: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            pw.println("诊断时长: " + durationSec + " 秒");
            pw.println("");
            pw.println("=== 当前配置 ===");
            pw.println("靠近阈值: " + s.getRssiThreshold() + " dBm");
            pw.println("离开阈值: " + s.getRssiLeaveThreshold() + " dBm");
            pw.println("滞回区间: " + s.getHysteresisDb() + " dB");
            pw.println("扫描间隔: " + s.getScanIntervalMs() + " ms");
            pw.println("连续达标: " + s.getConsecutiveRequired() + " 次");
            pw.println("触发策略: 强信号1次 / 提前趋势2次 / 连续达标" + s.getConsecutiveRequired() + "次");
            pw.println("冷却时间: " + s.getAutoOnCooldownSec() + " 秒");
            pw.println("滤波窗口: " + s.getFilterWindow());
            pw.println("自动开机: " + s.isAutoOn());
            pw.println("自动关机: " + s.isAutoOff());
            pw.println("目标MAC: " + s.getDeviceMac());
            pw.println("目标名称: " + s.getDeviceName());
            pw.println("车辆状态: " + s.getVehicleState());
            pw.println("");
            pw.println("=== 诊断数据（时间 | 类型 | 详情） ===");
            pw.close();

            log("DIAG", "开始诊断记录，时长 " + durationSec + " 秒，文件: " + fileName);

            // 定时结束
            diagHandler.postDelayed(this::stopDiagnostic, durationSec * 1000L);
        } catch (Exception e) {
            Log.e(TAG, "start diagnostic failed", e);
            diagnosticActive = false;
        }
    }

    /** 结束诊断记录 */
    public synchronized void stopDiagnostic() {
        if (!diagnosticActive) return;
        try {
            long duration = (System.currentTimeMillis() - diagnosticStartTime) / 1000;
            log("DIAG", "诊断记录结束，实际时长 " + duration + " 秒");

            if (diagnosticFile != null) {
                PrintWriter pw = new PrintWriter(new FileWriter(diagnosticFile, true));
                pw.println("");
                pw.println("=== 诊断结束 ===");
                pw.println("实际时长: " + duration + " 秒");
                pw.println("文件大小: " + diagnosticFile.length() + " 字节");
                pw.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "stop diagnostic failed", e);
        }
        diagnosticActive = false;
        diagnosticFile = null;
    }

    /** 是否正在诊断记录 */
    public boolean isDiagnosticActive() {
        return diagnosticActive;
    }

    /** 获取诊断剩余秒数 */
    public int getDiagnosticRemaining() {
        if (!diagnosticActive) return 0;
        int elapsed = (int)((System.currentTimeMillis() - diagnosticStartTime) / 1000);
        return Math.max(0, diagnosticDuration - elapsed);
    }

    /** 获取所有未上传的诊断文件 */
    public List<File> getPendingDiagnosticFiles() {
        List<File> files = new ArrayList<>();
        File[] all = logDir.listFiles((dir, name) ->
                name.startsWith("diagnostic_") && name.endsWith(".txt"));
        if (all != null) {
            for (File f : all) files.add(f);
            Collections.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        }
        return files;
    }

    /** 删除诊断文件（上传成功后调用） */
    public void deleteDiagnosticFile(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    // ========== 导出和清理 ==========

    /** 导出指定时间段的日志（精确到分钟） */
    public synchronized File exportLogs(String startDateTime, String endDateTime) {
        try {
            List<String> lines = new ArrayList<>();
            lines.add("=== 小牛靠近开机助手 诊断日志 ===");
            lines.add("时间段: " + startDateTime + " ~ " + endDateTime);
            lines.add("导出时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            lines.add("");

            Settings s = new Settings(ctx);
            lines.add("=== 当前配置 ===");
            lines.add("靠近阈值: " + s.getRssiThreshold() + " dBm");
            lines.add("离开阈值: " + s.getRssiLeaveThreshold() + " dBm");
            lines.add("滞回区间: " + s.getHysteresisDb() + " dB");
            lines.add("扫描间隔: " + s.getScanIntervalMs() + " ms");
            lines.add("连续达标: " + s.getConsecutiveRequired() + " 次");
            lines.add("触发策略: 强信号1次 / 提前趋势2次 / 连续达标" + s.getConsecutiveRequired() + "次");
            lines.add("冷却时间: " + s.getAutoOnCooldownSec() + " 秒");
            lines.add("滤波窗口: " + s.getFilterWindow());
            lines.add("自动开机: " + s.isAutoOn());
            lines.add("自动关机: " + s.isAutoOff());
            lines.add("目标MAC: " + s.getDeviceMac());
            lines.add("目标名称: " + s.getDeviceName());
            lines.add("");

            SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            Date start = dtFmt.parse(startDateTime);
            Date end = dtFmt.parse(endDateTime);
            SimpleDateFormat dayFmt2 = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFmt2 = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

            Calendar cal = Calendar.getInstance();
            cal.setTime(start);
            Calendar endCal = Calendar.getInstance();
            endCal.setTime(end);
            while (!cal.getTime().after(endCal.getTime())) {
                String day = dayFmt2.format(cal.getTime());
                File dayFile = new File(logDir, day + ".log");
                if (dayFile.exists()) {
                    lines.add("=== " + day + " ===");
                    BufferedReader br = new BufferedReader(new FileReader(dayFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        int pipeIdx = line.indexOf(" | ");
                        if (pipeIdx > 0) {
                            String timeStr = line.substring(0, pipeIdx);
                            try {
                                Date lineTime = timeFmt2.parse(timeStr);
                                Calendar lineCal = Calendar.getInstance();
                                lineCal.setTime(cal.getTime());
                                Calendar tCal = Calendar.getInstance();
                                tCal.setTime(lineTime);
                                lineCal.set(Calendar.HOUR_OF_DAY, tCal.get(Calendar.HOUR_OF_DAY));
                                lineCal.set(Calendar.MINUTE, tCal.get(Calendar.MINUTE));
                                lineCal.set(Calendar.SECOND, tCal.get(Calendar.SECOND));
                                if (lineCal.getTime().before(start) || lineCal.getTime().after(end)) {
                                    continue;
                                }
                            } catch (Exception ignored) {}
                        }
                        lines.add(line);
                    }
                    br.close();
                    lines.add("");
                }
                // v2.73: 合并当天的诊断日志文件（diagnostic_yyyyMMdd_HHmmss.txt），
                // 否则AI调参/日志导出永远看不到RSSI采样数据
                File[] diagFiles = logDir.listFiles((dir, name) ->
                        name.startsWith("diagnostic_") && name.endsWith(".txt"));
                if (diagFiles != null) {
                    for (File df : diagFiles) {
                        String dname = df.getName().replace("diagnostic_", "").replace(".txt", "");
                        try {
                            SimpleDateFormat diagDayFmt = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
                            Date diagDay = diagDayFmt.parse(dname.length() >= 8 ? dname.substring(0, 8) : dname);
                            if (dayFmt2.format(diagDay).equals(day)) {
                                lines.add("--- 诊断日志 " + df.getName() + " ---");
                                BufferedReader dbr = new BufferedReader(new FileReader(df));
                                String dline;
                                while ((dline = dbr.readLine()) != null) lines.add(dline);
                                dbr.close();
                                lines.add("");
                            }
                        } catch (Exception ignored) {}
                    }
                }
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }

            String safeStart = startDateTime.replace(" ", "_").replace(":", "");
            String safeEnd = endDateTime.replace(" ", "_").replace(":", "");
            File exportFile = new File(logDir, "export_" + safeStart + "_" + safeEnd + ".txt");
            PrintWriter pw = new PrintWriter(new FileWriter(exportFile));
            for (String l : lines) pw.println(l);
            pw.close();
            return exportFile;
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            return null;
        }
    }

    /** 获取可用的日志日期列表 */
    public List<String> getAvailableDays() {
        List<String> days = new ArrayList<>();
        File[] files = logDir.listFiles((dir, name) -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.log"));
        if (files != null) {
            for (File f : files) {
                days.add(f.getName().replace(".log", ""));
            }
            Collections.sort(days);
        }
        return days;
    }

    /** 获取日志总大小（字节） */
    public long getTotalLogSize() {
        long total = 0;
        File[] files = logDir.listFiles();
        if (files != null) {
            for (File f : files) total += f.length();
        }
        return total;
    }

    /** 清理过期日志 */
    private void cleanupOldLogs() {
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -KEEP_DAYS);
            Date cutoff = cal.getTime();
            File[] files = logDir.listFiles((dir, name) -> name.matches("\\d{4}-\\d{2}-\\d{2}\\.log"));
            if (files != null) {
                for (File f : files) {
                    String day = f.getName().replace(".log", "");
                    try {
                        Date d = dayFmt.parse(day);
                        if (d.before(cutoff)) f.delete();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cleanup failed", e);
        }
    }

    /** 清空所有日志 */
    public void clearAll() {
        File[] files = logDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    public File getLogDir() { return logDir; }
}
