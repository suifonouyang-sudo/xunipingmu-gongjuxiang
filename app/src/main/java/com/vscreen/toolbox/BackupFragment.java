package com.vscreen.toolbox;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用备份页：读出已安装应用 → 勾选 → 备份。恢复在独立的[应用恢复]页。
 *
 * <p>通道由 {@link RootShell} 的实测结果决定，而不是猜：
 * <ul>
 *   <li><b>root</b>：安装包 + {@code /data/data} 数据一起打包。</li>
 *   <li><b>adb(Shizuku)</b>：只备份安装包。数据目录对 uid 2000 不可读，
 *       这是系统划定的边界，页面会把「含应用数据」置灰并说明原因。</li>
 * </ul>
 *
 * <p>root 探测必须在后台线程——未授权时会等管理器弹窗，最长 25 秒。
 */
public class BackupFragment extends Fragment {

    /** 「含应用数据」的开关状态；只有真拿到 root 才会被用到。 */
    private boolean wantData = true;
    /** 是否只显示三方应用。 */
    private boolean userOnly = true;

    private TextView tvBackupMode;
    private EditText etBackupDir;
    private EditText etBackupFilter;
    private TextView tvBackupResult;
    private TextView btnBackupWithData;
    private TextView btnBackupUserOnly;
    private TextView btnBackupClear;
    private TextView btnBackupRun;
    private ListView lvApps;

    private AppAdapter appAdapter;

    private final List<AppBackup.AppInfo> allApps = new ArrayList<>();
    private final Set<String> checked = new HashSet<>();

    /** root 实测结果；null 表示还没测过。 */
    private RootShell.Probe probe;
    private boolean probing;
    private boolean busy;
    private boolean appsLoaded;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_backup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvBackupMode = v.findViewById(R.id.tvBackupMode);
        etBackupDir = v.findViewById(R.id.etBackupDir);
        etBackupFilter = v.findViewById(R.id.etBackupFilter);
        tvBackupResult = v.findViewById(R.id.tvBackupResult);
        btnBackupWithData = v.findViewById(R.id.btnBackupWithData);
        btnBackupUserOnly = v.findViewById(R.id.btnBackupUserOnly);
        btnBackupClear = v.findViewById(R.id.btnBackupClear);
        btnBackupRun = v.findViewById(R.id.btnBackupRun);
        lvApps = v.findViewById(R.id.lvApps);

        appAdapter = new AppAdapter();
        lvApps.setAdapter(appAdapter);
        // 整行点击 = 切换勾选（行内的 CheckBox 是纯指示器，不抢焦点）
        lvApps.setOnItemClickListener((p, row, pos, id) -> toggleCheck(appAdapter.getItem(pos)));

        btnBackupWithData.setOnClickListener(x -> {
            if (!hasRoot()) {
                toast("当前没有 root，无法备份应用数据");
                return;
            }
            wantData = !wantData;
            renderMode();
        });
        btnBackupUserOnly.setOnClickListener(x -> {
            userOnly = !userOnly;
            btnBackupUserOnly.setSelected(userOnly);
            loadApps();
        });
        v.findViewById(R.id.btnBackupRetest).setOnClickListener(x -> {
            RootShell.clearCache();
            probe = null;
            probeRoot();
        });
        btnBackupClear.setOnClickListener(x -> {
            checked.clear();
            appAdapter.notifyDataSetChanged();
            renderRunButton();
        });
        btnBackupRun.setOnClickListener(x -> runBackup());

