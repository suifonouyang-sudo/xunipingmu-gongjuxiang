package com.vscreen.toolbox;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * root（su）通道 —— 探测与执行。
 *
 * <p>和 {@link ShizukuCmd} 并列的第二条特权通道：Shizuku 走 uid 2000(shell)，
 * 本类走 uid 0(root)。两者互补：
 * <ul>
 *   <li>有 root 时本类能做的事严格多于 Shizuku —— {@code stop adbd} / {@code start adbd}
 *       只有 root 能做（shell 执行返回 {@code Must be root}，exit 1），
 *       而这正是「真正固定 5555 明文端口」一直缺的那一环。</li>
 *   <li>本类<b>完全不依赖 Shizuku</b>：su 是直接从应用进程 fork 的，
 *       Shizuku 没起来 / 未授权时照样可用。</li>
 * </ul>
 *
 * <p><b>本类不做任何提权。</b>它只负责「系统里已经存在 su 时把它用起来」。
 * 设备没有 root（无 su 二进制）时所有探测都如实返回未 root，不存在绕过行为。
 *
 * <p>两个必须记住的实现坑：
 * <ol>
 *   <li><b>读流与等待必须并发。</b>su 的输出一旦写满管道缓冲（64KB）就会阻塞写端，
 *       进程也就不会退出；串行的「先读完 → 再 waitFor」会双向死等。
 *       所以读取放在独立线程，主线程只轮询退出码。</li>
 *   <li><b>必须带超时。</b>su 存在但未授权时 root 管理器会弹窗等用户点按，
 *       没人应答时 su 会一直挂着。没有超时就会永久卡死调用线程
 *       （首次授权实测给了 25 秒，够用户看清弹窗再点）。</li>
 * </ol>
 */
public final class RootShell {

    private RootShell() {
    }

