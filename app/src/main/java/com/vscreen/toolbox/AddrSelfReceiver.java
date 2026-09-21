package com.vscreen.toolbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 地址广播的「回环自检」接收端。
 *
 * <p>存在的意义：{@link AddrBroadcaster#emit} 里 {@code sendBroadcast} 只要不抛异常就算
 * 「发送成功」，但「发出去了」和「真的能被收到」是两回事——本机没有其它 app 监听这个
 * Action 时，{@code dumpsys activity broadcasts} 里根本查不到这条广播，页面上也无从判断
 * 链路是否通。有这个接收端兜底，广播永远至少有一个接收者，页面[自检]那一行就能显示
 * 「已送达」，而不是让用户猜 Tasker 为什么没反应。
 */
public class AddrSelfReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AddrBroadcaster.ACTION.equals(intent.getAction())) {
            return;
        }
        Prefs.setLastBroadcastEcho(context, System.currentTimeMillis());
    }
}
