package com.vscreen.toolbox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用备份：有 root 备份「安装包 + 数据」，没有 root 时经 Shizuku(shell/uid 2000) 只备份安装包。
 *
 * <p>两条通道的能力边界是<b>被系统权限客观划定的</b>，不是功能取舍：
 * <ul>
 *   <li><b>安装包</b>：{@code /data/app/…/base.apk} 权限是 {@code -rw-r--r--}，实测 uid 2000
 *       能完整读出（APK 的 PK 文件头读得到），所以<b>无需 root 即可备份</b>。</li>
 *   <li><b>应用数据</b>：{@code /data/data/<pkg>} 权限是 {@code drwx------} 且属主是应用自身 uid，
 *       实测 shell 连 {@code ls} 都返回 Permission denied。要读它必须 uid 0，即 root。</li>
 * </ul>
 *
 * <p>三个实测出来的实现要点：
 * <ol>
 *   <li><b>这台 Android 11 没有 {@code pm install-multiple}</b>（返回 Unknown command，厂商裁剪掉了），
 *       所以带 split 分片的应用（{@code pm path} 返回多行）只能用 session 方式恢复：
 *       {@code pm install-create} → {@code install-write} → {@code install-commit}。
 *       单分片则直接用 {@code pm install -r}，路径更短也更稳。</li>
 *   <li><b>{@code tar} 是 toybox 0.8.3</b>，支持 {@code -z}(gzip) 与 {@code --exclude}，
 *       但 exclude 匹配的是文件名而非路径，打包时必须 {@code -C} 到父目录再给相对名。</li>
 *   <li><b>恢复数据必须在装完包之后</b>：{@code /data/data/<pkg>} 由安装过程创建并分配 uid，
 *       先解包再安装会被系统清掉；解包后文件属主变成 root，必须按 {@code stat -c %u} 取到的
 *       uid 再 {@code chown -R} 回去，否则应用打不开自己的数据。</li>
 * </ol>
 */
public final class AppBackup {

    private AppBackup() {
    }

    /** 默认备份根目录。放在内部存储，用户能直接用文件管理器取走。 */
    public static final String DEFAULT_DIR = "/sdcard/AppBackup";

    /**
     * 恢复时的中转目录。
     *
     * <p>备份必须落在用户拿得到的地方（/sdcard），但安装是由 system_server 读文件完成的，
     * 而 SELinux 不允许它读 FUSE 挂载点（实测 {@code avc: denied … tcontext=u:object_r:fuse:s0}，
     * pm 也会提示 {@code Consider using a file under /data/local/tmp/}）。
     * 所以恢复时先把 apk 搬到这里再装，装完删掉。
     */
    private static final String STAGE_DIR = "/data/local/tmp";

    /** 备份单个应用的最外层超时。大应用（微信数据几个 G）的 tar 可能跑很久。 */
    public static final long BACKUP_TIMEOUT_MS = 10 * 60 * 1000L;

    // ------------------------------------------------------------------ 数据模型

    /** 列表里的一项：一个已安装应用。 */
    public static final class AppInfo {
        public String pkg = "";
        public String label = "";
        public String versionName = "";
        public long versionCode = -1;
        /** FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP 视为系统应用。 */
        public boolean system;
        /** {@code pm path} 拿到的分片数，>1 说明有 split。 */
        public int splitCount;
        /** 勾选状态由 Fragment 维护，这里只是顺手放着的 UI 状态。 */
        public boolean checked;

        /** 列表副标题：包名 + 版本。 */
        public String sub() {
            return pkg + (versionCode >= 0
                    ? " · v" + versionName + " (" + versionCode + ")" : "");
        }
    }

    /** 一条已完成的备份记录。 */
    public static final class Backup {
        /** 备份目录的完整路径。 */
        public String dir = "";
        public String pkg = "";
        public String label = "";
        public String versionName = "";
        public long versionCode = -1;
        /** 备份完成时间（毫秒）。 */
        public long time;
        /** 是否含应用数据。 */
        public boolean withData;
        /** 备份体积（字节）。 */
        public long size;
        /** 使用的通道：root / adb。 */
        public String channel = "";
        /** 安装包分片数。 */
        public int fileCount;

        public String timeText() {
            if (time <= 0) {
                return "时间未知";
            }
            return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(time));
        }

