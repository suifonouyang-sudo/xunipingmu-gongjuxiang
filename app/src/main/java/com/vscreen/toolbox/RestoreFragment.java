package com.vscreen.toolbox;

import android.app.AlertDialog;
import android.content.Context;
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

/**
 * 应用恢复页：读取备份记录 → 恢复 / 删除。备份在独立的[应用备份]页。
 *
 * <p>能不能连带恢复数据，取决于<b>这台设备当前有没有 root</b>，
 * 而不是当初备份时用的什么通道：
 * <ul>
 *   <li>有 root → 安装包 + {@code /data/data} 数据一起回，解包后按应用 uid 还属主。</li>
 *   <li>无 root → 只装回安装包。数据目录对 uid 2000 不可读，写也不行；
 *       含数据的备份会明确提示「数据部分将跳过」，不让用户误以为全恢复了。</li>
 * </ul>
 */
public class RestoreFragment extends Fragment {

    private EditText etRestoreDir;
    private TextView tvRestoreMode;
    private TextView tvRestoreCount;
    private TextView tvRestoreResult;
    private TextView btnRestoreOnlyData;
    private ListView lvRestores;

    private RecordAdapter adapter;
    private final List<AppBackup.Backup> all = new ArrayList<>();
    /** 只列含数据的备份。 */
    private boolean onlyData;

    private RootShell.Probe probe;
    private boolean probing;
    private boolean busy;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_restore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        etRestoreDir = v.findViewById(R.id.etRestoreDir);
        tvRestoreMode = v.findViewById(R.id.tvRestoreMode);
        tvRestoreCount = v.findViewById(R.id.tvRestoreCount);
        tvRestoreResult = v.findViewById(R.id.tvRestoreResult);
        btnRestoreOnlyData = v.findViewById(R.id.btnRestoreOnlyData);
        lvRestores = v.findViewById(R.id.lvRestores);

        adapter = new RecordAdapter();
        lvRestores.setAdapter(adapter);
        lvRestores.setOnItemClickListener((p, row, pos, id) -> showDetail(adapter.getItem(pos)));

        v.findViewById(R.id.btnRestoreRefresh).setOnClickListener(x -> load());
        v.findViewById(R.id.btnRestoreRetest).setOnClickListener(x -> {
            RootShell.clearCache();
            probe = null;
            probeRoot();
        });
        btnRestoreOnlyData.setOnClickListener(x -> {
            onlyData = !onlyData;
            btnRestoreOnlyData.setSelected(onlyData);
            applyFilter();
        });

