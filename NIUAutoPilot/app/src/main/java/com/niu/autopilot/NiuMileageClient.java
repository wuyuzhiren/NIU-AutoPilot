package com.niu.autopilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 小牛里程与骑行数据客户端 v3.3
 *
 * 数据来源（均为小牛官方 App 使用的私有接口，实测可用）：
 *   1. POST /motoinfo/overallTally          → 总里程 totalMileage (km) + 绑车天数
 *   2. GET  /v5/scooter/motor_data/index_info → lastTrack.distance(米)/ridingTime(秒) + nowSpeed
 *   3. POST /v5/track/list/v2               → 历史骑行 items[].distance(米)/avespeed(km/h)
 *   4. GET  /v3/motor_data/cycling_statistics?sortby=1 → 每日 everydayMileage/AvgSpeed/maxSpeed
 *
 * 注意（踩坑记录）：
 *   - lastTrack.distance 单位是【米】，totalMileage 单位是【公里】，不要混
 *   - cycling_statistics 字段是 PascalCase 混 camelCase（AvgSpeed 大写 A）
 *   - track/list/v2 的 token 历史上要求放 body，这里 body 和 header 都带
 *   - 所有字段一律容错解析（服务端类型会变：number ↔ string）
 */
public class NiuMileageClient {
    private static final String TAG = "NiuMileage";

    private static final String BASE = "https://app-api.niu.com";
    private static final String URL_OVERALL_TALLY = BASE + "/motoinfo/overallTally";
    private static final String URL_INDEX_INFO = BASE + "/v5/scooter/motor_data/index_info";
    private static final String URL_TRACK_LIST = BASE + "/v5/track/list/v2";
    private static final String URL_CYCLING_STATS = BASE + "/v3/motor_data/cycling_statistics";

    private static final String UA = "manager/5.17.4 (android; IN2020 12);lang=zh-CN;clientIdentifier=Domestic;timezone=Asia/Shanghai;model=IN2020;deviceName=IN2020;ostype=android";
    private static final String APP_ID = "niu_ktdrr960";
    private static final String LOGIN_URL = "https://account.niu.com/v3/api/oauth2/token";

    /** 里程汇总数据 */
    public static class MileageSummary {
        /** 总里程 km（-1 表示未获取到） */
        public double totalMileageKm = -1;
        /** 绑车天数 */
        public int bindDays = -1;
        /** 今日里程 km（-1 表示未获取到） */
        public double todayKm = -1;
        /** 今日平均速度 km/h */
        public double todayAvgSpeed = -1;
        /** 今日最高速度 km/h */
        public double todayMaxSpeed = -1;
        /** 今日骑行时长（秒） */
        public int todayRidingTimeSec = -1;

        /** 上次骑行里程 km（来自 index_info.lastTrack） */
        public double lastRideKm = -1;
        /** 上次骑行时长（秒） */
        public int lastRideSec = -1;
        /** 上次骑行结束时间（毫秒时间戳） */
        public long lastRideEndMs = -1;
        /** 上次骑行的平均速度 km/h（v3.7：与「上次骑行里程」同一份数据） */
        public double lastRideAvgSpeed = -1;
        /** 上次骑行的最高速度 km/h（v3.7：同一份数据；接口未下发时用同日 statistics 兜底） */
        public double lastRideMaxSpeed = -1;
        /** 最近一个「非今天」日子的日期 yyyyMMdd（兜底时用于核对是不是同一次骑行） */
        public String prevDayDate = "";
        /** 最近一个「非今天」日子的平均速度 km/h */
        public double prevDayAvgSpeed = -1;
        /** 最近一个「非今天」日子的最高速度 km/h */
        public double prevDayMaxSpeed = -1;

        /**
         * v3.7: 「上次骑行」所属日期 yyyyMMdd。
         * 来自 index_info.lastTrack.time；若 lastTrack 缺失、由 statistics 兜底，
         * 则填兜底那一天的日期。用于把均速/最高速精确对齐到同一次骑行。
         */
        public String lastRideDate = "";
        /** v3.7: cycling_statistics 的按日索引，key = yyyyMMdd */
        public java.util.Map<String, DayStat> statsByDate = new java.util.HashMap<>();
        /** 当前速度 km/h */
        public double nowSpeed = -1;
        /** 剩余续航 km */
        public double estimatedMileageKm = -1;

        /** 最近若干条历史骑行 */
        public List<Ride> recentRides = new ArrayList<>();

        public boolean hasAny() {
            return totalMileageKm >= 0 || todayKm >= 0 || lastRideKm >= 0
                    || !recentRides.isEmpty();
        }
    }

