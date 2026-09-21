package com.vscreen.toolbox;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import rikka.shizuku.Shizuku;

/**
 * 授权检查页：把本工具跑起来需要的每一项权限、授权与系统开关集中列出来，
 * 逐项体检并给出跳转到对应系统设置页的入口。
 *
 * <p>分四组：
 * <ul>
 *   <li><b>Shizuku</b>——执行底座。前几项是「看起来如何」（binder 是否活着、是否已授权、
 *       服务版本与身份），最后一项（shell 命令实测）是实跑一条 {@code id} 命令，
 *       也就是「真的能用吗」。只有这一项成功才代表后续所有系统命令能落地。</li>
 *   <li><b>应用权限</b>——通知 / 悬浮窗 / 忽略电池优化，以及只能手动进设置页的
 *       厂商后台自启动开关。虚拟屏上挂机的应用最容易被省电策略清掉，
 *       电池优化白名单是最有实际收益的一项。</li>
 *   <li><b>系统开关</b>——ADB 调试、无线调试，都会直接影响 Shizuku 本身
 *       能不能启动。这些读的是 {@code settings get global}，必须经 Shizuku 以 shell 身份读。</li>
 *   <li><b>Root 通道</b>——与 Shizuku 并列的第二条特权通道，见 {@link RootShell}。
 *       属可选增强：有 root 时能真正重启 adbd（shell 做不到），没有也不计入「待处理」。</li>
 * </ul>
 *
 * <p>为了少跑几趟进程，所有需要 shell 的读探针合并成**一条**命令（见 {@link #PROBE}），
 * 用 {@code echo '|'} 当分隔符拆回来，一次 binder 往返就能拿全。
 */
public class AuthFragment extends Fragment {

    /** 一次拿全所有 shell 侧信息：adb 开关 | 无线调试开关 | su 路径 | id 实测。 */
    private static final String PROBE = "settings get global adb_enabled; echo '|';"
            + " settings get global adb_wifi_enabled; echo '|';"
            + " which su 2>/dev/null; echo '|'; id";

    private static final int REQ_NOTIFICATION = 1001;

    /** 体检等级：INFOR 不计入统计，OK / WARN / ERR 计入。 */
    private static final int LV_INFO = 0;
    private static final int LV_OK = 1;
    private static final int LV_WARN = 2;
    private static final int LV_ERR = 3;

    private TextView tvAuthSummary;
    private TextView tvAuthDevice;
    private LinearLayout lvAuthShizuku;
    private LinearLayout lvAuthApp;
    private LinearLayout lvAuthSys;
    private LinearLayout lvAuthRoot;

    private int okCount;
    private int badCount;
    /** 本轮体检里是否出现过 ERR —— 汇总行据此在「红」与「黄」之间选择。 */
    private boolean anyErr;
    /** 正在后台跑 root 授权实测，避免重复发起（每次都会触发一次 su 调用）。 */
    private boolean rootProbing;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_auth, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvAuthSummary = v.findViewById(R.id.tvAuthSummary);
        tvAuthDevice = v.findViewById(R.id.tvAuthDevice);
        lvAuthShizuku = v.findViewById(R.id.lvAuthShizuku);
        lvAuthApp = v.findViewById(R.id.lvAuthApp);
        lvAuthSys = v.findViewById(R.id.lvAuthSys);
        lvAuthRoot = v.findViewById(R.id.lvAuthRoot);

        v.findViewById(R.id.btnAuthRecheck).setOnClickListener(x -> {
            refresh();
            toast("已重新体检");
        });
        v.findViewById(R.id.btnAuthRequest).setOnClickListener(x -> {
            ShizukuCmd.requestPermission(requireContext());
            refresh();
        });
        v.findViewById(R.id.btnAuthOpenShizuku).setOnClickListener(x -> openShizukuApp());
        v.findViewById(R.id.btnAuthAppDetails).setOnClickListener(x -> openAppDetails());
        v.findViewById(R.id.btnAuthSysRefresh).setOnClickListener(x -> {
            refresh();
            toast("已重新读取系统开关");
        });
        v.findViewById(R.id.btnAuthRootTest).setOnClickListener(x -> requestRoot(true));
        v.findViewById(R.id.btnAuthRootClear).setOnClickListener(x -> {
            RootShell.clearCache();
            Prefs.setRootEverGranted(requireContext(), false);
            toast("已清除 root 探测缓存");
            refresh();
        });

