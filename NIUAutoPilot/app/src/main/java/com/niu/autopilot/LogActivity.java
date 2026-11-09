package com.niu.autopilot;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * v2.34 诊断日志导出/上传界面（修复导出崩溃）
 */
public class LogActivity extends AppCompatActivity {
    private TextView tvLogInfo, tvResult;
    private Button btnStartDate, btnEndDate, btnExport, btnUpload, btnClear;
    private String startDateTime, endDateTime;
    private final SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    private File exportedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        setTitle("诊断日志");

        tvLogInfo = findViewById(R.id.tv_log_info);
        tvResult = findViewById(R.id.tv_result);
        btnStartDate = findViewById(R.id.btn_start_date);
        btnEndDate = findViewById(R.id.btn_end_date);
        btnExport = findViewById(R.id.btn_export);
        btnUpload = findViewById(R.id.btn_upload);
        btnClear = findViewById(R.id.btn_clear);

        Calendar cal = Calendar.getInstance();
        endDateTime = dtFmt.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, -1);
        startDateTime = dtFmt.format(cal.getTime());
        updateDateButtons();
        refreshLogInfo();

        btnStartDate.setOnClickListener(v -> pickDateTime(true));
        btnEndDate.setOnClickListener(v -> pickDateTime(false));

        findViewById(R.id.btn_quick_today).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            startDateTime = dtFmt.format(c.getTime());
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            endDateTime = dtFmt.format(c.getTime());
            updateDateButtons();
        });
        findViewById(R.id.btn_quick_3days).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            endDateTime = dtFmt.format(c.getTime());
            c.add(Calendar.DAY_OF_MONTH, -2);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            startDateTime = dtFmt.format(c.getTime());
            updateDateButtons();
        });
        findViewById(R.id.btn_quick_7days).setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            endDateTime = dtFmt.format(c.getTime());
            c.add(Calendar.DAY_OF_MONTH, -6);
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            startDateTime = dtFmt.format(c.getTime());
            updateDateButtons();
        });

        btnExport.setOnClickListener(v -> exportLogs());
        btnUpload.setOnClickListener(v -> uploadLogs());
        btnClear.setOnClickListener(v -> clearLogs());
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
                updateDateButtons();
            }, hour, minute, true).show();
        }, year, month, day).show();
    }

    private void updateDateButtons() {
        btnStartDate.setText(startDateTime);
        btnEndDate.setText(endDateTime);
    }

    private void refreshLogInfo() {
        AppLogger logger = AppLogger.get(this);
        List<String> days = logger.getAvailableDays();
        long sizeKB = logger.getTotalLogSize() / 1024;
        String info = "可用日志天数: " + days.size() + " 天\n" +
                "总大小: " + sizeKB + " KB\n" +
                "保留最近14天，自动清理过期日志";
        if (!days.isEmpty()) {
            info += "\n最早: " + days.get(0) + "\n最晚: " + days.get(days.size() - 1);
        }
        tvLogInfo.setText(info);
    }

    private void exportLogs() {
        try {
            AppLogger logger = AppLogger.get(this);
            exportedFile = logger.exportLogs(startDateTime, endDateTime);
            if (exportedFile == null || !exportedFile.exists()) {
                showResult("导出失败或该时间段无日志", false);
                return;
            }
            long sizeKB = exportedFile.length() / 1024;
            String msg = "导出成功！\n文件: " + exportedFile.getName() +
                    "\n大小: " + sizeKB + " KB\n路径: " + exportedFile.getAbsolutePath();
            showResult(msg, true);

            // 弹出分享选项（v2.34: 加try-catch防崩溃）
            try {
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", exportedFile);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "分享日志"));
            } catch (Exception e) {
                showResult(msg + "\n\n（分享面板启动失败，可手动到上述路径取文件）", true);
            }
        } catch (Exception e) {
            showResult("导出异常: " + e.getMessage(), false);
        }
    }

    private void uploadLogs() {
        try {
            if (exportedFile == null || !exportedFile.exists()) {
                exportedFile = AppLogger.get(this).exportLogs(startDateTime, endDateTime);
            }
            if (exportedFile == null || !exportedFile.exists()) {
                showResult("导出失败或该时间段无日志", false);
                return;
            }

            Settings s = new Settings(this);
            if (s.getGithubToken() == null || s.getGithubToken().isEmpty()) {
                showResult("未配置 GitHub Token！\n请先在AI调参设置里填写 GitHub Token", false);
                return;
            }

            showResult("正在上传到 GitHub...", true);
            btnUpload.setEnabled(false);

            LogUploader.upload(this, exportedFile, new LogUploader.UploadCallback() {
                @Override
                public void onSuccess(String url) {
                    btnUpload.setEnabled(true);
                    showResult("上传成功！\n日志地址:\n" + url, true);
                    Toast.makeText(LogActivity.this, "日志已上传", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(String error) {
                    btnUpload.setEnabled(true);
                    showResult("上传失败: " + error, false);
                }
            });
        } catch (Exception e) {
            btnUpload.setEnabled(true);
            showResult("上传异常: " + e.getMessage(), false);
        }
    }

    private void clearLogs() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("确认清空")
                .setMessage("确定要清空所有诊断日志吗？此操作不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    AppLogger.get(this).clearAll();
                    refreshLogInfo();
                    exportedFile = null;
                    showResult("日志已清空", true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showResult(String text, boolean success) {
        tvResult.setVisibility(TextView.VISIBLE);
        tvResult.setText(text);
        tvResult.setTextColor(success ? 0xFF2E7D32 : 0xFFC62828);
    }
}
