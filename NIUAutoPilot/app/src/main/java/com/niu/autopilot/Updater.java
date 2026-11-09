package com.niu.autopilot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.33 软件自动更新（修复CDN缓存问题）
 * - api.github.com 无缓存，优先使用
 * - raw/jsdelivr 地址加时间戳绕缓存
 * - 空地址自动用默认值
 */
public class Updater {

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String downloadUrl;
        public String releaseNotes;
        public boolean hasUpdate;
        public String usedMirror;
    }

    public interface CheckCallback {
        void onResult(UpdateInfo info);
        void onError(String msg);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete(File apkFile);
        void onError(String msg);
    }

    private static final String CHANNEL_ID = "niu_update";
    private static final int NOTIF_ID = 20001;
    private static final int TIMEOUT_MS = 6000;

    /** v2.33: api.github.com 无缓存，优先；其他地址加时间戳 */
    private static final String API_GITHUB_URL =
            "https://api.github.com/repos/wuyuzhiren/NIU-AutoPilot/contents/version.json?ref=main";
    private static final String RAW_URL =
            "https://raw.githubusercontent.com/wuyuzhiren/NIU-AutoPilot/main/version.json";
    private static final String JSDELIVR_URL =
            "https://cdn.jsdelivr.net/gh/wuyuzhiren/NIU-AutoPilot@main/version.json";
    private static final String GHPROXY_URL =
            "https://ghproxy.com/https://raw.githubusercontent.com/wuyuzhiren/NIU-AutoPilot/main/version.json";
    private static final String MIRROR_GHPROXY_URL =
            "https://mirror.ghproxy.com/https://raw.githubusercontent.com/wuyuzhiren/NIU-AutoPilot/main/version.json";

    /** 检查更新（多地址轮询） */
    public static void checkUpdate(Context ctx, String customUrl, CheckCallback cb) {
        new Thread(() -> {
            long ts = System.currentTimeMillis();
            List<String> urls = new ArrayList<>();
            // 用户自定义地址优先（如果不是内置地址）
            if (customUrl != null && !customUrl.trim().isEmpty()
                    && !customUrl.contains("githubusercontent")
                    && !customUrl.contains("jsdelivr")
                    && !customUrl.contains("api.github.com")) {
                urls.add(customUrl);
            }
            // 内置地址（加时间戳绕缓存）
            urls.add(API_GITHUB_URL);
            urls.add(RAW_URL + "?t=" + ts);
            urls.add(JSDELIVR_URL + "?t=" + ts);
            urls.add(GHPROXY_URL);
            urls.add(MIRROR_GHPROXY_URL);

            String lastError = "所有地址均失败";
            for (String url : urls) {
                try {
                    UpdateInfo info = doCheck(ctx, url);
                    if (info != null) {
                        info.usedMirror = url;
                        new Handler(Looper.getMainLooper()).post(() -> cb.onResult(info));
                        return;
                    }
                } catch (Exception e) {
                    lastError = url + " -> " + e.getMessage();
                }
            }
            postError(cb, lastError);
        }).start();
    }

    private static UpdateInfo doCheck(Context ctx, String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "NIUAutoPilot-Update");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        InputStream is = conn.getInputStream();
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
        is.close();
        conn.disconnect();

        String jsonStr = sb.toString();

        // v2.33: api.github.com 返回 base64 编码，需要解码
        if (urlStr.contains("api.github.com")) {
            try {
                JSONObject resp = new JSONObject(jsonStr);
                String content = resp.optString("content", "").replace("\n", "");
                byte[] decoded = Base64.decode(content, Base64.DEFAULT);
                jsonStr = new String(decoded);
            } catch (Exception e) {
                throw new Exception("api.github返回解析失败: " + e.getMessage());
            }
        }

        JSONObject json = new JSONObject(jsonStr);
        UpdateInfo info = new UpdateInfo();
        info.versionCode = json.optInt("versionCode", 0);
        info.versionName = json.optString("versionName", "");
        info.downloadUrl = json.optString("downloadUrl", "");
        // v2.44: 兼容 version.json 使用 apkUrl 字段名的情况
        if (info.downloadUrl == null || info.downloadUrl.isEmpty()) {
            info.downloadUrl = json.optString("apkUrl", "");
        }
        info.releaseNotes = json.optString("releaseNotes", "");
        int current = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        info.hasUpdate = info.versionCode > current && !info.downloadUrl.isEmpty();
        return info;
    }

    /** 为下载URL生成多个镜像地址（国内优先jsdelivr，raw/github兜底） */
    private static List<String> buildDownloadUrls(String primaryUrl) {
        List<String> urls = new ArrayList<>();
        String owner=null,repo=null,branch=null,filePath=null;
        try {
            if (primaryUrl.contains("raw.githubusercontent.com")) {
                String p = primaryUrl.replace("https://raw.githubusercontent.com/", "").replace("http://raw.githubusercontent.com/", "");
                String[] parts = p.split("/", 4);
                if (parts.length >= 4) { owner=parts[0]; repo=parts[1]; branch=parts[2]; filePath=parts[3]; }
            } else if (primaryUrl.contains("github.com/") && primaryUrl.contains("/raw/")) {
                String p = primaryUrl.replaceFirst("^https?://github.com/", "").replaceFirst("/raw/", "/");
                String[] parts = p.split("/", 4);
                if (parts.length >= 4) { owner=parts[0]; repo=parts[1]; branch=parts[2]; filePath=parts[3]; }
            } else if (primaryUrl.contains("jsdelivr.net/gh/")) {
                String p = primaryUrl.replaceFirst("^https?://[^/]+/gh/", "");
                int at = p.indexOf("@");
                if (at > 0) {
                    String[] or = p.substring(0, at).split("/");
                    if (or.length >= 2) { owner=or[0]; repo=or[1]; }
                    String rest = p.substring(at+1);
                    int sl = rest.indexOf("/");
                    if (sl > 0) { branch=rest.substring(0, sl); filePath=rest.substring(sl+1); }
                }
            }
        } catch (Exception ignored) {}
        if (owner != null && repo != null && branch != null && filePath != null) {
            urls.add("https://cdn.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + filePath);
            urls.add("https://fastly.jsdelivr.net/gh/" + owner + "/" + repo + "@" + branch + "/" + filePath);
            urls.add("https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/" + filePath);
            urls.add("https://mirror.ghproxy.com/https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/" + filePath);
            urls.add("https://ghfast.top/https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + branch + "/" + filePath);
            urls.add(primaryUrl);
        } else {
            urls.add(primaryUrl);
        }
        return urls;
    }

    /** 下载APK（多地址轮询，带通知进度） */
    public static void downloadApk(Context ctx, String primaryUrl, DownloadCallback cb) {
        createChannel(ctx);
        new Thread(() -> {
            List<String> urls = buildDownloadUrls(primaryUrl);
            String lastError = "所有下载地址均失败";

            for (String url : urls) {
                try {
                    File result = doDownload(ctx, url, cb);
                    if (result != null) {
                        cancelNotif(ctx);
                        new Handler(Looper.getMainLooper()).post(() -> cb.onComplete(result));
                        return;
                    }
                } catch (Exception e) {
                    lastError = url + " -> " + e.getMessage();
                }
            }
            cancelNotif(ctx);
            postDlError(cb, lastError);
        }).start();
    }

    private static File doDownload(Context ctx, String url, DownloadCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(30000);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + code);
        }
        int total = conn.getContentLength();
        InputStream is = conn.getInputStream();
        File outDir = new File(ctx.getExternalFilesDir(null), "updates");
        if (!outDir.exists()) outDir.mkdirs();
        File outFile = new File(outDir, "update.apk");
        FileOutputStream fos = new FileOutputStream(outFile);

        byte[] buf = new byte[8192];
        int n, downloaded = 0, lastPercent = -1;
        while ((n = is.read(buf)) > 0) {
            fos.write(buf, 0, n);
            downloaded += n;
            if (total > 0) {
                int percent = (int) (downloaded * 100L / total);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    int p = percent;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        cb.onProgress(p);
                        showProgress(ctx, p);
                    });
                }
            }
        }
        fos.flush();
        fos.close();
        is.close();
        conn.disconnect();

        if (outFile.length() < 1024 * 1024) {
            outFile.delete();
            throw new Exception("文件过小(" + outFile.length() + "字节)");
        }
        return outFile;
    }

    /** 安装APK */
    public static void installApk(Context ctx, File apkFile) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri;
            if (Build.VERSION.SDK_INT >= 24) {
                uri = FileProvider.getUriForFile(ctx,
                        ctx.getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            ctx.startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(ctx, "安装失败: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "软件更新",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("下载更新包进度");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private static void showProgress(Context ctx, int percent) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        b.setContentTitle("正在下载更新")
                .setContentText(percent + "%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, false)
                .setOngoing(true);
        nm.notify(NOTIF_ID, b.build());
    }

    private static void cancelNotif(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID);
    }

    private static void postError(CheckCallback cb, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onError(msg));
    }

    private static void postDlError(DownloadCallback cb, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onError(msg));
    }
}
