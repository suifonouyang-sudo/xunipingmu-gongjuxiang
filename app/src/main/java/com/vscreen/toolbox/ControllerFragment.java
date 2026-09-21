package com.vscreen.toolbox;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;
import java.util.TreeMap;

/**
 * 显示控制页：操作目标选择、分辨率 / DPI 修改与重置、屏幕旋转、
 * 状态栏导航栏显隐、显示屏列表。
 *
 * <p>操作目标是「点一行选中」的显示屏列表——设备上可能同时存在内置屏、本工具创建的
 * overlay 虚拟屏、以及第三方（如 scrcpy）创建的 virtual 虚拟屏，自动挑一块并不合适。
 * 选中后分辨率 / DPI 走 {@code wm size|density -d N}，旋转走对应的旋转命令，
 * 都只作用于选中那一块屏。系统栏显隐走 policy_control，是全局开关，不受选择影响。
 *
 * <p>旋转命令按系统版本自动切换语法（见 {@link Displays}）：Android 12+ 是
 * {@code cmd window user-rotation -d N}；Android 11 是
 * {@code cmd window set-user-rotation lock -d N X}（注意 {@code -d} 在模式之后）。
 * 两者都能精确作用到选中那一块屏；只有 Android 10 及以下真的做不到，那时角度按钮会
 * 变灰、「横竖屏切换（对调宽高）」是可用的替代方案。
 *
 * <p><b>注意</b>：旋转屏幕会让 Activity 重建、Fragment 脱离，所有延时任务都必须
 * 先经过 {@link #postRefresh} / {@link #alive()} 判活，否则 {@code requireContext()}
 * 会抛 IllegalStateException 直接崩掉进程。
 */
public class ControllerFragment extends Fragment {

    private TextView tvWmState;
    private TextView tvList;
    private TextView tvRotation;
    private TextView tvTarget;
    private TextView tvRotForce;
    private TextView tvRotHint;
    private TextView tvDispParams;
    private TextView btnRotSwap;
    private LinearLayout lvTargets;
    private EditText etWidth;
    private EditText etHeight;
    private EditText etDpi;

    /** 当前选中的目标屏 display id，-1 表示还没选/一块都没读到。 */
    private int selectedId = -1;
    /** 最近一次扫描到的显示屏，键为 display id。 */
    private TreeMap<Integer, Displays.Vp> vps = new TreeMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_controller, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvWmState = v.findViewById(R.id.tvWmState);
        tvList = v.findViewById(R.id.tvList);
        tvRotation = v.findViewById(R.id.tvRotation);
        tvTarget = v.findViewById(R.id.tvTarget);
        tvRotForce = v.findViewById(R.id.tvRotForce);
        tvRotHint = v.findViewById(R.id.tvRotHint);
        tvDispParams = v.findViewById(R.id.tvDispParams);
        btnRotSwap = v.findViewById(R.id.btnRotSwap);
        lvTargets = v.findViewById(R.id.lvTargets);
        etWidth = v.findViewById(R.id.etWidth2);
        etHeight = v.findViewById(R.id.etHeight2);
        etDpi = v.findViewById(R.id.etDpi2);

        etDpi.setText("240");

        v.findViewById(R.id.btnTargetRefresh).setOnClickListener(view -> {
            refresh();
            toast(selectedId >= 0 ? "共 " + vps.size() + " 块显示屏，目标：display " + selectedId
                    : "未读取到显示屏");
        });

        v.findViewById(R.id.btnRefreshWm).setOnClickListener(view -> refresh());
        v.findViewById(R.id.btnApplySize).setOnClickListener(view -> applySize());
        v.findViewById(R.id.btnResetSize).setOnClickListener(view -> reset("wm size reset", "分辨率"));
        v.findViewById(R.id.btnApplyDpi).setOnClickListener(view -> applyDpi());
        v.findViewById(R.id.btnResetDpi).setOnClickListener(view -> reset("wm density reset", "DPI"));
        v.findViewById(R.id.btnHideStatus).setOnClickListener(view -> bars("immersive.status=*"));
        v.findViewById(R.id.btnHideNav).setOnClickListener(view -> bars("immersive.nav=*"));
        v.findViewById(R.id.btnShowBars).setOnClickListener(view -> bars(null));
        v.findViewById(R.id.btnList).setOnClickListener(view -> refreshList());
        v.findViewById(R.id.btnDispParams).setOnClickListener(view -> refreshDispParams());

