package com.vscreen.toolbox;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/** Shizuku 权限与远程命令执行封装。 */
public final class ShizukuCmd {

    private ShizukuCmd() {
    }

    /** Shizuku binder 可用且已授权。 */
    public static boolean isReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    public static void requestPermission(Context ctx) {
        if (!binderAlive()) {
            LogStore.add(ctx, "ERR", "Shizuku 未运行：请先打开 Shizuku 应用并启动服务，或用无线调试启动");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            LogStore.add(ctx, "INFO", "向 Shizuku 申请授权");
            Shizuku.requestPermission(9527);
        } catch (Exception e) {
            LogStore.add(ctx, "ERR", "申请 Shizuku 授权失败：" + e.getMessage());
        }
    }

    /** 以 shell 身份执行命令；成功返回 stdout，失败返回 null 并记日志。 */
    public static String exec(Context ctx, String cmd) {
        return run(ctx, cmd, false);
    }

    /**
     * 和 {@link #exec} 一样，但**不把非零退出码当失败**：只要 stdout 有内容就返回。
     *
     * <p>专供探测类命令用。{@code cmd <service> -h} 打印完帮助后是以 255 退出的
     * （cmd 的 help 分支返回 -1），按退出码严格判断会把它当失败，
     * 结果什么都读不到，探测永远得出「不支持」的错误结论。
     */
    public static String execLenient(Context ctx, String cmd) {
        return run(ctx, cmd, true);
    }

    /**
     * 只等命令结束、只取退出码，**不读它的输出流**。
     *
     * <p>专供 {@code pm install} / {@code pm uninstall} 这类会派生后台子进程的命令：
     * 子进程会继承 stdout/stderr 的写端，主进程退出后管道依然等不到 EOF，
     * {@link #exec} 里的读循环于是永久阻塞——实测装 267MB 的微信时 UI 一直停在
     * 「正在安装」、日志也停在「开始安装」，就是这个原因。
     *
     * <p>配套用法是把命令的输出先用 {@code > 文件} 重定向掉，再让调用方去读文件。
     *
     * @return 退出码；Shizuku 不可用或执行异常时返回 -1
     */
    public static int execQuiet(Context ctx, String cmd) {
        if (!isReady()) {
            LogStore.add(ctx, "ERR", "Shizuku 未授权，命令未执行：" + cmd);
            return -1;
        }
        try {
            IBinder binder = new ShizukuBinderWrapper(Shizuku.getBinder());
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess proc = service.newProcess(
                    new String[]{"sh", "-c", cmd}, null, null);
            int code = proc.waitFor();
            proc.destroy();
            return code;
        } catch (Exception e) {
            LogStore.add(ctx, "ERR", "$ " + cmd + "  →  " + e.getMessage());
            return -1;
        }
    }

    private static String run(Context ctx, String cmd, boolean lenient) {
        if (!isReady()) {
            LogStore.add(ctx, "ERR", "Shizuku 未授权，命令未执行：" + cmd);
            return null;
        }
        try {
            IBinder binder = new ShizukuBinderWrapper(Shizuku.getBinder());
            IShizukuService service = IShizukuService.Stub.asInterface(binder);
            IRemoteProcess proc = service.newProcess(
                    new String[]{"sh", "-c", cmd}, null, null);
            String out = read(new ParcelFileDescriptor.AutoCloseInputStream(
                    proc.getInputStream()));
            String err = read(new ParcelFileDescriptor.AutoCloseInputStream(
                    proc.getErrorStream()));
            int code = proc.waitFor();
            proc.destroy();
            if (code == 0) {
                LogStore.add(ctx, "OK", "$ " + cmd
                        + (out.trim().isEmpty() ? "" : "  →  " + firstLine(out)));
                return out;
            }
            if (lenient && !out.trim().isEmpty()) {
                LogStore.add(ctx, "OK", "$ " + cmd + "  →  " + firstLine(out)
                        + "（exit " + code + "，按容错模式仍采用）");
                return out;
            }
            LogStore.add(ctx, "ERR", "$ " + cmd + "  →  exit " + code
                    + (err.trim().isEmpty() ? "" : " | " + firstLine(err)));
            return null;
        } catch (Exception e) {
            LogStore.add(ctx, "ERR", "$ " + cmd + "  →  " + e.getMessage());
            return null;
        }
    }

    private static String firstLine(String s) {
        s = s.trim();
        int i = s.indexOf('\n');
        String line = i > 0 ? s.substring(0, i) : s;
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    private static String read(java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
