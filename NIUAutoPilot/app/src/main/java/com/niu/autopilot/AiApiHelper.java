package com.niu.autopilot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * v2.32 AI API 统一调用工具
 * 兼容 OpenAI 格式的国内主流接口：硅基流动、智谱、通义千问、Moonshot、DeepSeek、零一万物等
 */
public class AiApiHelper {
    private static final String TAG = "AiApiHelper";

    /** 内置国内主流接口 */
    public static final String[][] PRESET_APIS = {
            {"硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1"},
            {"智谱 AI Zhipu", "https://open.bigmodel.cn/api/paas/v4"},
            {"阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1"},
            {"月之暗面 Moonshot", "https://api.moonshot.cn/v1"},
            {"DeepSeek", "https://api.deepseek.com/v1"},
            {"零一万物 Yi", "https://api.lingyiwanwu.com/v1"},
            {"MiniMax", "https://api.minimax.chat/v1"},
            {"自定义", ""}
    };

    public interface ModelsCallback {
        void onSuccess(List<String> models);
        void onFailure(String error);
    }

    public interface ChatCallback {
        void onSuccess(String content);
        void onFailure(String error);
    }

    /** 获取模型列表（GET /models） */
    public static void fetchModels(String apiBase, String apiKey, ModelsCallback callback) {
        new Thread(() -> {
            try {
                String urlStr = apiBase.endsWith("/") ? apiBase + "models" : apiBase + "/models";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                int code = conn.getResponseCode();
                BufferedReader br;
                if (code >= 200 && code < 300) {
                    br = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                } else {
                    br = new BufferedReader(new java.io.InputStreamReader(conn.getErrorStream()));
                }
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 200 && code < 300) {
                    JSONObject resp = new JSONObject(sb.toString());
                    JSONArray data = resp.optJSONArray("data");
                    List<String> models = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject m = data.getJSONObject(i);
                            String id = m.optString("id", "");
                            if (!id.isEmpty()) models.add(id);
                        }
                    }
                    postModelsSuccess(callback, models);
                } else {
                    postModelsFailure(callback, "HTTP " + code + ": " + sb.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "fetchModels failed", e);
                postModelsFailure(callback, e.getMessage());
            }
        }).start();
    }

    /** 聊天补全（POST /chat/completions） */
    public static void chat(String apiBase, String apiKey, String model,
                            String systemPrompt, String userPrompt, ChatCallback callback) {
        new Thread(() -> {
            try {
                String urlStr = apiBase.endsWith("/") ? apiBase + "chat/completions" : apiBase + "/chat/completions";
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("temperature", 0.3);
                JSONArray messages = new JSONArray();
                JSONObject sysMsg = new JSONObject();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                messages.put(sysMsg);
                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt);
                messages.put(userMsg);
                body.put("messages", messages);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.close();

                int code = conn.getResponseCode();
                BufferedReader br;
                if (code >= 200 && code < 300) {
                    br = new BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                } else {
                    br = new BufferedReader(new java.io.InputStreamReader(conn.getErrorStream()));
                }
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                if (code >= 200 && code < 300) {
                    JSONObject resp = new JSONObject(sb.toString());
                    JSONArray choices = resp.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        String content = choices.getJSONObject(0)
                                .getJSONObject("message")
                                .optString("content", "");
                        postChatSuccess(callback, content);
                    } else {
                        postChatFailure(callback, "无返回内容: " + sb.toString());
                    }
                } else {
                    postChatFailure(callback, "HTTP " + code + ": " + sb.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "chat failed", e);
                postChatFailure(callback, e.getMessage());
            }
        }).start();
    }

    private static void postModelsSuccess(ModelsCallback cb, List<String> models) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(models));
    }

    private static void postModelsFailure(ModelsCallback cb, String err) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onFailure(err));
    }

    private static void postChatSuccess(ChatCallback cb, String content) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onSuccess(content));
    }

    private static void postChatFailure(ChatCallback cb, String err) {
        new Handler(Looper.getMainLooper()).post(() -> cb.onFailure(err));
    }
}
