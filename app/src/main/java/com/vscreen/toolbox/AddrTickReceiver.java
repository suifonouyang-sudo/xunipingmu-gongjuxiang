package com.vscreen.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 周期闹钟的落点：重发一次地址广播，并顺手把可能已被系统回收掉的 mDNS 注册补回来。
 *
 * <p>mDNS 注册活在 app 进程里，进程被杀就没了；周期闹钟把进程拉起来时这里会重新注册，
 * 所以「开关是开的」最终会自愈，不需要用户回来点按钮。
 */
public class AddrTickReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AddrBroadcaster.autoOn(context)) {
            return;
        }
        AddrBroadcaster.tick(context, "周期");
    }
}
