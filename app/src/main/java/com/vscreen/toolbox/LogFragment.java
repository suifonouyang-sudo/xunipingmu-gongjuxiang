package com.vscreen.toolbox;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.List;

/** 日志页面：记录工具箱内的全部操作。 */
public class LogFragment extends Fragment {

    private TextView tvLog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        tvLog = v.findViewById(R.id.tvLog);
        v.findViewById(R.id.btnRefresh).setOnClickListener(view -> refresh());
        v.findViewById(R.id.btnClear).setOnClickListener(view -> {
            LogStore.clear(requireContext());
            LogStore.add(requireContext(), "INFO", "日志已清空");
            refresh();
            Toast.makeText(requireContext(), "已清空", Toast.LENGTH_SHORT).show();
        });
        v.findViewById(R.id.btnExport).setOnClickListener(view -> export());
        refresh();
    }

    public void refresh() {
        if (tvLog == null || getContext() == null) {
            return;
        }
        List<String> lines = LogStore.read(requireContext());
        if (lines.isEmpty()) {
            tvLog.setText(getString(R.string.log_empty));
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int start = sb.length();
            sb.append(line);
            if (i < lines.size() - 1) {
                sb.append('\n');
            }
            int color = colorOf(line);
            sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvLog.setText(sb);
    }

    private int colorOf(String line) {
        int color;
        if (line.contains("[OK]")) {
            color = 0xFF16A34A;
        } else if (line.contains("[ERR]")) {
            color = 0xFFDC2626;
        } else if (line.contains("[WARN]")) {
            color = 0xFFB45309;
        } else {
            color = 0xFF374151;
        }
        return (int) (color & 0xFFFFFFFFL) | 0;
    }

    private void export() {
        try {
            File f = LogStore.export(requireContext());
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "导出日志"));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