        etRestoreDir.setText(Prefs.backupDir(requireContext()));
        etRestoreDir.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (getContext() != null) {
                    Prefs.setBackupDir(requireContext(), currentDir());
                }
            }
        });
        etRestoreDir.setOnLongClickListener(x -> {
            etRestoreDir.setText(AppBackup.DEFAULT_DIR);
            toast("已恢复默认目录");
            load();
            return true;
        });

        btnRestoreOnlyData.setSelected(false);
        probeRoot();
    }

    /** 切回本页时重读一次：备份页可能刚备完新内容。 */
    public void refresh() {
        if (tvRestoreMode == null || getContext() == null) {
            return;
        }
        if (probe == null && !probing) {
            probeRoot();
        } else if (!busy) {
            load();
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
        tvRestoreMode.setText("正在检测 root 通道…");
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
                load();
            });
        }, "probe-root").start();
    }

    private void renderMode() {
        if (tvRestoreMode == null || getContext() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (probe == null) {
            sb.append("通道：检测中…");
        } else if (hasRoot()) {
            sb.append("通道：root（").append(probe.manager == null ? "已授权" : probe.manager)
                    .append("）· 可恢复 安装包+数据 · Shizuku ")
                    .append(ShizukuCmd.isReady() ? "已授权" : "未授权");
        } else {
            sb.append("通道：ADB / Shizuku（uid 2000）· 仅安装包 · Shizuku ")
                    .append(ShizukuCmd.isReady() ? "已授权" : "未授权")
                    .append("\n恢复数据需 root：").append(probe.error == null ? "未获得 root" : probe.error);
        }
        tvRestoreMode.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 读取记录

    private void load() {
        if (getContext() == null || busy) {
            return;
        }
        if (!hasRoot() && !ShizukuCmd.isReady()) {
            tvRestoreResult.setText("无法读取：既没有 root，Shizuku 也未授权。\n"
                    + "请到[授权检查]页完成 Shizuku 授权。");
            tvRestoreCount.setText("备份记录（需授权）");
            return;
        }
        final String dir = currentDir();
        final boolean root = hasRoot();
        final Context ctx = requireContext().getApplicationContext();
        tvRestoreCount.setText("备份记录（读取中…）");
        new Thread(() -> {
            List<AppBackup.Backup> list = AppBackup.listBackups(ctx, dir, root);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                all.clear();
                all.addAll(list);
                applyFilter();
                LogStore.add(requireContext(), "OK", "[恢复] 读取到 " + all.size() + " 条备份（" + dir + "）");
            });
        }, "list-backups").start();
    }

    private void applyFilter() {
        if (adapter == null || getContext() == null) {
            return;
        }
        List<AppBackup.Backup> shown = new ArrayList<>();
        for (AppBackup.Backup b : all) {
            if (onlyData && !b.withData) {
                continue;
            }
            shown.add(b);
        }
        adapter.setItems(shown);
        long total = 0;
        for (AppBackup.Backup b : shown) {
            total += b.size;
        }
        tvRestoreCount.setText(shown.isEmpty() ? "备份记录：无"
                : "备份记录 " + shown.size() + " 条 · " + AppBackup.humanSize(total));
        if (all.isEmpty()) {
            tvRestoreResult.setText(currentDir() + "\n下没有找到备份记录。"
                    + "\n确认目录正确，或先到[应用备份]页备一份。");
        } else {
            // 结果区要跟着刷新：否则上一轮的「没有找到备份记录」会一直赖在这儿，
            // 看起来像列表读出来了、页面却仍然报错。
            tvRestoreResult.setText("源目录：" + currentDir()
                    + "\n点一行看详情，或用右侧[恢复]按钮直接装回。");
        }
    }

    // ------------------------------------------------------------------ 详情 / 恢复 / 删除

    private void showDetail(AppBackup.Backup b) {
        if (getContext() == null || b == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("应用：").append(b.title()).append('\n');
        sb.append("包名：").append(b.pkg).append('\n');
        if (b.versionCode >= 0) {
            sb.append("版本：v").append(b.versionName).append(" (").append(b.versionCode).append(")\n");
        }
        sb.append("备份时间：").append(b.timeText()).append('\n');
        sb.append("体积：").append(b.sizeText())
                .append("（").append(b.fileCount).append(" 个安装包文件）\n");
        sb.append("内容：").append(b.withData ? "安装包 + 应用数据" : "仅安装包").append('\n');
        sb.append("通道：").append(b.channel).append('\n');
        sb.append("目录：").append(b.dir).append('\n');
        if (b.withData && !hasRoot()) {
            sb.append("\n注意：这条备份含应用数据，但当前没有 root，\n"
                    + "恢复时只会装回安装包，数据部分会被跳过。");
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("备份详情")
                .setMessage(sb.toString())
                .setPositiveButton("恢复", (d, w) -> confirmRestore(b))
                .setNeutralButton("删除", (d, w) -> confirmDelete(b))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void confirmRestore(AppBackup.Backup b) {
        if (getContext() == null) {
            return;
        }
        if (!hasRoot() && !ShizukuCmd.isReady()) {
            tvRestoreResult.setText("无法恢复：既没有 root，Shizuku 也未授权。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("将恢复 ").append(b.title()).append('\n');
        sb.append("版本：v").append(b.versionName).append(" (").append(b.versionCode).append(")\n");
        sb.append("内容：").append(b.withData ? "安装包 + 应用数据" : "仅安装包").append('\n');
        if (b.withData) {
            sb.append(hasRoot()
                    ? "\n会先装回安装包，再覆盖 /data/data 下的数据（同名文件将被替换）。"
                    : "\n当前无 root，数据部分不会恢复。");
        }
        sb.append("\n恢复前会强制停止该应用。");
        new AlertDialog.Builder(requireContext())
                .setTitle("确认恢复？")
                .setMessage(sb.toString())
                .setPositiveButton("恢复", (d, w) -> runRestore(b))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runRestore(AppBackup.Backup b) {
        if (getContext() == null || busy) {
            return;
        }
        final boolean root = hasRoot();
        final Context ctx = requireContext().getApplicationContext();
        busy = true;
        tvRestoreResult.setText("正在恢复 " + b.title() + " …\n（大应用请耐心等待）");
        new Thread(() -> {
            AppBackup.Result r = AppBackup.restore(ctx, b, root);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                busy = false;
                if (getContext() == null) {
                    return;
                }
                tvRestoreResult.setText((r.ok ? "✓ " : "✗ ") + r.msg
                        + "\n── 输出 ──\n" + trim(r.detail));
                LogStore.add(requireContext(), r.ok ? "OK" : "ERR", "[恢复] " + b.title()
                        + (r.ok ? " 成功" : " 失败：" + r.msg));
                toast(r.ok ? "恢复完成" : "恢复失败，见结果区");
            });
        }, "restore").start();
    }

    private void confirmDelete(AppBackup.Backup b) {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("删除这条备份？")
                .setMessage(b.title() + "\n" + b.dir
                        + "\n\n将从设备存储中删除，不可撤销。")
                .setPositiveButton("删除", (d, w) -> runDelete(b))
                .setNegativeButton("取消", null)
                .show();
    }

    private void runDelete(AppBackup.Backup b) {
        if (getContext() == null) {
            return;
        }
        final boolean root = hasRoot();
        final Context ctx = requireContext().getApplicationContext();
        new Thread(() -> {
            AppBackup.Result r = AppBackup.delete(ctx, b, root);
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (getContext() == null) {
                    return;
                }
                tvRestoreResult.setText((r.ok ? "✓ " : "✗ ") + r.msg);
                toast(r.ok ? "已删除" : "删除失败");
                load();
            });
        }, "del-backup").start();
    }

    private String currentDir() {
        String s = etRestoreDir == null ? "" : etRestoreDir.getText().toString();
        String p = s.trim().replaceAll("[^A-Za-z0-9_./\\-]", "");
        if (!p.startsWith("/")) {
            p = AppBackup.DEFAULT_DIR;
        }
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** pm / tar 失败时输出可能有十几行，只留前几行，完整内容在日志页。 */
    private String trim(String s) {
        if (s == null || s.isEmpty()) {
            return "（无输出）";
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

    // ------------------------------------------------------------------ 适配器

    private class RecordAdapter extends BaseAdapter {
        private final List<AppBackup.Backup> items = new ArrayList<>();

        void setItems(List<AppBackup.Backup> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppBackup.Backup getItem(int position) {
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
                        .inflate(R.layout.item_backup_record, parent, false);
            }
            AppBackup.Backup b = items.get(position);
            TextView name = row.findViewById(R.id.tvBkName);
            TextView meta = row.findViewById(R.id.tvBkMeta);
            TextView tag = row.findViewById(R.id.tvBkTag);
            TextView btnR = row.findViewById(R.id.btnBkRestore);
            TextView btnD = row.findViewById(R.id.btnBkDelete);

            name.setText(b.title());
            meta.setText(b.pkg + " · v" + b.versionName + " (" + b.versionCode + ")");
            tag.setText(b.timeText() + " · " + b.sizeText() + " · "
                    + (b.withData ? "含数据" : "仅安装包") + " · " + b.channel);
            // 无 root 时含数据的备份只能恢复一半，按钮弱化并在确认框里讲清楚
            if (b.withData && !hasRoot()) {
                btnR.setBackgroundResource(R.drawable.bg_btn_outline);
                btnR.setTextColor(parent.getContext().getColor(R.color.text_main));
                btnR.setText("恢复(仅包)");
            } else {
                btnR.setBackgroundResource(R.drawable.bg_btn_primary);
                btnR.setTextColor(0xFFFFFFFF);
                btnR.setText("恢复");
            }
            btnR.setOnClickListener(x -> confirmRestore(b));
            btnD.setOnClickListener(x -> confirmDelete(b));
            return row;
        }
    }
}
