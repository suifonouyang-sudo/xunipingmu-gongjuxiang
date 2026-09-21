package com.vscreen.toolbox;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.Settings;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 调试地址固定页：地址显示/复制/局域网广播、固定 5555、监听检测、mDNS 发现、开机自启。 */
public class PortFragment extends Fragment {

    private static final String PREF = "toolbox_prefs";
    private static final String KEY_AUTOSTART = "autofix_port";
    private static final String KEY_LAST_FIX = "last_fixed_addr";

    private TextView tvPort;
    private TextView tvListen;
    private TextView tvMdns;
    private TextView tvAddr;
    private TextView tvNet;
    private TextView tvLastFix;
    private TextView tvBroadcast;
    private TextView tvPrivShizuku;
    private TextView tvPrivDhizuku;
    private TextView tvPrivRoot;
    private TextView tvForceWifi;
    private TextView btnAutostart;
    private TextView btnBroadcast;
    private TextView btnAutoBroadcast;
    private TextView tvAutoBroadcast;
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener mdnsListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_port, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvPort = v.findViewById(R.id.tvPort);
        tvListen = v.findViewById(R.id.tvListen);
        tvMdns = v.findViewById(R.id.tvMdns);
        tvAddr = v.findViewById(R.id.tvAddr);
        tvNet = v.findViewById(R.id.tvNet);
        tvLastFix = v.findViewById(R.id.tvLastFix);
        tvBroadcast = v.findViewById(R.id.tvBroadcast);
        tvPrivShizuku = v.findViewById(R.id.tvPrivShizuku);
        tvPrivDhizuku = v.findViewById(R.id.tvPrivDhizuku);
        tvPrivRoot = v.findViewById(R.id.tvPrivRoot);
        tvForceWifi = v.findViewById(R.id.tvForceWifi);
        btnAutostart = v.findViewById(R.id.btnAutostart);
        btnBroadcast = v.findViewById(R.id.btnBroadcast);
        btnAutoBroadcast = v.findViewById(R.id.btnAutoBroadcast);
        tvAutoBroadcast = v.findViewById(R.id.tvAutoBroadcast);

        v.findViewById(R.id.btnRefreshPort).setOnClickListener(view -> refreshPort());
        v.findViewById(R.id.btnFix5555).setOnClickListener(view -> fixPort(true));
        v.findViewById(R.id.btnRestore).setOnClickListener(view -> fixPort(false));
        v.findViewById(R.id.btnListen).setOnClickListener(view -> detectListening());
        v.findViewById(R.id.btnMdns).setOnClickListener(view -> startMdns());
        v.findViewById(R.id.btnCopyAddr).setOnClickListener(view -> copyAddr());
        v.findViewById(R.id.btnForceWifiOn).setOnClickListener(view -> forceWifi(true));
        v.findViewById(R.id.btnForceWifiOff).setOnClickListener(view -> forceWifi(false));
        v.findViewById(R.id.btnForceWifiCheck).setOnClickListener(view -> refreshForceWifi());
        v.findViewById(R.id.btnOpenDevOpt).setOnClickListener(view -> openDevOptions());
        btnBroadcast.setOnClickListener(view -> toggleBroadcast());
        btnAutoBroadcast.setOnClickListener(view -> toggleAutoBroadcast());
        btnAutostart.setOnClickListener(view -> toggleAutostart());