        etBackupDir.setText(Prefs.backupDir(requireContext()));
        // 目录改动实时共享给恢复页，避免「备完了却在恢复页看不到」
        etBackupDir.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (getContext() == null) {
                    return;
                }
                Prefs.setBackupDir(requireContext(), currentDir());
            }
        });
        // 独立的「恢复默认目录」按钮太占地方，改成长按目录框
        etBackupDir.setOnLongClickListener(x -> {
            etBackupDir.setText(AppBackup.DEFAULT_DIR);
            toast("已恢复默认目录");
            return true;
        });

        etBackupFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        btnBackupUserOnly.setSelected(userOnly);
        renderMode();
        loadApps();
        probeRoot();
    }

    /** 切回本页时：root 状态可能变了（用户在管理器里点了允许），重新探一次。 */
    public void refresh() {
        if (tvBackupMode == null || getContext() == null) {
            return;
        }
        if (probe == null && !probing) {
            probeRoot();
        }
        if (!appsLoaded) {
            loadApps();
        }
    }

    private boolean hasRoot() {
        return probe != null && probe.granted;
    }

    private void toast(String s) {
        if (getContext() != null) {
            Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ root 探测

    private void probeRoot() {
        if (getContext() == null || probing) {
            return;
        }
        probing = true;
        tvBackupMode.setText("正在检测 root 通道…");
        final Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            RootShell.Probe p = RootShell.probe(ctx);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                probing = false;
                if (getContext() == null) {
                    return;
                }
                probe = p;
                renderMode();
            });
        }, "probe-root").start();
    }

    /** 顶部通道摘要 + 数据开关状态。 */
    private void renderMode() {
        if (tvBackupMode == null || getContext() == null) {
            return;
        }
        boolean root = hasRoot();
        StringBuilder sb = new StringBuilder();
        if (probe == null) {
            sb.append("通道：检测中…");
        } else if (root) {
            sb.append("通道：root（").append(probe.manager == null ? "已授权" : probe.manager)
                    .append("）· 可备份 安装包+数据 · Shizuku ")
                    .append(ShizukuCmd.isReady() ? "已授权" : "未授权");
        } else {
            sb.append("通道：ADB / Shizuku（uid 2000）· 仅安装包 · Shizuku ")
                    .append(ShizukuCmd.isReady() ? "已授权" : "未授权")
                    .append("\n数据需 root：").append(probe.error == null ? "未获得 root" : probe.error)
                    .append("（/data/data 只有 root 读得到）");
        }
        tvBackupMode.setText(sb.toString());

        btnBackupWithData.setSelected(root && wantData);
        btnBackupWithData.setEnabled(root);
        btnBackupWithData.setText(root
                ? (wantData ? "含应用数据 ✓" : "不含应用数据")
                : "含应用数据（需 root）");
        btnBackupWithData.setAlpha(root ? 1f : 0.45f);
        renderRunButton();
    }

    private void renderRunButton() {
        if (btnBackupRun == null) {
            return;
        }
        int n = checked.size();
        btnBackupRun.setText(n == 0 ? "备份选中的应用（先勾选）"
                : "备份选中的 " + n + " 个应用");
        btnBackupRun.setAlpha(n == 0 ? 0.5f : 1f);
    }

    // ------------------------------------------------------------------ 应用列表

    private void loadApps() {
        if (getContext() == null) {
            return;
        }
        final Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            List<AppBackup.AppInfo> list = AppBackup.listApps(ctx, userOnly);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                appsLoaded = true;
                allApps.clear();
                allApps.addAll(list);
                // 切到「仅三方」时，已勾选的系统应用要从选择里剔掉
                Set<String> valid = new HashSet<>();
                for (AppBackup.AppInfo a : allApps) {
                    valid.add(a.pkg);
                }
                checked.retainAll(valid);
                applyFilter();
            });
        }, "load-apps").start();
    }

    private void toggleCheck(AppBackup.AppInfo a) {
        if (a == null) {
            return;
        }
        if (checked.contains(a.pkg)) {
            checked.remove(a.pkg);
        } else {
            checked.add(a.pkg);
        }
        appAdapter.notifyDataSetChanged();
        renderRunButton();
    }

    private void applyFilter() {
        if (appAdapter == null || getContext() == null) {
            return;
        }
        String q = etBackupFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppBackup.AppInfo> shown = new ArrayList<>();
        for (AppBackup.AppInfo a : allApps) {
            if (!q.isEmpty()
                    && !a.label.toLowerCase(Locale.ROOT).contains(q)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(a);
        }
        appAdapter.setItems(shown);
    }

    // ------------------------------------------------------------------ 执行备份

    private void runBackup() {
        if (getContext() == null || busy) {
            return;
        }
        if (checked.isEmpty()) {
            toast("先勾选要备份的应用");
            return;
        }
        if (!hasRoot() && !ShizukuCmd.isReady()) {
            tvBackupResult.setText("无法备份：既没有 root，Shizuku 也未授权。\n"
                    + "请到[授权检查]页完成 Shizuku 授权，或先给设备装好 root。");
            return;
        }
        final List<AppBackup.AppInfo> targets = new ArrayList<>();
        for (AppBackup.AppInfo a : allApps) {
            if (checked.contains(a.pkg)) {
                targets.add(a);
            }
        }
        final String dir = currentDir();
        final boolean root = hasRoot();
        final boolean withData = root && wantData;
        final Context ctx = requireContext().getApplicationContext();

        busy = true;
        btnBackupRun.setAlpha(0.5f);
        tvBackupResult.setText("开始备份 0/" + targets.size() + " …");
        LogStore.add(ctx, "INFO", "[备份] 开始备份 " + targets.size() + " 个应用到 " + dir
                + "（" + (withData ? "含数据 / root" : "仅安装包 / " + (root ? "root" : "adb")) + "）");

        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int ok = 0;
            for (AppBackup.AppInfo a : targets) {
                final int done = ok;
                requireActivity().runOnUiThread(() -> tvBackupResult.setText(
                        "备份中 " + done + "/" + targets.size() + " …\n当前：" + a.label));
                AppBackup.Result r = AppBackup.backup(ctx, a, dir, withData, root);
                if (r.ok) {
                    ok++;
                }
                report.append(r.ok ? "✓ " : "✗ ").append(a.label)
                        .append(r.ok ? "" : "  →  " + r.msg).append('\n');
            }
            final String text = report.toString();
            final int okCount = ok;
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                busy = false;
                if (getContext() == null) {
                    return;
                }
                btnBackupRun.setAlpha(1f);
                tvBackupResult.setText("备份完成：" + okCount + "/" + targets.size() + " 成功\n\n" + text
                        + (okCount > 0 ? "\n到[应用恢复]页可以装回这些备份。" : ""));
                LogStore.add(requireContext(), okCount == targets.size() ? "OK" : "WARN",
                        "[备份] 完成 " + okCount + "/" + targets.size());
                toast("备份完成：" + okCount + "/" + targets.size());
            });
        }, "backup-run").start();
    }

    /** 单个应用直接备份（列表行里的[备份]按钮）。 */
    private void backupOne(AppBackup.AppInfo a) {
        if (getContext() == null || busy) {
            return;
        }
        if (!hasRoot() && !ShizukuCmd.isReady()) {
            tvBackupResult.setText("无法备份：既没有 root，Shizuku 也未授权。");
            return;
        }
        checked.clear();
        checked.add(a.pkg);
        appAdapter.notifyDataSetChanged();
        runBackup();
    }

    /** 目录输入框的内容，统一做安全清洗。 */
    private String currentDir() {
        String s = etBackupDir == null ? "" : etBackupDir.getText().toString();
        String p = s.trim().replaceAll("[^A-Za-z0-9_./\\-]", "");
        if (!p.startsWith("/")) {
            p = AppBackup.DEFAULT_DIR;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    // ------------------------------------------------------------------ 适配器

    private class AppAdapter extends BaseAdapter {
        private final List<AppBackup.AppInfo> items = new ArrayList<>();

        void setItems(List<AppBackup.AppInfo> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppBackup.AppInfo getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_app_backup, parent, false);
            }
            AppBackup.AppInfo a = items.get(position);
            CheckBox cb = row.findViewById(R.id.cbApp);
            TextView name = row.findViewById(R.id.tvAppName);
            TextView meta = row.findViewById(R.id.tvAppMeta);
            TextView btn = row.findViewById(R.id.btnAppBackup);

            cb.setChecked(checked.contains(a.pkg));
            name.setText(a.label);
            meta.setText(a.sub() + (a.splitCount > 0
                    ? " · " + (a.splitCount + 1) + " 分片" : "")
                    + (a.system ? " · 系统" : ""));
            btn.setOnClickListener(x -> backupOne(a));
            return row;
        }
    }
}
