package com.vscreen.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/** 开机后自动把 adb TCP 端口固定为 5555（需 Shizuku 已就绪）。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences sp = context.getSharedPreferences("toolbox_prefs", Context.MODE_PRIVATE);
        boolean fix = sp.getBoolean("autofix_port", false);
        boolean broadcast = Prefs.autoBroadcast(context);
        if (!fix && !broadcast) {
            return;
        }
        // 广播不依赖 Shizuku（取不到属性就退 5555），所以先办它：
        // 重排周期闹钟（闹钟不跨重启存活）+ 立刻发一次。
        if (broadcast) {
            AddrBroadcaster.rescheduleIfEnabled(context);
            AddrBroadcaster.tick(context, "开机");
        }
        if (!fix) {
            return;
        }
        // Shizuku 服务可能比本广播晚就绪，延迟重试几次
        Handler h = new Handler(Looper.getMainLooper());
        for (int i = 0; i < 6; i++) {
            final int attempt = i;
            h.postDelayed(() -> {
                if (!ShizukuCmd.isReady()) {
                    LogStore.add(context, "WARN", "开机固定端口：Shizuku 未就绪（第 " + (attempt + 1) + " 次）");
                    return;
                }
                String out = ShizukuCmd.exec(context,
                        "setprop service.adb.tcp.port 5555 && stop adbd && start adbd");
                if (out != null) {
                    LogStore.add(context, "OK", "开机自动固定端口为 5555 成功");
                    // 端口刚落地，地址最准确，这时补发一次
                    if (Prefs.autoBroadcast(context)) {
                        AddrBroadcaster.tick(context, "开机");
                    }
                }
            }, 30_000L * (i + 1));
        }
    }
}
