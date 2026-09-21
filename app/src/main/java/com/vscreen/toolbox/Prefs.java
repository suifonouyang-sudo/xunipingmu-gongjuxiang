package com.vscreen.toolbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 全局偏好。目前只有「字体大小」。
 *
 * <p>字体大小不是逐个控件去改 textSize，而是改 {@code Configuration.fontScale}
 * 后交给 Activity 重建：布局里所有尺寸都写的是 sp，fontScale 一放大就整体跟着放大，
 * 不用维护一张控件清单。
 */
final class Prefs {

    private static final String FILE = "toolbox_prefs";
    private static final String KEY_FONT_PERCENT = "font_percent";
    private static final String KEY_ROOT_EVER = "root_ever_granted";
    private static final String KEY_BACKUP_DIR = "backup_dir";
    private static final String KEY_AUTO_BROADCAST = "auto_broadcast";
    private static final String KEY_LAST_BROADCAST = "last_broadcast";
    private static final String KEY_BROADCAST_COUNT = "broadcast_count";
    private static final String KEY_BROADCAST_ECHO = "broadcast_echo";

    /** 可选档位（百分比）。用离散档位而不是连续滑动，避免出现 103% 这种奇怪值。 */
    static final int[] FONT_STEPS = {80, 90, 100, 115, 130, 150};
    static final int FONT_DEFAULT = 100;

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int fontPercent(Context ctx) {
        return sp(ctx).getInt(KEY_FONT_PERCENT, FONT_DEFAULT);
    }

    static void setFontPercent(Context ctx, int percent) {
        sp(ctx).edit().putInt(KEY_FONT_PERCENT, percent).apply();
    }

    /**
     * 本机是否曾经成功拿到过 root。
     *
     * <p>用来决定要不要自动复查 root：曾经授权过说明 root 管理器已记住本应用，
     * 再跑 {@code su -c id} 不会再弹授权框，可以静默复查；
     * 从没授权过则不能自动探测，否则一打开授权检查页就凭空弹一个授权框，很突兀。
     */
    static boolean rootEverGranted(Context ctx) {
        return sp(ctx).getBoolean(KEY_ROOT_EVER, false);
    }

    static void setRootEverGranted(Context ctx, boolean granted) {
        sp(ctx).edit().putBoolean(KEY_ROOT_EVER, granted).apply();
    }

    /**
     * 应用备份的根目录。
     *
     * <p>由[应用备份]页与[应用恢复]页<b>共享</b>：两页各存一份会让用户在一页改了目录、
     * 另一页却读不到刚备的记录，这种「备份成功却在恢复页看不到」的困惑很难自查。
     */
    static String backupDir(Context ctx) {
        return sp(ctx).getString(KEY_BACKUP_DIR, AppBackup.DEFAULT_DIR);
    }

    static void setBackupDir(Context ctx, String dir) {
        sp(ctx).edit().putString(KEY_BACKUP_DIR, dir).apply();
    }

    /**
     * [端口固定]页「自动广播地址」开关。
     *
     * <p>开机后要能自己恢复，所以必须落盘：{@link BootReceiver} 靠它重排周期闹钟
     * （闹钟不跨重启存活）并补发一次广播。
     */
    static boolean autoBroadcast(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_BROADCAST, false);
    }

    static void setAutoBroadcast(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_AUTO_BROADCAST, on).apply();
    }

    static long lastBroadcast(Context ctx) {
        return sp(ctx).getLong(KEY_LAST_BROADCAST, 0L);
    }

    static int broadcastCount(Context ctx) {
        return sp(ctx).getInt(KEY_BROADCAST_COUNT, 0);
    }

    /** 记一次成功广播，同时把累计次数 +1（调用方保证发送确实成功）。 */
    static void setLastBroadcast(Context ctx, long time) {
        sp(ctx).edit()
                .putLong(KEY_LAST_BROADCAST, time)
                .putInt(KEY_BROADCAST_COUNT, broadcastCount(ctx) + 1)
                .apply();
    }

    /** 回环自检：自己发的广播被自己收到的时刻（0 = 从没收到过）。 */
    static long lastBroadcastEcho(Context ctx) {
        return sp(ctx).getLong(KEY_BROADCAST_ECHO, 0L);
    }

    static void setLastBroadcastEcho(Context ctx, long time) {
        sp(ctx).edit().putLong(KEY_BROADCAST_ECHO, time).apply();
    }

    /** 按当前档位缩放一个基准 Context，供 {@code attachBaseContext} 使用。 */
    static Context wrapFontScale(Context base) {
        int percent = fontPercent(base);
        if (percent == FONT_DEFAULT) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = percent / 100f;
        return base.createConfigurationContext(cfg);
    }
}
