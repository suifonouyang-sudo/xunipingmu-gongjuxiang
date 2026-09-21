package com.vscreen.toolbox;

import android.content.Context;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/** 工具箱主页：授权检查 / 虚拟屏 / 虚拟屏控制 / 端口固定 / 应用安装 / 应用备份 / 应用恢复 / 日志。 */
public class MainActivity extends AppCompatActivity {

    /**
     * 八个页面的 tag，重建时靠它把旧实例找回来。
     * 顺序即标签页顺序：授权检查打头（进应用先看授权是否齐全），日志压在最后。
     */
    private static final String[] TAGS = {"pageAuth", "pageDisplay", "pageController",
            "pagePort", "pageInstall", "pageBackup", "pageRestore", "pageLog"};

    /**
     * 保存的是页面 tag 而不是下标。页序调过一次（授权检查提到首位、日志压到最后），
     * 用下标会在升级后把用户恢复到错误的页面；tag 则与顺序解耦。
     */
    private static final String KEY_TAB = "current_tab_tag";

    private final List<Fragment> pages = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private int current = 0;

    private TextView tvFontScale;

    /**
     * 字号缩放的落地点：所有布局尺寸都写 sp，把 fontScale 换掉，整套界面会一起缩放。
     * 必须在 super 之前包好，否则 onCreate 里 inflate 出来的控件已经用了旧缩放。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(Prefs.wrapFontScale(newBase));
    }

    /**
     * 本 Activity 声明了 configChanges（旋转、改 DPI 都不重建，见 Manifest），
     * 但系统会在配置变化后把 fontScale 冲回系统值，于是新建的控件（比如复用的列表行）
     * 会变成错误字号。这里把用户档位补回去。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float want = Prefs.fontPercent(this) / 100f;
        if (Math.abs(newConfig.fontScale - want) > 0.0001f) {
            Configuration cfg = new Configuration(newConfig);
            cfg.fontScale = want;
            getResources().updateConfiguration(cfg, getResources().getDisplayMetrics());
        }
    }

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                LogStore.add(this, grantResult == 0 ? "OK" : "WARN",
                        "Shizuku 授权" + (grantResult == 0 ? "成功" : "被拒绝"));
                onPageShown(current);
            });

    /** 地址广播的回环自检接收端（动态注册，见 onCreate）。 */
    private AddrSelfReceiver selfReceiver;

