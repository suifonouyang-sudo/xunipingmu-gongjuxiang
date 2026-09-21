package com.vscreen.toolbox;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 虚拟屏页：创建/销毁虚拟屏、启动应用、固定虚拟主页、后台管理。 */
public class DisplayFragment extends Fragment {

    private TextView tvShizuku;
    private TextView tvScreenState;
    private TextView tvTasks;
    private EditText etWidth;
    private EditText etHeight;
    private EditText etDpi;
    private EditText etPackage;
    private EditText etDisplayId;

    private ListView lvAppsUser;
    private ListView lvAppsSys;
    private EditText etAppFilter;
    private TextView tvAppCount;
    private TextView tvUserHead;
    private TextView tvSysHead;
    private AppAdapter userAdapter;
    private AppAdapter sysAdapter;

    /** 全量应用（用户 + 系统），左右两栏都从这里切分。 */
    private final List<AppEntry> allApps = new ArrayList<>();
    private String selectedPkg = "";

    private int lastDetectedId = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_display, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvShizuku = v.findViewById(R.id.tvShizuku);
        tvScreenState = v.findViewById(R.id.tvScreenState);
        tvTasks = v.findViewById(R.id.tvTasks);
        etWidth = v.findViewById(R.id.etWidth);
        etHeight = v.findViewById(R.id.etHeight);
        etDpi = v.findViewById(R.id.etDpi);
        etPackage = v.findViewById(R.id.etPackage);
        etDisplayId = v.findViewById(R.id.etDisplayId);

        etWidth.setText("1080");
        etHeight.setText("1920");
        etDpi.setText("240");

        v.findViewById(R.id.btnShizuku).setOnClickListener(view -> {
            ShizukuCmd.requestPermission(requireContext());
            refresh();
        });
        v.findViewById(R.id.btnCreate).setOnClickListener(view -> createScreen());
        v.findViewById(R.id.btnDestroy).setOnClickListener(view -> destroyScreens());
        v.findViewById(R.id.btnPickApp).setOnClickListener(view -> pickApp());
        v.findViewById(R.id.btnDetect).setOnClickListener(view -> detectVirtual());
        v.findViewById(R.id.btnLaunch).setOnClickListener(view -> launchApp(0));
        v.findViewById(R.id.btnLaunchFallbackP).setOnClickListener(view -> launchApp(1));
        v.findViewById(R.id.btnPinApp).setOnClickListener(view -> pinApp());
        v.findViewById(R.id.btnScreenAuto).setOnClickListener(view -> {
            detectVirtual();
            if (lastDetectedId >= 0) {
                etDisplayId.setText(String.valueOf(lastDetectedId));
                toast("启动屏 = 虚拟屏 display " + lastDetectedId);
            }
        });
        v.findViewById(R.id.btnScreenPhys).setOnClickListener(view -> {
            etDisplayId.setText("0");
            LogStore.add(requireContext(), "INFO", "启动屏已设为物理屏 display 0");
            toast("启动屏 = 物理屏 display 0");
        });
        v.findViewById(R.id.btnLaunchHome).setOnClickListener(view -> launchHome());
        v.findViewById(R.id.btnListTasks).setOnClickListener(view -> listTasks());
        v.findViewById(R.id.btnForceStop).setOnClickListener(view -> forceStopInput());
        v.findViewById(R.id.btnKillAll).setOnClickListener(view -> confirmKillAll());

        lvAppsUser = v.findViewById(R.id.lvAppsUser);
        lvAppsSys = v.findViewById(R.id.lvAppsSys);
        etAppFilter = v.findViewById(R.id.etAppFilter);
        tvAppCount = v.findViewById(R.id.tvAppCount);
        tvUserHead = v.findViewById(R.id.tvUserHead);
        tvSysHead = v.findViewById(R.id.tvSysHead);

