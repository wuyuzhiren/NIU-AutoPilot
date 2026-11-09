package com.niu.autopilot;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * v2.36 AI 智能调参页面（快捷选项直接导出日志）
 */
public class AiTuningActivity extends AppCompatActivity {
    private Button btnStartTime, btnEndTime, btnAnalyze, btnApply;
    private Button btnPickLocal, btnPickGithub;
    private TextView tvAiConfig, tvAnalyzing, tvAnalysis, tvError, tvSelectedLog;
    private TextView tvRecNear, tvRecFar, tvRecHyster, tvRecInterval, tvRecConsec, tvRecCooldown, tvRecConfidence;
    private CardView cardResult;
    private String startDateTime, endDateTime;
    private final SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private JSONObject recommendedParams;
    private File selectedLogFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_tuning);
        setTitle("AI 智能调参");

        btnStartTime = findViewById(R.id.btn_start_time);
        btnEndTime = findViewById(R.id.btn_end_time);
        btnAnalyze = findViewById(R.id.btn_analyze);
        btnApply = findViewById(R.id.btn_apply);
        btnPickLocal = findViewById(R.id.btn_pick_local);
        btnPickGithub = findViewById(R.id.btn_pick_github);
        tvAiConfig = findViewById(R.id.tv_ai_config);
        tvAnalyzing = findViewById(R.id.tv_analyzing);
        tvAnalysis = findViewById(R.id.tv_analysis);
        tvError = findViewById(R.id.tv_error);
        tvSelectedLog = findViewById(R.id.tv_selected_log);
        cardResult = findViewById(R.id.card_result);
        tvRecNear = findViewById(R.id.tv_rec_near);
        tvRecFar = findViewById(R.id.tv_rec_far);
        tvRecHyster = findViewById(R.id.tv_rec_hyster);
        tvRecInterval = findViewById(R.id.tv_rec_interval);
        tvRecConsec = findViewById(R.id.tv_rec_consec);
        tvRecCooldown = findViewById(R.id.tv_rec_cooldown);
        tvRecConfidence = findViewById(R.id.tv_rec_confidence);

        Calendar cal = Calendar.getInstance();
        endDateTime = dtFmt.format(cal.getTime());
        cal.add(Calendar.HOUR_OF_DAY, -1);
        startDateTime = dtFmt.format(cal.getTime());
        updateTimeButtons();
        refreshAiConfigStatus();

        btnStartTime.setOnClickListener(v -> pickDateTime(true));
        btnEndTime.setOnClickListener(v -> pickDateTime(false));
        btnPickLocal.setOnClickListener(v -> pickLocalLog());
        btnPickGithub.setOnClickListener(v -> pickGithubLog());

        findViewById(R.id.btn_quick_1h).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            endDateTime = dtFmt.format(c.getTime());
            c.add(Calendar.HOUR_OF_DAY, -1);
            startDateTime = dtFmt.format(c.getTime());
            updateTimeButtons();
            exportAndShowLog();
        });
        findViewById(R.id.btn_quick_3h).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            endDateTime = dtFmt.format(c.getTime());
            c.add(Calendar.HOUR_OF_DAY, -3);
            startDateTime = dtFmt.format(c.getTime());
            updateTimeButtons();
            exportAndShowLog();
        });
        findViewById(R.id.btn_quick_today).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            startDateTime = dtFmt.format(c.getTime());
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            endDateTime = dtFmt.format(c.getTime());
            updateTimeButtons();
            exportAndShowLog();
        });

        btnAnalyze.setOnClickListener(v -> doAnalyze());
        btnApply.setOnClickListener(v -> applyParams());
    }

    /** v2.36: 按当前时间段导出日志并显示 */
    private void exportAndShowLog() {
        tvSelectedLog.setText("正在导出 " + startDateTime + " ~ " + endDateTime + " 的日志...");
        tvSelectedLog.setTextColor(0xFF1976D2);
        tvSelectedLog.setBackgroundColor(0xFFE3F2FD);

        new Thread(() -> {
            try {
                File logFile = AppLogger.get(this).exportLogs(startDateTime, endDateTime);
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (logFile != null && logFile.exists()) {
                        selectedLogFile = logFile;
                        int lines = countLines(logFile);
                        tvSelectedLog.setText("已导出日志: " + logFile.getName() +
                                "\n时间段: " + startDateTime + " ~ " + endDateTime +
                                "\n大小: " + (logFile.length() / 1024) + " KB | 行数: " + lines);
                        tvSelectedLog.setTextColor(0xFF2E7D32);
                        tvSelectedLog.setBackgroundColor(0xFFE8F5E9);
                    } else {
                        selectedLogFile = null;
                        tvSelectedLog.setText("该时间段无日志数据\n时间段: " + startDateTime + " ~ " + endDateTime +
                                "\n请先使用自动模式产生日志，或选择本地/GitHub日志");
                        tvSelectedLog.setTextColor(0xFFC62828);
                        tvSelectedLog.setBackgroundColor(0xFFFFEBEE);
                    }
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    tvSelectedLog.setText("导出失败: " + e.getMessage());
                    tvSelectedLog.setTextColor(0xFFC62828);
                });
            }
        }).start();
    }

    private void refreshAiConfigStatus() {
        Settings s = new Settings(this);
        String base = s.getAiApiBase();
        String model = s.getAiModel();
        if (s.getAiApiKey().isEmpty() || model.isEmpty()) {
            tvAiConfig.setText("⚠️ 未配置 AI，请先到设置页配置 AI 接口");
            tvAiConfig.setTextColor(0xFFC62828);
        } else {
            tvAiConfig.setText("已配置: " + base + " | 模型: " + model);
            tvAiConfig.setTextColor(0xFF2E7D32);
        }
    }

    private void pickDateTime(boolean isStart) {
        Calendar cal = Calendar.getInstance();
        try {
            cal.setTime(dtFmt.parse(isStart ? startDateTime : endDateTime));
        } catch (Exception ignored) {}
        final int year = cal.get(Calendar.YEAR);
        final int month = cal.get(Calendar.MONTH);
        final int day = cal.get(Calendar.DAY_OF_MONTH);
        final int hour = cal.get(Calendar.HOUR_OF_DAY);
        final int minute = cal.get(Calendar.MINUTE);

        new DatePickerDialog(this, (view, y, m, d) -> {
            new TimePickerDialog(this, (tp, h, min) -> {
                Calendar c = Calendar.getInstance();
                c.set(y, m, d, h, min);
                String dt = dtFmt.format(c.getTime());
                if (isStart) startDateTime = dt; else endDateTime = dt;
                updateTimeButtons();
            }, hour, minute, true).show();
        }, year, month, day).show();
    }

    private void updateTimeButtons() {
        btnStartTime.setText(startDateTime);
        btnEndTime.setText(endDateTime);
    }

    private void pickLocalLog() {
        File logDir = AppLogger.get(this).getLogDir();
        File[] files = logDir.listFiles((dir, name) ->
                name.startsWith("export_") && name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            Toast.makeText(this, "本地没有已导出的日志，请先到诊断日志页面导出", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            long kb = files[i].length() / 1024;
            names[i] = files[i].getName() + " (" + kb + " KB)";
        }
        final File[] finalFiles = files;
        new AlertDialog.Builder(this)
                .setTitle("选择本地日志文件")
                .setItems(names, (d, which) -> {
                    selectedLogFile = finalFiles[which];
                    int lines = countLines(selectedLogFile);
                    tvSelectedLog.setText("已选本地日志: " + selectedLogFile.getName() +
                            "\n大小: " + (selectedLogFile.length() / 1024) + " KB | 行数: " + lines);
                    tvSelectedLog.setTextColor(0xFF2E7D32);
                    tvSelectedLog.setBackgroundColor(0xFFE8F5E9);
                    Toast.makeText(this, "已选择: " + selectedLogFile.getName(), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickGithubLog() {
        Settings s = new Settings(this);
        String token = s.getGithubToken();
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "未配置 GitHub Token，请先到AI调参设置里填写", Toast.LENGTH_LONG).show();
            return;
        }
        tvSelectedLog.setText("正在从 GitHub 获取日志列表...");
        tvSelectedLog.setTextColor(0xFF1976D2);
        btnPickGithub.setEnabled(false);

        new Thread(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/wuyuzhiren/NIU-AutoPilot/contents/logs?ref=main";
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestProperty("Authorization", "token " + token);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
                is.close();
                conn.disconnect();

                JSONArray arr = new JSONArray(sb.toString());
                List<String> fileNames = new ArrayList<>();
                List<String> downloadUrls = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String name = item.optString("name", "");
                    String dlUrl = item.optString("download_url", "");
                    if (name.endsWith(".txt") || name.endsWith(".log")) {
                        fileNames.add(name);
                        downloadUrls.add(dlUrl);
                    }
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    btnPickGithub.setEnabled(true);
                    if (fileNames.isEmpty()) {
                        tvSelectedLog.setText("GitHub logs/ 目录下没有日志文件");
                        tvSelectedLog.setTextColor(0xFFC62828);
                        return;
                    }
                    String[] names = fileNames.toArray(new String[0]);
                    new AlertDialog.Builder(AiTuningActivity.this)
                            .setTitle("选择 GitHub 日志")
                            .setItems(names, (d, which) ->
                                    downloadGithubLog(downloadUrls.get(which), fileNames.get(which)))
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    btnPickGithub.setEnabled(true);
                    tvSelectedLog.setText("获取 GitHub 日志列表失败: " + e.getMessage());
                    tvSelectedLog.setTextColor(0xFFC62828);
                });
            }
        }).start();
    }

    private void downloadGithubLog(String downloadUrl, String fileName) {
        tvSelectedLog.setText("正在下载: " + fileName + "...");
        tvSelectedLog.setTextColor(0xFF1976D2);

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream is = conn.getInputStream();
                File outDir = AppLogger.get(this).getLogDir();
                File outFile = new File(outDir, "github_" + fileName);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                fos.flush();
                fos.close();
                is.close();
                conn.disconnect();

                selectedLogFile = outFile;
                int lines = countLines(outFile);
                new Handler(Looper.getMainLooper()).post(() -> {
                    tvSelectedLog.setText("已选GitHub日志: " + fileName +
                            "\n大小: " + (outFile.length() / 1024) + " KB | 行数: " + lines);
                    tvSelectedLog.setTextColor(0xFF2E7D32);
                    tvSelectedLog.setBackgroundColor(0xFFE8F5E9);
                    Toast.makeText(this, "下载完成: " + fileName, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    tvSelectedLog.setText("下载失败: " + e.getMessage());
                    tvSelectedLog.setTextColor(0xFFC62828);
                });
            }
        }).start();
    }

    private int countLines(File file) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));
            int count = 0;
            while (br.readLine() != null) count++;
            br.close();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private void doAnalyze() {
        Settings s = new Settings(this);
        if (s.getAiApiKey().isEmpty() || s.getAiModel().isEmpty()) {
            Toast.makeText(this, "请先到设置页配置 AI 接口和模型", Toast.LENGTH_SHORT).show();
            return;
        }

        File logFile = selectedLogFile;
        if (logFile == null || !logFile.exists()) {
            logFile = AppLogger.get(this).exportLogs(startDateTime, endDateTime);
        }
        if (logFile == null || !logFile.exists()) {
            tvError.setText("该时间段无日志，请先使用自动模式产生日志，或选择本地/GitHub日志");
            tvError.setVisibility(View.VISIBLE);
            cardResult.setVisibility(View.GONE);
            return;
        }

        String logContent = readLogWithLimit(logFile, 3000);
        if (logContent.isEmpty()) {
            tvError.setText("日志内容为空（文件: " + logFile.getName() + "），请检查日志是否有实际数据");
            tvError.setVisibility(View.VISIBLE);
            cardResult.setVisibility(View.GONE);
            return;
        }

        String systemPrompt = "你是一个蓝牙信号与电动车自动解锁专家。用户用BLE扫描电动车蓝牙信号(RSSI)判定距离实现靠近自动开机/离开自动关机。" +
                "RSSI为负数，越大(如-50)信号越强距离越近，越小(如-85)信号越弱距离越远。" +
                "重要：用户近期已做过GPS+RSSI智能校准，校准结果(下方<最近校准记录>)是用户在实际步行距离下拟合的，应作为基准参考。" +
                "校准日志(下方<校准日志片段>)记录了每次标定时的RSSI采样、拟合A/n值、触发误差，用于判断当前环境是否与校准时一致。" +
                "请结合当前运行日志、校准记录、环境特征综合分析：自动开机/关机是否灵敏？是否误触发？是否漏触发？给出参数优化建议。" +
                "注意：不同环境(开阔地/地库/半遮挡)信号衰减不同，若运行日志显示RSSI波动大或阈值附近抖动，应增大滞回或提高连续达标次数。" +
                "必须只返回JSON格式，不要任何其他文字、解释或markdown标记。";

        String userPrompt = buildUserPrompt(s, logContent);

        tvError.setVisibility(View.GONE);
        cardResult.setVisibility(View.GONE);
        tvAnalyzing.setText("AI 正在分析日志（" + logFile.getName() + "），请稍候...");
        tvAnalyzing.setVisibility(View.VISIBLE);
        btnAnalyze.setEnabled(false);

        AiApiHelper.chat(s.getAiApiBase(), s.getAiApiKey(), s.getAiModel(),
                systemPrompt, userPrompt, new AiApiHelper.ChatCallback() {
                    @Override
                    public void onSuccess(String content) {
                        btnAnalyze.setEnabled(true);
                        tvAnalyzing.setVisibility(View.GONE);
                        parseAndShowResult(content);
                    }

                    @Override
                    public void onFailure(String error) {
                        btnAnalyze.setEnabled(true);
                        tvAnalyzing.setVisibility(View.GONE);
                        tvError.setText("AI 分析失败: " + error);
                        tvError.setVisibility(View.VISIBLE);
                    }
                });
    }

    private String buildUserPrompt(Settings s, String logContent) {
        StringBuilder sb = new StringBuilder();
        // v2.62: 加入最近校准记录和校准日志
        sb.append("===== 最近校准记录 =====").append("\n");
        try {
            String hist = s.getCalibrationHistory();
            if (hist == null || hist.isEmpty() || "[]".equals(hist)) {
                sb.append("(未做过校准)").append("\n");
            } else {
                org.json.JSONArray arr = new org.json.JSONArray(hist);
                int n = Math.min(arr.length(), 3);
                for (int i = 0; i < n; i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    sb.append("- ").append(o.optString("time","?")).append(": near=").append(o.optInt("nearThreshold",0))
                      .append(" far=").append(o.optInt("farThreshold",0)).append(" hyst=").append(o.optInt("hysteresis",0))
                      .append(" conf=").append(o.optDouble("confidence",0)).append("\n");
                }
            }
        } catch (Exception e) { sb.append("(校准记录读取失败)\n"); }
        sb.append("\n===== 当前参数 =====").append("\n");
        sb.append("- 靠近阈值: ").append(s.getRssiThreshold()).append(" dBm（信号>=此值触发开机）\n");
        sb.append("- 离开阈值: ").append(s.getRssiLeaveThreshold()).append(" dBm（信号<=此值触发关机）\n");
        sb.append("- 滞回区间: ").append(s.getHysteresisDb()).append(" dB\n");
        sb.append("- 扫描间隔: ").append(s.getScanIntervalMs()).append(" ms\n");
        sb.append("- 连续达标次数: ").append(s.getConsecutiveRequired()).append(" 次\n");
        sb.append("- 冷却时间: ").append(s.getAutoOnCooldownSec()).append(" 秒\n");
        sb.append("- 滤波窗口: ").append(s.getFilterWindow()).append("\n\n");
        sb.append("日志数据（时间 | 类型 | 详情）：\n");
        sb.append("RSSI行: raw=原始信号 smooth=滤波后信号 near=靠近阈值 far=离开阈值 consec=连续达标次数\n");
        sb.append("TRIGGER行: 触发记录\n\n");
        sb.append(logContent);
        sb.append("\n\n请分析以上数据，返回JSON：\n");
        sb.append("{\"analysis\":\"分析说明\",\"nearThreshold\":数字,\"farThreshold\":数字,");
        sb.append("\"hysteresis\":数字,\"scanIntervalMs\":数字,\"consecutiveRequired\":数字,");
        sb.append("\"cooldownSec\":数字,\"confidence\":0-1}");
        return sb.toString();
    }

    private String readLogWithLimit(File file, int maxLines) {
        try {
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null && lines.size() < maxLines + 50) {
                lines.add(line);
            }
            br.close();

            int startIdx = 0;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(" | ") && l.length() > 10) {
                    String firstPart = l.substring(0, l.indexOf(" | "));
                    if (firstPart.contains(":") && firstPart.contains(".")) {
                        startIdx = i;
                        break;
                    }
                }
            }

            int endIdx = Math.min(startIdx + maxLines, lines.size());
            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(lines.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void parseAndShowResult(String content) {
        try {
            String jsonStr = content;
            int jsonStart = content.indexOf("{");
            int jsonEnd = content.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                jsonStr = content.substring(jsonStart, jsonEnd + 1);
            }
            recommendedParams = new JSONObject(jsonStr);

            String analysis = recommendedParams.optString("analysis", "无分析说明");
            tvAnalysis.setText(analysis);

            tvRecNear.setText(recommendedParams.optInt("nearThreshold") + " dBm");
            tvRecFar.setText(recommendedParams.optInt("farThreshold") + " dBm");
            tvRecHyster.setText(recommendedParams.optInt("hysteresis") + " dB");
            tvRecInterval.setText(recommendedParams.optInt("scanIntervalMs") + " ms");
            tvRecConsec.setText(recommendedParams.optInt("consecutiveRequired") + " 次");
            tvRecCooldown.setText(recommendedParams.optInt("cooldownSec") + " 秒");
            float conf = (float) recommendedParams.optDouble("confidence", 0) * 100;
            tvRecConfidence.setText((int) conf + "%");

            cardResult.setVisibility(View.VISIBLE);
            tvError.setVisibility(View.GONE);
        } catch (Exception e) {
            tvError.setText("AI 返回格式解析失败，请重试。原始返回:\n" + content);
            tvError.setVisibility(View.VISIBLE);
            cardResult.setVisibility(View.GONE);
        }
    }

    private void applyParams() {
        if (recommendedParams == null) return;
        try {
            Settings s = new Settings(this);
            s.setRssiThreshold(recommendedParams.optInt("nearThreshold", s.getRssiThreshold()));
            s.setRssiLeaveThreshold(recommendedParams.optInt("farThreshold", s.getRssiLeaveThreshold()));
            s.setHysteresisDb(recommendedParams.optInt("hysteresis", s.getHysteresisDb()));
            s.setScanIntervalMs(recommendedParams.optInt("scanIntervalMs", s.getScanIntervalMs()));
            s.setConsecutiveRequired(recommendedParams.optInt("consecutiveRequired", s.getConsecutiveRequired()));
            s.setAutoOnCooldownSec(recommendedParams.optInt("cooldownSec", s.getAutoOnCooldownSec()));
            // v2.37: 应用参数后自动删除临时导出的日志文件（只删export_开头的，不删原始日志）
            if (selectedLogFile != null && selectedLogFile.getName().startsWith("export_")) {
                selectedLogFile.delete();
            }
            Toast.makeText(this, "参数已应用！临时日志已清理", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "应用失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