    /** 一条历史骑行记录 */
    public static class Ride {
        public long startMs;
        public long endMs;
        /** 里程 km */
        public double distanceKm;
        /** 平均速度 km/h */
        public double avgSpeed;
        /** 骑行时长 秒 */
        public int ridingSec;
        /** 日期 yyyyMMdd */
        public String date = "";
    }

    /**
     * v3.7: cycling_statistics 里某一天的汇总（按日期索引）。
     * 用来把「上次骑行」的均速/最高速精确对齐到那一天，而不是误用今天的数。
     */
    public static class DayStat {
        public double km = -1;
        public double avgSpeed = -1;
        public double maxSpeed = -1;
        public int ridingSec = -1;
    }

    public interface SummaryCallback {
        void onDone(boolean ok, MileageSummary summary, String msg);
    }

    public interface RidesCallback {
        void onDone(boolean ok, List<Ride> rides, String msg);
    }

    private final Context ctx;
    private final SharedPreferences sp;

    public NiuMileageClient(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences("niu_autopilot", Context.MODE_PRIVATE);
    }

    // ==================== 对外入口 ====================

    /**
     * 一次性拉取全部里程数据（后台线程，回调在主线程）。
     * 任一子请求失败不影响其它字段，尽量拼出可用结果。
     */
    public void loadSummary(final String account, final String password, final String sn,
                            final SummaryCallback cb) {
        new Thread(() -> {
            MileageSummary s = new MileageSummary();
            StringBuilder err = new StringBuilder();
            try {
                String token = getToken(account, password);
                if (token == null || token.isEmpty()) {
                    post(cb, false, s, "登录失败，请检查账号/密码/网络");
                    return;
                }

                // v3.4: 4 个请求【并发】执行（PM PRD 缺陷修复）。
                // 旧实现串行 + 每个 30s 超时，最坏要 120s 才返回，用户看半天"—"。
                // 现在并发 + 8s 超时，最坏 ~8s 就能出全部结果。
                final String tok = token;
                java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(4);
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(4);

                final String[] errs = new String[4];

                pool.submit(() -> {
                    try { fetchOverallTally(tok, sn, s); }
                    catch (Exception e) { errs[0] = "总里程:" + e.getMessage(); Log.w(TAG, "overallTally failed", e); }
                    finally { latch.countDown(); }
                });
                pool.submit(() -> {
                    try { fetchIndexInfo(tok, sn, s); }
                    catch (Exception e) { errs[1] = "上次骑行:" + e.getMessage(); Log.w(TAG, "indexInfo failed", e); }
                    finally { latch.countDown(); }
                });
                pool.submit(() -> {
                    try { fetchTodayStats(tok, sn, s); }
                    catch (Exception e) { errs[2] = "今日统计:" + e.getMessage(); Log.w(TAG, "cyclingStats failed", e); }
                    finally { latch.countDown(); }
                });
                pool.submit(() -> {
                    try { s.recentRides = fetchRides(tok, sn, 10); }
                    catch (Exception e) { errs[3] = "历史骑行:" + e.getMessage(); Log.w(TAG, "trackList failed", e); }
                    finally { latch.countDown(); }
                });

                // 总超时 10s（比单请求超时 8s 略宽，容纳线程调度）
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
                pool.shutdownNow();

                // v3.7: 等 4 个并发请求都回来后再解析「上次骑行的均速/最高速」，
                // 因为要跨 track/list（有 avespeed）与 cycling_statistics（有 maxSpeed）取数。
                resolveLastRideSpeeds(s);

                for (String e : errs) if (e != null) err.append(e).append(' ');

                boolean ok = s.hasAny();
                post(cb, ok, s, ok ? "OK" : ("全部查询失败 " + err.toString().trim()));
            } catch (Exception e) {
                Log.e(TAG, "loadSummary failed", e);
                post(cb, false, s, "异常: " + e.getMessage());
            }
        }).start();
    }

    // ==================== 各接口实现 ====================

