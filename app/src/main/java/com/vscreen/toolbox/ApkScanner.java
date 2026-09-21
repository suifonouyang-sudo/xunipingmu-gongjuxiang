package com.vscreen.toolbox;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 安装包扫描 / 解析 / 安装 / 卸载，全部以 shell(uid 2000) 身份执行。
 *
 * <p><b>为什么要借 shell 读</b>：本应用 targetSdk 35，安卓 11 起 scoped storage 生效，
 * 应用自己用 File API 既进不去 /sdcard 也进不去 /data/local/tmp。
 * 而 shell 两者都能读，所以扫描与安装都必须走 Shizuku。
 *
 * <p><b>元数据怎么读</b>：优先调用设备上现成的 {@code aapt dump badging}（adb 用户常把它
 * 放在 /data/local/tmp）。没有 aapt 时退化为只显示文件名 / 大小 / 时间，不影响安装。
 *
 * <p>整批扫描+解析合并成**一次** shell 调用（一个 while 循环里完成），
 * 避免每个文件一次 binder 往返。
 */
public final class ApkScanner {

    private ApkScanner() {
    }

    /** 一个安装包条目。解析不到的字段留空 / -1，UI 只展示拿到的部分。 */
    public static class Apk {
        public String path = "";
        public long size;
        public long mtime;          // 秒
        public String pkg = "";
        public String label = "";
        public String zhLabel = "";
        public String versionName = "";
        public int versionCode = -1;
        public int targetSdk = -1;
        public int minSdk = -1;
        /** 本机是否已安装同名包，以及本机版本号（用于判断是「安装」还是「降级」）。 */
        public boolean installed;
        public int installedCode = -1;

        public String fileName() {
            int i = path.lastIndexOf('/');
            return i >= 0 ? path.substring(i + 1) : path;
        }

        /** 优先中文名，其次 aapt 的默认名，最后退回文件名。 */
        public String title() {
            if (!zhLabel.isEmpty()) {
                return zhLabel;
            }
            if (!label.isEmpty()) {
                return label;
            }
            String n = fileName();
            return n.toLowerCase().endsWith(".apk") ? n.substring(0, n.length() - 4) : n;
        }

        /** 相对本机已装版本是升级、降级还是同版本。 */
        public String compareText() {
            if (!installed) {
                return "未安装";
            }
            if (versionCode < 0 || installedCode < 0) {
                return "已安装";
            }
            if (versionCode > installedCode) {
                return "可升级";
            }
            if (versionCode < installedCode) {
                return "**降级**（需 -d）";
            }
            return "同版本";
        }

        public String sizeText() {
            return humanSize(size);
        }
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 扫描安装包。dirs 是**已拼好的 shell 目录列表**（空格分隔），调用方保证是白名单路径，
     * 不要直接把用户输入原样拼进命令。
     *
     * @return 解析结果；Shizuku 不可用时返回 null（调用方负责区分「空列表」和「失败」）。
     */
    public static List<Apk> scan(Context ctx, String dirs) {
        String out = ShizukuCmd.execLenient(ctx, buildScanCmd(dirs));
        if (out == null) {
            return null;
        }
        List<Apk> list = parse(out);
        markInstalled(ctx, list);
        // 最新的排前面：刚拷进来的安装包一眼就能看到
        list.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        return list;
    }

    /**
     * 扫描命令。输出结构（每行一个标记，靠行首前缀区分，避免引号嵌套）：
     * <pre>
     * @@F:/path/to/x.apk      ← 新条目开始
     * 12345 1699999999        ← stat 的 大小 修改时间（可能缺失）
     * package: name=...       ← aapt 的 badging 行（可能缺失）
     * </pre>
     */
    private static String buildScanCmd(String dirs) {
        return "A=''; "
                + "for c in /data/local/tmp/aapt /system/bin/aapt /system/xbin/aapt; do "
                + "[ -x \"$c\" ] && A=\"$c\" && break; done; "
                + "for d in " + dirs + "; do find \"$d\" -iname '*.apk' 2>/dev/null; done "
                + "| while IFS= read -r f; do "
                + "echo \"@@F:$f\"; "
                + "stat -c '%s %Y' \"$f\" 2>/dev/null; "
                + "if [ -n \"$A\" ]; then "
                + "o=$($A dump badging \"$f\" 2>/dev/null); "
                + "echo \"$o\" | grep -E '^(package:|sdkVersion:|targetSdkVersion:|application-label:)'; "
                + "echo \"$o\" | grep -E '^application-label-zh' | head -2; "
                + "fi; "
                + "done; echo @@END";
    }