    /**
     * su 的常见落点，按命中概率排序。
     *
     * <p>{@code /system/bin/su} 是 Magisk 的默认注入点；
     * {@code /debug_ramdisk/su} 是 Android 11+ Magisk 在 ramdisk 阶段的落点；
     * {@code /sbin/su} 是 magiskinit 早期版本的位置。老机型还会落在
     * {@code /system/xbin/su}（SuperSU 时代）。
     */
    private static final String[] SU_PATHS = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/system/sbin/su",
            "/vendor/bin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
    };

    /** 安装包名 → 管理器名。App 声明了 QUERY_ALL_PACKAGES，可直接查。 */
    private static final String[][] MANAGER_PKGS = {
            {"com.topjohnwu.magisk", "Magisk"},
            {"io.github.huskydg.magisk", "Magisk Delta"},
            {"me.weishu.kernelsu", "KernelSU"},
            {"com.rifsxd.ksunext", "KernelSU Next"},
            {"me.bmax.apatch", "APatch"},
            {"eu.chainfire.supersu", "SuperSU"},
            {"com.koushikdutta.superuser", "Superuser"},
            {"com.thirdparty.superuser", "Superuser"},
    };

    /**
     * 拿到 root 后问一下具体是谁在管。都读不到就认不出，属正常
     * （换了包名的隐藏版 Magisk / 定制管理器）。
     */
    private static final String MANAGER_PROBE =
            "magisk -V 2>/dev/null || magisk -v 2>/dev/null; echo '|'; "
                    + "ksud -V 2>/dev/null; echo '|'; "
                    + "/data/adb/apd -V 2>/dev/null; echo '|'; "
                    + "ls -d /data/adb/magisk /data/adb/ksu /data/adb/ap 2>/dev/null";

    /** 首次授权要给用户看清弹窗的时间。 */
    public static final long GRANT_TIMEOUT_MS = 25_000L;
    /** 已授权之后的普通 root 命令。 */
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;

    /** waitFor 的哨兵：不是退出码，而是「等超时了」。 */
    private static final int TIMEOUT = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ 探测结果

    /** 一次完整探测的结果。字段都是只读快照，可以安全地在页面间传递。 */
    public static final class Probe {
        /** 找到 su 二进制。 */
        public boolean suFound;
        /** su 的绝对路径；从 PATH 里找到时记作 {@code "PATH:su"}。 */
        public String suPath;
        /** 识别出的管理器名，可能为 null。 */
        public String manager;
        /** 管理器版本，可能为 null。 */
        public String managerVer;
        /** 真正的判据：{@code su -c id} 返回了 uid=0。 */
        public boolean granted;
        /** 实测的 id 输出，用于展示。 */
        public String idOut;
        /** 失败原因（未 root / 未授权 / 超时 / 异常）。 */
        public String error;
        /** 走 su 通道时拿到的 SELinux 上下文。 */
        public String seLinux;
        /** 探测时间戳。 */
        public long at;

        /** 通道可用性：只有真拿到 uid 0 才算。 */
        public boolean usable() {
            return granted;
        }
    }

    private static volatile Probe cache;

    /** 上一次探测的缓存（可能为 null，表示还没探过）。 */
    public static Probe cached() {
        return cache;
    }

    /** 通道是否可用。未探测过一律按不可用处理，避免误判。 */
    public static boolean hasRoot() {
        Probe p = cache;
        return p != null && p.granted;
    }

    /** 已知的 su 路径（未探测过则现场找一次，不执行）。 */
    public static String suPath() {
        Probe p = cache;
        if (p != null && p.suPath != null) {
            return p.suPath;
        }
        return findSu();
    }

    public static void clearCache() {
        cache = null;
    }

    // ------------------------------------------------------------------ 快速探测（不执行 su）

    /**
     * 只做「看得见」的判定：找 su 二进制 + 认管理器包名。
     * 全程毫秒级、不 fork 任何进程，可以放心在主线程调用。
     *
     * <p>它是给 UI 先画一版用的 —— 真正的授权实测要靠 {@link #probe}，
     * 那个会阻塞（等弹窗），必须放后台线程。
     */
    public static Probe quick(Context ctx) {
        Probe p = new Probe();
        p.at = System.currentTimeMillis();
        p.suPath = findSu();
        p.suFound = p.suPath != null;
        p.manager = detectManagerByPackage(ctx);
        if (!p.suFound) {
            p.error = "未找到 su 二进制";
        } else {
            p.error = "尚未实测授权";
        }
        return p;
    }

    /** 在已知落点里找 su；都找不到再退回 PATH 查找。 */
    private static String findSu() {
        for (String path : SU_PATHS) {
            try {
                if (new File(path).exists()) {
                    return path;
                }
            } catch (Exception ignored) {
            }
        }
        return findSuInPath();
    }

    /**
     * 从应用进程的 PATH 里找 su。
     *
     * <p>不能用 {@code which}：应用进程的 PATH 通常含 /system/bin，
     * 但 which 自身在部分精简 ROM 上不存在。直接逐个目录探更可靠。
     */
    private static String findSuInPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            pathEnv = "/sbin:/system/sbin:/system/bin:/system/xbin";
        }
        for (String dir : pathEnv.split(":")) {
            if (dir.trim().isEmpty()) {
                continue;
            }
            try {
                File f = new File(dir.trim(), "su");
                if (f.exists() && f.canExecute()) {
                    return "PATH:" + f.getAbsolutePath();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String detectManagerByPackage(Context ctx) {
        if (ctx == null) {
            return null;
        }
        PackageManager pm = ctx.getPackageManager();
        for (String[] m : MANAGER_PKGS) {
            try {
                pm.getPackageInfo(m[0], 0);
                return m[1];
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 完整探测（会执行 su）

    /**
     * 完整探测：找 su → 实测 {@code su -c id} → 认管理器。
     *
     * <p><b>必须在后台线程调用</b>：未授权时这里会等管理器弹窗，最长
     * {@link #GRANT_TIMEOUT_MS}。结果会写进缓存供 {@link #hasRoot()} 使用。
     */
    public static Probe probe(Context ctx) {
        Probe p = quick(ctx);
        if (!p.suFound) {
            p.granted = false;
            cache = p;
            LogStore.add(ctx, "INFO", "root 探测：未找到 su 二进制，root 通道不可用"
                    + "（设备未 root，属正常）");
            return p;
        }

        Result r = exec(ctx, "id", GRANT_TIMEOUT_MS);
        if (r.ok && r.out.contains("uid=0")) {
            p.granted = true;
            p.idOut = r.out.trim();
            p.seLinux = extract(r.out, "context=([^\\s]+)");
            // 拿到 root 后再问具体是谁在管（/data/adb 只有 root 读得到）
            refineManager(ctx, p);
            LogStore.add(ctx, "OK", "root 通道已就绪："
                    + (p.manager == null ? "已授权" : p.manager
                    + (p.managerVer == null ? "" : " " + p.managerVer))
                    + " · " + firstLine(r.out));
        } else {
            p.granted = false;
            p.idOut = r.ok ? r.out.trim() : null;
            if (r.timeout) {
                p.error = "授权超时：" + (r.hint == null ? "" : r.hint);
                LogStore.add(ctx, "WARN", "root 探测：su 存在（" + p.suPath
                        + "）但等待授权超时，root 管理器可能弹了授权框未被点按");
            } else if (r.ok) {
                p.error = "su 已执行但未拿到 root（返回：" + firstLine(r.out) + "）";
                LogStore.add(ctx, "WARN", "root 探测：su 执行成功但没有 uid=0，"
                        + "授权可能被拒绝");
            } else {
                p.error = r.error == null ? "su 执行失败" : r.error;
                LogStore.add(ctx, "WARN", "root 探测：su 执行失败 - " + p.error);
            }
        }
        cache = p;
        return p;
    }

    /** 用 root 问一下 /data/adb 下是谁的地盘，把管理器认出来。 */
    private static void refineManager(Context ctx, Probe p) {
        Result r = exec(ctx, MANAGER_PROBE, DEFAULT_TIMEOUT_MS);
        if (!r.ok) {
            return;
        }
        String[] part = r.out.split("\\|", -1);
        String magisk = part.length > 0 ? part[0].trim() : "";
        String ksu = part.length > 1 ? part[1].trim() : "";
        String apd = part.length > 2 ? part[2].trim() : "";
        String dirs = part.length > 3 ? part[3].trim() : "";
        if (!magisk.isEmpty()) {
            p.manager = "Magisk";
            p.managerVer = magisk;
        } else if (!ksu.isEmpty()) {
            p.manager = "KernelSU";
            p.managerVer = ksu;
        } else if (!apd.isEmpty()) {
            p.manager = "APatch";
            p.managerVer = apd;
        } else if (dirs.contains("/data/adb/magisk")) {
            p.manager = p.manager == null ? "Magisk" : p.manager;
        } else if (dirs.contains("/data/adb/ksu")) {
            p.manager = p.manager == null ? "KernelSU" : p.manager;
        } else if (dirs.contains("/data/adb/ap")) {
            p.manager = p.manager == null ? "APatch" : p.manager;
        }
    }

    // ------------------------------------------------------------------ 执行

    /** root 命令的执行结果。 */
    public static final class Result {
        /** 进程正常跑完（不代表拿到了 root）。 */
        public boolean ok;
        /** 退出码。 */
        public int code;
        /** 合并后的 stdout + stderr。 */
        public String out = "";
        /** 失败说明。 */
        public String error;
        /** 是否因为超时被强杀。 */
        public boolean timeout;
        /** 超时的补充提示。 */
        public String hint;

        /** 是否确实以 uid 0 执行。 */
        public boolean rooted() {
            return ok && out.contains("uid=0");
        }
    }

    /**
     * 以 root 执行一条命令（{@code su -c "cmd"}）。
     *
     * <p>读流与等待并发进行，并且带超时；超时会 {@code destroyForcibly} 掉 su，
     * 避免管理器弹窗无人应答时永久挂起。
     */
    public static Result exec(Context ctx, String cmd, long timeoutMs) {
        Result res = new Result();
        String su = suPath();
        if (su == null) {
            res.error = "未找到 su 二进制（设备未 root）";
            return res;
        }
        String exe = su.startsWith("PATH:") ? su.substring(5) : su;

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe, "-c", cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            Pump pump = new Pump(proc.getInputStream());
            Thread reader = new Thread(pump, "root-out");
            reader.setDaemon(true);
            reader.start();

            int code = waitFor(proc, timeoutMs);
            if (code == TIMEOUT) {
                proc.destroyForcibly();
                reader.join(500);
                res.timeout = true;
                res.error = "等待 " + (timeoutMs / 1000) + " 秒无响应";
                res.hint = "若 root 管理器弹出了授权框，请点[允许]后重试";
                return res;
            }
            reader.join(1500);
            res.out = pump.text();
            res.code = code;
            res.ok = true;
            return res;
        } catch (Exception e) {
            res.error = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return res;
        } finally {
            if (proc != null) {
                proc.destroy();
            }
        }
    }

    /** 用默认超时执行。 */
    public static Result exec(Context ctx, String cmd) {
        return exec(ctx, cmd, DEFAULT_TIMEOUT_MS);
    }

    // ------------------------------------------------------------------ 自动选通道

    /** 上一次 {@link #execAuto} 走的通道，供 UI 显示用。 */
    private static volatile String lastChannel = "未执行";

    public static String lastChannel() {
        return lastChannel;
    }

    /**
     * 自动选特权通道执行：**有 root 走 root，没有回落 Shizuku**。
     *
     * <p>有 root 时优先 root 的原因不只是权限更大 —— root 通道不依赖 Shizuku 服务，
     * 少一层 binder，也不会因为 Shizuku 未授权而整条命令不可用。
     *
     * @return 命令输出；两条通道都不可用时返回 null
     */
    public static String execAuto(Context ctx, String cmd) {
        if (hasRoot()) {
            Result r = exec(ctx, cmd);
            if (r.ok) {
                lastChannel = "root";
                LogStore.add(ctx, "OK", "$ su -c " + cmd
                        + (r.out.trim().isEmpty() ? "" : "  →  " + firstLine(r.out)));
                return r.out;
            }
            lastChannel = "shizuku";
            LogStore.add(ctx, "WARN", "root 通道执行失败（" + r.error + "），回落到 Shizuku");
        } else {
            lastChannel = "shizuku";
        }
        return ShizukuCmd.exec(ctx, cmd);
    }

    /**
     * 只走 root 通道执行；没有 root 时返回 null 并记一条日志。
     * 用于「这件事 shell 做不到，必须 root」的场景（如 adbd 重启）。
     */
    public static Result execRootOnly(Context ctx, String cmd) {
        if (!hasRoot()) {
            LogStore.add(ctx, "WARN", "该操作需要 root，当前设备未 root：" + cmd);
            return null;
        }
        Result r = exec(ctx, cmd);
        if (r.ok) {
            LogStore.add(ctx, "OK", "$ su(root) " + cmd
                    + (r.out.trim().isEmpty() ? "" : "  →  " + firstLine(r.out)));
        } else {
            LogStore.add(ctx, "ERR", "$ su(root) " + cmd + "  →  " + r.error);
        }
        return r;
    }

    // ------------------------------------------------------------------ 内部

    private static int waitFor(Process proc, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                return proc.exitValue();
            } catch (IllegalThreadStateException e) {
                Thread.sleep(40);
            }
        }
        return TIMEOUT;
    }

    /** 后台读流，避免管道写满导致子进程卡死。 */
    private static final class Pump implements Runnable {
        private final InputStream in;
        private final StringBuilder sb = new StringBuilder();

        Pump(InputStream in) {
            this.in = in;
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
            } catch (Exception ignored) {
            }
        }

        synchronized String text() {
            return sb.toString();
        }
    }

    private static String extract(String s, String regex) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int i = s.indexOf('\n');
        String line = i > 0 ? s.substring(0, i) : s;
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }

    /** 把 su 路径列表暴露给「授权检查」页做逐项展示。 */
    public static List<String> knownPaths() {
        return new ArrayList<>(java.util.Arrays.asList(SU_PATHS));
    }
}