        renderAutostart();
        refreshPort();
        refreshAddr();
        refreshPriv();
        refreshForceWifi();
        renderAuto();
        renderBroadcast();
        // 开关是开着的话，进程重启后 mDNS 注册会丢，这里补回来
        final Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            AddrBroadcaster.resumeIfEnabled(app);
            if (tvAutoBroadcast != null) {
                tvAutoBroadcast.post(() -> {
                    renderAuto();
                    renderBroadcast();
                });
            }
        }, "resume-bcast").start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopMdns();
    }

    public void refresh() {
        refreshPort();
        refreshAddr();
        refreshPriv();
        refreshForceWifi();
        renderAuto();
        renderBroadcast();
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 当前 IP:端口 / 网络类型 / 上次固定记录。 */
    private void refreshAddr() {
        if (tvAddr == null || getContext() == null) {
            return;
        }
        String ip = AddrBroadcaster.localIp();
        // 端口读取逻辑与广播出去的那份必须是同一个（AddrBroadcaster.port），
        // 否则页面上显示 5555、广播出去却是旧值，排查起来很冤。
        String p = AddrBroadcaster.port(requireContext());
        tvAddr.setText((ip == null ? "（未获取到局域网 IP）" : ip) + ":" + p);
        tvNet.setText("网络：" + netType());
        String last = prefs().getString(KEY_LAST_FIX, null);
        tvLastFix.setText("上次固定：" + (last == null ? "无记录" : last));
    }

    private String netType() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String n = ni.getName();
                if (n.startsWith("wlan")) {
                    return "Wi-Fi (" + n + ")";
                }
                if (n.startsWith("ap")) {
                    return "热点 (" + n + ")";
                }
                if (n.startsWith("eth")) {
                    return "以太网 (" + n + ")";
                }
            }
        } catch (Exception ignored) {
        }
        return "未知";
    }

    private void copyAddr() {
        Context c = requireContext();
        String addr = tvAddr.getText().toString();
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb address", addr));
        }
        LogStore.add(c, "OK", "已复制调试地址：" + addr);
        toast("已复制：" + addr);
    }

    /**
     * [立即广播一次]：发一条自定义广播 + 注册 mDNS；再点一次停止。
     *
     * <p>mDNS 注册统一交给 {@link AddrBroadcaster}（进程级单例持有 listener）。
     * 本 Fragment 自己再持一份会跟「自动广播」那份打架——同名服务第二次注册必然
     * NAME_IN_USE，用户只看到「注册失败」却不知道是两处在抢。
     */
    private void toggleBroadcast() {
        // 自动广播开着时这个按钮只负责「再广播一次」——停用要走下面的自动开关，
        // 否则按钮文案一会儿是广播、一会儿是停止，点了停掉却又被周期闹钟自己发出来。
        if (!AddrBroadcaster.autoOn(requireContext()) && AddrBroadcaster.mdnsOn()) {
            AddrBroadcaster.stopMdns(requireContext());
            toast("已停止广播");
            renderBroadcast();
            renderAuto();
            return;
        }
        AddrBroadcaster.tick(requireContext(), "手动");
        toast("已广播一次（Intent + mDNS）");
        renderBroadcast();
        // 注册是异步的，稍后回读一次真实结果，否则状态会一直停在「注册中…」
        if (tvBroadcast != null) {
            tvBroadcast.postDelayed(this::renderBroadcast, 1500);
        }
        renderAuto();
    }

    /** 自动广播开关：开 = 立即发一次 + 排周期闹钟 + 注册 mDNS。 */
    private void toggleAutoBroadcast() {
        Context c = requireContext();
        if (AddrBroadcaster.autoOn(c)) {
            AddrBroadcaster.disable(c);
            toast("自动广播已关闭");
        } else {
            AddrBroadcaster.enable(c);
            toast("自动广播已开启，每 "
                    + (AddrBroadcaster.PERIOD_MS / 60000) + " 分钟重发一次");
        }
        renderAuto();
        renderBroadcast();
        // mDNS 注册和自检回环都是异步回填的，立刻读只能读到「注册中…」，
        // 所以延迟一下再刷一次（和[立即广播一次]一个道理）。
        if (tvAutoBroadcast != null) {
            tvAutoBroadcast.postDelayed(() -> {
                renderAuto();
                renderBroadcast();
            }, 1500);
        }
    }

    private void renderAuto() {
        if (tvAutoBroadcast == null || btnAutoBroadcast == null || getContext() == null) {
            return;
        }
        btnAutoBroadcast.setText("自动广播地址："
                + (AddrBroadcaster.autoOn(requireContext()) ? "开" : "关"));
        tvAutoBroadcast.setText(AddrBroadcaster.status(requireContext()));
    }

    private void renderBroadcast() {
        if (tvBroadcast == null || getContext() == null) {
            return;
        }
        boolean auto = AddrBroadcaster.autoOn(requireContext());
        tvBroadcast.setText("广播：发一条 ADB_ADDRESS 广播 + 注册局域网 mDNS（_adb._tcp）。"
                + "\n当前：" + AddrBroadcaster.mdnsStatus()
                + "\n" + (auto
                        ? "自动广播开启中，[立即广播一次]只是再补发一条；要停止请关掉上面的[自动广播地址]。"
                        : "再点一次[立即广播一次]可停止。"));
    }

    /** 特权通道信息：Shizuku 身份与 Dhizuku 安装情况。 */
    private void refreshPriv() {
        if (tvPrivShizuku == null || getContext() == null) {
            return;
        }
        Context c = requireContext();
        if (!ShizukuCmd.binderAlive()) {
            tvPrivShizuku.setText("Shizuku：未连接（请先打开 Shizuku 并启动服务）");
        } else if (!ShizukuCmd.isReady()) {
            tvPrivShizuku.setText("Shizuku：已连接但未授权本应用\n（请在 Shizuku 弹窗中点[允许]，或点上方[刷新状态]后重试）");
        } else {
            String idOut = ShizukuCmd.exec(c, "id");
            String who = "uid 2000";
            if (idOut != null) {
                Matcher um = Pattern.compile("uid=(\\d+)").matcher(idOut);
                if (um.find()) {
                    who = "uid " + um.group(1);
                }
            }
            tvPrivShizuku.setText("Shizuku：已就绪 · shell (" + who + ")");
        }
        boolean dhizuku;
        try {
            c.getPackageManager().getPackageInfo("com.rosan.dhizuku", 0);
            dhizuku = true;
        } catch (Exception e) {
            dhizuku = false;
        }
        tvPrivDhizuku.setText(dhizuku
                ? "Dhizuku：已安装 · 可作为设备所有者授予更高权限"
                : "Dhizuku：未安装（本机未检测到 Dhizuku 应用）");

        refreshPrivRoot(c);
    }

    /**
     * root 通道状态。这里只读缓存与「看得见」的信息，不做授权实测
     * —— 实测会弹 root 管理器授权框，必须由用户在[授权检查]页显式触发。
     */
    private void refreshPrivRoot(Context c) {
        if (tvPrivRoot == null) {
            return;
        }
        if (RootShell.hasRoot()) {
            RootShell.Probe p = RootShell.cached();
            String who = p == null || p.manager == null ? "已授权" : p.manager
                    + (p.managerVer == null || p.managerVer.isEmpty()
                    ? "" : " " + p.managerVer);
            tvPrivRoot.setText("Root：" + who + " · uid 0 可用\n"
                    + "→ [固定为 5555] 会直接重启 adbd，写入即生效，不需要插拔 USB");
        } else if (RootShell.suPath() != null) {
            tvPrivRoot.setText("Root：检测到 su（" + RootShell.suPath() + "）但尚未授权\n"
                    + "→ 到[授权检查]页点「检测 root 并申请授权」，授权后回本页刷新");
        } else {
            tvPrivRoot.setText("Root：未 root（未找到 su）\n"
                    + "→ 端口属性可写，但要等 adbd 重启才生效；"
                    + "需插拔一次 USB 线，或由电脑执行 adb tcpip 5555");
        }
    }

    private void refreshPort() {
        if (tvPort == null || getContext() == null) {
            return;
        }
        String port = ShizukuCmd.exec(requireContext(), "getprop service.adb.tcp.port");
        String persist = ShizukuCmd.exec(requireContext(), "getprop persist.adb.tcp.port");
        if (port == null) {
            tvPort.setText("读取失败（检查 Shizuku 授权）");
            return;
        }
        String p = port.trim();
        String pp = persist == null ? "" : persist.trim();
        String state;
        if ("5555".equals(p) || "5555".equals(pp)) {
            state = "已固定为 5555 ✓";
        } else if (p.isEmpty() || "-1".equals(p)) {
            state = "未开启 TCP（仅 USB 调试）";
        } else {
            state = "TCP 端口 = " + p + "（未固定）";
        }
        tvPort.setText(state
                + "\nservice.adb.tcp.port = " + (p.isEmpty() ? "（空）" : p)
                + "\npersist.adb.tcp.port = " + (pp.isEmpty() ? "（空）" : pp));
    }

    /** 解析监听端口，排除仅绑定 127.0.0.1 / ::1 的本机内部端口。 */
    private void detectListening() {
        // 三种来源一并采集，避免某些机型缺少 netstat/ss 导致漏检
        String out = ShizukuCmd.exec(requireContext(),
                "(netstat -tlnp 2>/dev/null; ss -tlnp 2>/dev/null; "
                        + "cat /proc/net/tcp 2>/dev/null; cat /proc/net/tcp6 2>/dev/null)");
        if (out == null) {
            tvListen.setText("检测失败（检查 Shizuku 授权）");
            return;
        }
        java.util.LinkedHashSet<String> lines = new java.util.LinkedHashSet<>();
        for (String raw : out.split("\n")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            // netstat/ss 行含 LISTEN；/proc/net/tcp 行状态列为 0A
            boolean isListen = s.contains("LISTEN") || s.matches(".*\\s0A\\s.*");
            if (!isListen) {
                continue;
            }
            String addr = extractAddr(s);
            if (addr == null) {
                continue;
            }
            // 排除仅本机可访问的端口
            if (addr.startsWith("127.") || addr.startsWith("[::1]")
                    || addr.startsWith("[::ffff:127.")) {
                continue;
            }
            lines.add(addr);
        }
        if (lines.isEmpty()) {
            tvListen.setText("adbd 当前没有监听任何可被 PC 访问的 TCP 端口"
                    + "\n（若需要无线调试，请先执行[固定为 5555]）");
            LogStore.add(requireContext(), "WARN", "adbd 无对外监听端口");
            return;
        }
        StringBuilder sb = new StringBuilder("对外监听（" + lines.size() + " 个）：\n");
        for (String l : lines) {
            sb.append("  ").append(l);
            if (l.endsWith(":5555")) {
                sb.append("   ← 无线调试 adb");
            }
            sb.append('\n');
        }
        tvListen.setText(sb.toString().trim());
        LogStore.add(requireContext(), "OK", "检测到对外监听 "
                + lines.size() + " 个端口：" + String.join(", ", lines));
    }

    /**
     * 从一行里抽取 "地址:端口"。依次兼容
     * IPv4（192.168.1.100:5555）、方括号 IPv6（[::]:5555）、
     * ss 通配写法（*:5555）以及 /proc/net/tcp 的十六进制写法。
     */
    private String extractAddr(String line) {
        // 1) IPv4
        Matcher m = Pattern.compile("((?:\\d{1,3}\\.){3}\\d{1,3}):(\\d{1,5})").matcher(line);
        if (m.find()) {
            return m.group(1) + ":" + m.group(2);
        }
        // 2) 方括号 IPv6： [::]:5555 / [::ffff:192.168.1.100]:53601
        m = Pattern.compile("\\[([0-9a-fA-F:.]+)\\]:?(\\d{1,5})").matcher(line);
        if (m.find()) {
            return "[" + m.group(1) + "]:" + m.group(2);
        }
        // 3) ss 的通配形式： *:5555 / :::5555
        m = Pattern.compile("(?:^|\\s)(\\*|:::)(\\d{1,5})\\s").matcher(line);
        if (m.find()) {
            return "0.0.0.0:" + m.group(2);
        }
        // 4) /proc/net/tcp 十六进制：0100007F:1E78（小端）
        m = Pattern.compile("(?:^|\\s)([0-9A-F]{8}):([0-9A-F]{4})\\s").matcher(line);
        if (m.find()) {
            try {
                long ipLong = Long.parseLong(m.group(1), 16);
                int port = Integer.parseInt(m.group(2), 16);
                String ip = (ipLong & 0xFF) + "." + ((ipLong >> 8) & 0xFF) + "."
                        + ((ipLong >> 16) & 0xFF) + "." + ((ipLong >> 24) & 0xFF);
                return ip + ":" + port;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void startMdns() {
        if (nsdManager == null) {
            nsdManager = (NsdManager) requireContext()
                    .getSystemService(Context.NSD_SERVICE);
        }
        stopMdns();
        tvMdns.setText("发现中…（约 5 秒）");
        List<String> found = new ArrayList<>();
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int e) {
                postMdns("mDNS 启动失败（code " + e + "）");
            }
            @Override public void onStopDiscoveryFailed(String type, int e) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }
            @Override public void onServiceFound(NsdServiceInfo s) {
                // onServiceFound 给的 host/port 是空的（显示成 null:0），必须再解析一次
                // 才拿得到真实地址——广播了却看不到端口，这个页面就没用了。
                try {
                    nsdManager.resolveService(s, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo si, int e) {
                            add(si.getServiceName() + "  （解析失败 code " + e + "）");
                        }
                        @Override public void onServiceResolved(NsdServiceInfo si) {
                            add(si.getServiceName() + "  "
                                    + si.getHost() + ":" + si.getPort());
                        }
                    });
                } catch (Exception e) {
                    add(s.getServiceName() + "  （无法解析：" + e.getMessage() + "）");
                }
            }

            private void add(String item) {
                found.add(item);
                postMdns("发现服务（" + found.size() + " 个）：\n  "
                        + String.join("\n  ", found));
            }
        };
        mdnsListener = listener;
        // 5 秒后收尾：有结果就保持，没结果给出明确结论而不是一直停在「发现中」
        tvMdns.postDelayed(() -> {
            // 屏幕旋转会重建 Activity 让本 Fragment 脱离，此时不能再碰 context
            if (getContext() == null) {
                return;
            }
            stopMdns();
            if (tvMdns == null) {
                return;
            }
            if (found.isEmpty()) {
                tvMdns.setText("未发现 _adb._tcp 服务（5 秒内无响应）\n"
                        + "请确认手机与电脑处于同一局域网，且已在开发者选项里开启无线调试");
                LogStore.add(requireContext(), "WARN", "mDNS 未发现 _adb._tcp 服务");
            }
        }, 5000);
        try {
            nsdManager.discoverServices("_adb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
            LogStore.add(requireContext(), "INFO", "mDNS 开始发现 _adb._tcp 无线调试服务");
        } catch (Exception e) {
            tvMdns.setText("mDNS 不可用：" + e.getMessage());
        }
    }

    private void postMdns(String text) {
        if (tvMdns != null) {
            tvMdns.post(() -> tvMdns.setText(text));
        }
    }

    private void stopMdns() {
        if (nsdManager != null && mdnsListener != null) {
            try {
                nsdManager.stopServiceDiscovery(mdnsListener);
            } catch (Exception ignored) {
            }
            mdnsListener = null;
        }
    }

    /**
     * 固定 / 恢复 adb TCP 端口。
     *
     * <p>这件事分两步，两步需要的权限不一样：
     * <ol>
     *   <li>写 {@code service.adb.tcp.port} —— shell(uid 2000) 就够；</li>
     *   <li>重启 adbd 让它重读该属性 —— <b>只有 root 能做</b>：
     *       shell 执行 {@code stop adbd} 返回 {@code Must be root}，exit 1（实测）。</li>
     * </ol>
     *
     * <p>所以有 root 时一步到位、当场生效；没有 root 时属性照样写得进去，
     * 但要等 adbd 下次重启才被读取，只能靠插拔一次 USB 线或 PC 端 {@code adb tcpip 5555}。
     * 这个差别必须在文案里说清楚，否则用户会以为按钮没生效。
     *
     * <p>root 执行会 fork su 且要等 adbd 重启（约 1~2 秒），所以整体放后台线程，
     * 避免卡住 UI。
     */
    private void fixPort(boolean on) {
        final Context app = requireContext().getApplicationContext();
        final String portVal = on ? "5555" : "-1";
        final boolean rooted = RootShell.hasRoot();
        tvPort.setText(on ? "正在固定为 5555…" : "正在恢复默认…");

        new Thread(() -> {
            // 结果先落在临时变量里，最后一次性赋给 final，
            // 否则 javac 既无法证明分支收敛、也不让 lambda 捕获。
            boolean effTmp = false;
            String textTmp = "端口固定未生效（未知原因）";
            boolean handled = false;

            // 瑞芯微等方案板自带厂商开关：只要写 persist.internet_adb_enable=1，
            // init 就会自动 setprop service.adb.tcp.port 5555 并 restart adbd
            // （见 /vendor/etc/init/hw/init.rk30board.rc）。比运行时属性强两点：
            // ① 当场生效，不用插拔 USB；② persist 属性跨重启保留，开机自动恢复。
            // 没有这个规则的设备走下面的常规逻辑。
            String rcFile = ShizukuCmd.execLenient(app,
                    "grep -rl 'persist.internet_adb_enable' "
                            + "/vendor/etc/init/hw /system/etc/init /odm/etc/init 2>/dev/null | head -1");
            boolean hasSwitch = rcFile != null && rcFile.trim().endsWith(".rc");
            final String wantSwitch = on ? "1" : "0";
            if (hasSwitch) {
                String cur = ShizukuCmd.execLenient(app, "getprop persist.internet_adb_enable");
                String curV = cur == null ? "" : cur.trim();
                // 属性没变化时 init 的 on property 不会触发，这种情况交给常规逻辑处理
                if (!wantSwitch.equals(curV)) {
                    ShizukuCmd.execLenient(app, "setprop persist.internet_adb_enable " + wantSwitch);
                    effTmp = true;
                    // adbd 一重启，靠 adb 起身的 Shizuku 会跟着掉线，提前说清楚免得以为坏了
                    textTmp = on
                            ? "已写入厂商持久化开关，init 自动重启 adbd：端口立即生效，且重启后仍保持 5555"
                                    + "\n（Shizuku 若显示未运行，去 Shizuku 里点一次[启动]即可）"
                            : "已关闭厂商持久化开关，重启后不再监听 5555";
                    LogStore.add(app, "OK", textTmp);
                    handled = true;
                }
            }

            if (!handled) {
                if (rooted) {
                    RootShell.Result r = RootShell.exec(app,
                            "setprop service.adb.tcp.port " + portVal + "; "
                                    + "stop adbd; sleep 1; start adbd; sleep 1; "
                                    + "getprop init.svc.adbd");
                    effTmp = r.ok && r.out.contains("running");
                    textTmp = effTmp
                            ? "已用 root 重启 adbd，端口立即生效"
                            : "root 执行失败：" + (r.error == null ? "未知原因" : r.error);
                    LogStore.add(app, effTmp ? "OK" : "ERR", textTmp);
                } else {
                    String w = ShizukuCmd.exec(app, "setprop service.adb.tcp.port " + portVal);
                    effTmp = false;
                    textTmp = w == null
                            ? "写入端口属性失败（检查 Shizuku 授权）"
                            : "端口属性已写入，但未重启 adbd（重启 adbd 需要 root）";
                    LogStore.add(app, w == null ? "ERR" : "WARN", textTmp);
                }
            }
            final boolean effective = effTmp;
            final String msg = textTmp;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                toast(msg);
                if (effective && on) {
                    prefs().edit().putString(KEY_LAST_FIX, tvAddr.getText().toString()).apply();
                }
                tvPort.postDelayed(this::refreshPort, effective ? 2500 : 600);
                tvAddr.postDelayed(this::refreshAddr, effective ? 2500 : 600);
                refreshPriv();
                refreshForceWifi();
                // 端口刚落地时地址最准确，自动广播开着就立刻补发一次。
                // 要等 adbd 重启完（约 2.5s）再取，否则广播出去的是旧端口。
                if (on && AddrBroadcaster.autoOn(app)) {
                    tvAddr.postDelayed(() -> new Thread(() -> {
                        AddrBroadcaster.tick(app, "端口固定后");
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> {
                                if (isAdded()) {
                                    renderAuto();
                                }
                            });
                        }
                    }, "bcast-after-fix").start(), effective ? 2600 : 800);
                }
            });
        }, "fix-port").start();
    }

    // ---------------------------------------------------------- 强制开启无线调试

    /**
     * 只读探针。{@code @@KEY=VALUE} 便于解析，{@code @@READY} 之后原样带出 LISTEN 监听表。
     * 末尾 {@code true} 兜底，避免 netstat 无匹配时整条命令非零退出。
     */
    private static final String CMD_WIFI_STATUS =
            "echo @@P=$(getprop service.adb.tcp.port); "
                    + "echo @@A=$(settings get global adb_enabled); "
                    + "echo @@W=$(settings get global adb_wifi_enabled); "
                    + "echo @@V=$(settings get global development_settings_enabled); "
                    + "echo @@D=$(getprop init.svc.adbd); "
                    + "echo @@READY; "
                    + "netstat -tlnp 2>/dev/null | grep LISTEN; true";

    /**
     * 一键强制开启 / 关闭无线调试。
     *
     * <p><b>能做</b>：adb_enabled、adb_wifi_enabled、development_settings_enabled 三个全局开关
     * 直接写开（shell 持有 WRITE_SECURE_SETTINGS），并写入 {@code service.adb.tcp.port}。
     * 其中 {@code adb_wifi_enabled=1} 会真正拉起 Android 11 的系统无线调试 TLS 端口
     * （实测：置 0 端口消失、置 1 端口重现且每次随机）。
     *
     * <p><b>做不到</b>：uid 2000(shell) 无权 {@code stop adbd}/{@code start adbd}
     * （实测返回 "Must be root"，exit 1），{@code svc usb setFunctions} 会被 SIGKILL。
     * 所以写入的属性要等 adbd 下次重启才被读取，因此本方法回读监听状态并给出后续动作。
     */
    private void forceWifi(boolean on) {
        if (tvForceWifi == null || getContext() == null) {
            return;
        }
        Context c = requireContext();
        if (!ShizukuCmd.isReady()) {
            tvForceWifi.setText("Shizuku 未就绪，无法执行。\n请先到[授权检查]页确认 Shizuku 已授权。");
            LogStore.add(c, "ERR", "强制开启无线调试失败：Shizuku 未授权");
            return;
        }
        tvForceWifi.setText(on ? "正在强制开启无线调试…" : "正在关闭无线调试…");
        // 端口属性和无线调试开关直接写；开发者选项 / USB 调试只在确为关闭时才写，
        // 避免无谓地触发 system_server 重启 adbd（adb_enabled 0→1 会重配 USB 功能）。
        String cmd = on
                ? "setprop service.adb.tcp.port 5555; echo @@SETPROP=$?; "
                        + "if [ \"$(settings get global development_settings_enabled)\" != \"1\" ]; "
                        + "then settings put global development_settings_enabled 1; echo @@DEV=written; "
                        + "else echo @@DEV=skip; fi; "
                        + "if [ \"$(settings get global adb_enabled)\" != \"1\" ]; "
                        + "then settings put global adb_enabled 1; echo @@ADB=written; "
                        + "else echo @@ADB=skip; fi; "
                        + "settings put global adb_wifi_enabled 1; echo @@WIFI=$?; "
                        + "sleep 2; " + CMD_WIFI_STATUS
                : "settings put global adb_wifi_enabled 0; echo @@WIFI=$?; "
                        + "setprop service.adb.tcp.port -1; echo @@SETPROP=$?; "
                        + "sleep 2; " + CMD_WIFI_STATUS;
        String out = ShizukuCmd.execLenient(c, cmd);
        if (out == null) {
            tvForceWifi.setText("执行失败（Shizuku 授权异常或命令被拒）");
            return;
        }
        if (on) {
            prefs().edit().putString(KEY_LAST_FIX, tvAddr.getText().toString()).apply();
        }
        LogStore.add(c, "OK", on ? "已强制开启无线调试" : "已关闭无线调试");

        // 有 root 时补上 shell 做不到的那一步：真正重启 adbd，让刚写入的端口属性立即被读取。
        // 没有这一步就只能靠插拔 USB 线或 PC 端 adb tcpip 5555。
        if (RootShell.hasRoot()) {
            RootShell.Result r = RootShell.exec(c, "stop adbd; sleep 1; start adbd");
            LogStore.add(c, r.ok ? "OK" : "WARN",
                    "root 重启 adbd " + (r.ok ? "完成，端口属性已生效"
                            : "失败：" + (r.error == null ? "未知原因" : r.error)));
            if (r.ok) {
                // 再回读一次真实状态并追加到输出尾部。两段输出的 @@ 键不重叠
                // （写入段是 SETPROP/DEV/ADB/WIFI，状态段是 P/A/W/V/D），合并安全。
                String fresh = ShizukuCmd.execLenient(c, "sleep 1; " + CMD_WIFI_STATUS);
                if (fresh != null) {
                    out = out + "\n" + fresh;
                }
            }
        }
        toast(on ? "已强制开启无线调试" : "已关闭无线调试");
        renderForceWifi(out, true);
        // 开启成功后地址才成立，自动广播开着就补发一次
        if (on && AddrBroadcaster.autoOn(c)) {
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                AddrBroadcaster.tick(app, "无线调试开启后");
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (isAdded()) {
                            renderAuto();
                        }
                    });
                }
            }, "bcast-after-wifi").start();
        }
    }

    /** 跳开发者选项，方便查看无线调试开关、配对码与端口。 */
    private void openDevOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                toast("无法打开设置页：" + e2.getMessage());
            }
        }
    }

    /** 只读刷新，不做任何写入。 */
    private void refreshForceWifi() {
        if (tvForceWifi == null || getContext() == null) {
            return;
        }
        String out = ShizukuCmd.execLenient(requireContext(), CMD_WIFI_STATUS);
        if (out == null) {
            tvForceWifi.setText("读取失败（检查 Shizuku 授权）");
            return;
        }
        renderForceWifi(out, false);
    }

    /** 解析探针输出并渲染逐项状态；afterAction 为真时额外展示写入结果。 */
    private void renderForceWifi(String out, boolean afterAction) {
        if (tvForceWifi == null) {
            return;
        }
        HashMap<String, String> kv = new HashMap<>();
        StringBuilder net = new StringBuilder();
        boolean inNet = false;
        for (String seg : out.split("@@")) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (inNet) {
                net.append(s).append('\n');
                continue;
            }
            if (s.startsWith("READY")) {
                inNet = true;
                net.append(s.substring(5).trim()).append('\n');
                continue;
            }
            int eq = s.indexOf('=');
            if (eq > 0) {
                String val = s.substring(eq + 1).trim();
                int nl = val.indexOf('\n');
                if (nl >= 0) {
                    val = val.substring(0, nl).trim();
                }
                kv.put(s.substring(0, eq).trim(), val);
            }
        }

        String port = val(kv, "P");
        String adbEn = val(kv, "A");
        String wifiEn = val(kv, "W");
        String devEn = val(kv, "V");
        String daemon = val(kv, "D");

        List<Integer> ports = externalPorts(net.toString());
        boolean has5555 = ports.contains(5555);
        boolean plainOk = has5555 && "5555".equals(port);
        List<Integer> tls = new ArrayList<>();
        for (int p : ports) {
            if (p != 5555) {
                tls.add(p);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (afterAction) {
            sb.append("写入结果：端口属性").append(exitText(kv.get("SETPROP")))
                    .append(" · 无线调试开关").append(exitText(kv.get("WIFI")))
                    .append(" · 开发者选项").append(chooseText(kv.get("DEV")))
                    .append(" · USB调试").append(chooseText(kv.get("ADB")));
            sb.append('\n');
        }
        sb.append(kvLine("开发者选项", "1".equals(devEn) ? "已开启" : "已关闭", "1".equals(devEn)));
        sb.append(kvLine("USB 调试", "1".equals(adbEn) ? "已开启" : "已关闭", "1".equals(adbEn)));
        sb.append(kvLine("系统无线调试开关", "1".equals(wifiEn) ? "已开启" : "已关闭", "1".equals(wifiEn)));
        sb.append(kvLine("adbd 服务", daemon, "running".equals(daemon)));
        sb.append(kvLine("明文端口属性", "-1".equals(port) ? "未设置" : port, "5555".equals(port)));
        sb.append(kvLine("对外监听端口", ports.isEmpty() ? "无" : join(ports), null));
        sb.append('\n');

        if (plainOk) {
            sb.append("✓ 明文无线调试已就绪，电脑执行：adb connect ")
                    .append(hostOnly(tvAddr.getText().toString())).append(":5555");
        } else if (!"running".equals(daemon)) {
            sb.append("✗ adbd 未运行，无线调试不可用。\n")
                    .append("处理：重新插拔一次 USB 线，或先在系统设置里打开 USB 调试。");
        } else {
            sb.append("✗ 属性已写入，但 adbd 还没重新读取，明文端口尚未监听。\n");
            if (RootShell.hasRoot()) {
                sb.append("原因：adbd 未重启。本机 root 通道可用，说明这次重启没成功，")
                        .append("可再点一次[固定为 5555]，或到[授权检查]页复查 root 授权状态。");
            } else {
                sb.append("原因：uid 2000(shell) 无权 stop / start adbd，重启 adbd 需要 root。\n")
                        .append("处理：重新插拔一次 USB 线，或用电脑执行 adb tcpip 5555。\n")
                        .append("提示：设备 root 后本页会自动改用 root 重启 adbd，这一步就不用再手动做了。");
            }
        }
        if (!tls.isEmpty()) {
            String host = hostOnly(tvAddr.getText().toString());
            sb.append("\n✓ 系统无线调试 TLS 端口在监听：").append(join(tls))
                    .append("\n   已在本机授权过的电脑：直接 adb connect ")
                    .append(host).append(":").append(tls.get(0))
                    .append("\n   未授权过的新电脑：先在手机上进入 开发者选项 → 无线调试 → 使用配对码配对设备，")
                    .append("\n   再执行 adb pair ").append(host).append(":<配对端口> <配对码>");
        }
        tvForceWifi.setText(sb.toString());
    }

    /** exit code 归一化：0 → ✓，其它 → ✗(exit N)，缺失 → —。 */
    private String exitText(String v) {
        if (v == null || v.isEmpty()) {
            return "—";
        }
        return "0".equals(v) ? "✓" : "✗(exit " + v + ")";
    }

    /** 条件写入结果：written → 已写入，skip → 本来已开。 */
    private String chooseText(String v) {
        if (v == null || v.isEmpty()) {
            return "—";
        }
        if ("skip".equals(v)) {
            return "·(已开)";
        }
        if ("written".equals(v)) {
            return "✓(已写入)";
        }
        return v;
    }

    /** 从 netstat 输出取对外（非 127.0.0.1 / ::1）的 LISTEN 端口。 */
    private List<Integer> externalPorts(String netBlock) {
        List<Integer> out = new ArrayList<>();
        for (String line : netBlock.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || !s.contains("LISTEN")) {
                continue;
            }
            String[] f = s.split("\\s+");
            if (f.length < 4) {
                continue;
            }
            String local = f[3];
            if (local.startsWith("127.") || local.startsWith("[::1]")) {
                continue;
            }
            int i = local.lastIndexOf(':');
            if (i < 0) {
                continue;
            }
            try {
                int p = Integer.parseInt(local.substring(i + 1).trim());
                if (!out.contains(p)) {
                    out.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 中文按两个西文字宽计算，保证 monospace 下标签对齐。 */
    private String kvLine(String label, String value, Boolean ok) {
        StringBuilder b = new StringBuilder(label);
        int w = 0;
        for (int i = 0; i < label.length(); i++) {
            w += label.charAt(i) > 0x7F ? 2 : 1;
        }
        while (w < 20) {
            b.append(' ');
            w++;
        }
        b.append(value);
        if (ok != null) {
            b.append(ok ? "   ✓" : "   ✗");
        }
        return b.append('\n').toString();
    }

    private String val(Map<String, String> kv, String key) {
        String v = kv.get(key);
        return v == null || v.isEmpty() ? "—" : v;
    }

    private String join(List<Integer> ports) {
        StringBuilder b = new StringBuilder();
        for (int p : ports) {
            if (b.length() > 0) {
                b.append("、");
            }
            b.append(p);
        }
        return b.toString();
    }

    private String hostOnly(String addr) {
        int i = addr.lastIndexOf(':');
        return i > 0 ? addr.substring(0, i) : addr;
    }

    private void toggleAutostart() {
        SharedPreferences sp = requireContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        boolean now = !sp.getBoolean(KEY_AUTOSTART, false);
        sp.edit().putBoolean(KEY_AUTOSTART, now).apply();
        LogStore.add(requireContext(), "INFO", "开机自动固定端口：" + (now ? "开" : "关"));
        renderAutostart();
    }

    private void renderAutostart() {
        if (btnAutostart == null || getContext() == null) {
            return;
        }
        boolean on = requireContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTOSTART, false);
        btnAutostart.setText("开机自动固定：" + (on ? "开" : "关"));
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }
}