    /**
     * 总里程：POST /motoinfo/overallTally
     * ⚠️ 实测（2026-10-28 真机验证）：本接口 body 必须是 **JSON**，
     * 用 form（sn=xxx）会返回 status=1301 且 trace 为
     * "invalid character 's' looking for beginning of value"。
     * token 走 header 即可。
     */
    private void fetchOverallTally(String token, String sn, MileageSummary s) throws Exception {
        String body = "{\"sn\":\"" + sn + "\"}";
        String resp = http(URL_OVERALL_TALLY,
                body, "application/json; charset=utf-8", token, "POST");
        JSONObject j = new JSONObject(resp);
        if (j.optInt("status", -1) != 0) {
            throw new Exception("status=" + j.optInt("status")
                    + " " + j.optString("desc", "") + " " + j.optString("trace", ""));
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return;
        s.totalMileageKm = optD(d, "totalMileage");
        s.bindDays = (int) optD(d, "bindDaysCount");
    }

    /** index_info：lastTrack + nowSpeed + estimatedMileage */
    private void fetchIndexInfo(String token, String sn, MileageSummary s) throws Exception {
        String resp = http(URL_INDEX_INFO + "?sn=" + enc(sn), null, null, token, "GET");
        JSONObject j = new JSONObject(resp);
        if (j.optInt("status", -1) != 0) {
            throw new Exception("status=" + j.optInt("status"));
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return;

        s.nowSpeed = optD(d, "nowSpeed");
        s.estimatedMileageKm = optD(d, "estimatedMileage");

        JSONObject lt = d.optJSONObject("lastTrack");
        if (lt != null) {
            double meters = optD(lt, "distance");
            // 距离 > 0 才算有效（副账号常返回 0，视为无效，由 cycling_statistics 兜底）
            if (meters > 0) {
                s.lastRideKm = meters / 1000.0;   // 米 → 公里
                s.lastRideSec = (int) optD(lt, "ridingTime");
                s.lastRideEndMs = (long) optD(lt, "time");
                // v3.7: 记下这次骑行的日期，供均速/最高速精确对齐
                if (s.lastRideEndMs > 0) {
                    s.lastRideDate = new java.text.SimpleDateFormat("yyyyMMdd",
                            java.util.Locale.US).format(new java.util.Date(s.lastRideEndMs));
                }
            }
        }
    }

    /** 今日统计：GET /v3/motor_data/cycling_statistics?sortby=1&sn=xxx */
    private void fetchTodayStats(String token, String sn, MileageSummary s) throws Exception {
        String resp = http(URL_CYCLING_STATS + "?sortby=1&sn=" + enc(sn),
                null, null, token, "GET");
        JSONObject j = new JSONObject(resp);
        if (j.optInt("status", -1) != 0) {
            throw new Exception("status=" + j.optInt("status"));
        }
        JSONObject d = j.optJSONObject("data");
        if (d == null) return;
        JSONArray items = d.optJSONArray("items");
        if (items == null || items.length() == 0) return;

        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        // 昨天日期（用于"上次骑行"兜底）
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1);
        String yesterday = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(cal.getTime());

        JSONObject hit = null;
        JSONObject yesterdayHit = null;
        JSONObject latestPrevHit = null;   // 最近一个「非今天」的日子
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String dstr = it.optString("date", "");
            // v3.7: 先把所有日子建成索引，供 resolveLastRideSpeeds() 按日期精确取数
            if (!dstr.isEmpty()) {
                DayStat ds = new DayStat();
                // ⚠️ 官方字段是 everdayMileage（少一个 y，拼写错误但真实如此）
                ds.km = optDFirst(it, "everdayMileage", "everydayMileage");
                ds.avgSpeed = optD(it, "AvgSpeed");        // 注意大写 A
                ds.maxSpeed = optD(it, "maxSpeed");
                ds.ridingSec = (int) optD(it, "ridingTime");
                s.statsByDate.put(dstr, ds);
            }
            if (today.equals(dstr)) {
                hit = it;
            } else {
                // 记录最近一个非今天的日子（数组按日期倒序）
                if (latestPrevHit == null && !dstr.isEmpty()) latestPrevHit = it;
                if (yesterday.equals(dstr)) yesterdayHit = it;
            }
        }

        // v3.4: 修复 PM PRD 指出的口径缺陷 ——
        // 「今日无骑行必须显示 0.0」，不能回落成往日值。
        if (hit != null) {
            // ⚠️ 官方字段是 everdayMileage（少一个 y，拼写错误但真实如此）
            s.todayKm = optDFirst(hit, "everdayMileage", "everydayMileage");
            s.todayAvgSpeed = optD(hit, "AvgSpeed");        // 注意大写 A
            s.todayMaxSpeed = optD(hit, "maxSpeed");
            s.todayRidingTimeSec = (int) optD(hit, "ridingTime");
        } else {
            // 今天没有记录 = 今天还没骑，显式置 0（而不是回落成昨天/前天的数）
            s.todayKm = 0;
            s.todayAvgSpeed = 0;
            s.todayMaxSpeed = 0;
            s.todayRidingTimeSec = 0;
        }

        // 上次骑行：优先用 index_info.lastTrack（>0 才有效）；
        // v3.8: 若 lastTrack 全 0（本车实测返回 distance=0/ridingTime=0/time=0，
        // 即账号无轨迹权限时服务端不下发），取【最近一个有骑行记录的日子，包含今天】。
        //
        // v3.7 的错误：这里用了「最近一个【非今天】的日子」，于是今天刚骑完也看不见，
        // 直接显示成昨天那次。用户用官方 App 截图对照指出：
        //   官方「最近骑行」= 今天 00:17-00:32 / 6.7km / 14'35" / 31.7km-h
        //   而我们显示 60.0km / 1小时37分 / 36km-h / 90  ← 全是 11-02 的
        // 正确语义：最近一次骑行就是「最近一个有记录的日子」，今天骑过就用今天。
        DayStat lastStat = null;
        String lastStatDate = "";
        if (s.statsByDate != null) {
            for (java.util.Map.Entry<String, DayStat> e : s.statsByDate.entrySet()) {
                DayStat ds = e.getValue();
                if (ds == null || ds.km <= 0) continue;   // 只认「真的骑了」的日子
                if (lastStatDate.isEmpty() || e.getKey().compareTo(lastStatDate) > 0) {
                    lastStatDate = e.getKey();
                    lastStat = ds;
                }
            }
        }
        if (s.lastRideKm < 0 && lastStat != null) {
            s.lastRideKm = lastStat.km;
            s.lastRideSec = lastStat.ridingSec;
            s.lastRideDate = lastStatDate;
            // 三格指标必须与里程同源，否则又变成「里程来自 A、速度来自 B」
            s.lastRideAvgSpeed = lastStat.avgSpeed;
            s.lastRideMaxSpeed = lastStat.maxSpeed;
        }

        // prevDay* 仅作「更早一天」的补充兜底（resolveLastRideSpeeds 里用）
        JSONObject prev = (yesterdayHit != null) ? yesterdayHit : latestPrevHit;
        if (prev != null) {
            s.prevDayDate = prev.optString("date", "");
            s.prevDayAvgSpeed = optD(prev, "AvgSpeed");
            s.prevDayMaxSpeed = optD(prev, "maxSpeed");
        }
    }

