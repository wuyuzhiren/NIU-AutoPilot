package com.niu.autopilot;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步命令执行器 v2.12
 * 独立线程消费命令，失败自动重试（指数退避，最多2次）
 * 适配 NiuHttpClient 现有的 callback API
 */
public class CommandExecutor {
    private static final String TAG = "CommandExecutor";
    private static final int MAX_RETRY = 2;
    private static final long RETRY_BASE_DELAY = 1500;

    private HandlerThread commandThread;
    private Handler commandHandler;
    private Handler mainHandler;
    private NiuHttpClient httpClient;
    private String account, password, sn;
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    public interface CommandCallback {
        void onSuccess(String command);
        void onFailure(String command, String error);
    }

    public CommandExecutor(Context ctx, NiuHttpClient client) {
        this.httpClient = client;
        Settings s = new Settings(ctx);
        this.account = s.getAccount();
        this.password = s.getPassword();
        this.sn = s.getSn();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** 刷新账号信息（登录后调用） */
    public void refreshCredentials(Context ctx) {
        Settings s = new Settings(ctx);
        this.account = s.getAccount();
        this.password = s.getPassword();
        this.sn = s.getSn();
    }

    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            commandThread = new HandlerThread("NiuCommandThread");
            commandThread.start();
            commandHandler = new Handler(commandThread.getLooper());
            Log.d(TAG, "命令执行线程已启动");
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (commandHandler != null) commandHandler.removeCallbacksAndMessages(null);
            if (commandThread != null) commandThread.quitSafely();
            Log.d(TAG, "命令执行线程已停止");
        }
    }

    /**
     * 提交命令（异步执行，不阻塞调用线程）
     */
    public void execute(String command, CommandCallback callback) {
        if (!isRunning.get() || commandHandler == null) {
            Log.w(TAG, "执行器未启动，忽略命令: " + command);
            return;
        }
        if (account == null || account.isEmpty() || sn == null || sn.isEmpty()) {
            postFailure(command, "账号或SN未配置", callback);
            return;
        }
        commandHandler.post(() -> executeWithRetry(command, 0, callback));
    }

    private void executeWithRetry(String command, int retryCount, CommandCallback callback) {
        try {
            httpClient.sendCommand(account, password, sn, command, (ok, msg) -> {
                if (ok) {
                    postSuccess(command, callback);
                } else {
                    handleFailure(command, retryCount, msg, callback);
                }
            });
        } catch (Exception e) {
            handleFailure(command, retryCount, e.getMessage(), callback);
        }
    }

    private void handleFailure(String command, int retryCount, String error, CommandCallback callback) {
        if (retryCount < MAX_RETRY && commandHandler != null) {
            long delay = RETRY_BASE_DELAY * (1L << retryCount);
            Log.w(TAG, String.format("命令%s失败(%s)，%dms后第%d次重试",
                    command, error, delay, retryCount + 1));
            commandHandler.postDelayed(() ->
                    executeWithRetry(command, retryCount + 1, callback), delay);
        } else {
            Log.e(TAG, "命令" + command + "最终失败: " + error);
            postFailure(command, error, callback);
        }
    }

    private void postSuccess(String command, CommandCallback callback) {
        if (callback != null) mainHandler.post(() -> callback.onSuccess(command));
    }

    private void postFailure(String command, String error, CommandCallback callback) {
        if (callback != null) mainHandler.post(() -> callback.onFailure(command, error));
    }

    public boolean isRunning() { return isRunning.get(); }
}