        public String sizeText() {
            return humanSize(size);
        }

        /** 列表标题：优先应用名，没有就退回包名。 */
        public String title() {
            return label.isEmpty() ? pkg : label;
        }
    }

    /** 一次操作的返回值。 */
    public static final class Result {
        public boolean ok;
        /** 一句话结论，直接给 UI 展示。 */
        public String msg = "";
        /** 命令原始输出，失败时才需要展开看。 */
        public String detail = "";

        static Result ok(String msg, String detail) {
            Result r = new Result();
            r.ok = true;
            r.msg = msg;
            r.detail = detail == null ? "" : detail;
            return r;
        }

        static Result fail(String msg, String detail) {
            Result r = new Result();
            r.ok = false;
            r.msg = msg;
            r.detail = detail == null ? "" : detail;
            return r;
        }
    }

    // ------------------------------------------------------------------ 列出已安装应用

    /**
     * 读已安装应用列表。
     *
     * <p>这里刻意<b>不走 shell</b>：PackageManager 就能拿到 label / 版本 / 是否系统应用，
     * 比 {@code pm list packages} + 逐条 {@code dumpsys} 快几个数量级，也不依赖授权。
     */
    public static List<AppInfo> listApps(Context ctx, boolean userOnly) {
        List<AppInfo> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> installed;
        try {
            installed = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return out;
        }
        for (PackageInfo pi : installed) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || pi.packageName == null) {
                continue;
            }
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (userOnly && sys) {
                continue;
            }
            AppInfo a = new AppInfo();
            a.pkg = pi.packageName;
            a.label = pm.getApplicationLabel(ai).toString();
            a.versionName = pi.versionName == null ? "" : pi.versionName;
            a.versionCode = pi.versionCode;
            a.system = sys;
            a.splitCount = pi.splitNames == null ? 0 : pi.splitNames.length;
            out.add(a);
        }
        out.sort(Comparator.comparing(x -> x.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ 执行备份

    /**
     * 备份一个应用。
     *
     * @param withData 是否连带打包 {@code /data/data/<pkg>}；无 root 时会被自动降级为 false
     * @param useRoot  本次走哪条通道
     */
    public static Result backup(Context ctx, AppInfo app, String rootDir,
                                boolean withData, boolean useRoot) {
        if (ctx == null || app == null || app.pkg.isEmpty()) {
            return Result.fail("参数不完整", "");
        }
        final String dir = rootDir == null || rootDir.trim().isEmpty()
                ? DEFAULT_DIR : rootDir.trim();
        final String safePkg = safeToken(app.pkg);
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        final String target = dir + "/" + safePkg + "__" + app.versionCode + "__" + stamp;

        boolean doData = withData && useRoot;

        StringBuilder cmd = new StringBuilder();
        // 1) 建目录并复制全部分片（pm path 有几行就复制几个）
        cmd.append("D='").append(target).append("'; ")
                .append("rm -rf \"$D\"; mkdir -p \"$D\" || exit 1; ")
                .append("N=0; ")
                .append("for p in $(pm path ").append(safePkg)
                .append(" 2>/dev/null | sed 's/package://'); do ")
                .append("cp \"$p\" \"$D/\" 2>&1 || { echo \"COPY_FAIL:$p\"; exit 2; }; ")
                .append("N=$((N+1)); done; ")
                .append("[ \"$N\" -gt 0 ] || { echo 'NO_APK'; exit 3; }; ");
        // 2) root 通道追加数据打包。exclude 只匹配文件名，所以 -C 到 /data/data 再给包名
        if (doData) {
            cmd.append("tar -czf \"$D/data.tar.gz\" -C /data/data ")
                    .append("--exclude=cache --exclude=code_cache ")
                    .append(safePkg)
                    .append(" 2>&1 || { echo 'TAR_FAIL'; exit 4; }; ");
        }
        // 3) 写元信息，供「备份记录」页离线解析
        cmd.append("printf '%s\\n' ")
                .append("'pkg=").append(safePkg).append("' ")
                .append("'label=").append(safeText(app.label)).append("' ")
                .append("'versionName=").append(safeText(app.versionName)).append("' ")
                .append("'versionCode=").append(app.versionCode).append("' ")
                .append("'time=").append(System.currentTimeMillis()).append("' ")
                .append("'withData=").append(doData ? 1 : 0).append("' ")
                .append("'channel=").append(useRoot ? "root" : "adb").append("' ")
                .append("> \"$D/info.txt\"; ")
                // cp 出来的文件属主是执行 shell 的 uid、默认 600。
                // 注意：/sdcard 是 FUSE，chmod 在这里是「返回成功但不生效」的 no-op（实测 644 改不动，
                // 仍然是 600）；只有落到 ext4/sdcardfs 的目的地（外置卡某些挂载）才可能生效。
                // 保留这条是因为它对另一些目的地有用，且对 FUSE 无害。
                // 代价是手机上第三方文件管理器可能只读得到目录名读不到内容，
                // 但 adb pull 走 adbd（有 DAC 豁免）导出不受影响。
                .append("chmod 644 \"$D\"/* 2>/dev/null; ")
                .append("echo \"APKS=$N\"; ")
                .append("du -sk \"$D\" 2>/dev/null | cut -f1");

        Cmd r = run(ctx, cmd.toString(), useRoot, BACKUP_TIMEOUT_MS);
        if (!r.ok) {
            return Result.fail("备份失败：" + r.out.trim(), r.out);
        }
        String out = r.out;
        if (out.contains("NO_APK")) {
            return Result.fail("pm path 没返回任何安装包路径（应用可能已被卸载）", out);
        }
        if (out.contains("COPY_FAIL")) {
            return Result.fail("复制安装包失败（存储已满或路径不可读）", out);
        }
        if (out.contains("TAR_FAIL")) {
            return Result.fail("打包应用数据失败", out);
        }
        String detail = doData ? "安装包 + 数据" : "仅安装包";
        LogStore.add(ctx, "OK", "[备份] " + app.label + " → " + target
                + "（" + detail + "，" + (useRoot ? "root" : "adb") + "）");
        return Result.ok("已备份 " + app.label + "（" + detail + "）", out);
    }

    // ------------------------------------------------------------------ 列出备份记录

    /**
     * 枚举备份根目录里已有的备份。
     *
     * <p>必须经 shell：应用自身受 scoped storage 限制，读不到 {@code /sdcard/AppBackup}。
     * 只认带 {@code info.txt} 的目录，用户自己丢进去的文件夹不会被误当成备份。
     */
    public static List<Backup> listBackups(Context ctx, String rootDir, boolean useRoot) {
        List<Backup> out = new ArrayList<>();
        final String dir = rootDir == null || rootDir.trim().isEmpty()
                ? DEFAULT_DIR : rootDir.trim();
        String cmd = "for d in \"" + dir + "\"/*; do "
                + "f=\"$d/info.txt\"; "
                + "[ -f \"$f\" ] || continue; "
                + "echo '@@B'; echo \"dir=$d\"; cat \"$f\"; "
                + "echo \"size=$(du -sk \"$d\" 2>/dev/null | cut -f1)\"; "
                + "echo \"files=$(ls \"$d\"/*.apk 2>/dev/null | wc -l)\"; "
                + "done; true";
        Cmd r = run(ctx, cmd, useRoot, 60_000L);
        if (!r.ok || r.out.trim().isEmpty()) {
            return out;
        }
        Backup cur = null;
        for (String line : r.out.split("\n")) {
            String s = line.trim();
            if (s.equals("@@B")) {
                if (cur != null && !cur.pkg.isEmpty()) {
                    out.add(cur);
                }
                cur = new Backup();
                continue;
            }
            if (cur == null) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = s.substring(0, eq);
            String v = s.substring(eq + 1);
            try {
                switch (k) {
                    case "dir":
                        cur.dir = v;
                        break;
                    case "pkg":
                        cur.pkg = v;
                        break;
                    case "label":
                        cur.label = v;
                        break;
                    case "versionName":
                        cur.versionName = v;
                        break;
                    case "versionCode":
                        cur.versionCode = Long.parseLong(v);
                        break;
                    case "time":
                        cur.time = Long.parseLong(v);
                        break;
                    case "withData":
                        cur.withData = "1".equals(v);
                        break;
                    case "channel":
                        cur.channel = v;
                        break;
                    case "size":
                        // du 报的是 KB
                        cur.size = Long.parseLong(v) * 1024L;
                        break;
                    case "files":
                        cur.fileCount = Integer.parseInt(v);
                        break;
                    default:
                        break;
                }
            } catch (Exception ignored) {
                // 某一行坏了不影响其余记录
            }
        }
        if (cur != null && !cur.pkg.isEmpty()) {
            out.add(cur);
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    // ------------------------------------------------------------------ 恢复

    /**
     * 从一条备份恢复。
     *
     * <p>顺序不能变：先停应用 → 装包（让系统建好 {@code /data/data/<pkg>} 并分配 uid）
     * → 解数据 → 还属主。反过来解包会被安装过程清掉。
     */
    public static Result restore(Context ctx, Backup b, boolean useRoot) {
        if (ctx == null || b == null || b.dir.isEmpty()) {
            return Result.fail("参数不完整", "");
        }
        final String d = b.dir;
        final String safePkg = safeToken(b.pkg);

        StringBuilder cmd = new StringBuilder();
        cmd.append("D='").append(d).append("'; ")
                .append("[ -d \"$D\" ] || { echo 'NO_DIR'; exit 1; }; ")
                .append("am force-stop ").append(safePkg).append(" 2>/dev/null; ")
                // 中转：system_server 读不了 /sdcard（SELinux u:object_r:fuse:s0），
                // 备份又必须落在用户拿得到的地方，所以恢复时先搬到 /data/local/tmp
                .append("T=").append(STAGE_DIR).append("/.vsbox_rst_")
                .append(System.currentTimeMillis()).append("; ")
                .append("mkdir -p \"$T\" || exit 90; ")
                .append("cp \"$D\"/*.apk \"$T\"/ 2>/dev/null ")
                .append("|| { echo '中转失败：无法复制到 /data/local/tmp（存储空间不足？）'; ")
                .append("rm -rf \"$T\"; exit 91; }; ");

        // 只装 base.apk 用 pm install；有 split 只能走 session（本机没有 pm install-multiple）
        // 注意：不能写成 `pm install ... | tail -3; RC=$?`，那样取到的是 tail 的退出码
        cmd.append("CNT=$(ls \"$T\"/*.apk 2>/dev/null | wc -l); ")
                .append("if [ \"$CNT\" -le 1 ]; then ")
                .append("pm install -r -d \"$T\"/base.apk 2>&1; ")
                .append("RC=$?; ")
                .append("else ")
                .append("S=$(pm install-create -r -t 2>&1 | grep -oE '[0-9]+' | head -1); ")
                .append("[ -n \"$S\" ] || { echo 'SESSION_FAIL'; rm -rf \"$T\"; exit 2; }; ")
                .append("i=0; ")
                .append("for f in \"$T\"/base.apk \"$T\"/split_*.apk; do ")
                .append("[ -f \"$f\" ] || continue; ")
                .append("i=$((i+1)); ")
                .append("pm install-write \"$S\" \"s$i\" \"$f\" >/dev/null 2>&1 ")
                .append("|| { echo \"WRITE_FAIL:$f\"; pm install-abandon \"$S\" >/dev/null 2>&1; ")
                .append("rm -rf \"$T\"; exit 3; }; ")
                .append("done; ")
                .append("pm install-commit \"$S\" 2>&1; ")
                .append("RC=$?; ")
                .append("fi; ");

        // 数据恢复：只有备份里带、且当前有 root 才做
        if (b.withData && useRoot) {
            cmd.append("if [ -f \"$D/data.tar.gz\" ]; then ")
                    .append("U=$(stat -c '%u' /data/data/").append(safePkg)
                    .append(" 2>/dev/null); ")
                    .append("[ -n \"$U\" ] || { echo 'NO_DATADIR'; rm -rf \"$T\"; exit 4; }; ")
                    .append("tar -xpzf \"$D/data.tar.gz\" -C /data/data 2>&1 ")
                    .append("|| { echo 'UNTAR_FAIL'; rm -rf \"$T\"; exit 5; }; ")
                    .append("chown -R \"$U:$U\" /data/data/").append(safePkg)
                    .append(" 2>/dev/null; ")
                    .append("restorecon -R /data/data/").append(safePkg)
                    .append(" >/dev/null 2>&1; ")
                    .append("echo 'DATA_OK'; ")
                    .append("fi; ");
        } else if (b.withData) {
            cmd.append("echo 'DATA_SKIP'; ");
        }
        cmd.append("am force-stop ").append(safePkg).append(" 2>/dev/null; ")
                .append("rm -rf \"$T\"; ")
                .append("echo \"RC=$RC\"");

        Cmd r = run(ctx, cmd.toString(), useRoot, BACKUP_TIMEOUT_MS);
        String out = r.out;
        if (out.contains("NO_DIR")) {
            return Result.fail("备份目录不存在（可能已被删除）", out);
        }
        if (out.contains("SESSION_FAIL")) {
            return Result.fail("创建安装会话失败", out);
        }
        if (out.contains("WRITE_FAIL")) {
            return Result.fail("写入安装分片失败", out);
        }
        if (out.contains("NO_DATADIR")) {
            return Result.fail("装包后未找到 /data/data 目录，数据未恢复", out);
        }
        if (out.contains("UNTAR_FAIL")) {
            return Result.fail("解压应用数据失败", out);
        }
        // 整个脚本以 `echo RC=$RC` 收尾，shell 自身的退出码恒为 0，
        // 所以只能从这一行拿 pm 的真实结果
        int rc = -1;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("RC=")) {
                try {
                    rc = Integer.parseInt(s.substring(3).trim());
                } catch (Exception ignored) {
                    // 解析不出就保持 -1，改由输出文本判断
                }
            }
        }
        if (out.contains("Failure") || out.contains("INSTALL_FAILED")
                || (rc != 0 && !out.contains("Success"))) {
            return Result.fail("安装失败：" + reasonOf(out), out);
        }
        boolean dataDone = out.contains("DATA_OK");
        String tail = dataDone ? "（安装包 + 数据）"
                : (b.withData ? "（只恢复了安装包：当前无 root，数据已跳过）" : "（仅安装包）");
        LogStore.add(ctx, "OK", "[备份] 已恢复 " + b.title() + " " + tail);
        return Result.ok("已恢复 " + b.title() + " " + tail, out);
    }

    /** 删掉一条备份。 */
    public static Result delete(Context ctx, Backup b, boolean useRoot) {
        if (ctx == null || b == null || b.dir.isEmpty()) {
            return Result.fail("参数不完整", "");
        }
        Cmd r = run(ctx, "rm -rf '" + b.dir + "'; echo DONE", useRoot, 60_000L);
        if (r.ok) {
            LogStore.add(ctx, "OK", "[备份] 已删除备份 " + b.title() + " → " + b.dir);
            return Result.ok("已删除 " + b.title(), r.out);
        }
        return Result.fail("删除失败", r.out);
    }

    // ------------------------------------------------------------------ 通道分发

    /** 统一的命令执行结果。 */
    private static final class Cmd {
        boolean ok;
        String out = "";
    }

    /**
     * 按通道执行命令。
     *
     * <p>root 通道走 {@link RootShell}（不依赖 Shizuku，权限更大）；
     * 否则回落 {@link ShizukuCmd}，以 uid 2000 执行。
     */
    private static Cmd run(Context ctx, String cmd, boolean useRoot, long timeoutMs) {
        Cmd c = new Cmd();
        if (useRoot) {
            RootShell.Result r = RootShell.exec(ctx, cmd, timeoutMs);
            c.ok = r.ok && r.code == 0;
            c.out = r.out == null ? "" : r.out;
            if (!c.ok && c.out.isEmpty()) {
                c.out = String.valueOf(r.error);
            }
            return c;
        }
        String out = ShizukuCmd.execLenient(ctx, cmd);
        c.ok = out != null;
        c.out = out == null ? "" : out;
        return c;
    }

    // ------------------------------------------------------------------ 工具

    /** shell 里当单词用：只留安全字符，其余丢弃。包名本就只含 [A-Za-z0-9_.]。 */
    private static String safeToken(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^A-Za-z0-9_.:-]", "");
    }

    /** 写进 info.txt 的文本：去掉会破坏 printf 单引号参数的字符。 */
    private static String safeText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("'", "").replace("\\", "")
                .replace("\n", " ").replace("\r", "");
    }

    /** 从 pm 输出里抠出失败原因。 */
    private static String reasonOf(String out) {
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.startsWith("Failure [") && s.endsWith("]")) {
                return s.substring(9, s.length() - 1);
            }
        }
        return out.trim().isEmpty() ? "无输出" : out.trim();
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0 ? (bytes + " B")
                : String.format(Locale.US, "%.1f %s", v, u[i]);
    }
}