    /**
     * v3.7: 把「上次骑行的均速/最高速」补齐到同一份数据上。
     *
     * 数据来源优先级：
     *   1. track/list/v2 最近一条（有 avespeed，最准，且与 lastRideKm 同源）
     *   2. index_info.lastTrack 只有 distance/ridingTime，没有速度 → 用
     *      cycling_statistics 里【最近一个非今天】那天的 AvgSpeed / maxSpeed 兜底
     *   3. 都没有就留 -1，UI 显示「—」
     *
     * 关键：绝不能再用「今天」的 AvgSpeed/maxSpeed —— 那是用户明确指出的口径错误
     * （上次骑行 60.0 km 却配今天 31 km/h 的均速）。
     */
    private void resolveLastRideSpeeds(MileageSummary s) {
        // 0) 若能定位到「上次骑行」的日期，直接用那一天的 statistics（最精确的对齐）
        DayStat sameDay = null;
        if (s.lastRideDate != null && !s.lastRideDate.isEmpty()) {
            sameDay = s.statsByDate.get(s.lastRideDate);
        }

        // 1) 均速：优先 track/list 最近一条（avespeed，同一次骑行最准）
        if (s.recentRides != null) {
            for (Ride r : s.recentRides) {
                if (r.distanceKm < 0) continue;
                if (s.lastRideAvgSpeed < 0 && r.avgSpeed >= 0) s.lastRideAvgSpeed = r.avgSpeed;
                break;   // 只认最近一条，不往后找
            }
        }
        // 2) 均速兜底：同一天 statistics 的 AvgSpeed
        if (s.lastRideAvgSpeed < 0 && sameDay != null && sameDay.avgSpeed >= 0) {
            s.lastRideAvgSpeed = sameDay.avgSpeed;
        }
        // 3) 均速再兜底：最近一个「非今天」那天的 AvgSpeed
        if (s.lastRideAvgSpeed < 0 && s.prevDayAvgSpeed >= 0) {
            s.lastRideAvgSpeed = s.prevDayAvgSpeed;
        }
        // 4) 最高速：track/list 与 index_info 都不下发 → 只能用同一天 statistics 的 maxSpeed
        if (s.lastRideMaxSpeed < 0 && sameDay != null && sameDay.maxSpeed >= 0) {
            s.lastRideMaxSpeed = sameDay.maxSpeed;
        }
        if (s.lastRideMaxSpeed < 0 && s.prevDayMaxSpeed >= 0) {
            s.lastRideMaxSpeed = s.prevDayMaxSpeed;
        }
        // 5) 骑行时长：lastTrack 没给就用同一天 statistics 的
        if (s.lastRideSec < 0 && sameDay != null && sameDay.ridingSec >= 0) {
            s.lastRideSec = sameDay.ridingSec;
        }
    }

