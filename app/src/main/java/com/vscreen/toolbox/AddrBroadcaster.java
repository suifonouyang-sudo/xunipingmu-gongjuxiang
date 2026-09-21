package com.vscreen.toolbox;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「自动广播地址」：把当前 {@code ip:端口} 用两种通道发布出去，让局域网里的电脑 /
 * 本机自动化（Tasker、MacroDroid、termux-api）不用手抄就能拿到连接地址。
 *
 * <p>两种通道：
 * <ol>
 *   <li><b>自定义广播 Intent</b> —— {@link #ACTION}，直接 {@code sendBroadcast}；
 *       本机任意 app 用「接收 Intent」即可触发自己的动作。</li>
 *   <li><b>mDNS</b> —— 注册 {@code _adb._tcp} 服务，同网段设备用
 *       {@code adb mdns services} 或本页[开始发现]就能扫到。</li>
 * </ol>
 *
 * <p>触发时机（三处，都收敛到 {@link #tick(Context, String)}）：
 * <ul>
 *   <li>开启开关 / 手动点[立即广播一次]；</li>
 *   <li>端口固定成功、强制开启无线调试成功之后立刻发一次；</li>
 *   <li>开机（{@link BootReceiver}）与周期闹钟（{@link AddrTickReceiver}）。</li>
 * </ul>
 *
 * <p><b>能力边界要如实说清</b>：mDNS 注册活在 app 进程里，进程被系统回收就断；
 * 周期闹钟会把进程重新拉起来补注册，但 doze 下会被推迟。IP 变化时不需要重新注册
 * —— mDNS 应答的是设备当前地址，周期重发只是为了把新 IP 主动告知 Intent 接收方。
 */
public final class AddrBroadcaster {

    /** 对外广播的 Action。extras 见 {@link #emit}。 */
    public static final String ACTION = "com.vscreen.toolbox.ADB_ADDRESS";
    public static final String EXTRA_IP = "ip";
    public static final String EXTRA_PORT = "port";
    /** 拼好的 "ip:端口"。 */
    public static final String EXTRA_ADDR = "addr";
    /** 可直接执行的完整命令："adb connect ip:端口"。 */
    public static final String EXTRA_CMD = "cmd";
    /** 广播时刻的毫秒时间戳。 */
    public static final String EXTRA_TIME = "time";
    /** 来源：手动 / 开机 / 端口固定后 / 无线调试开启后 / 周期。 */
    public static final String EXTRA_SOURCE = "source";

    /** 周期闹钟的间隔。闹钟会被 doze 推迟，所以这里只是「不早于」。 */
    public static final long PERIOD_MS = 5 * 60 * 1000L;

    private static final String TICK_ACTION = "com.vscreen.toolbox.ADDR_TICK";
    private static final String SERVICE_TYPE = "_adb._tcp.";

    private static final Object LOCK = new Object();

    // mDNS 是异步的，且同一个 listener 只能注册一次，所以三态都要记：
    // registered=已注册成功、registering=正在注册、其余=未注册
    private static NsdManager nsd;
    private static NsdManager.RegistrationListener listener;
    private static boolean registered;
    private static boolean registering;
    private static String mdnsText = "mDNS：未注册";

    private AddrBroadcaster() {
    }

    // ------------------------------------------------------------------ 地址计算

    /** 当前 {@code ip:端口}。取不到 IP 时用 0.0.0.0，保证 Intent 接收方永远拿得到结构化值。 */
    public static String addr(Context ctx) {
        String ip = localIp();
        return (ip == null ? "0.0.0.0" : ip) + ":" + port(ctx);
    }

    /** adb TCP 端口：先看运行中的属性，再退到 persist，都没有则按 5555 显示。 */
    public static String port(Context ctx) {
        String s = trim(ShizukuCmd.exec(ctx, "getprop service.adb.tcp.port"));
        if (s.isEmpty() || "-1".equals(s)) {
            s = trim(ShizukuCmd.exec(ctx, "getprop persist.adb.tcp.port"));
        }
        if (s.isEmpty() || "-1".equals(s)) {
            s = "5555";
        }
        return s;
    }

    /** 取活动网卡的 IPv4 地址（优先 Wi-Fi / 热点 / 以太网）。 */
    public static String localIp() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (String prefix : new String[]{"wlan", "ap", "eth"}) {
                for (NetworkInterface ni : all) {
                    if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith(prefix)) {
                        continue;
                    }
                    String ip = firstV4(ni);
                    if (ip != null) {
                        return ip;
                    }
                }
            }
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String ip = firstV4(ni);
                if (ip != null) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String firstV4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 广播一次

    /**
     * 发一条自定义广播（不碰 mDNS）。
     *
     * <p>extras：{@link #EXTRA_IP} / {@link #EXTRA_PORT} / {@link #EXTRA_ADDR} /
     * {@link #EXTRA_CMD} / {@link #EXTRA_TIME} / {@link #EXTRA_SOURCE}。
     */
    public static void emit(Context ctx, String source) {
        final Context app = ctx.getApplicationContext();
        String a = addr(app);
        int colon = a.lastIndexOf(':');
        String ip = colon > 0 ? a.substring(0, colon) : "";
        String p = colon >= 0 ? a.substring(colon + 1) : "5555";
        Intent i = new Intent(ACTION);
        i.putExtra(EXTRA_IP, ip);
        i.putExtra(EXTRA_PORT, p);
        i.putExtra(EXTRA_ADDR, a);
        i.putExtra(EXTRA_CMD, "adb connect " + a);
        i.putExtra(EXTRA_TIME, System.currentTimeMillis());
        i.putExtra(EXTRA_SOURCE, source == null ? "手动" : source);
        try {
            app.sendBroadcast(i);
            Prefs.setLastBroadcast(app, System.currentTimeMillis());
            LogStore.add(app, "OK", "[广播] " + a + "（来源：" + i.getStringExtra(EXTRA_SOURCE) + "）");
        } catch (Exception e) {
            LogStore.add(app, "ERR", "[广播] 发送失败：" + e.getMessage());
        }
    }

    /**
     * 完整广播一次：发 Intent + 保证 mDNS 在线。
     * 开机、周期闹钟、端口固定成功都走这里。
     */
    public static void tick(Context ctx, String source) {
        Context app = ctx.getApplicationContext();
        emit(app, source);
        ensureMdns(app);
    }

    // ------------------------------------------------------------------ 开关

    public static boolean autoOn(Context ctx) {
        return Prefs.autoBroadcast(ctx);
    }

    /** 开启自动广播：置开关、排周期闹钟、立即发一次并注册 mDNS。 */
    public static void enable(Context ctx) {
        Context app = ctx.getApplicationContext();
        Prefs.setAutoBroadcast(app, true);
        schedule(app);
        tick(app, "手动");
        LogStore.add(app, "INFO", "自动广播地址已开启（每 " + (PERIOD_MS / 60000) + " 分钟重发）");
    }

    /** 关闭：撤闹钟、注销 mDNS。手动注册过的 mDNS 也一并停掉。 */
    public static void disable(Context ctx) {
        Context app = ctx.getApplicationContext();
        Prefs.setAutoBroadcast(app, false);
        cancel(app);
        stopMdns(app);
        LogStore.add(app, "INFO", "自动广播地址已关闭");
    }

    /**
     * 进程活过来时（用户打开 app、或系统把它拉起来）补回 mDNS 注册。
     *
     * <p>mDNS 注册是进程级的，进程一死就没了，但开关还开着——只靠周期闹钟补会有一个
     * 「用户看着开关是开的、其实没在广播」的空档。地址超过一个周期没广播过就顺带重发。
     */
    public static void resumeIfEnabled(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Prefs.autoBroadcast(app)) {
            return;
        }
        ensureMdns(app);
        if (System.currentTimeMillis() - Prefs.lastBroadcast(app) >= PERIOD_MS) {
            emit(app, "启动");
        }
    }

    /**
     * 开机后重排闹钟——<b>闹钟不跨重启存活</b>，不在 BOOT_COMPLETED 里补一次的话
     * 开关看起来是开的、周期广播却再也不会触发。
     */
    public static void rescheduleIfEnabled(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Prefs.autoBroadcast(app)) {
            return;
        }
        schedule(app);
        LogStore.add(app, "INFO", "开机恢复自动广播地址（周期闹钟已重排）");
    }

    // ------------------------------------------------------------------ 周期闹钟

    private static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            LogStore.add(ctx, "ERR", "[广播] 取不到 AlarmManager，周期广播不可用");
            return;
        }
        try {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + PERIOD_MS, PERIOD_MS, tickPi(ctx));
        } catch (Exception e) {
            LogStore.add(ctx, "ERR", "[广播] 排周期闹钟失败：" + e.getMessage());
        }
    }

    private static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.cancel(tickPi(ctx));
            } catch (Exception ignored) {
            }
        }
    }

    private static PendingIntent tickPi(Context ctx) {
        Intent i = new Intent(ctx, AddrTickReceiver.class);
        i.setAction(TICK_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, 1, i, flags);
    }

    // ------------------------------------------------------------------ mDNS

    /** 没注册过就注册；已注册或正在注册则什么都不做（重复注册会报 NAME_IN_USE）。 */
    public static void ensureMdns(Context ctx) {
        synchronized (LOCK) {
            startMdnsLocked(ctx.getApplicationContext());
        }
    }

    public static void stopMdns(Context ctx) {
        synchronized (LOCK) {
            if (nsd != null && listener != null && (registered || registering)) {
                try {
                    nsd.unregisterService(listener);
                } catch (Exception ignored) {
                }
            }
            listener = null;
            registered = false;
            registering = false;
            mdnsText = "mDNS：已停止";
        }
    }

    public static boolean mdnsOn() {
        synchronized (LOCK) {
            return registered || registering;
        }
    }

    private static void startMdnsLocked(final Context app) {
        if (registered || registering) {
            return;
        }
        if (nsd == null) {
            nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        }
        if (nsd == null) {
            mdnsText = "mDNS：系统不支持 NsdManager";
            return;
        }
        int port = 5555;
        try {
            port = Integer.parseInt(port(app));
        } catch (Exception ignored) {
        }
        final int fPort = port;
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName());
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                synchronized (LOCK) {
                    registered = true;
                    registering = false;
                    mdnsText = "mDNS：已注册 " + s.getServiceName() + " → " + fPort;
                }
                LogStore.add(app, "OK", "[广播] mDNS 已注册 " + s.getServiceName() + ":" + fPort);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int e) {
                synchronized (LOCK) {
                    registered = false;
                    registering = false;
                    mdnsText = "mDNS：注册失败（code " + e + "），下次周期会自动重试";
                }
                LogStore.add(app, "ERR", "[广播] mDNS 注册失败 code=" + e);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
                synchronized (LOCK) {
                    registered = false;
                    registering = false;
                    mdnsText = "mDNS：已停止";
                }
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int e) {
                synchronized (LOCK) {
                    mdnsText = "mDNS：注销失败（code " + e + "）";
                }
            }
        };
        listener = l;
        registering = true;
        mdnsText = "mDNS：注册中…";
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (Exception e) {
            registering = false;
            listener = null;
            mdnsText = "mDNS：不可用（" + e.getMessage() + "）";
        }
    }

    private static String serviceName() {
        return "adb-" + Build.MODEL.replaceAll("[^A-Za-z0-9-]", "");
    }

    // ------------------------------------------------------------------ 状态文案

    /** mDNS 通道的单行状态，供页面[广播到局域网]下方显示。 */
    public static String mdnsStatus() {
        synchronized (LOCK) {
            return mdnsText;
        }
    }

    /** 自动广播卡片的状态文本。 */
    public static String status(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("自动广播：").append(autoOn(ctx) ? "开" : "关")
                .append(" · 周期 ")
                .append(PERIOD_MS / 60000).append(" 分钟");
        sb.append('\n').append(mdnsStatus());
        long last = Prefs.lastBroadcast(ctx);
        sb.append('\n').append("上次广播：");
        if (last <= 0) {
            sb.append("尚未广播");
        } else {
            sb.append(fmt(last))
                    .append(" · 累计 ").append(Prefs.broadcastCount(ctx)).append(" 次");
        }
        // 自检：sendBroadcast 不抛异常 ≠ 真被收到；这一行由 AddrSelfReceiver 回填
        long echo = Prefs.lastBroadcastEcho(ctx);
        sb.append('\n').append("自检：").append(echo > 0 ? "✓ 已送达 " + fmt(echo) : "—");
        sb.append('\n').append("Intent：").append(ACTION);
        return sb.toString();
    }

    private static String fmt(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(time));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
