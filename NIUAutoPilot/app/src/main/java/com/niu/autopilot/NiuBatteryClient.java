package com.niu.autopilot;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 电池电量客户端 v2.77（多平台）
 *
 * 支持平台：
 *  - jichi  极驰锐动租电：固定URL https://syzzz01.huandian.cloud/realTime/soc?type=2
 *           响应 data.dumpEnergy 为电量百分比
 *  - xinneng 鑫能出行：固定URL https://wechat.fnjkj.cn/battery_wechat/device/detail?idDevice=<电池ID>
 *           请求头带 appId/sign/identify/app-type/appType/access-token
 *           响应 data.batteryPackageDto.soc 为电量百分比
 *  - hello / custom  哈啰 / 自定义API：设置页填接口URL，
 *           GET请求带 Authorization: token，响应自动识别字段：
 *           dumpEnergy / battery / soc / percent / level / power / 电量
 *
 * v2.77 升级（对应投喂包 03-xinneng-battery-fix）：
 *  - appId / sign / identify 改为 Settings 可配置（默认保留抓包原值，接口变更可改，sign 可留空不发送）
 *  - 本地缓存：同步失败时保留上次电量并显示「上次同步时间」，不因一次失败清空
 *  - 错误分级提示：Token过期 / 未查到电池 / 网络失败 / 解析异常 分开给用户
 *
 * token 获取：Charles 抓包对应平台小程序，复制电量接口请求的 Authorization/access-token 头，
 *             在设置页「电池电量」卡粘贴保存。
 */
public class NiuBatteryClient {

    private static final String JICHI_URL = "https://syzzz01.huandian.cloud/realTime/soc?type=2";
    /** v2.68 鑫能出行设备详情接口模板（%s=电池ID，抓包 device/detail?idDevice=xxx 获得） */
    private static final String XINNENG_URL = "https://wechat.fnjkj.cn/battery_wechat/device/detail?idDevice=%s";
    /** v2.70 鑫能出行：按 token 自动发现当前电池的接口（换电池无需改ID） */
    private static final String[] XINNENG_DISCOVER = {
            "https://wechat.fnjkj.cn/battery_wechat/device/myDevice",
            "https://wechat.fnjkj.cn/battery_wechat/device/getDeviceList",
            "https://wechat.fnjkj.cn/battery_wechat/device/list"
    };

    public interface BatteryCallback {
        void onDone(boolean ok, int percent, String msg);
    }

    private final Context ctx;
    private final Settings settings;