        refresh();
    }

    private boolean alive() {
        return isAdded() && getView() != null && getContext() != null;
    }

    // ------------------------------------------------------------------ 体检主流程

    /** 重新体检全部项目。所有控件都是重建而非增量更新，避免上一轮的按钮/状态残留。 */
    public void refresh() {
        if (!alive() || lvAuthShizuku == null) {
            return;
        }
        Context c = requireContext();
        okCount = 0;
        badCount = 0;
        anyErr = false;
        lvAuthShizuku.removeAllViews();
        lvAuthApp.removeAllViews();
        lvAuthSys.removeAllViews();
        if (lvAuthRoot != null) {
            lvAuthRoot.removeAllViews();
        }

        boolean binder = ShizukuCmd.binderAlive();
        boolean granted = ShizukuCmd.isReady();

        int shizukuVer = -1;
        int shizukuUid = -1;
        String seLinux = null;
        if (binder) {
            try {
                shizukuVer = Shizuku.getVersion();
                shizukuUid = Shizuku.getUid();
                seLinux = Shizuku.getSELinuxContext();
            } catch (Exception ignored) {
            }
        }

        // 一次 shell 往返拿全 shell 侧信息；未授权时全为 null
        String adbEnabled = null;
        String wifiAdb = null;
        String suPath = null;
        String idOut = null;
        if (granted) {
            String out = ShizukuCmd.exec(c, PROBE);
            if (out != null) {
                String[] p = out.split("\\|", -1);
                adbEnabled = p.length > 0 ? p[0].trim() : null;
                wifiAdb = p.length > 1 ? p[1].trim() : null;
                suPath = p.length > 2 ? p[2].trim() : null;
                idOut = p.length > 3 ? p[3].trim() : null;
            }
        }

        buildShizukuCard(c, binder, granted, shizukuVer, shizukuUid, seLinux, idOut);
        buildAppPermCard(c);
        buildSysCard(c, granted, adbEnabled, wifiAdb, suPath);
        buildRootCard(c);
        buildEnvCard(c, binder, granted, shizukuVer, shizukuUid);

        // 曾经授权过就自动复查一次：Magisk / KernelSU 会记住授权，这次不会再弹框，
        // 能省掉用户一次手动点击。从没授权过则不自动探测，免得凭空弹一个授权框。
        if (RootShell.cached() == null
                && Prefs.rootEverGranted(c)
                && RootShell.suPath() != null
                && !rootProbing) {
            requestRoot(false);
        }

        String state = badCount == 0 ? "全部通过" : (badCount + " 项待处理");
        int color = badCount == 0 ? colorOf(R.color.ok)
                : anyErr ? colorOf(R.color.err) : colorOf(R.color.warn);
        tvAuthSummary.setText("共 " + (okCount + badCount) + " 项：正常 " + okCount
                + " · " + state);
        tvAuthSummary.setTextColor(color);
    }

    // ------------------------------------------------------------------ ① Shizuku

    private void buildShizukuCard(Context c, boolean binder, boolean granted,
                                  int ver, int uid, String seLinux, String idOut) {
        // 1) 服务是否运行
        if (binder) {
            row(lvAuthShizuku, "Shizuku 服务", "binder 可连接，服务正在运行",
                    "运行中 ✓", LV_OK, null, null);
        } else {
            row(lvAuthShizuku, "Shizuku 服务", "服务没起来。先在 Shizuku 应用里启动服务"
                            + "（root 或无线调试方式），否则本工具所有功能都不可用",
                    "未运行 ✗", LV_ERR, "打开 Shizuku 应用", this::openShizukuApp);
        }

        // 2) 本应用授权
        if (!binder) {
            row(lvAuthShizuku, "本应用授权", "服务未运行，无法申请授权",
                    "无法检查", LV_WARN, null, null);
        } else if (granted) {
            row(lvAuthShizuku, "本应用授权", "已允许本应用通过 Shizuku 执行系统命令",
                    "已授权 ✓", LV_OK, null, null);
        } else {
            row(lvAuthShizuku, "本应用授权", "点右侧按钮在弹出的 Shizuku 对话框里选「允许」",
                    "未授权 ✗", LV_ERR, "申请授权",
                    () -> {
                        ShizukuCmd.requestPermission(requireContext());
                        refresh();
                    });
        }

        // 3) 服务版本与身份
        String who = uid < 0 ? "身份未知"
                : (uid == 0 ? "uid=0(root)" : "uid=" + uid
                + (uid == 2000 ? "(shell)" : ""));
        String verText = ver < 0 ? "版本未知" : "v" + ver;
        row(lvAuthShizuku, "服务版本 / 身份",
                "Shizuku 服务版本与它当前用的执行身份" + (seLinux == null ? ""
                        : "，SELinux: " + seLinux),
                verText + " · " + who,
                binder ? LV_INFO : LV_WARN, null, null);

        // 4) 真实可用性实测
        if (!granted) {
            row(lvAuthShizuku, "shell 命令实测", "未授权，跳过实测（先完成上面两项）",
                    "未测", LV_WARN, null, null);
        } else if (idOut != null && idOut.contains("uid=")) {
            String first = idOut.split("\n")[0].trim();
            row(lvAuthShizuku, "shell 命令实测", "实跑 id 命令的真实返回，这一步成功才算真的能用",
                    "通过 ✓", LV_OK, null, null);
            // 把完整 id 输出放到描述里，方便直接看到 gid / groups
            row(lvAuthShizuku, "实测返回（id）", first, "—", LV_INFO, null, null);
        } else {
            row(lvAuthShizuku, "shell 命令实测", "命令没有返回，或返回为空——即使显示已授权，"
                            + "实际也执行不了，建议重启 Shizuku 服务后重试",
                    "失败 ✗", LV_ERR, "重跑实测", this::refresh);
        }
    }

    // ------------------------------------------------------------------ ② 应用权限

    private void buildAppPermCard(Context c) {
        String pkg = c.getPackageName();

        // 通知权限：统一用 areNotificationsEnabled，它同时覆盖 33+ 的运行时权限
        // 和用户在系统设置里手动关掉通知的情况，比只看 checkSelfPermission 更准。
        boolean notif = NotificationManagerCompat.from(c).areNotificationsEnabled();
        boolean needRuntime = Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        Runnable notifAction = () -> {
            if (Build.VERSION.SDK_INT >= 33 && needRuntime) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATION);
            } else {
                Intent i = Build.VERSION.SDK_INT >= 26
                        ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        : appDetailsIntent(pkg);
                // 部分 ROM 不认通知设置页，退回应用详情页
                safeStart(i, appDetailsIntent(pkg));
            }
        };
        row(lvAuthApp, "通知权限",
                Build.VERSION.SDK_INT >= 33
                        ? "Android 13+ 需手动授予，否则后台运行 / 端口固定的提醒看不到"
                        : "系统版本低于 13，安装即视为允许；被手动关闭过可点右侧开启",
                notif ? "已允许 ✓" : "未允许 ✗",
                notif ? LV_OK : LV_WARN,
                notif ? "打开通知设置" : "去允许", notifAction);

        // 悬浮窗权限
        boolean overlay = canDrawOverlays(c);
        row(lvAuthApp, "悬浮窗权限",
                "本工具自身不画悬浮窗；部分 ROM 会据此限制后台弹窗与后台启动，可按需开启",
                overlay ? "已允许 ✓" : "未允许",
                overlay ? LV_OK : LV_WARN,
                overlay ? "打开权限设置" : "去允许",
                () -> safeStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + pkg)), appDetailsIntent(pkg)));

        // 忽略电池优化
        boolean battery = ignoringBattery(c);
        row(lvAuthApp, "忽略电池优化",
                "虚拟屏上挂机的应用最容易被省电策略清掉，加入白名单能明显提升存活率（推荐开启）",
                battery ? "已加入白名单 ✓" : "未加入",
                battery ? LV_OK : LV_WARN,
                battery ? "查看电池设置" : "去加入白名单",
                () -> safeStart(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + pkg)),
                        new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));

        // 厂商后台自启动：无统一接口，只能进应用详情页手动开
        row(lvAuthApp, "后台自启动 / 省电策略",
                "小米 / OPPO / vivo / 华为等 ROM 的私有开关，没有统一接口，"
                        + "需在应用详情页里手动允许自启动与后台运行",
                "需手动", LV_INFO, "打开应用详情页", this::openAppDetails);
    }

    private boolean canDrawOverlays(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    private boolean ignoringBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    // ------------------------------------------------------------------ ③ 系统开关

    private void buildSysCard(Context c, boolean granted,
                              String adb, String wifiAdb, String suPath) {
        if (!granted) {
            row(lvAuthSys, "ADB 调试 / 无线调试 / Root",
                    "需要 Shizuku 授权后才能以 shell 身份读取这些系统设置",
                    "无法读取", LV_WARN, "申请授权",
                    () -> {
                        ShizukuCmd.requestPermission(requireContext());
                        refresh();
                    });
            return;
        }

        row(lvAuthSys, "ADB 调试 (adb_enabled)",
                "关掉 ADB 调试后无线调试也无法开启，Shizuku 会随之失效",
                onOffText(adb), levelOf(adb, "1"), null, null);

        row(lvAuthSys, "无线调试 (adb_wifi_enabled)",
                "Android 11+ 用无线调试重启 Shizuku 时必须是开启状态",
                onOffText(wifiAdb), levelOf(wifiAdb, "1"), null, null);

        // 注意：shell 侧 which su 的结果这里不再展示 —— root 已经独立成第 ④ 张卡片，
        // 那里的探测（RootShell）不依赖 Shizuku，比经 shell 查更准。
    }

    // ------------------------------------------------------------------ ④ Root 通道

    /**
     * ④ Root 通道卡片。
     *
     * <p>这里只画「看得见」的快速判定（找 su 二进制 + 按包名认管理器，毫秒级）。
     * 真正的授权实测要 fork su、等管理器弹窗（最长 25 秒），必须由用户主动触发
     * （见 {@link #requestRoot}）——否则每次打开本页都会凭空弹一个授权框。
     *
     * <p>root 对本工具是<b>可选增强</b>：没有 root 的所有行都记为 INFO 不计入汇总的
     * 「待处理」项，避免把「设备没 root」渲染成一个必须修的毛病。
     */
    private void buildRootCard(Context c) {
        if (lvAuthRoot == null) {
            return;
        }
        RootShell.Probe quick = RootShell.quick(c);
        RootShell.Probe p = RootShell.cached();
        boolean suFound = quick.suFound || (p != null && p.suFound);
        String suPath = (p != null && p.suPath != null) ? p.suPath : quick.suPath;

        // 1) su 二进制
        if (suFound) {
            row(lvAuthRoot, "su 二进制",
                    "已找到 su，root 通道具备基础（找到不等于已授权，见下面实测行）",
                    "找到 ✓", LV_INFO, null, null);
            row(lvAuthRoot, "su 路径", suPath, "—", LV_INFO, null, null);
        } else {
            row(lvAuthRoot, "su 二进制",
                    "常见落点（/system/bin、/system/xbin、/sbin、/debug_ramdisk、/vendor/bin 等）"
                            + "都没有 su，设备未 root。这不影响任何功能，本工具会自动全程走 Shizuku",
                    "未找到", LV_INFO, null, null);
        }

        // 2) root 管理器
        String mgr = (p != null && p.manager != null) ? p.manager : quick.manager;
        String mgrVer = p == null ? null : p.managerVer;
        if (mgr != null) {
            row(lvAuthRoot, "root 管理器", "按已安装应用的包名识别",
                    mgr + (mgrVer == null || mgrVer.isEmpty() ? "" : " " + mgrVer),
                    LV_INFO, null, null);
        } else if (suFound) {
            row(lvAuthRoot, "root 管理器",
                    "没能按包名认出管理器（可能是改了包名的隐藏版或定制管理器），不影响使用",
                    "未识别", LV_INFO, null, null);
        }

        // 3) 授权实测 —— 唯一能证明「真的能用」的一行
        if (rootProbing) {
            row(lvAuthRoot, "授权实测 (su -c id)",
                    "正在执行 su -c id。若 root 管理器弹出授权框，请点[允许]",
                    "检测中…", LV_INFO, null, null);
        } else if (p == null) {
            row(lvAuthRoot, "授权实测 (su -c id)",
                    suFound ? "还没实测过。点下方按钮会真的执行一次 su -c id"
                            : "没有 su 可执行，跳过实测",
                    "未检测", LV_INFO, null, null);
        } else if (p.granted) {
            row(lvAuthRoot, "授权实测 (su -c id)",
                    "已拿到 uid=0(root)，root 通道可用"
                            + (p.seLinux == null ? "" : "，SELinux: " + p.seLinux),
                    "通过 ✓", LV_OK, null, null);
            row(lvAuthRoot, "实测返回 (id)", firstLine(p.idOut), "—", LV_INFO, null, null);
        } else {
            row(lvAuthRoot, "授权实测 (su -c id)",
                    p.error == null ? "未通过" : p.error,
                    "未通过", LV_INFO, null, null);
        }

        // 4) 结论：当前实际会走哪条通道
        String channel = RootShell.hasRoot()
                ? "root (uid 0) 优先 —— 端口固定等操作可真正重启 adbd"
                : "Shizuku (uid 2000) —— 功能可用，但重启 adbd 类操作做不到";
        row(lvAuthRoot, "当前特权通道", "需要特权时本工具自动选用的通道", channel,
                LV_INFO, null, null);
    }

    /**
     * 触发一次完整 root 探测：fork su 执行 {@code id}，未授权时会等管理器弹窗。
     *
     * @param fromUser 是否由用户点击触发。false 表示「曾经授权过」的自动复查，
     *                 这种情况下管理器已记住授权、不会再弹框，所以不需要提示。
     */
    private void requestRoot(boolean fromUser) {
        if (!alive() || rootProbing) {
            return;
        }
        Context c = requireContext();
        if (RootShell.suPath() == null) {
            if (fromUser) {
                toast("未找到 su，本机未 root");
                LogStore.add(c, "WARN", "root 检测：未找到 su 二进制，设备未 root");
                refresh();
            }
            return;
        }
        rootProbing = true;
        if (fromUser) {
            Toast.makeText(c, "正在检测 root，若弹出授权框请点[允许]",
                    Toast.LENGTH_LONG).show();
        }
        refresh();

        new Thread(() -> {
            RootShell.Probe p = RootShell.probe(c);
            Prefs.setRootEverGranted(c, p.granted);
            if (!alive()) {
                rootProbing = false;
                return;
            }
            requireActivity().runOnUiThread(() -> {
                rootProbing = false;
                if (alive()) {
                    refresh();
                    toast(p.granted ? "root 通道已就绪" : "root 不可用：" + p.error);
                }
            });
        }, "root-probe").start();
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "—";
        }
        s = s.trim();
        if (s.isEmpty()) {
            return "—";
        }
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    // ------------------------------------------------------------------ 运行环境

    private void buildEnvCard(Context c, boolean binder, boolean granted,
                              int ver, int uid) {
        String env = "@" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "应用包名 " + c.getPackageName() + "  (uid " + Process.myUid() + ")\n"
                + "Shizuku " + (ver < 0 ? "未连接" : "v" + ver)
                + (binder ? "" : "（服务未运行）")
                + (granted ? " 已授权" : " 未授权")
                + (uid < 0 ? "" : "  服务 uid=" + uid)
                + "\n目标 SDK " + c.getApplicationInfo().targetSdkVersion;
        tvAuthDevice.setText(env);
    }

    // ------------------------------------------------------------------ 小工具

    private String onOffText(String v) {
        if (v == null || v.isEmpty()) {
            return "未知";
        }
        if ("1".equals(v)) {
            return "已开启";
        }
        if ("0".equals(v)) {
            return "已关闭";
        }
        return v;
    }

    private int levelOf(String v, String good) {
        if (v == null || v.isEmpty()) {
            return LV_WARN;
        }
        return good.equals(v) ? LV_OK : LV_WARN;
    }

    private Intent appDetailsIntent(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
    }

    /** 打开系统设置页；首选打不开（部分 ROM 裁剪了该页面）时退回备选。 */
    private void safeStart(Intent primary, Intent fallback) {
        Context c = getContext();
        if (c == null) {
            return;
        }
        try {
            startActivity(primary);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(fallback);
            } catch (ActivityNotFoundException e2) {
                toast("本机没有对应的系统设置页，请手动到系统设置里找");
            }
        }
    }

    private void openShizukuApp() {
        Context c = getContext();
        if (c == null) {
            return;
        }
        Intent i = c.getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.manager");
        if (i == null) {
            toast("未安装 Shizuku 应用，请先安装并启动其服务");
            return;
        }
        startActivity(i);
    }

    private void openAppDetails() {
        Context c = getContext();
        if (c == null) {
            return;
        }
        safeStart(appDetailsIntent(c.getPackageName()), appDetailsIntent(c.getPackageName()));
        toast("在本页可手动开启自启动与后台运行");
    }

    /**
     * 往卡片里加一行体检项。
     *
     * @param level  LV_OK / LV_WARN / LV_ERR 计入汇总统计，LV_INFO 只展示不计分
     * @param action 非 null 时显示按钮，label 为空串表示不显示按钮
     * @return 新加的行；异步探测需要回填状态时可以对它取子控件更新
     */
    private View row(LinearLayout parent, String name, String desc, String state,
                     int level, String actionLabel, Runnable action) {
        if (parent == null) {
            return null;
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_auth_check, parent, false);
        if (parent.getChildCount() > 0) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.topMargin = dp(6);
            v.setLayoutParams(lp);
        }
        ((TextView) v.findViewById(R.id.tvCheckName)).setText(name);
        ((TextView) v.findViewById(R.id.tvCheckDesc)).setText(desc);

        TextView st = v.findViewById(R.id.tvCheckState);
        st.setText(state);
        st.setTextColor(colorOf(level == LV_OK ? R.color.ok
                : level == LV_WARN ? R.color.warn
                : level == LV_ERR ? R.color.err : R.color.text_sub));

        TextView btn = v.findViewById(R.id.btnCheckAction);
        if (actionLabel == null || actionLabel.isEmpty() || action == null) {
            btn.setVisibility(View.GONE);
        } else {
            btn.setVisibility(View.VISIBLE);
            btn.setText(actionLabel);
            btn.setOnClickListener(x -> {
                if (alive()) {
                    action.run();
                }
            });
        }
        parent.addView(v);

        if (level == LV_OK) {
            okCount++;
        } else if (level == LV_WARN || level == LV_ERR) {
            badCount++;
            if (level == LV_ERR) {
                anyErr = true;
            }
        }
        return v;
    }

    private int colorOf(int res) {
        Context c = getContext();
        return c == null ? 0xFF000000 : ContextCompat.getColor(c, res);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void toast(String s) {
        Context c = getContext();
        if (c != null) {
            Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
        }
    }

    // 通知权限回调后重新体检，状态即时刷新
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATION) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Context c = getContext();
            if (c != null) {
                LogStore.add(c, ok ? "OK" : "WARN",
                        "通知权限" + (ok ? "已授予" : "被拒绝"));
            }
            refresh();
        }
    }
}
