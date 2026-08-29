package com.niu.autopilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 小牛 HTTP 客户端：
 * 1) 登录拿 token（密码 MD5）
 * 2) 发送远程命令 (v5/cmd/creat)
 * 3) 查询车辆状态 (isAccOn)
 */
public class NiuHttpClient {
    private static final String TAG = "NiuHttp";
    private static final String LOGIN_URL = "https://account.niu.com/v3/api/oauth2/token";
    private static final String CMD_URL = "https://app-api.niu.com/v5/cmd/creat";
    private static final String STATE_URL = "https://app-api.niu.com/v5/scooter/motor_data/index_info";
    private static final String APP_ID = "niu_ktdrr960";
    private static final String UA_ANDROID = "manager/5.17.4 (android; IN2020 12);lang=zh-CN;clientIdentifier=Domestic;timezone=Asia/Shanghai;model=IN2020;deviceName=IN2020;ostype=android";
    private static final String UA_IOS = "manager/5.12.4 (iPhone; iOS 18.5; Scale/3.00);deviceName=iPhone;timezone=Asia/Shanghai;model=iPhone13,4;lang=zh-CN;ostype=iOS;clientIdentifier=Domestic";

    public interface Result {
        void onDone(boolean ok, String msg);
    }

    private final SharedPreferences sp;

    public NiuHttpClient(Context ctx) {
        sp = ctx.getSharedPreferences("niu_autopilot", Context.MODE_PRIVATE);
    }

    public static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return s; }
    }

    /** 获取有效 token（缓存优先，快过期时重新登录） */
    public synchronized String getToken(String account, String password) {
        String token = sp.getString("access_token", "");
        long expire = sp.getLong("token_expire", 0);
        if (token != null && !token.isEmpty() && expire > System.currentTimeMillis() + 60000) {
            return token;
        }
        String form = "account=" + enc(account) + "&password=" + md5(password)
                + "&grant_type=password&scope=base&app_id=" + APP_ID;
        String resp = http(LOGIN_URL, form, "application/x-www-form-urlencoded", UA_ANDROID, null, "POST");
        try {
            JSONObject j = new JSONObject(resp);
            if (j.optInt("status") == 0) {
                JSONObject data = j.getJSONObject("data");
                JSONObject t = data.getJSONObject("token");
                String tok = t.getString("access_token");
                sp.edit().putString("access_token", tok)
                        .putLong("token_expire", System.currentTimeMillis() + 3600 * 1000L).apply();
                return tok;
            }
            Log.e(TAG, "登录失败: " + resp);
        } catch (Exception e) {
            Log.e(TAG, "解析登录失败: " + e);
        }
        return "";
    }

    /** 发送远程命令（后台线程） */
    public void sendCommand(final String account, final String password, final String sn,
                            final String type, final Result cb) {
        new Thread(() -> {
            try {
                String token = getToken(account, password);
                if (token.isEmpty()) { cb.onDone(false, "登录失败，请检查账号/密码/网络"); return; }
                String body = "{\"sn\":\"" + sn + "\",\"type\":\"" + type + "\"}";
                String resp = http(CMD_URL, body, "application/json; charset=utf-8", UA_IOS, token, "POST");
                JSONObject j = new JSONObject(resp);
                int status = j.optInt("status");
                if (status == 0) {
                    cb.onDone(true, "命令已发送: " + type);
                } else {
                    cb.onDone(false, "命令失败 status=" + status + " " + j.optString("desc"));
                }
            } catch (Exception e) {
                cb.onDone(false, "异常: " + e.getMessage());
            }
        }).start();
    }

    /** 查询车辆是否开机（后台线程），msg 返回 "0" 关机 / "1" 开机 */
    public void queryAccState(final String account, final String password, final String sn, final Result cb) {
        new Thread(() -> {
            try {
                String token = getToken(account, password);
                if (token.isEmpty()) { cb.onDone(false, "登录失败"); return; }
                String resp = http(STATE_URL + "?sn=" + enc(sn), null, null, UA_ANDROID, token, "GET");
                JSONObject j = new JSONObject(resp);
                if (j.optInt("status") == 0) {
                    JSONObject d = j.getJSONObject("data");
                    int isAccOn = d.optInt("isAccOn");
                    cb.onDone(true, String.valueOf(isAccOn));
                } else {
                    cb.onDone(false, "状态查询失败 " + j.optInt("status"));
                }
            } catch (Exception e) {
                cb.onDone(false, "状态查询异常: " + e.getMessage());
            }
        }).start();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String http(String url, String body, String contentType, String ua, String token, String method) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (token != null && !token.isEmpty()) c.setRequestProperty("token", token);
            if (contentType != null) c.setRequestProperty("Content-Type", contentType);
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "http err: " + e);
            return "{\"status\":-1,\"desc\":\"" + e.getMessage() + "\"}";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