        // 屏幕旋转：锁定角 0/90/180/270，null 表示恢复自动
        v.findViewById(R.id.btnRot0).setOnClickListener(view -> rotate(0, "0°"));
        v.findViewById(R.id.btnRot90).setOnClickListener(view -> rotate(90, "90°"));
        v.findViewById(R.id.btnRot180).setOnClickListener(view -> rotate(180, "180°"));
        v.findViewById(R.id.btnRot270).setOnClickListener(view -> rotate(270, "270°"));
        v.findViewById(R.id.btnRotReset).setOnClickListener(view -> rotate(null, "自动旋转"));
        v.findViewById(R.id.btnRotForce).setOnClickListener(view -> toggleIgnoreOrientation());
        btnRotSwap.setOnClickListener(view -> swapOrientation());

        refresh();
    }

    /** Fragment 是否仍可安全使用：旋转屏幕会导致 Activity 重建、本 Fragment 脱离。 */
    private boolean alive() {
        return isAdded() && getView() != null && getContext() != null;
    }

    /**
     * 延时刷新。旋转屏幕会让 Activity 重建，此时挂起的延时任务若直接访问
     * context / 控件就会崩，所以统一走这里先判活。
     */
    private void postRefresh(long delayMs) {
        View anchor = getView();
        if (anchor == null) {
            return;
        }
        anchor.postDelayed(() -> {
            if (alive()) {
                refresh();
            }
        }, delayMs);
    }

    public void refresh() {
        if (!alive()) {
            return;
        }
        refreshTarget();
        applyRotationUi();
        refreshWm();
        refreshRotation();
        refreshForce();
        renderDispParams();
    }

    /**
     * 详情卡：逐屏罗列<b>每一块</b>显示屏的全部参数（不只是当前选中的那块）。
     * 复用 {@link #refreshTarget()} 已经扫好的结果，不再多跑一次 dumpsys。
     */
    private void renderDispParams() {
        if (tvDispParams == null) {
            return;
        }
        tvDispParams.setText(Displays.paramReport(vps));
    }

    // ---------------------------------------------------------------- 操作目标

    /** 重新扫描显示屏、必要时修正选中项，并把列表重画一遍。 */
    private void refreshTarget() {
        Context c = getContext();
        if (c == null || lvTargets == null) {
            return;
        }
        vps = Displays.viewports(c);
        if (selectedId < 0 || !vps.containsKey(selectedId)) {
            selectedId = defaultTarget();
        }
        renderTargets();
        applyTargetUi();
    }

    /**
     * 首次进入时的默认目标：优先 overlay 虚拟屏（本工具自己建的），
     * 其次任意虚拟屏，最后才是内置屏；都由 id 最大的那块胜出。
     */
    private int defaultTarget() {
        int overlay = -1;
        int virt = -1;
        for (Displays.Vp vp : vps.values()) {
            if (vp.isOverlay()) {
                if (vp.id > overlay) {
                    overlay = vp.id;
                }
            } else if (vp.uniqueId.startsWith("virtual:") && vp.id > virt) {
                virt = vp.id;
            }
        }
        if (overlay >= 0) {
            return overlay;
        }
        if (virt >= 0) {
            return virt;
        }
        return vps.isEmpty() ? -1 : vps.firstKey();
    }

    /** 把扫描结果渲染成可点选的列表，选中项高亮。 */
    private void renderTargets() {
        Context c = getContext();
        if (lvTargets == null || c == null) {
            return;
        }
        lvTargets.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(c);
        boolean first = true;
        for (Displays.Vp vp : vps.values()) {
            View row = inflater.inflate(R.layout.item_display, lvTargets, false);
            if (!first) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);
            }
            first = false;

            TextView title = row.findViewById(R.id.tvDispTitle);
            TextView sub = row.findViewById(R.id.tvDispSub);
            TextView param = row.findViewById(R.id.tvDispParam);

            title.setText("display " + vp.id + " · " + vp.briefText());
            sub.setText(vp.uniqueId.isEmpty() ? "（无 uniqueId）" : vp.uniqueId);

            // 逐屏罗列全部参数：物理 / wm覆盖 / 应用区 / 缩放范围 / 模式 / 前台
            StringBuilder pb = new StringBuilder();
            // 「唯一ID」已单独一行显示，这里跳过它，避免重复占位
            for (String line : vp.paramLines()) {
                if (line.startsWith("类型") || line.startsWith("唯一ID")
                        || line.startsWith("逻辑尺寸")) {
                    continue;
                }
                if (pb.length() > 0) {
                    pb.append('\n');
                }
                pb.append(line);
            }
            param.setText(pb.toString());

            row.setActivated(vp.id == selectedId);
            final int id = vp.id;
            row.setOnClickListener(v -> {
                selectedId = id;
                refresh();
                toast("目标已切到 display " + id);
            });
            lvTargets.addView(row);
        }
    }

    private void applyTargetUi() {
        if (tvTarget == null || getContext() == null) {
            return;
        }
        if (selectedId < 0) {
            tvTarget.setText("目标：—（未读取到显示屏，检查 Shizuku 授权）");
            return;
        }
        Displays.Vp vp = vps.get(selectedId);
        tvTarget.setText("目标：display " + selectedId
                + (vp == null ? "" : " · " + vp.typeLabel() + " · " + vp.sizeText()));
    }

    /** 目标屏的 display id；没有可用目标时返回 -1。 */
    private int targetId() {
        return selectedId;
    }

    /**
     * 目标屏的 {@code -d N} 参数，形如 " -d 16"。没有选中目标时返回 null，
     * 调用方必须先判空——否则命令会落到默认屏上，用户以为控制了目标屏其实没有。
     */
    private String dArg() {
        int id = targetId();
        return id < 0 ? null : " -d " + id;
    }

    private String targetName() {
        return "display " + selectedId;
    }

    private void toastNoTarget() {
        toast("没有选中的显示屏，请先点上方「重新扫描显示屏」并选一块");
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    // ---------------------------------------------------------------- 分辨率 / DPI

    private void refreshWm() {
        if (tvWmState == null || getContext() == null) {
            return;
        }
        String d = dArg();
        if (d == null) {
            tvWmState.setText("未选中目标屏，请先在上方列表里选一块。");
            return;
        }
        String size = ShizukuCmd.exec(requireContext(), "wm size" + d);
        String dens = ShizukuCmd.exec(requireContext(), "wm density" + d);
        if (size == null || dens == null) {
            tvWmState.setText("读取失败（检查 Shizuku 授权）");
            return;
        }
        tvWmState.setText(size.trim() + "\n" + dens.trim());
    }

    private void applySize() {
        Integer w = parseInt(etWidth);
        Integer h = parseInt(etHeight);
        if (w == null || h == null || w < 120 || h < 120) {
            toast("请输入有效的分辨率");
            return;
        }
        String d = dArg();
        if (d == null) {
            toastNoTarget();
            return;
        }
        if (ShizukuCmd.exec(requireContext(), "wm size " + w + "x" + h + d) != null) {
            LogStore.add(requireContext(), "OK", "修改分辨率（" + targetName() + "）→ " + w + "x" + h);
            toast("分辨率已应用到" + targetName());
            postRefresh(1200);
        }
    }

    private void applyDpi() {
        Integer dpi = parseInt(etDpi);
        if (dpi == null || dpi < 60 || dpi > 1000) {
            toast("请输入有效的 dpi");
            return;
        }
        String d = dArg();
        if (d == null) {
            toastNoTarget();
            return;
        }
        if (ShizukuCmd.exec(requireContext(), "wm density " + dpi + d) != null) {
            LogStore.add(requireContext(), "OK", "修改DPI（" + targetName() + "）→ " + dpi);
            toast("DPI 已应用到" + targetName());
            refreshWm();
        }
    }

    private void reset(String cmd, String what) {
        String d = dArg();
        if (d == null) {
            toastNoTarget();
            return;
        }
        if (ShizukuCmd.exec(requireContext(), cmd + d) != null) {
            LogStore.add(requireContext(), "OK", "重置" + what + "（" + targetName() + "）完成");
            toast(what + "已重置");
            postRefresh(1200);
        }
    }

    // ---------------------------------------------------------------- 屏幕旋转

    /**
     * 设置目标屏的旋转。lockDeg 取 0/90/180/270，null 表示恢复自动旋转。
     * 具体命令语法由 {@link Displays#setRotation} 按系统版本选择。
     */
    private void rotate(Integer lockDeg, String label) {
        int id = targetId();
        if (id < 0) {
            toastNoTarget();
            return;
        }
        Context c = getContext();
        if (c == null) {
            return;
        }
        if (!Displays.canRotateDisplay(c, id)) {
            toast("本机 Android " + Build.VERSION.RELEASE
                    + " 的旋转命令不支持指定显示屏，请改用下方「横竖屏切换」");
            return;
        }
        String cmd = Displays.setRotation(c, id, lockDeg);
        if (cmd == null) {
            toast("旋转失败（检查 Shizuku 授权）");
            return;
        }
        LogStore.add(c, "OK", "屏幕旋转（" + targetName() + "）→ " + label + "  ·  $ " + cmd);
        toast(targetName() + " → " + label);
        postRefresh(1200);
    }

    /**
     * 横竖屏切换：把目标屏的宽高对调。和「旋转」是两件事——旋转让内容转向，
     * 这里是真把屏幕做成另一个长宽比；在 Android 10 及以下（旋转命令不支持 -d）时
     * 它就是横竖屏互换的唯一手段。
     */
    private void swapOrientation() {
        int id = targetId();
        if (id < 0) {
            toastNoTarget();
            return;
        }
        Context c = getContext();
        if (c == null) {
            return;
        }
        String cmd = Displays.swapOrientation(c, id);
        if (cmd == null) {
            toast("读不到该屏尺寸，切换失败");
            return;
        }
        LogStore.add(c, "OK", "横竖屏切换（" + targetName() + "）  ·  $ " + cmd);
        toast("已对调 " + targetName() + " 的宽高");
        postRefresh(1200);
    }

    /** 读取并显示目标屏当前的旋转状态。 */
    private void refreshRotation() {
        if (tvRotation == null || getContext() == null) {
            return;
        }
        int id = targetId();
        if (id < 0) {
            tvRotation.setText("当前角度：—（未选中目标屏）");
            return;
        }
        Displays.Rot r = Displays.rotation(requireContext(), id);
        String deg = r.deg >= 0 ? r.deg + "°" : "未知";
        String mode;
        if (r.mode == 0) {
            mode = "自动（跟随方向请求）";
        } else if (r.mode == 1) {
            mode = "已锁定";
        } else {
            mode = "模式未知";
        }
        tvRotation.setText("当前角度（display " + id + "）：" + deg + "   ·   " + mode);
    }

    /**
     * 旋转卡片按「本机支持程度 + 当前目标屏」切换可用状态：
     * 能指定显示屏就把角度按钮点亮；不能（Android 10 及以下）就置灰并提示改用
     * 「横竖屏切换」。只要目标是虚拟屏就保留「横竖屏切换」——它是另一件事，
     * 用来改变屏幕本身的长宽比，不只是转向。
     */
    private void applyRotationUi() {
        View root = getView();
        if (root == null || tvRotHint == null) {
            return;
        }
        int id = targetId();
        Context c = getContext();
        boolean rotOk = id >= 0 && c != null && Displays.canRotateDisplay(c, id);
        boolean swapOk = id > 0;
        int[] ids = {R.id.btnRot0, R.id.btnRot90, R.id.btnRot180, R.id.btnRot270,
                R.id.btnRotReset};
        for (int rid : ids) {
            View b = root.findViewById(rid);
            if (b != null) {
                b.setEnabled(rotOk);
                b.setAlpha(rotOk ? 1f : 0.35f);
            }
        }
        if (btnRotSwap != null) {
            btnRotSwap.setVisibility(swapOk ? View.VISIBLE : View.GONE);
        }
        if (rotOk) {
            tvRotHint.setVisibility(View.GONE);
        } else {
            tvRotHint.setVisibility(View.VISIBLE);
            tvRotHint.setText("本机 Android " + Build.VERSION.RELEASE
                    + " 的旋转命令不带 -d，无法指定显示屏。请用下方「横竖屏切换」"
                    + "把宽高对调，效果等同于横竖屏互换。");
        }
    }

    /** 目标屏上应用常自己请求横竖屏，会盖掉锁定角度；这里可强制忽略其请求。 */
    private void toggleIgnoreOrientation() {
        int id = targetId();
        if (id < 0) {
            toastNoTarget();
            return;
        }
        Context c = getContext();
        if (c == null) {
            return;
        }
        boolean on = Displays.readIgnoreOrientation(c, id) == 1;
        String cmd = Displays.setIgnoreOrientation(c, id, !on);
        if (cmd == null) {
            toast("切换失败（检查 Shizuku 授权）");
            return;
        }
        LogStore.add(c, "OK",
                "忽略应用方向请求（display " + id + "）→ " + (!on) + "  ·  $ " + cmd);
        toast("已" + (on ? "关闭" : "开启") + "：忽略应用方向请求");
        postRefresh(800);
    }

    private void refreshForce() {
        if (tvRotForce == null || getContext() == null) {
            return;
        }
        int id = targetId();
        if (id < 0) {
            tvRotForce.setText("忽略应用方向请求：—");
            return;
        }
        int st = Displays.readIgnoreOrientation(requireContext(), id);
        String s = st == 1 ? "已开启" : st == 0 ? "已关闭" : "未知";
        tvRotForce.setText("忽略应用方向请求（display " + id + "）：" + s);
    }

    // ---------------------------------------------------------------- 系统栏 / 显示屏列表

    /** policy_control 沉浸控制：null = 恢复显示。该设置在系统层是全局的，不区分显示屏。 */
    private void bars(String policy) {
        Context c = getContext();
        if (c == null) {
            return;
        }
        String cmd = policy == null
                ? "settings put global policy_control null"
                : "settings put global policy_control " + policy;
        if (ShizukuCmd.exec(c, cmd) != null) {
            LogStore.add(c, "OK", policy == null ? "系统栏已恢复显示" : "系统栏策略：" + policy);
            toast(policy == null ? "已恢复显示" : "已应用");
        }
    }

    /** 详情卡的手动刷新：重新扫描所有屏并重画列表与参数。 */
    private void refreshDispParams() {
        refreshTarget();
        renderDispParams();
        toast(vps.isEmpty() ? "未读取到显示屏" : "已读取 " + vps.size() + " 块显示屏的参数");
    }

    private void refreshList() {
        Context c = getContext();
        if (c == null || tvList == null) {
            return;
        }
        List<String> displays = Displays.list(c);
        if (displays.isEmpty()) {
            tvList.setText("未读取到显示屏（检查 Shizuku 授权）");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : displays) {
            sb.append(line).append('\n');
        }
        tvList.setText(sb.toString().trim());
    }

    private Integer parseInt(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String s) {
        Context c = getContext();
        if (c == null) {
            return;
        }
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
