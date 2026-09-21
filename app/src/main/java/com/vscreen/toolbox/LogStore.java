package com.vscreen.toolbox;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 操作日志：追加写入内部文件，最多保留 500 条。 */
public final class LogStore {

    private static final String FILE_NAME = "toolbox.log";
    private static final int MAX_LINES = 500;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);

    private LogStore() {
    }

    public static synchronized void add(Context ctx, String level, String msg) {
        String line = FMT.format(new Date()) + " [" + level + "] " + msg;
        File f = file(ctx);
        try {
            List<String> lines = readRaw(ctx);
            lines.add(line);
            if (lines.size() > MAX_LINES) {
                lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
            }
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            try (FileOutputStream os = new FileOutputStream(f, false)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 最近的操作排在最前。 */
    public static synchronized List<String> read(Context ctx) {
        List<String> lines = readRaw(ctx);
        Collections.reverse(lines);
        return lines;
    }

    public static synchronized void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) {
            f.delete();
        }
    }

    /** 导出为可分享的文件。 */
    public static File export(Context ctx) throws IOException {
        File dir = new File(ctx.getExternalFilesDir(null), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "toolbox-log.txt");
        List<String> lines = readRaw(ctx);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        try (FileOutputStream os = new FileOutputStream(out, false)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static List<String> readRaw(Context ctx) {
        List<String> lines = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) {
            return lines;
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }
}