    private final Shizuku.OnBinderReceivedListener binderListener =
            () -> runOnUiThread(() -> {
                LogStore.add(this, "INFO", "Shizuku 服务已连接");
                onPageShown(current);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tabs.add(findViewById(R.id.tab0));
        tabs.add(findViewById(R.id.tab1));
        tabs.add(findViewById(R.id.tab2));
        tabs.add(findViewById(R.id.tab3));
        tabs.add(findViewById(R.id.tab4));
        tabs.add(findViewById(R.id.tab5));
        tabs.add(findViewById(R.id.tab6));
        tabs.add(findViewById(R.id.tab7));

        buildPages(savedInstanceState);

        // 地址广播的回环自检接收端必须动态注册：Android 8.0 起静态 receiver
        // 收不到隐式广播（实测：写在 Manifest 里一次都没回调）。
        selfReceiver = new AddrSelfReceiver();
        ContextCompat.registerReceiver(this, selfReceiver,
                new IntentFilter(AddrBroadcaster.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            tabs.get(i).setOnClickListener(v -> select(index));
        }

        // 字体大小：A- / 百分比（点一下复位）/ A+
        tvFontScale = findViewById(R.id.tvFontScale);
        tvFontScale.setText(Prefs.fontPercent(this) + "%");
        findViewById(R.id.btnFontMinus).setOnClickListener(v -> stepFont(-1));
        findViewById(R.id.btnFontPlus).setOnClickListener(v -> stepFont(1));
        tvFontScale.setOnClickListener(v -> {
            if (Prefs.fontPercent(this) != Prefs.FONT_DEFAULT) {
                Prefs.setFontPercent(this, Prefs.FONT_DEFAULT);
                LogStore.add(this, "INFO", "[字体] 已恢复默认 " + Prefs.FONT_DEFAULT + "%");
                recreate();
            }
        });

        // 旋转屏幕等配置变化会重建 Activity，这里恢复用户当时所在的标签页
        select(savedInstanceState == null ? 0 : indexOfTag(savedInstanceState.getString(KEY_TAB)));
        if (savedInstanceState == null) {
            LogStore.add(this, "INFO", "工具箱已启动");
        }

        Shizuku.addRequestPermissionResultListener(permListener);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
    }

    /**
     * 建立八个页面（顺序必须与 {@link #TAGS} 一一对应）。
     *
     * <p>关键点：Activity 重建时 FragmentManager 已经把旧实例连同 hide/show 状态一起
     * 恢复了，这里**只能 findFragmentByTag 取回来**，绝不能再 new 一遍——否则会出现
     * 两套页面实例叠加，旧的延时任务还会在脱离上下文后继续跑并崩溃。
     */
    private void buildPages(Bundle savedInstanceState) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment[] fresh = {new AuthFragment(), new DisplayFragment(), new ControllerFragment(),
                new PortFragment(), new InstallFragment(), new BackupFragment(),
                new RestoreFragment(), new LogFragment()};

        if (savedInstanceState == null) {
            FragmentTransaction ft = fm.beginTransaction();
            for (int i = 0; i < TAGS.length; i++) {
                ft.add(R.id.container, fresh[i], TAGS[i]);
                ft.hide(fresh[i]);
                pages.add(fresh[i]);
            }
            ft.commit();
            return;
        }

        // 重建分支：优先取回受管的旧实例，个别缺失（异常情况）才补建
        FragmentTransaction ft = null;
        for (int i = 0; i < TAGS.length; i++) {
            Fragment f = fm.findFragmentByTag(TAGS[i]);
            if (f == null) {
                f = fresh[i];
                if (ft == null) {
                    ft = fm.beginTransaction();
                }
                ft.add(R.id.container, f, TAGS[i]);
                ft.hide(f);
            }
            pages.add(f);
        }
        if (ft != null) {
            ft.commit();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_TAB, TAGS[current]);
    }

    /** tag → 页面下标；取不到（旧版本数据 / 未保存）时回首页。 */
    private int indexOfTag(String tag) {
        for (int i = 0; i < TAGS.length; i++) {
            if (TAGS[i].equals(tag)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
            Shizuku.removeBinderReceivedListener(binderListener);
        } catch (Exception ignored) {
        }
        if (selfReceiver != null) {
            try {
                unregisterReceiver(selfReceiver);
            } catch (Exception ignored) {
            }
            selfReceiver = null;
        }
    }

    /**
     * 在 {@link Prefs#FONT_STEPS} 里往上/下一档。改完必须 recreate()——sp 只在
     * inflate 时换算成像素，不重建的话已经画出来的控件不会变。
     */
    private void stepFont(int dir) {
        int cur = Prefs.fontPercent(this);
        int[] steps = Prefs.FONT_STEPS;
        int next = cur;
        if (dir > 0) {
            for (int s : steps) {
                if (s > cur) {
                    next = s;
                    break;
                }
            }
        } else {
            for (int i = steps.length - 1; i >= 0; i--) {
                if (steps[i] < cur) {
                    next = steps[i];
                    break;
                }
            }
        }
        if (next == cur) {
            Toast.makeText(this, dir > 0 ? "已是最大字号" : "已是最小字号", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.setFontPercent(this, next);
        LogStore.add(this, "INFO", "[字体] " + cur + "% → " + next + "%");
        recreate();
    }

    private void select(int index) {
        if (index == current && tabs.get(index).isSelected()) {
            onPageShown(index);
            return;
        }
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.hide(pages.get(current));
        ft.show(pages.get(index));
        ft.commit();
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setSelected(i == index);
        }
        current = index;
        onPageShown(index);
    }

    private void onPageShown(int index) {
        Fragment f = pages.get(index);
        if (f instanceof DisplayFragment) {
            ((DisplayFragment) f).refresh();
        } else if (f instanceof ControllerFragment) {
            ((ControllerFragment) f).refresh();
        } else if (f instanceof PortFragment) {
            ((PortFragment) f).refresh();
        } else if (f instanceof InstallFragment) {
            ((InstallFragment) f).refresh();
        } else if (f instanceof BackupFragment) {
            ((BackupFragment) f).refresh();
        } else if (f instanceof RestoreFragment) {
            ((RestoreFragment) f).refresh();
        } else if (f instanceof LogFragment) {
            ((LogFragment) f).refresh();
        } else if (f instanceof AuthFragment) {
            ((AuthFragment) f).refresh();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onPageShown(current);
    }

    @Override
    public void onBackPressed() {
        if (current != 0) {
            select(0);
            return;
        }
        super.onBackPressed();
    }
}