    public NiuBatteryClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.settings = new Settings(ctx);
    }

    /** 手动同步：用户在小程序查看后手动录入百分比（0~100） */
    public void manualSync(int percent) {
        settings.setBatteryPercent(Math.max(0, Math.min(100, percent)));
        settings.setBatterySyncedAt(System.currentTimeMillis());
        settings.setBatterySource("manual");
    }

    /** 当前平台显示名 */
    public static String platformName(Settings s) {
        String p = s.getBatteryPlatform();
        if ("hello".equals(p)) return "哈啰";
        if ("xinneng".equals(p)) return "鑫能出行";
        if ("custom".equals(p)) return "自定义API";
        return "极驰锐动";
    }

    /** 该平台是否需要填接口URL（极驰/鑫能不需要，其余需要） */
    public static boolean needUrl(Settings s) {
        String p = s.getBatteryPlatform();
        return !"jichi".equals(p) && !"xinneng".equals(p);
    }

    /**
     * 查询电量（后台线程回调）。
     * 极驰锐动用固定URL；鑫能出行用固定模板+电池ID+专属请求头；自定义平台用设置里填的URL，均带 token。
     */
    public void queryBattery(final BatteryCallback cb) {
        new Thread(() -> {
            String token = settings.getBatteryToken();
            if (token == null || token.trim().isEmpty()) {
                if (cb != null) cb.onDone(false, -1,
                        "请先在设置页「电池电量」卡粘贴 " + platformName(settings) + " token\n（抓包电量接口请求的 access-token/Authorization 值）");
                return;
            }
            token = token.trim();

            String platform = settings.getBatteryPlatform();
            String urlStr = null;
            String deviceId = null;
            boolean xinneng = "xinneng".equals(platform);
            if (xinneng) {
                // 优先使用缓存/手填电池ID直接查 detail；无缓存时才自动发现
                String cached = settings.getBatteryDeviceId();
                if (cached != null && !cached.trim().isEmpty()) {
                    deviceId = cached.trim();
                } else {
                    deviceId = discoverDeviceId(token);
                    if (deviceId != null) {
                        settings.setBatteryDeviceId(deviceId);
                    }
                }
                if (deviceId == null || deviceId.trim().isEmpty()) {
                    if (cb != null) cb.onDone(false, -1,
                            "未找到电池ID：请打开「鑫能出行」小程序 → 我的设备，复制 BTA 开头的电池ID，"
                                    + "粘贴到设置页「电池ID」输入框后再同步");
                    return;
                }
            } else if ("jichi".equals(platform)) {
                urlStr = JICHI_URL;
            } else {
                urlStr = settings.getBatteryApiUrl();
                if (urlStr == null || urlStr.trim().isEmpty()) {
                    if (cb != null) cb.onDone(false, -1,
                            "自定义平台需要先在设置页填写「接口地址 URL」（GET 接口）");
                    return;
                }
                urlStr = urlStr.trim();
            }

            // v2.80 鑫能：最多两轮。第一轮 detail 返回"电池不存在/无效"（换电池后ID失效）时，
            // 自动清缓存 → 用 token 重新发现新电池ID → 重查一轮，实现"只用 token、换电池免手填"。
            for (int attempt = 0; attempt < 2; attempt++) {
                if (xinneng) {
                    urlStr = String.format(XINNENG_URL, deviceId.trim());
                }
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    if (xinneng) {
                        // v2.77 鑫能出行专属请求头（appId/sign 从设置页读取，可配置；sign 留空则不发送）
                        conn.setRequestProperty("access-token", token);
                        String appId = settings.getBatteryAppId();
                        if (appId != null && !appId.trim().isEmpty()) {
                            conn.setRequestProperty("appId", appId.trim());
                        }
                        String sign = settings.getBatterySign();
                        if (sign != null && !sign.trim().isEmpty()) {
                            conn.setRequestProperty("sign", sign.trim());
                            conn.setRequestProperty("identify", sign.trim());
                        }
                        conn.setRequestProperty("app-type", "MULTI_SERVICE");
                        conn.setRequestProperty("appType", "weapp");
                    } else {
                        conn.setRequestProperty("Authorization", token);
                    }
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    String resp = sb.toString();
                    conn.disconnect();

                    // 通用解析：先试常见JSON字段，兼容极驰{"code":"200","success":true,"data":{"dumpEnergy":"59"}}
                    int percent = parsePercent(resp);
                    if (percent >= 0 && percent <= 100) {
                        if (xinneng) {
                            // v2.80 成功时提取响应里的当前电池ID，与缓存比对，不同则自动更新（换电池后detail能返回新ID）
                            String respId = extractBatteryId(resp);
                            if (respId != null && !respId.equals(settings.getBatteryDeviceId())) {
                                settings.setBatteryDeviceId(respId);
                            }
                        }
                        settings.setBatteryPercent(percent);
                        settings.setBatterySyncedAt(System.currentTimeMillis());
                        settings.setBatterySource("api");
                        if (cb != null) cb.onDone(true, percent, "同步成功");
                        return;
                    }

                    String msg;
                    if (xinneng) {
                        // v2.76 鑫能：透出业务错误（如"未查询到该电池数据"）
                        String biz = parseXinnengError(resp);
                        if (biz != null) {
                            if (biz.contains("未查询到") || biz.contains("不存在") || biz.contains("无效")) {
                                if (attempt == 0) {
                                    // v2.80 换电池自动识别：清掉旧ID缓存，用token重新发现新电池ID并重查
                                    settings.setBatteryDeviceId("");
                                    String newId = discoverDeviceId(token);
                                    if (newId != null) {
                                        settings.setBatteryDeviceId(newId);
                                        deviceId = newId;
                                        continue; // 第二轮用新ID重查
                                    }
                                    msg = "未查到当前电池电量：" + biz + "。自动识别新电池ID失败，请打开鑫能出行小程序「我的设备」复制 BTA 开头的电池ID，更新设置页「电池ID」";
                                } else {
                                    msg = "未查到当前电池电量：" + biz + "。请打开鑫能出行小程序「我的设备」复制 BTA 开头的电池ID，更新设置页「电池ID」";
                                }
                            } else {
                                msg = "同步失败：" + biz + "（若持续出现请检查设置页 appId/sign 是否与最新抓包一致）";
                            }
                        } else {
                            msg = "同步失败: " + resp;
                        }
                    } else {
                        msg = "同步失败: " + resp;
                    }
                    if (code == 401 || code == 403) {
                        msg = "token 已过期，请打开鑫能出行小程序重新抓包更新 access-token";
                    } else if (code == 404) {
                        msg = "接口地址不存在（404），请检查电池ID或联系开发者确认接口变更";
                    } else if (code >= 500) {
                        msg = "鑫能服务器异常（HTTP " + code + "），请稍后重试";
                    }
                    // v2.77 失败时保留上次电量缓存，提示用户
                    if (settings.getBatteryPercent() >= 0 && settings.getBatterySyncedAt() > 0) {
                        msg += "\n（已保留上次电量 " + settings.getBatteryPercent() + "%，来自 " + syncedText() + "）";
                    }
                    if (cb != null) cb.onDone(false, -1, msg);
                    return;
                } catch (Exception e) {
                    String em = e.getMessage() == null ? e.toString() : e.getMessage();
                    String netMsg;
                    if (em.contains("timeout") || em.contains("Timeout") || em.contains("timed out")) {
                        netMsg = "网络连接超时，请检查网络后重试";
                    } else if (em.contains("UnknownHost") || em.contains("Unable to resolve")) {
                        netMsg = "无法访问服务器（域名解析失败），请检查网络";
                    } else if (em.contains("ConnectException") || em.contains("failed to connect")) {
                        netMsg = "连接服务器失败，请检查网络后重试";
                    } else {
                        netMsg = "请求异常: " + em;
                    }
                    if (settings.getBatteryPercent() >= 0 && settings.getBatterySyncedAt() > 0) {
                        netMsg += "\n（已保留上次电量 " + settings.getBatteryPercent() + "%，来自 " + syncedText() + "）";
                    }
                    if (cb != null) cb.onDone(false, -1, netMsg);
                    return;
                }
            }
        }).start();
    }

    /** v2.80 从鑫能 detail 响应中提取当前电池ID（batteryPackageDto.batteryId），宽松正则匹配 */
    private String extractBatteryId(String resp) {
        if (resp == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"batteryId\"\\s*:\\s*\"([^\"]+)\"").matcher(resp);
        if (m.find()) {
            String id = m.group(1).trim();
            if (looksLikeDeviceId(id)) return id;
        }
        return null;
    }

    /**
     * v2.70 鑫能出行：用 token 自动发现当前电池ID。
     * 依次尝试多个设备查询接口，从响应中深度查找 batteryId/deviceId/idDevice 等字段。
     * 换电池后无需手动填写电池ID，点同步自动识别。
     */
    private String discoverDeviceId(String token) {
        for (String base : XINNENG_DISCOVER) {
            try {
                URL url = new URL(base);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("access-token", token);
                String appId = settings.getBatteryAppId();
                if (appId != null && !appId.trim().isEmpty()) {
                    conn.setRequestProperty("appId", appId.trim());
                }
                String sign = settings.getBatterySign();
                if (sign != null && !sign.trim().isEmpty()) {
                    conn.setRequestProperty("sign", sign.trim());
                    conn.setRequestProperty("identify", sign.trim());
                }
                conn.setRequestProperty("app-type", "MULTI_SERVICE");
                conn.setRequestProperty("appType", "weapp");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                String resp = sb.toString();
                conn.disconnect();
                if (code >= 200 && code < 300) {
                    String id = extractDeviceId(resp);
                    if (id != null) return id;
                }
            } catch (Exception ignored) {
                // 换下一个接口
            }
        }
        return null;
    }

    /** 从鑫能响应 JSON 中深度提取电池ID（兼容对象/数组/多层嵌套，键名宽松匹配） */
    private String extractDeviceId(String resp) {
        if (resp == null || resp.trim().isEmpty()) return null;
        String t = resp.trim();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(t);
            for (int i = 0; i < arr.length(); i++) {
                Object o = arr.opt(i);
                String id = null;
                if (o instanceof org.json.JSONObject) id = findDeviceId((org.json.JSONObject) o, 0);
                if (id != null) return id;
            }
        } catch (Exception ignored) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(t);
                return findDeviceId(j, 0);
            } catch (Exception ignored2) {}
        }
        return null;
    }

    private String findDeviceId(org.json.JSONObject o, int depth) {
        if (o == null || depth > 8) return null;
        // 优先常见键名
        String[] keys = {"batteryId", "deviceId", "idDevice", "batteryID", "deviceID", "id", "batterySn", "sn"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && looksLikeDeviceId(v)) return v.trim();
        }
        // 递归子对象/数组
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v instanceof org.json.JSONObject) {
                String id = findDeviceId((org.json.JSONObject) v, depth + 1);
                if (id != null) return id;
            } else if (v instanceof org.json.JSONArray) {
                org.json.JSONArray a = (org.json.JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    Object e = a.opt(i);
                    if (e instanceof org.json.JSONObject) {
                        String id = findDeviceId((org.json.JSONObject) e, depth + 1);
                        if (id != null) return id;
                    } else if (e instanceof String) {
                        String s = ((String) e).trim();
                        if (looksLikeDeviceId(s)) return s;
                    }
                }
            }
        }
        return null;
    }

    /** 宽松判断：电池ID通常 BTA/字母数字混合且长度>=8 */
    private boolean looksLikeDeviceId(String v) {
        if (v.length() < 8) return false;
        return v.matches("[A-Za-z0-9_-]{8,64}");
    }

    /** v2.76 解析鑫能业务错误：code!=0 且有 message 时返回提示语（如"未查询到该电池数据"） */
    private String parseXinnengError(String resp) {
        if (resp == null || resp.trim().isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(resp.trim());
            if (j.has("code")) {
                Object c = j.opt("code");
                boolean isErr = c instanceof Number ? ((Number) c).intValue() != 0
                        : !"0".equals(String.valueOf(c)) && !"200".equals(String.valueOf(c));
                if (isErr) {
                    String m = j.optString("message", "");
                    if (!m.isEmpty()) return m;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从响应文本里解析电量百分比（-1=失败），兼容多种字段名与嵌套 */
    private int parsePercent(String resp) {
        if (resp == null || resp.trim().isEmpty()) return -1;
        String t = resp.trim();
        try {
            JSONObject j = new JSONObject(t);
            // v2.68 深度递归查找（鑫能 soc 在 data.batteryPackageDto 两层嵌套里）
            int p = findDeep(j, 0);
            if (p >= 0) return p;
            // 极驰：data 子对象
            JSONObject data = j.optJSONObject("data");
            if (data != null) {
                p = findInObject(data);
                if (p >= 0) return p;
            }
            // result / resultData 子对象
            JSONObject r = j.optJSONObject("result");
            if (r != null) { p = findInObject(r); if (p >= 0) return p; }
            JSONObject rd = j.optJSONObject("resultData");
            if (rd != null) { p = findInObject(rd); if (p >= 0) return p; }
        } catch (Exception ignored) {
            // 非JSON，试正则提取数字
        }
        // 非JSON：尝试匹配 "xxx": 88 或 88%
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("[\"']?(battery|soc|percent|dumpEnergy|power|level|电量)[\"']?\\s*[:=]\\s*[\"']?(\\d{1,3})")
                    .matcher(t);
            if (m.find()) {
                int v = Integer.parseInt(m.group(2));
                if (v >= 0 && v <= 100) return v;
            }
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d{1,3})\\s*%").matcher(t);
            if (m2.find()) {
                int v = Integer.parseInt(m2.group(1));
                if (v >= 0 && v <= 100) return v;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** v2.68 深度递归查找电量字段（最多下探6层，防止鑫能等嵌套结构） */
    private int findDeep(JSONObject o, int depth) {
        if (o == null || depth > 6) return -1;
        int p = findInObject(o);
        if (p >= 0) return p;
        java.util.Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                p = findDeep((JSONObject) v, depth + 1);
                if (p >= 0) return p;
            }
        }
        return -1;
    }

    private int findInObject(JSONObject o) {
        String[] keys = {"dumpEnergy", "battery", "soc", "percent", "power", "level", "电量", "batteryLevel", "batteryPercent"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.isEmpty()) continue;
            try {
                int p = Integer.parseInt(v.trim());
                if (p >= 0 && p <= 100) return p;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** 获取电量百分比（未同步返回 -1） */
    public int getPercent() { return settings.getBatteryPercent(); }

    /** 电量等级：0=未同步 1=低(<20) 2=中(20~49) 3=高(>=50) */
    public int level() {
        int p = settings.getBatteryPercent();
        if (p < 0) return 0;
        if (p < 20) return 1;
        if (p < 50) return 2;
        return 3;
    }

    /** 电量显示颜色（配合 level） */
    public static int colorFor(int level) {
        switch (level) {
            case 1: return 0xFFE53935; // 红：低电量
            case 2: return 0xFFFB8C00; // 橙：中
            case 3: return 0xFF43A047; // 绿：充足
            default: return 0xFF9E9E9E; // 灰：未同步
        }
    }

    /** 同步时间描述 */
    public String syncedText() {
        long t = settings.getBatterySyncedAt();
        if (t <= 0) return "未同步";
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1) return "刚刚同步";
        if (min < 60) return min + " 分钟前同步";
        long h = min / 60;
        if (h < 24) return h + " 小时前同步";
        return (h / 24) + " 天前同步";
    }
}