    private static List<Apk> parse(String out) {
        List<Apk> list = new ArrayList<>();
        Apk cur = null;
        boolean wantStat = false;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("@@F:")) {
                cur = new Apk();
                cur.path = line.substring(4).trim();
                if (cur.path.isEmpty()) {
                    cur = null;
                    continue;
                }
                list.add(cur);
                wantStat = true;
                continue;
            }
            if (cur == null || line.isEmpty()) {
                continue;
            }
            if (wantStat) {
                wantStat = false;
                String[] p = line.split("\\s+");
                if (p.length >= 2) {
                    try {
                        cur.size = Long.parseLong(p[0]);
                        cur.mtime = Long.parseLong(p[1]);
                        continue;
                    } catch (NumberFormatException ignored) {
                        // 不是 stat 行（stat 失败 / aapt 先输出），落到下面的解析
                    }
                }
            }
            if (line.startsWith("package: name='")) {
                cur.pkg = between(line, "name='", "'");
                cur.versionName = between(line, "versionName='", "'");
                cur.versionCode = (int) after(line, "versionCode='");
            } else if (line.startsWith("sdkVersion:")) {
                cur.minSdk = (int) after(line, "sdkVersion:'");
            } else if (line.startsWith("targetSdkVersion:")) {
                cur.targetSdk = (int) after(line, "targetSdkVersion:'");
            } else if (line.startsWith("application-label:")) {
                cur.label = between(line, "application-label:'", "'");
            } else if (line.startsWith("application-label-zh")) {
                // aapt 会列出所有中文变体（zh / zh-CN / zh-Hans…），取第一个非空的
                if (cur.zhLabel.isEmpty()) {
                    cur.zhLabel = between(line, ":'", "'");
                }
            }
        }
        return list;
    }

    /** 用本机 PackageManager 标注「已安装 / 未安装」，不需要 shell。 */
    private static void markInstalled(Context ctx, List<Apk> list) {
        PackageManager pm = ctx.getPackageManager();
        for (Apk a : list) {
            a.installed = false;
            a.installedCode = -1;
            if (a.pkg.isEmpty()) {
                continue;
            }
            try {
                PackageInfo pi = pm.getPackageInfo(a.pkg, 0);
                a.installed = true;
                a.installedCode = pi.versionCode;
            } catch (Exception ignored) {
                // 未安装
            }
        }
    }

    // ------------------------------------------------------------------ 安装

    /** 一次安装的结果。 */
    public static class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }

        /**
         * 抽一句人能看懂的原因。
         *
         * <p>正常失败是 {@code Failure [INSTALL_FAILED_...]}；参数阶段的失败则是
         * {@code IllegalArgumentException: Error: Failed to parse APK file: /path: ...} ——
         * 后者后半句往往把同一路径又念一遍，切掉只留前半句。
         */
        public String reason() {
            String fallback = null;
            for (String line : output.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.startsWith("Failure")) {
                    return s;
                }
                if (fallback == null) {
                    fallback = s;
                }
                int i = s.indexOf("Error: ");
                if (i >= 0) {
                    String r = s.substring(i + 7).trim();
                    int cut = r.indexOf(": /");
                    if (cut > 8) {
                        r = r.substring(0, cut);
                    }
                    return r;
                }
            }
            return fallback == null ? "无输出" : fallback;
        }
    }

    /**
     * 安装 APK。
     *
     * <p>实测（uid 2000）：{@code pm install} 可用，成功输出 {@code Success} 且 exit 0；
     * 失败要么输出 {@code Failure [INSTALL_FAILED_...]}，要么直接抛 IllegalArgumentException
     * 到 stderr 并 exit 255 —— 两种情况都按失败处理。
     *
     * <p><b>不在 /data/local/tmp 下的包必须先中转过去。</b>安装由 system_server 读文件完成，
     * 而 SELinux 不允许它读 FUSE 挂载点（实测 {@code avc: denied … tcontext=u:object_r:fuse:s0}，
     * pm 也随之报 {@code Error: Can't open file} 并建议
     * {@code Consider using a file under /data/local/tmp/}）。也就是说
     * <b>直接装 /sdcard 上的 APK 必然失败</b>，可本页默认扫描目录就包含 /sdcard，
     * 不中转的话用户点「安装」会莫名其妙失败。
     *
     * @param allowDowngrade 加 {@code -d}，允许版本号更低时覆盖安装
     * @param grantAll       加 {@code -g}，安装后直接授予全部运行时权限
     */
    public static Result install(Context ctx, String path, boolean allowDowngrade, boolean grantAll) {
        StringBuilder pm = new StringBuilder("pm install -r");
        if (allowDowngrade) {
            pm.append(" -d");
        }
        if (grantAll) {
            pm.append(" -g");
        }
        String target = path;
        String pre = "";
        String post = "";
        if (path != null && !path.startsWith(STAGE_DIR + "/")) {
            String tmp = STAGE_DIR + "/.vsbox_inst_" + System.currentTimeMillis();
            String name = safeName(path);
            target = tmp + "/" + name;
            // 退出码用 exit 传出去，runPm 的 @@CODE=$? 才能拿到 pm 的真实结果
            pre = "T='" + tmp + "'; mkdir -p \"$T\" || exit 90; "
                    + "cp " + quote(path) + " '" + target + "' "
                    + "|| { echo '中转失败：无法复制到 /data/local/tmp（存储空间不足或源文件不可读）'; "
                    + "rm -rf \"$T\"; exit 91; }; ";
            post = "; RC=$?; rm -rf \"$T\"; exit $RC";
        }
        pm.append(' ').append(quote(target));
        return runPm(ctx, pre + pm + post);
    }

    /**
     * pm 唯一能读的落点。SELinux 允许 system_server 读这里，不允许读 /sdcard。
     * 见 {@link #install} 的说明。
     */
    private static final String STAGE_DIR = "/data/local/tmp";

    /** 中转时用的文件名：剥掉目录，非安全字符替换成下划线，避免破坏 shell 引号。 */
    private static String safeName(String path) {
        String n = path == null ? "" : new File(path).getName();
        n = n.replaceAll("[^A-Za-z0-9_.-]", "_");
        return n.isEmpty() ? "target.apk" : n;
    }

    /** 卸载指定包名。 */
    public static Result uninstall(Context ctx, String pkg) {
        return runPm(ctx, "pm uninstall " + quote(pkg));
    }

    /**
     * 跑一条 pm 命令并取回它的输出。
     *
     * <p><b>为什么输出必须先落到文件</b>：pm 会派生持有 stdout/stderr 写端的子进程，
     * 命令结束后管道也等不到 EOF。此时若用 {@link ShizukuCmd#exec} 读流会永久阻塞 ——
     * 实测装 267MB 的微信时安装早就成功了（lastUpdateTime 都变了），
     * 而 UI 一直停在「正在安装」、日志停在「开始安装」，就是卡在这里。
     * 改成 shell 侧重定向到文件、应用侧读文件后，shell 自身的流不再被继承，
     * {@link ShizukuCmd#execQuiet} 一次 waitFor 就干净返回。
     *
     * <p>文件放在应用的外部私有目录：shell 有写权限（实测 shell 能往
     * /sdcard/Android/data/&lt;包名&gt;/files/ 写），应用读它又不需要任何权限。
     */
    private static Result runPm(Context ctx, String pmCmd) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        // 每次换文件名：既避开上一轮残留，也避开「shell 建的文件属主不是本应用」的写冲突
        File out = new File(dir, "pm_result_" + System.currentTimeMillis() + ".txt");
        String cmd = "{ " + pmCmd + " ; echo \"@@CODE=$?\"; } > "
                + quote(out.getAbsolutePath()) + " 2>&1";
        int exit = ShizukuCmd.execQuiet(ctx, cmd);
        if (exit < 0) {
            return new Result(false, "Shizuku 未授权或命令通道不可用");
        }
        String text = readFile(out);
        //noinspection ResultOfMethodCallIgnored
        out.delete();

        int code = exit;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith("@@CODE=")) {
                try {
                    code = Integer.parseInt(s.substring(7).trim());
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            body.append(line).append('\n');
        }
        String result = body.toString().trim();
        // 退出码为 0 且打出 Success 才算真的装上：有些 ROM 失败也给 0
        boolean ok = code == 0 && result.contains("Success");
        return new Result(ok, result);
    }

    private static String readFile(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
            // 文件不存在或读不到：当作空输出，交给退出码判定
        }
        return sb.toString().trim();
    }

    /** 包名 / 路径进 shell 前统一加单引号，内部单引号按 POSIX 方式转义。 */
    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ 小工具

    /** 抽出 tag 与其后 first…second 之间的内容。 */
    private static String between(String s, String from, String to) {
        int i = s.indexOf(from);
        if (i < 0) {
            return "";
        }
        int b = i + from.length();
        int e = s.indexOf(to, b);
        return e < 0 ? s.substring(b) : s.substring(b, e);
    }

    /** 抽出 tag 后面的数字。 */
    private static long after(String s, String tag) {
        int i = s.indexOf(tag);
        if (i < 0) {
            return -1;
        }
        int b = i + tag.length();
        int e = b;
        while (e < s.length() && Character.isDigit(s.charAt(e))) {
            e++;
        }
        try {
            return Long.parseLong(s.substring(b, e));
        } catch (Exception e2) {
            return -1;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double k = bytes / 1024.0;
        if (k < 1024) {
            return String.format(java.util.Locale.US, "%.0f KB", k);
        }
        double m = k / 1024.0;
        if (m < 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", m);
        }
        return String.format(java.util.Locale.US, "%.2f GB", m / 1024.0);
    }

    /** 秒级时间戳 → "09-02 10:47"。 */
    public static String timeText(long sec) {
        if (sec <= 0) {
            return "—";
        }
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(sec * 1000L));
    }
}