    /**
     * 历史骑行：POST /v5/track/list/v2
     * ⚠️ 实测（2026-10-28 真机验证）：
     *  - body 必须用 **form 格式**（sn/index/pagesize），JSON 会 1301 参数错误
     *  - token 走 header
     *  - 副账号会返回 status=1304「此操作需车主权限」——属预期，静默降级为空列表
     */
    private List<Ride> fetchRides(String token, String sn, int pageSize) throws Exception {
        List<Ride> out = new ArrayList<>();
        String body = "sn=" + enc(sn) + "&index=0&pagesize=" + pageSize;
        String resp = http(URL_TRACK_LIST, body,
                "application/x-www-form-urlencoded", token, "POST");
        JSONObject j = new JSONObject(resp);
        int st = j.optInt("status", -1);
        if (st == 1304) {
            // 副账号无车主权限：不是错误，只是没有历史记录可看
            Log.i(TAG, "trackList: 副账号无车主权限，跳过历史骑行");
            return out;
        }
        if (st != 0) {
            throw new Exception("status=" + st + " " + j.optString("desc", ""));
        }
        JSONObject d = j.optJSONObject("data");
        JSONArray items = null;
        if (d != null) {
            items = d.optJSONArray("items");
            // 兼容部分版本 data 直接含数组
            if (items == null && d.has("list")) items = d.optJSONArray("list");
        } else {
            items = j.optJSONArray("data");
        }
        if (items == null) return out;

        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            Ride r = new Ride();
            r.startMs = (long) optD(it, "startTime");
            r.endMs = (long) optD(it, "endTime");
            double meters = optD(it, "distance");
            r.distanceKm = meters >= 0 ? meters / 1000.0 : -1;
            r.avgSpeed = optD(it, "avespeed");
            r.ridingSec = (int) optD(it, "ridingtime");
            r.date = it.optString("date", "");
            if (r.distanceKm >= 0 || r.startMs > 0) out.add(r);
        }
        return out;
    }

    // ==================== 工具 ====================

    /** 容错读取数字：兼容 number 与 string（服务端字段类型不稳定） */
    private static double optD(JSONObject o, String key) {
        if (o == null || !o.has(key)) return -1;
        try {
            Object v = o.get(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) {
                String sv = ((String) v).trim();
                if (sv.isEmpty()) return -1;
                return Double.parseDouble(sv);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 依次尝试多个 key，返回第一个有效值（应对官方字段拼写不一致） */
    private static double optDFirst(JSONObject o, String... keys) {
        for (String k : keys) {
            double v = optD(o, k);
            if (v >= 0) return v;
        }
        return -1;
    }

    private final Object tokenLock = new Object();

    private String getToken(String account, String password) {
        synchronized (tokenLock) {
            String token = sp.getString("access_token", "");
            long expire = sp.getLong("token_expire", 0);
            if (token != null && !token.isEmpty() && expire > System.currentTimeMillis() + 60000) {
                return token;
            }
            String form = "account=" + enc(account) + "&password=" + NiuHttpClient.md5(password)
                    + "&grant_type=password&scope=base&app_id=" + APP_ID;
            String resp = http(LOGIN_URL, form, "application/x-www-form-urlencoded", null, "POST");
            try {
                JSONObject j = new JSONObject(resp);
                if (j.optInt("status") == 0) {
                    JSONObject data = j.getJSONObject("data");
                    JSONObject t = data.getJSONObject("token");
                    String tok = t.getString("access_token");
                    long ttl = (long) optD(t, "expires_in");
                    if (ttl <= 0) ttl = 3600;
                    sp.edit().putString("access_token", tok)
                            .putLong("token_expire", System.currentTimeMillis() + (long) ttl * 1000L)
                            .apply();
                    return tok;
                }
            } catch (Exception e) {
                Log.e(TAG, "login parse failed", e);
            }
            return "";
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static void post(SummaryCallback cb, boolean ok, MileageSummary s, String msg) {
        if (cb != null) {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onDone(ok, s, msg));
        }
    }

    private static String http(String url, String body, String contentType,
                               String token, String method) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            // v3.4: 单请求 8s 超时（配合并发，整体最坏 ~10s 出结果）
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
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
            if (is == null) return "{\"status\":-1,\"desc\":\"empty\"}";
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "http err: " + e);
            return "{\"status\":-1,\"desc\":\"" + e.getMessage() + "\"}";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
