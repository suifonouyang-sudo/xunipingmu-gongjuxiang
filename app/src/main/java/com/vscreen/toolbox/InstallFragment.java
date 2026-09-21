package com.vscreen.toolbox;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 安装包页：读取设备上的 APK、解析元数据、一键安装 / 卸载。
 *
 * <p>应用自身在安卓 11+ 受 scoped storage 限制，读不到 /sdcard 也读不到 /data/local/tmp，
 * 因此扫描与安装都以 shell(uid 2000) 身份执行，见 {@link ApkScanner}。
 */
public class InstallFragment extends Fragment {

    /** 默认扫描范围：内部存储根 + adb 最常用的安装包投放目录。 */
    private static final String DEFAULT_DIRS = "/sdcard /data/local/tmp";

    /**
     * 枚举设备上全部可访问的存储卷：内部存储、内存卡 / U 盘（/storage 下的卷）、adb 落地区。
     *
     * <p>/storage 下同时挂着 emulated（内部存储本体）与 self（进程视角别名），
     * 这两者与 /sdcard 是同一份数据，跳过以免重复扫描。
     * 可移除卷（如 1234-5678）走 FUSE，实测 shell 可正常遍历。
     */
    private static final String CMD_LIST_VOLUMES =
            "for v in /sdcard /storage/* /data/local/tmp; do "
                    + "case \"$v\" in /storage/emulated|/storage/self) continue;; esac; "
                    + "[ -d \"$v\" ] && echo \"@@V:$v\"; "
                    + "done; true";

    private EditText etApkDirs;
    private EditText etApkFilter;
    private TextView tvApkStat;
    private TextView tvApkResult;
    private TextView btnApkOnlyNew;
    private ListView lvApks;

    private ApkAdapter adapter;
    /** 全量扫描结果；搜索 / 仅未安装过滤都在这之上做。 */
    private final List<ApkScanner.Apk> all = new ArrayList<>();
    private boolean onlyNotInstalled;
    private boolean scanning;
    /** 是否已经扫过一轮。扫出 0 个也算扫过，避免每次切回本页都重扫一遍。 */
    private boolean hasScanned;
    /** 上次扫描是否真的解析出了元数据（判断 aapt 在不在）。 */
    private boolean aaptWorked;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        etApkDirs = v.findViewById(R.id.etApkDirs);
        etApkFilter = v.findViewById(R.id.etApkFilter);
        tvApkStat = v.findViewById(R.id.tvApkStat);
        tvApkResult = v.findViewById(R.id.tvApkResult);
        btnApkOnlyNew = v.findViewById(R.id.btnApkOnlyNew);
        lvApks = v.findViewById(R.id.lvApks);

        adapter = new ApkAdapter();
        lvApks.setAdapter(adapter);
        lvApks.setOnItemClickListener((parent, row, pos, id) -> showDetail(adapter.getItem(pos)));

