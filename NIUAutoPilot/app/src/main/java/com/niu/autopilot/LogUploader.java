package com.niu.autopilot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.38 日志上传器（支持自定义路径，用于诊断日志定时上传）
 */
public class LogUploader {
    private static final String TAG = "LogUploader";
    private static final String REPO = "wuyuzhiren/NIU-AutoPilot";
    private static final String BRANCH = "main";

    public interface UploadCallback {
        void onSuccess(String url);
        void onFailure(String error);
    }

    /** v2.38: 上传内容到指定路径（用于诊断日志定时上传） */
    public static void uploadToPath(Context ctx, String token, String path, String content, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (token == null || token.isEmpty()) {
                    postFailure(callback, "未配置 GitHub Token");
                    return;
                }
                String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String refUrl = "https://api.github.com/repos/" + REPO + "/git/refs/heads/" + BRANCH;
                JSONObject refResp = apiGet(token, refUrl);
                String commitSha = refResp.getJSONObject("object").getString("sha");
                String commitUrl = "https://api.github.com/repos/" + REPO + "/git/commits/" + commitSha;
                JSONObject commitResp = apiGet(token, commitUrl);
                String treeSha = commitResp.getJSONObject("tree").getString("sha");
                JSONObject blobBody = new JSONObject();
                blobBody.put("content", b64);
                blobBody.put("encoding", "base64");
                JSONObject blobResp = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/blobs", blobBody);
                String blobSha = blobResp.getString("sha");
                JSONObject treeEntry = new JSONObject();
                treeEntry.put("path", path);
                treeEntry.put("mode", "100644");
                treeEntry.put("type", "blob");
                treeEntry.put("sha", blobSha);
                JSONObject treeBody = new JSONObject();
                treeBody.put("base_tree", treeSha);
                treeBody.put("tree", new org.json.JSONArray().put(treeEntry));
                JSONObject treeResp = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/trees", treeBody);
                String newTreeSha = treeResp.getString("sha");
                JSONObject commitBody = new JSONObject();
                commitBody.put("message", "upload diagnostic log: " + path);
                commitBody.put("tree", newTreeSha);
                commitBody.put("parents", new org.json.JSONArray().put(commitSha));
                JSONObject newCommit = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/commits", commitBody);
                String newCommitSha = newCommit.getString("sha");
                JSONObject refBody = new JSONObject();
                refBody.put("sha", newCommitSha);
                refBody.put("force", false);
                apiPatch(token, refUrl, refBody);
                String htmlUrl = "https://github.com/" + REPO + "/blob/" + BRANCH + "/" + path;
                postSuccess(callback, htmlUrl);
            } catch (Exception e) {
                postFailure(callback, e.getMessage());
            }
        }).start();
    }

    /**
     * 上传日志文件到 GitHub
     */
    public static void upload(Context ctx, File logFile, UploadCallback callback) {
        new Thread(() -> {
            try {
                Settings s = new Settings(ctx);
                String token = s.getGithubToken();
                if (token == null || token.isEmpty()) {
                    postFailure(callback, "未配置 GitHub Token，请在设置页填写");
                    return;
                }
                if (logFile == null || !logFile.exists()) {
                    postFailure(callback, "日志文件不存在");
                    return;
                }

                StringBuilder content = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(logFile));
                String line;
                while ((line = br.readLine()) != null) {
                    content.append(line).append("\n");
                }
                br.close();

                // v2.73: 空内容不上传（避免GitHub出现0字节占位文件，AI分析永远拿不到数据）
                if (content.length() == 0) {
                    postFailure(callback, "日志内容为空（该时间段没有记录），请先运行自动模式产生日志");
                    return;
                }

                String b64 = Base64.encodeToString(content.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                String fileName = "log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                String path = "logs/" + fileName;

                String refUrl = "https://api.github.com/repos/" + REPO + "/git/refs/heads/" + BRANCH;
                JSONObject refResp = apiGet(token, refUrl);
                String commitSha = refResp.getJSONObject("object").getString("sha");
                String commitUrl = "https://api.github.com/repos/" + REPO + "/git/commits/" + commitSha;
                JSONObject commitResp = apiGet(token, commitUrl);
                String treeSha = commitResp.getJSONObject("tree").getString("sha");
                JSONObject blobBody = new JSONObject();
                blobBody.put("content", b64);
                blobBody.put("encoding", "base64");
                JSONObject blobResp = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/blobs", blobBody);
                String blobSha = blobResp.getString("sha");
                JSONObject treeEntry = new JSONObject();
                treeEntry.put("path", path);
                treeEntry.put("mode", "100644");
                treeEntry.put("type", "blob");
                treeEntry.put("sha", blobSha);
                JSONObject treeBody = new JSONObject();
                treeBody.put("base_tree", treeSha);
                treeBody.put("tree", new org.json.JSONArray().put(treeEntry));
                JSONObject treeResp = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/trees", treeBody);
                String newTreeSha = treeResp.getString("sha");
                JSONObject commitBody = new JSONObject();
                commitBody.put("message", "upload diagnostic log: " + fileName);
                commitBody.put("tree", newTreeSha);
                commitBody.put("parents", new org.json.JSONArray().put(commitSha));
                JSONObject newCommit = apiPost(token, "https://api.github.com/repos/" + REPO + "/git/commits", commitBody);
                String newCommitSha = newCommit.getString("sha");
                JSONObject refBody = new JSONObject();
                refBody.put("sha", newCommitSha);
                refBody.put("force", false);
                apiPatch(token, refUrl, refBody);

                String htmlUrl = "https://github.com/" + REPO + "/blob/" + BRANCH + "/" + path;
                postSuccess(callback, htmlUrl);

            } catch (Exception e) {
                Log.e(TAG, "upload failed", e);
                postFailure(callback, "上传失败: " + e.getMessage());
            }
        }).start();
    }

    private static JSONObject apiGet(String token, String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        BufferedReader br = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return new JSONObject(sb.toString());
    }

    private static JSONObject apiPost(String token, String urlStr, JSONObject body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        BufferedReader br = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return new JSONObject(sb.toString());
    }

    private static JSONObject apiPatch(String token, String urlStr, JSONObject body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("PATCH");
        conn.setRequestProperty("Authorization", "token " + token);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        os.close();
        BufferedReader br = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = br.readLine()) != null) sb.append(l);
        br.close();
        return new JSONObject(sb.toString());
    }

    private static void postSuccess(UploadCallback cb, String url) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(url));
    }

    private static void postFailure(UploadCallback cb, String err) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onFailure(err));
    }
}