        userAdapter = new AppAdapter();
        sysAdapter = new AppAdapter();
        lvAppsUser.setAdapter(userAdapter);
        lvAppsSys.setAdapter(sysAdapter);
        lvAppsUser.setOnItemClickListener((parent, row, pos, id) -> pickApp(userAdapter.getItem(pos)));
        lvAppsSys.setOnItemClickListener((parent, row, pos, id) -> pickApp(sysAdapter.getItem(pos)));

        v.findViewById(R.id.btnReloadApps).setOnClickListener(x -> {
            hideKeyboard();
            loadApps();
        });
        etAppFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyAppFilter();
            }
        });

        loadApps();

        refresh();
    }

    /** 点列表项：填入包名，并让两栏都刷新选中态。 */
    private void pickApp(AppEntry e) {
        if (e == null || getContext() == null) {
            return;
        }
        selectedPkg = e.pkg;
        etPackage.setText(e.pkg);
        applyAppFilter();
        hideKeyboard();
        LogStore.add(requireContext(), "INFO", "[读取] 选中应用 " + e.label + " → " + e.pkg);
        toast("已填入：" + e.label);
    }

    public void refresh() {
        if (tvShizuku == null || getContext() == null) {
            return;
        }
        if (!ShizukuCmd.binderAlive()) {
            tvShizuku.setText("Shizuku 未连接：请打开 Shizuku 应用启动服务");
            return;
        }
        tvShizuku.setText(ShizukuCmd.isReady()
                ? "Shizuku 已连接并授权 ✓"
                : "Shizuku 已连接，但尚未授权本应用");
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = requireActivity().getCurrentFocus();
        if (imm != null && focus != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private Integer parseInt(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void createScreen() {
        hideKeyboard();
        Integer w = parseInt(etWidth);
        Integer h = parseInt(etHeight);
        Integer dpi = parseInt(etDpi);
        if (w == null || h == null || dpi == null) {
            toast("宽 / 高 / DPI 必须为数字");
            return;
        }
        String spec = w + "x" + h + "/" + dpi;
        if (ShizukuCmd.exec(requireContext(),
                "settings put global overlay_display_devices \"" + spec + "\"") != null) {
            LogStore.add(requireContext(), "OK", "虚拟屏创建请求已下发：" + spec);
            toast("已创建：" + spec);
            tvScreenState.postDelayed(() -> {
                // 屏幕旋转会重建 Activity 让本 Fragment 脱离，此时不能再碰 context
                if (getContext() == null) {
                    return;
                }
                detectVirtual();
                if (lastDetectedId >= 0) {
                    etDisplayId.setText(String.valueOf(lastDetectedId));
                }
            }, 2500);
        }
    }

    private void destroyScreens() {
        if (ShizukuCmd.exec(requireContext(),
                "settings put global overlay_display_devices \"\"") != null) {
            LogStore.add(requireContext(), "OK", "已下发销毁全部虚拟屏");
            toast("已销毁全部虚拟屏");
            lastDetectedId = -1;
            tvScreenState.postDelayed(() -> tvScreenState.setText("当前虚拟屏：（无）"), 1500);
        }
    }

    /**
     * 自动检测虚拟屏。dumpsys display 的 mViewports 里每一项都是
     * {@code DisplayViewport{..., displayId=12, uniqueId='overlay:1', ...}}，
     * displayId 与 uniqueId 紧邻，必须用非贪婪方式严格绑定，否则会误取到别的屏。
     */
    private void detectVirtual() {
        String out = ShizukuCmd.exec(requireContext(), "dumpsys display");
        if (out == null) {
            return;
        }
        Pattern p = Pattern.compile("displayId=(\\d+), uniqueId='(overlay|virtual):([^']*)'");
        List<String> found = new ArrayList<>();
        List<String> overlayIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = p.matcher(out);
        while (m.find()) {
            String id = m.group(1);
            if (!seen.add(id)) {
                continue;
            }
            String type = m.group(2);
            String uid = m.group(3);
            if ("overlay".equals(type)) {
                overlayIds.add(id);
                found.add(id + "（overlay 虚拟屏）");
            } else {
                // 第三方软件（如 scrcpy）创建的虚拟屏，仅作提示
                found.add(id + "（virtual:" + (uid.isEmpty() ? "?" : uid) + "）");
            }
        }
        if (found.isEmpty()) {
            lastDetectedId = -1;
            tvScreenState.setText("当前虚拟屏：未检测到（可先创建）");
            toast("未检测到虚拟屏");
            return;
        }
        // 优先把「本应用创建的 overlay 虚拟屏」作为操作目标
        lastDetectedId = Integer.parseInt(
                overlayIds.isEmpty() ? found.get(0).split("（")[0] : overlayIds.get(0));
        tvScreenState.setText("当前虚拟屏：" + String.join("、", found)
                + "\n操作目标 display id = " + lastDetectedId);
        LogStore.add(requireContext(), "OK", "检测到虚拟屏 "
                + String.join("、", found) + " → 目标 display " + lastDetectedId);
        toast("检测到虚拟屏 display " + lastDetectedId);
    }

    private String targetDisplayId() {
        String manual = etDisplayId.getText().toString().trim();
        if (!manual.isEmpty()) {
            return manual;
        }
        detectVirtual();
        if (lastDetectedId < 0) {
            toast("未检测到虚拟屏，请先创建或手动填写 display id");
            return null;
        }
        return String.valueOf(lastDetectedId);
    }

    /** mode: 0=常规启动 1=兜底 -p 启动 */
    private void launchApp(int mode) {
        hideKeyboard();
        String pkg = etPackage.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("请输入或选择包名");
            return;
        }
        String displayId = targetDisplayId();
        if (displayId == null) {
            return;
        }
        String cmd;
        if (mode == 0) {
            String act = Displays.resolveLauncher(requireContext(), pkg);
            if (act == null) {
                toast("未找到该应用的启动 Activity，可尝试兜底启动");
                return;
            }
            cmd = "am start --display " + displayId + " -n " + act;
        } else {
            cmd = "am start --display " + displayId
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER -p " + pkg;
        }
        if (ShizukuCmd.exec(requireContext(), cmd) != null) {
            LogStore.add(requireContext(), "OK",
                    "启动目标=" + pkg + " → display " + displayId + (mode == 1 ? "（兜底-p）" : ""));
            toast("已发送启动指令");
        }
    }

    /** 把桌面启动到虚拟屏：画面留在源屏，虚拟屏显示桌面（镜像/主页固定）。 */
    private void launchHome() {
        String displayId = targetDisplayId();
        if (displayId == null) {
            return;
        }
        String out = ShizukuCmd.exec(requireContext(),
                "cmd package resolve-activity -a android.intent.action.MAIN"
                        + " -c android.intent.category.HOME");
        String act = null;
        if (out != null) {
            for (String line : out.split("\n")) {
                String s = line.trim();
                if (s.contains("/") && s.contains(".")) {
                    act = s;
                    break;
                }
            }
        }
        String home = act != null ? "-n " + act
                : "-c android.intent.category.HOME -a android.intent.action.MAIN";
        String cmd = "am start --display " + displayId + " " + home;
        if (ShizukuCmd.exec(requireContext(), cmd) != null) {
            LogStore.add(requireContext(), "OK",
                    "[桌面] 已固定到虚拟主页 → display " + displayId
                            + (act != null ? "（" + act + "）" : "（兜底HOME）"));
            toast("桌面已启动到虚拟屏");
        }
    }

    /**
     * 固定应用：先 force-stop 保证是干净的新实例，再在目标屏启动并置于前台，
     * 使该应用常驻所选屏幕（对应原「虚拟屏启动器」的固定应用）。
     */
    private void pinApp() {
        hideKeyboard();
        String pkg = etPackage.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("请先选择要固定的应用");
            return;
        }
        String displayId = targetDisplayId();
        if (displayId == null) {
            return;
        }
        ShizukuCmd.exec(requireContext(), "am force-stop " + pkg);
        String act = Displays.resolveLauncher(requireContext(), pkg);
        String cmd = act != null
                ? "am start --display " + displayId + " -n " + act
                : "am start --display " + displayId
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER -p " + pkg;
        if (ShizukuCmd.exec(requireContext(), cmd) != null) {
            LogStore.add(requireContext(), "OK",
                    "已固定应用 " + pkg + " 到 display " + displayId
                            + (act != null ? "（" + act + "）" : "（兜底-p）"));
            toast("已固定到 display " + displayId);
        }
    }

    private void pickApp() {
        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<String> labels = new ArrayList<>();
        List<String> pkgs = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) {
                continue;
            }
            labels.add(ai.loadLabel(pm) + "  (" + ai.packageName + ")");
            pkgs.add(ai.packageName);
        }
        String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle("选择应用（共 " + arr.length + " 个）")
                .setItems(arr, (d, which) -> etPackage.setText(pkgs.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 枚举指定 display 上运行的应用。 */
    private void listTasks() {
        String displayId = etDisplayId.getText().toString().trim();
        if (displayId.isEmpty() && lastDetectedId >= 0) {
            displayId = String.valueOf(lastDetectedId);
        }
        if (displayId.isEmpty()) {
            toast("请先填写或检测 display id");
            return;
        }
        String out = ShizukuCmd.exec(requireContext(), "dumpsys activity activities");
        if (out == null) {
            return;
        }
        Set<String> pkgs = new HashSet<>();
        boolean inSection = false;
        Pattern dispHead = Pattern.compile(".*[Dd]isplay ?#" + displayId + ".*");
        for (String line : out.split("\n")) {
            if (dispHead.matcher(line).matches()) {
                inSection = true;
                continue;
            }
            if (inSection && line.matches(".*[Dd]isplay ?#\\d+.*")) {
                break;
            }
            if (inSection) {
                Matcher m = Pattern.compile("cmp=ComponentInfo\\{([^}/]+)/").matcher(line);
                if (m.find()) {
                    pkgs.add(m.group(1));
                }
            }
        }
        if (pkgs.isEmpty()) {
            tvTasks.setText("display " + displayId + " 上没有运行中的应用（或仅系统核心）");
            LogStore.add(requireContext(), "INFO", "[读取] display " + displayId + " 无运行中的应用");
        } else {
            StringBuilder sb = new StringBuilder("display " + displayId + " 运行中（"
                    + pkgs.size() + " 个）：\n");
            for (String p : pkgs) {
                sb.append("  ").append(p).append('\n');
            }
            tvTasks.setText(sb.toString().trim());
            LogStore.add(requireContext(), "OK", "[读取] display " + displayId
                    + " 共 " + pkgs.size() + " 个应用");
        }
    }

    private void forceStopInput() {
        String pkg = etPackage.getText().toString().trim();
        if (pkg.isEmpty()) {
            toast("请先输入要停止的包名");
            return;
        }
        if (ShizukuCmd.exec(requireContext(), "am force-stop " + pkg) != null) {
            LogStore.add(requireContext(), "OK", "✓ force-stop " + pkg);
            toast("已停止 " + pkg);
        }
    }

    private void confirmKillAll() {
        new AlertDialog.Builder(requireContext())
                .setTitle("关闭全部后台")
                .setMessage("将 force-stop 所有第三方后台应用（排除系统核心、桌面、输入法与本应用）。继续？")
                .setPositiveButton("关闭", (d, w) -> killAllBackground())
                .setNegativeButton("取消", null)
                .show();
    }

    private void killAllBackground() {
        String out = ShizukuCmd.exec(requireContext(), "dumpsys activity activities");
        if (out == null) {
            return;
        }
        Set<String> pkgs = new HashSet<>();
        Matcher m = Pattern.compile("cmp=ComponentInfo\\{([^}/]+)/").matcher(out);
        while (m.find()) {
            pkgs.add(m.group(1));
        }
        String self = requireContext().getPackageName();
        Set<String> skip = new HashSet<>(java.util.Arrays.asList(
                "android", "com.android.systemui", self,
                "com.android.settings", "com.android.inputmethod"));
        int killed = 0;
        for (String p : pkgs) {
            if (skip.contains(p) || p.startsWith("com.android.")
                    || p.contains("launcher") || p.contains("inputmethod")) {
                continue;
            }
            if (ShizukuCmd.exec(requireContext(), "am force-stop " + p) != null) {
                killed++;
            }
        }
        LogStore.add(requireContext(), "OK", "后台清理完成，共关闭 " + killed + " 个应用");
        toast("已关闭 " + killed + " 个后台应用");
    }

    // ------------------------------------------------------------------
    // 应用列表：对应原「虚拟屏启动器」的「读取应用列表」+「点应用填包名」
    // ------------------------------------------------------------------

    /** 列表条目：应用名 + 包名 + 是否系统应用。 */
    private static class AppEntry {
        final String label;
        final String pkg;
        final boolean system;

        AppEntry(String label, String pkg, boolean system) {
            this.label = label;
            this.pkg = pkg;
            this.system = system;
        }
    }

    private class AppAdapter extends BaseAdapter {
        private final List<AppEntry> items = new ArrayList<>();

        void setItems(List<AppEntry> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppEntry getItem(int position) {
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
                        .inflate(R.layout.item_app, parent, false);
            }
            AppEntry e = items.get(position);
            TextView label = row.findViewById(R.id.tvAppLabel);
            TextView pkg = row.findViewById(R.id.tvAppPkg);
            label.setText(e.label);
            pkg.setText(e.pkg);
            row.setActivated(e.pkg.equals(selectedPkg));
            return row;
        }
    }

    /** 读取设备上安装的全部应用（用户 + 系统）。后台线程加载，避免主线程卡顿。 */
    private void loadApps() {
        if (getContext() == null || tvAppCount == null) {
            return;
        }
        tvAppCount.setText("读取中…");
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            PackageManager pm = appCtx.getPackageManager();
            List<AppEntry> list = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                list.add(new AppEntry(ai.loadLabel(pm).toString(), ai.packageName, sys));
            }
            Collator collator = Collator.getInstance(Locale.CHINA);
            list.sort((a, b) -> collator.compare(a.label, b.label));
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                allApps.clear();
                allApps.addAll(list);
                applyAppFilter();
                int sys = 0;
                for (AppEntry e : list) {
                    if (e.system) {
                        sys++;
                    }
                }
                LogStore.add(requireContext(), "OK", "[读取] 已读取应用 "
                        + list.size() + " 个（用户 " + (list.size() - sys) + " / 系统 " + sys + "）");
            });
        }).start();
    }

    /**
     * 关键词过滤后按「用户 / 系统」切成两栏：左栏用户应用，右栏系统应用。
     * 同一个关键词同时作用于两栏。
     */
    private void applyAppFilter() {
        if (tvAppCount == null || userAdapter == null || sysAdapter == null) {
            return;
        }
        String q = etAppFilter == null ? ""
                : etAppFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<AppEntry> users = new ArrayList<>();
        List<AppEntry> systems = new ArrayList<>();
        for (AppEntry e : allApps) {
            if (!q.isEmpty()
                    && !e.label.toLowerCase(Locale.ROOT).contains(q)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            if (e.system) {
                systems.add(e);
            } else {
                users.add(e);
            }
        }
        userAdapter.setItems(users);
        sysAdapter.setItems(systems);
        tvUserHead.setText("用户应用 " + users.size());
        tvSysHead.setText("系统应用 " + systems.size());
        tvAppCount.setText(allApps.isEmpty()
                ? "尚未读取"
                : "共 " + allApps.size() + " 个" + (q.isEmpty() ? "" : "（已过滤）"));
    }
}