        v.findViewById(R.id.btnApkScan).setOnClickListener(x -> scan());
        v.findViewById(R.id.btnApkAllVol).setOnClickListener(x -> scanAllVolumes());
        v.findViewById(R.id.btnApkDefault).setOnClickListener(x -> {
            etApkDirs.setText(DEFAULT_DIRS);
            toast("已恢复默认目录");
        });
        btnApkOnlyNew.setOnClickListener(x -> {
            onlyNotInstalled = !onlyNotInstalled;
            btnApkOnlyNew.setSelected(onlyNotInstalled);
            applyFilter();
        });
        etApkFilter.addTextChangedListener(new TextWatcher() {
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

        btnApkOnlyNew.setSelected(false);
        scan();
    }

    /** 切回本页时调用：只在还没扫过时补一次，不打断用户已看到的结果。 */
    public void refresh() {
        if (tvApkStat == null || getContext() == null) {
            return;
        }
        if (!ShizukuCmd.isReady()) {
            tvApkStat.setText("Shizuku 未就绪：请先到[授权检查]页完成授权");
            return;
        }
        if (!hasScanned && !scanning) {
            scan();
        }
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 扫描

    /**
     * 全盘扫描：先向 shell 要一份「设备上真实存在的存储卷」清单（内部存储 / 内存卡 / U 盘 /
     * adb 落地区），回填进目录框，再照常扫描。
     *
     * <p>刻意分两步而不是直接扫：回填后用户能看见究竟扫了哪些卷，也能随手改或删。
     */
    private void scanAllVolumes() {
        if (getContext() == null || scanning) {
            return;
        }
        if (!ShizukuCmd.isReady()) {
            tvApkStat.setText("Shizuku 未授权，无法枚举存储卷（先去[授权检查]页）");
            return;
        }
        scanning = true;
        tvApkStat.setText("正在枚举存储卷…");
        tvApkResult.setText("—");
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            String out = ShizukuCmd.execLenient(appCtx, CMD_LIST_VOLUMES);
            List<String> vols = new ArrayList<>();
            if (out != null) {
                for (String line : out.split("\n")) {
                    String s = line.trim();
                    if (!s.startsWith("@@V:")) {
                        continue;
                    }
                    String p = s.substring(4).trim();
                    if (!p.isEmpty() && !vols.contains(p)) {
                        vols.add(p);
                    }
                }
            }
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                scanning = false;
                if (getContext() == null) {
                    return;
                }
                if (vols.isEmpty()) {
                    tvApkStat.setText("未发现可用存储卷（检查 Shizuku 授权）");
                    return;
                }
                etApkDirs.setText(String.join(" ", vols));
                LogStore.add(requireContext(), "OK", "[安装包] 全盘扫描：发现 "
                        + vols.size() + " 个存储卷 → " + String.join("、", vols));
                toast("发现 " + vols.size() + " 个存储卷，开始扫描");
                scan();
            });
        }).start();
    }

    /** 只保留「绝对路径 + 安全字符」，避免用户输入被当作 shell 语法执行。 */
    private String sanitizeDirs(String s) {
        StringBuilder b = new StringBuilder();
        for (String part : s.split("\\s+")) {
            String p = part.replaceAll("[^A-Za-z0-9_./\\-]", "");
            if (p.isEmpty() || !p.startsWith("/")) {
                continue;
            }
            if (b.length() > 0) {
                b.append(' ');
            }
            b.append(p);
        }
        return b.toString();
    }

    private void scan() {
        if (getContext() == null || scanning) {
            return;
        }
        final String dirs = sanitizeDirs(etApkDirs.getText().toString());
        if (dirs.isEmpty()) {
            toast("请填写至少一个绝对路径目录");
            return;
        }
        etApkDirs.setText(dirs);
        if (!ShizukuCmd.isReady()) {
            tvApkStat.setText("Shizuku 未授权，无法扫描（先去[授权检查]页）");
            return;
        }
        scanning = true;
        // 目录串可能很长（全盘扫描会有好几个卷），统计区只报数量，完整清单在上面的输入框里
        tvApkStat.setText("扫描中…共 " + dirs.split(" ").length
                + " 个目录，内存卡 / U 盘容量大时可能需要数十秒");
        tvApkResult.setText("—");
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            List<ApkScanner.Apk> list = ApkScanner.scan(appCtx, dirs);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                scanning = false;
                hasScanned = true;
                if (getContext() == null) {
                    return;
                }
                all.clear();
                if (list == null) {
                    tvApkStat.setText("扫描失败：Shizuku 命令通道不可用");
                    applyFilter();
                    return;
                }
                all.addAll(list);
                aaptWorked = false;
                for (ApkScanner.Apk a : all) {
                    if (!a.pkg.isEmpty()) {
                        aaptWorked = true;
                        break;
                    }
                }
                applyFilter();
                LogStore.add(requireContext(), "OK", "[安装包] 扫描完成，共 "
                        + all.size() + " 个（" + dirs + "）"
                        + (aaptWorked ? "" : "，未找到 aapt，仅文件名"));
            });
        }).start();
    }

    /** 关键词 + 「仅未安装」双重过滤后刷新列表与统计。 */
    private void applyFilter() {
        if (adapter == null || tvApkStat == null || getContext() == null) {
            return;
        }
        String q = etApkFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<ApkScanner.Apk> shown = new ArrayList<>();
        int notInstalled = 0;
        for (ApkScanner.Apk a : all) {
            if (!a.installed) {
                notInstalled++;
            }
            if (onlyNotInstalled && a.installed) {
                continue;
            }
            if (!q.isEmpty()
                    && !a.title().toLowerCase(Locale.ROOT).contains(q)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(q)
                    && !a.fileName().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            shown.add(a);
        }
        adapter.setItems(shown);

        if (all.isEmpty()) {
            tvApkStat.setText(scanning ? "扫描中…" : "尚未扫描到安装包（换个目录再试）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(all.size()).append(" 个 · 未安装 ").append(notInstalled);
        if (shown.size() != all.size()) {
            sb.append(" · 显示 ").append(shown.size());
        }
        sb.append('\n').append(aaptWorked
                ? "元数据：aapt 解析正常"
                : "元数据：未找到 aapt（只显示文件名，安装不受影响）");
        tvApkStat.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 安装 / 卸载

    /** 统一的执行入口：后台线程跑 shell，回来后渲染结果并刷新该条状态。 */
    private void runInstall(ApkScanner.Apk a, boolean grantAll) {
        if (getContext() == null || !ShizukuCmd.isReady()) {
            tvApkResult.setText("Shizuku 未授权，无法安装。请先到[授权检查]页确认。");
            return;
        }
        final Context appCtx = requireContext().getApplicationContext();
        tvApkResult.setText("正在安装 …\n" + a.fileName()
                + "\n（文件越大越慢，微信这类 200MB+ 的包请耐心等待）");
        new Thread(() -> {
            // 先落一条「开始」日志：如果装的就是本应用，pm install 会先把本进程杀掉，
            // 安装完成后的回调根本来不及执行，只有这条能留下来。
            LogStore.add(appCtx, "INFO", "[安装包] 开始安装 " + a.path
                    + (grantAll ? "（含 -g 授权）" : ""));
            ApkScanner.Result r = ApkScanner.install(appCtx, a.path, true, grantAll);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(r.ok ? "✓ 安装成功\n" : "✗ 安装失败\n");
                sb.append("文件：").append(a.fileName()).append('\n');
                if (!a.pkg.isEmpty()) {
                    sb.append("包名：").append(a.pkg).append('\n');
                }
                if (!r.ok) {
                    sb.append("原因：").append(r.reason()).append('\n');
                }
                sb.append("── pm 输出 ──\n").append(trimOutput(r.output));
                tvApkResult.setText(sb.toString());
                LogStore.add(requireContext(), r.ok ? "OK" : "ERR",
                        "[安装包] " + (r.ok ? "安装成功 " : "安装失败 ") + a.path
                                + (r.ok ? "" : " | " + r.reason()));
                toast(r.ok ? "安装成功" : "安装失败，见结果区");
                if (r.ok) {
                    recheckInstalled(a);
                    applyFilter();
                }
            });
        }).start();
    }

    private void runUninstall(ApkScanner.Apk a) {
        if (getContext() == null || !ShizukuCmd.isReady() || a.pkg.isEmpty()) {
            return;
        }
        final Context appCtx = requireContext().getApplicationContext();
        tvApkResult.setText("正在卸载 " + a.pkg + " …");
        new Thread(() -> {
            ApkScanner.Result r = ApkScanner.uninstall(appCtx, a.pkg);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                tvApkResult.setText((r.ok ? "✓ 已卸载 " : "✗ 卸载失败 ") + a.pkg
                        + "\n── pm 输出 ──\n" + trimOutput(r.output));
                LogStore.add(requireContext(), r.ok ? "OK" : "ERR",
                        "[安装包] " + (r.ok ? "已卸载 " : "卸载失败 ") + a.pkg);
                toast(r.ok ? "已卸载" : "卸载失败");
                if (r.ok) {
                    recheckInstalled(a);
                    applyFilter();
                }
            });
        }).start();
    }

    /** 安装 / 卸载后只重查这一个包名，不必整盘重扫。 */
    private void recheckInstalled(ApkScanner.Apk a) {
        if (a.pkg.isEmpty() || getContext() == null) {
            return;
        }
        try {
            PackageInfo pi = requireContext().getPackageManager().getPackageInfo(a.pkg, 0);
            a.installed = true;
            a.installedCode = pi.versionCode;
        } catch (Exception e) {
            a.installed = false;
            a.installedCode = -1;
        }
    }

    /** 安装前确认。把「会装成什么」讲清楚，尤其降级与覆盖。 */
    private void confirmInstall(ApkScanner.Apk a) {
        if (getContext() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("应用：").append(a.title()).append('\n');
        sb.append("包名：").append(a.pkg.isEmpty() ? "（未解析，仅按文件安装）" : a.pkg).append('\n');
        if (a.versionCode >= 0) {
            sb.append("该包版本：v").append(a.versionName)
                    .append(" (").append(a.versionCode).append(")\n");
        }
        sb.append("本机状态：").append(a.compareText()).append('\n');
        sb.append("大小：").append(a.sizeText()).append('\n');
        sb.append("路径：").append(a.path).append('\n');
        if (a.installed) {
            if (a.versionCode < a.installedCode) {
                sb.append("\n注意：这是**降级安装**，将带上 -d 参数覆盖已有版本。");
            } else {
                sb.append("\n将覆盖安装（-r，保留应用数据）。");
            }
        }
        if (requireContext().getPackageName().equals(a.pkg)) {
            sb.append("\n提示：这正是本工具箱自身，装完系统会重启本应用。");
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("安装这个安装包？")
                .setMessage(sb.toString())
                .setPositiveButton("安装", (d, w) -> runInstall(a, false))
                .setNeutralButton("安装 + 授予权限", (d, w) -> runInstall(a, true))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点击列表行：完整信息 + 复制路径 / 卸载入口。 */
    private void showDetail(ApkScanner.Apk a) {
        if (getContext() == null || a == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文件名：").append(a.fileName()).append('\n');
        sb.append("大小：").append(a.sizeText()).append('\n');
        sb.append("修改时间：").append(ApkScanner.timeText(a.mtime)).append('\n');
        sb.append("路径：").append(a.path).append('\n');
        sb.append("包名：").append(a.pkg.isEmpty() ? "（未解析）" : a.pkg).append('\n');
        if (a.versionCode >= 0) {
            sb.append("版本：v").append(a.versionName)
                    .append(" (").append(a.versionCode).append(")\n");
        }
        if (a.minSdk >= 0 || a.targetSdk >= 0) {
            sb.append("SDK：min ").append(a.minSdk).append(" / target ").append(a.targetSdk).append('\n');
        }
        sb.append("本机状态：").append(a.compareText());
        if (a.installedCode >= 0) {
            sb.append("（本机 ").append(a.installedCode).append("）");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(requireContext())
                .setTitle(a.title())
                .setMessage(sb.toString())
                .setPositiveButton("安装", (d, w) -> confirmInstall(a))
                .setNeutralButton("复制路径", (d, w) -> copy(a.path))
                .setNegativeButton("关闭", null);
        if (a.installed && !a.pkg.isEmpty()) {
            // 卸载是破坏性操作，单独再确认一次
            b.setNegativeButton("卸载", (d, w) -> new AlertDialog.Builder(requireContext())
                    .setTitle("卸载 " + a.pkg + "？")
                    .setMessage("将从本机移除该应用及其数据，不可撤销。")
                    .setPositiveButton("卸载", (d2, w2) -> runUninstall(a))
                    .setNegativeButton("取消", null)
                    .show());
        }
        b.show();
    }

    /** pm 失败时输出十几行 Java 堆栈，整段贴出来会把结果区撑爆，只留前几行。 */
    private String trimOutput(String s) {
        if (s == null || s.isEmpty()) {
            return "（无）";
        }
        String[] lines = s.split("\n");
        if (lines.length <= 6) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            b.append(lines[i].trim()).append('\n');
        }
        b.append("…（共 ").append(lines.length).append(" 行，完整输出见[日志]页）");
        return b.toString();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("apk path", text));
        }
        toast("已复制路径");
    }

    // ------------------------------------------------------------------ 列表

    private class ApkAdapter extends BaseAdapter {
        private final List<ApkScanner.Apk> items = new ArrayList<>();

        void setItems(List<ApkScanner.Apk> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public ApkScanner.Apk getItem(int position) {
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
                        .inflate(R.layout.item_apk, parent, false);
            }
            ApkScanner.Apk a = items.get(position);
            TextView name = row.findViewById(R.id.tvApkName);
            TextView meta = row.findViewById(R.id.tvApkMeta);
            TextView file = row.findViewById(R.id.tvApkFile);
            TextView btn = row.findViewById(R.id.btnApkInstall);

            name.setText(a.title());
            if (a.pkg.isEmpty()) {
                meta.setText("（未解析元数据）");
            } else {
                meta.setText(a.pkg
                        + (a.versionCode >= 0 ? " · v" + a.versionName + " (" + a.versionCode + ")" : ""));
            }
            file.setText(a.fileName() + " · " + a.sizeText() + " · "
                    + ApkScanner.timeText(a.mtime) + " · " + a.compareText());

            // 已安装 → 描边弱化；未安装 → 实心强调，一眼能看出哪些还没装
            btn.setActivated(a.installed);
            Context c = parent.getContext();
            if (a.installed) {
                btn.setBackgroundResource(R.drawable.bg_btn_outline);
                btn.setTextColor(c.getColor(R.color.text_main));
            } else {
                btn.setBackgroundResource(R.drawable.bg_btn_primary);
                btn.setTextColor(0xFFFFFFFF);
            }
            if (!a.installed) {
                btn.setText("安装");
            } else if (a.versionCode >= 0 && a.installedCode >= 0 && a.versionCode > a.installedCode) {
                btn.setText("更新");
            } else {
                btn.setText("重装");
            }
            final ApkScanner.Apk item = a;
            btn.setOnClickListener(x -> confirmInstall(item));
            return row;
        }
    }
}
