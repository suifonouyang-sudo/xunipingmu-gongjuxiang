package com.vscreen.toolbox;

import android.content.Context;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 显示屏信息的统一入口。
 *
 * <p>各 Android 版本可用的旋转命令差异很大，这里做了兼容：
 * <ul>
 *   <li><b>Android 12+</b>：{@code cmd window user-rotation -d N {free|lock X}}，
 *       有查询子命令；方向忽略开关是 {@code get/set-ignore-orientation-request}。</li>
 *   <li><b>Android 11</b>：命令名是 {@code cmd window set-user-rotation}，
 *       <b>{@code -d} 放在模式之后</b>——完整语法
 *       {@code set-user-rotation [free|lock] [-d DISPLAY_ID] [rotation]}，
 *       实测 {@code set-user-rotation lock -d 10 1} 只把 display 10 转到 90°、
 *       display 0 纹丝不动。写成 {@code set-user-rotation -d 10 lock 1} 会报
 *       "lock mode needs to be either free or lock" —— 参数顺序错就会被当成模式解析。</li>
 *   <li><b>Android 11 读旋转状态</b>：没有查询子命令，但 {@code dumpsys window displays}
 *       每个 Display 段落里有 {@code mUserRotationMode=USER_ROTATION_{FREE|LOCKED}}，
 *       角度读 {@code mViewports} 的 {@code orientation=N}。</li>
 * </ul>
 *
 * <p>本类不假设「旧版一定行」：{@link #canRotateDisplay} 会先去
 * {@code cmd window -h} 的帮助里确认 {@code set-user-rotation} 那一行是否带
 * {@code -d DISPLAY_ID}（Android 10 及以下没有），不确定就返回 false，
 * 让界面上退到 {@link #swapOrientation} 的横竖屏切换。
 */
public final class Displays {

    private Displays() {
    }

    /** Android 12（API 31）起才有 {@code cmd window user-rotation} 与其查询命令。 */
    public static boolean modernRotationCmd() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    // ------------------------------------------------------------------ 显示屏枚举

    /**
     * 单个显示屏的全部参数。
     *
     * <p>尺寸有三个层次，别混：
     * <ul>
     *   <li>{@code physW/H/physDpi} —— 物理（初始）尺寸与密度，来自 window displays 的
     *       {@code init=1280x800 213dpi}；</li>
     *   <li>{@code baseW/H/baseDpi} —— {@code wm size|density -d N} 的覆盖值，来自
     *       {@code base=1000x700 213dpi}。**没有覆盖时该字段根本不出现**，所以为 -1 才表示
     *       「未覆盖」，而不是等于物理值；</li>
     *   <li>{@code logicalW/H} —— 旋转之后的逻辑尺寸，来自 {@code DisplayFrames w= h=}。</li>
     * </ul>
     * {@code appW/H} 是应用真正能用的区域（会被状态栏/导航栏吃掉一截），
     * {@code minW/H~maxW/H} 是应用可缩放范围，超出这个范围就会出现黑边。
     */
    public static final class Vp {
        public final int id;
        public final String uniqueId;
        /** 旋转角索引：0/1/2/3 对应 0°/90°/180°/270°，-1 表示未知。 */
        public int rotation = -1;
        /** 旋转之后的逻辑尺寸。 */
        public int logicalW = -1;
        public int logicalH = -1;
        /** 物理（初始）尺寸与密度。 */
        public int physW = -1;
        public int physH = -1;
        public int physDpi = -1;
        /** wm size / wm density 的覆盖值；-1 表示没有覆盖。 */
        public int baseW = -1;
        public int baseH = -1;
        public int baseDpi = -1;
        /** 应用可用区域。 */
        public int appW = -1;
        public int appH = -1;
        /** 应用可缩放范围，超出就会出现黑边。 */
        public int minW = -1;
        public int minH = -1;
        public int maxW = -1;
        public int maxH = -1;
        /** 旋转模式：0=自动 1=已锁定 -1=未知。 */
        public int rotMode = -1;
        /** 忽略应用方向请求（fixed-to-user-rotation）：0/1/-1。 */
        public int fixed = -1;
        /** 该屏上的前台应用包名；没有则为空串。 */
        public String topPkg = "";

        Vp(int id, String uniqueId) {
            this.id = id;
            this.uniqueId = uniqueId;
        }

        public boolean isVirtual() {
            return uniqueId.startsWith("overlay:") || uniqueId.startsWith("virtual:");
        }

        public boolean isOverlay() {
            return uniqueId.startsWith("overlay:");
        }

        /** 类型的中文说明。 */
        public String typeLabel() {
            if (uniqueId.startsWith("overlay:")) {
                return "OVERLAY 虚拟屏";
            }
            if (uniqueId.startsWith("virtual:")) {
                return "VIRTUAL 虚拟屏";
            }
            if (uniqueId.startsWith("local:")) {
                return "内置屏";
            }
            return "其他屏";
        }

        /** 供列表展示的简短 uniqueId（第三方虚拟屏的标识可能很长）。 */
        public String shortId() {
            return uniqueId.length() <= 30 ? uniqueId : uniqueId.substring(0, 30) + "…";
        }

        /** 形如 "1280x800"；尺寸读不到时返回 "—"。 */
        public String sizeText() {
            return logicalW > 0 && logicalH > 0 ? logicalW + "x" + logicalH : "—";
        }

        /** 旋转角的中文描述，如 "270°(r=3)"；未知返回 "未知"。 */
        public String rotationText() {
            return rotation < 0 ? "未知" : rotation * 90 + "°(r=" + rotation + ")";
        }

        /** 旋转模式：自动 / 已锁定 / 未知。 */
        public String modeText() {
            if (rotMode == 1) {
                return "已锁定";
            }
            if (rotMode == 0) {
                return "自动（跟随方向请求）";
            }
            return "未知";
        }

        /** 「忽略应用方向请求」状态文字。 */
        public String fixedText() {
            if (fixed == 1) {
                return "已开启（忽略应用方向请求）";
            }
            if (fixed == 0) {
                return "未开启";
            }
            return "未知";
        }

        /** 列表行里的一行摘要：类型 · 逻辑尺寸 · 角度 · 模式。 */
        public String briefText() {
            StringBuilder sb = new StringBuilder();
            sb.append(id == 0 ? typeLabel() + "（物理屏）" : typeLabel());
            sb.append(" · ").append(sizeText());
            sb.append(" · ").append(rotation < 0 ? "角度未知" : rotation * 90 + "°");
            if (rotMode == 1) {
                sb.append(" · 已锁定");
            } else if (rotMode == 0) {
                sb.append(" · 自动");
            }
            return sb.toString();
        }

        /**
         * 该屏的全部参数，每行一条，供详情卡逐屏罗列。
         * 键与值用空格对齐，等宽字体下呈两列。
         */
        public List<String> paramLines() {
            List<String> out = new ArrayList<>();
            out.add("类型     " + (id == 0 ? typeLabel() + "（物理屏）" : typeLabel()));
            out.add("唯一ID   " + (uniqueId.isEmpty() ? "—" : uniqueId));
            out.add("逻辑尺寸 " + sizeText() + "    旋转 " + rotationText());
            out.add("物理     " + (physW > 0
                    ? physW + "x" + physH + " @" + physDpi + "dpi" : "—"));
            // base= 只在 wm size / wm density 有覆盖时才出现
            out.add("wm覆盖   " + (baseW > 0
                    ? baseW + "x" + baseH + " @" + baseDpi + "dpi" : "无"));
            out.add("应用区域 " + (appW > 0 ? appW + "x" + appH : "—"));
            out.add("缩放范围 " + (minW > 0
                    ? minW + "x" + minH + " ~ " + maxW + "x" + maxH : "—"));
            out.add("旋转模式 " + modeText());
            out.add("固定旋转 " + fixedText());
            out.add("前台应用 " + (topPkg.isEmpty() ? "—" : topPkg));
            return out;
        }
    }

    /**
     * 解析所有显示屏，键为 displayId。
     *
     * <p>数据来自两处合并，缺一不可：
     * <ul>
     *   <li>{@code dumpsys display} 的 mViewports 段提供 displayId ↔ uniqueId 的对应，
     *       这段格式跨版本最稳定；</li>
     *   <li><b>旋转角与逻辑尺寸必须取 {@code dumpsys window displays} 里每个 Display
     *       段落的 {@code DisplayFrames w=W h=H r=R}</b>。mViewports 的
     *       {@code orientation} 在 overlay 虚拟屏（{@code overlay_display_devices}
     *       建的）上实测**恒为 0**——把屏锁到 90°/180°/270° 它都报 0，只有 logicalFrame
     *       会变。用它会得出「虚拟屏永远是 0°」的错误结论，还会让
     *       {@link #swapOrientation} 判错奇偶旋转、把宽高写反。</li>
     * </ul>
     */
    public static TreeMap<Integer, Vp> viewports(Context ctx) {
        TreeMap<Integer, Vp> map = new TreeMap<>();

        // 1) displayId → uniqueId（mViewports 段）
        Map<Integer, String> uids = new LinkedHashMap<>();
        String displayOut = ShizukuCmd.exec(ctx, "dumpsys display");
        if (displayOut != null) {
            int start = displayOut.indexOf("mViewports=");
            if (start >= 0) {
                int end = displayOut.indexOf('\n', start);
                String seg = end > 0 ? displayOut.substring(start, end) : displayOut.substring(start);
                for (String chunk : seg.split("DisplayViewport\\{")) {
                    Matcher idm = Pattern.compile("displayId=(\\d+)").matcher(chunk);
                    if (idm.find()) {
                        uids.put(parseInt(idm.group(1), -1),
                                group(chunk, "uniqueId='([^']*)'", ""));
                    }
                }
            }
        }

        // 2) 每屏的全部参数（window displays 段的 Display 段落）
        String winOut = ShizukuCmd.exec(ctx, "dumpsys window displays");
        if (winOut != null) {
            for (String section : winOut.split("Display: mDisplayId=")) {
                Matcher idm = Pattern.compile("^(\\d+)").matcher(section);
                if (!idm.find()) {
                    continue;
                }
                int id = parseInt(idm.group(1), -1);
                if (id < 0) {
                    continue;
                }
                Vp vp = new Vp(id, uids.containsKey(id) ? uids.get(id) : "");

                // DisplayFrames w=800 h=1280 r=3 —— 逻辑尺寸与旋转角，各屏都准
                Matcher fm = Pattern.compile(
                        "DisplayFrames w=(\\d+) h=(\\d+) r=(-?\\d+)").matcher(section);
                if (fm.find()) {
                    vp.logicalW = parseInt(fm.group(1), -1);
                    vp.logicalH = parseInt(fm.group(2), -1);
                    vp.rotation = parseInt(fm.group(3), -1);
                }
                // init=1280x800 213dpi base=1000x700 213dpi cur=... app=... rng=a-b
                Matcher im = Pattern.compile("init=(\\d+)x(\\d+) (\\d+)dpi").matcher(section);
                if (im.find()) {
                    vp.physW = parseInt(im.group(1), -1);
                    vp.physH = parseInt(im.group(2), -1);
                    vp.physDpi = parseInt(im.group(3), -1);
                }
                Matcher bm = Pattern.compile("base=(\\d+)x(\\d+) (\\d+)dpi").matcher(section);
                if (bm.find()) {   // 只在有 wm size/density 覆盖时出现
                    vp.baseW = parseInt(bm.group(1), -1);
                    vp.baseH = parseInt(bm.group(2), -1);
                    vp.baseDpi = parseInt(bm.group(3), -1);
                }
                Matcher am = Pattern.compile("app=(\\d+)x(\\d+)").matcher(section);
                if (am.find()) {
                    vp.appW = parseInt(am.group(1), -1);
                    vp.appH = parseInt(am.group(2), -1);
                }
                Matcher rm = Pattern.compile("rng=(\\d+)x(\\d+)-(\\d+)x(\\d+)").matcher(section);
                if (rm.find()) {
                    vp.minW = parseInt(rm.group(1), -1);
                    vp.minH = parseInt(rm.group(2), -1);
                    vp.maxW = parseInt(rm.group(3), -1);
                    vp.maxH = parseInt(rm.group(4), -1);
                }
                Matcher mm = Pattern.compile("mUserRotationMode=(USER_ROTATION_[A-Z_]+)")
                        .matcher(section);
                if (mm.find()) {
                    // LOCKED=已锁定，FREE / FULL_SENSOR / FULL_USER 都算自动
                    vp.rotMode = "USER_ROTATION_LOCKED".equals(mm.group(1)) ? 1 : 0;
                }
                Matcher xm = Pattern.compile("mFixedToUserRotation=(true|false)").matcher(section);
                if (xm.find()) {
                    vp.fixed = "true".equals(xm.group(1)) ? 1 : 0;
                }
                // mFocusedApp=ActivityRecord{ae9175c u0 com.kugou.android.lite/....MediaActivity t616}
                Matcher pm = Pattern.compile(
                        "mFocusedApp=ActivityRecord\\{[^}]*?([A-Za-z0-9_.]+)/").matcher(section);
                if (pm.find()) {
                    vp.topPkg = pm.group(1);
                }
                map.put(id, vp);
            }
        }

        // 3) 只在 mViewports 里出现、window displays 里没有的屏（少见）兜底
        for (Map.Entry<Integer, String> e : uids.entrySet()) {
            if (!map.containsKey(e.getKey())) {
                map.put(e.getKey(), new Vp(e.getKey(), e.getValue()));
            }
        }
        return map;
    }

    /** 当前 overlay_display_devices 配置原文（null = 读取失败）。 */
    public static String currentSpec(Context ctx) {
        String out = ShizukuCmd.exec(ctx, "settings get global overlay_display_devices");
        if (out == null) {
            return null;
        }
        String s = out.trim();
        return s.isEmpty() || "null".equals(s) ? "（无）" : s;
    }

    /** 所有显示屏的摘要行。 */
    public static List<String> list(Context ctx) {
        List<String> result = new ArrayList<>();
        String out = ShizukuCmd.exec(ctx, "dumpsys display");
        if (out == null) {
            return result;
        }
        Pattern p = Pattern.compile("displayId=(\\d+), uniqueId='([^']*)'");
        TreeSet<Integer> seen = new TreeSet<>();
        Matcher m = p.matcher(out);
        while (m.find()) {
            int id;
            try {
                id = Integer.parseInt(m.group(1));
            } catch (Exception e) {
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            String uid = m.group(2);
            String type;
            if (uid.startsWith("overlay:")) {
                type = "OVERLAY 虚拟屏";
            } else if (uid.startsWith("virtual:")) {
                type = "VIRTUAL 虚拟屏";
            } else if (uid.startsWith("local:")) {
                type = "内置屏";
            } else {
                type = "其他";
            }
            result.add("id=" + id + "   [" + type + "]   " + uid);
        }
        return result;
    }

    /** 当前最大的 display id（通常是最新创建的虚拟屏）。 */
    public static int maxDisplayId(Context ctx) {
        int max = -1;
        for (int id : viewports(ctx).keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max;
    }

    /**
     * 每一块显示屏的全部参数，拼成可直接展示的多行文本。
     * 每屏一段，段首是 {@code ━━ display N ━━} 分隔行。
     */
    public static String paramReport(Context ctx) {
        return paramReport(viewports(ctx));
    }

    /**
     * {@link #paramReport(Context)} 的离线版：直接给已扫描好的结果，
     * 免得界面每次刷新都把 dumpsys 再跑一遍。
     */
    public static String paramReport(TreeMap<Integer, Vp> map) {
        if (map == null || map.isEmpty()) {
            return "未读取到显示屏（检查 Shizuku 授权）";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Vp vp : map.values()) {
            if (!first) {
                sb.append('\n');
            }
            first = false;
            sb.append("━━ display ").append(vp.id).append("  ");
            sb.append(vp.isVirtual() ? "虚拟屏" : "物理/内置屏");
            sb.append(" ━━\n");
            for (String line : vp.paramLines()) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 虚拟屏 display id。优先 overlay（本工具用 overlay_display_devices 创建的），
     * 其次 virtual（第三方如 scrcpy 创建的虚拟屏）；都没有则返回 -1。
     * 同类多个时取 id 最大的那个（通常是最新创建的）。
     */
    public static int virtualDisplayId(Context ctx) {
        int overlay = -1;
        int virt = -1;
        for (Vp vp : viewports(ctx).values()) {
            if (vp.isOverlay()) {
                if (vp.id > overlay) {
                    overlay = vp.id;
                }
            } else if (vp.uniqueId.startsWith("virtual:") && vp.id > virt) {
                virt = vp.id;
            }
        }
        if (overlay >= 0 || virt >= 0) {
            return overlay >= 0 ? overlay : virt;
        }
        // 兜底：老写法，直接在 dumpsys 全文里找 uniqueId='overlay:|virtual:'
        String out = ShizukuCmd.exec(ctx, "dumpsys display");
        if (out == null) {
            return -1;
        }
        Matcher m = Pattern.compile("displayId=(\\d+), uniqueId='(overlay|virtual):").matcher(out);
        TreeSet<Integer> seen = new TreeSet<>();
        while (m.find()) {
            int id;
            try {
                id = Integer.parseInt(m.group(1));
            } catch (Exception e) {
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            if ("overlay".equals(m.group(2))) {
                if (id > overlay) {
                    overlay = id;
                }
            } else if (id > virt) {
                virt = id;
            }
        }
        return overlay >= 0 ? overlay : virt;
    }

    // ------------------------------------------------------------------ 屏幕旋转

    /** 旋转状态：mode 0=自动 1=已锁定 -1=未知；deg 为角度（度），-1 表示未知。 */
    public static final class Rot {
        public final int mode;
        public final int deg;

        Rot(int mode, int deg) {
            this.mode = mode;
            this.deg = deg;
        }
    }

    /** 读取指定屏的旋转状态。 */
    public static Rot rotation(Context ctx, int displayId) {
        if (modernRotationCmd()) {
            String out = ShizukuCmd.exec(ctx, "cmd window user-rotation -d " + displayId);
            if (out != null) {
                String s = out.trim();
                if (s.contains("free")) {
                    // 自动模式下没有锁定角，实际角度读 viewport
                    return new Rot(0, viewportDeg(ctx, displayId));
                }
                Matcher m = Pattern.compile("lock\\s+(\\d)").matcher(s);
                if (m.find()) {
                    return new Rot(1, parseInt(m.group(1), 0) * 90);
                }
            }
            return new Rot(-1, viewportDeg(ctx, displayId));
        }

        // 旧版：模式与角度都从 dumpsys window displays 读（每个 Display 段落里有
        // mUserRotationMode 和 DisplayFrames r=）。角度不读 mViewports——
        // 那里的 orientation 在 overlay 虚拟屏上恒为 0，读到的是错的。
        int mode = -1;
        int deg = -1;
        String out = ShizukuCmd.exec(ctx, "dumpsys window displays");
        if (out != null) {
            for (String section : out.split("Display: mDisplayId=")) {
                Matcher idm = Pattern.compile("^(\\d+)").matcher(section);
                if (!idm.find() || parseInt(idm.group(1), -1) != displayId) {
                    continue;
                }
                Matcher mm = Pattern.compile("mUserRotationMode=(USER_ROTATION_[A-Z_]+)")
                        .matcher(section);
                if (mm.find()) {
                    // LOCKED=已锁定，FREE / FULL_SENSOR / FULL_USER 都算自动
                    mode = "USER_ROTATION_LOCKED".equals(mm.group(1)) ? 1 : 0;
                }
                Matcher fm = Pattern.compile("DisplayFrames w=\\d+ h=\\d+ r=(-?\\d+)")
                        .matcher(section);
                if (fm.find()) {
                    int r = parseInt(fm.group(1), -1);
                    deg = r < 0 ? -1 : r * 90;
                }
                break;
            }
        }
        return new Rot(mode, deg);
    }

    /** 从 viewport 读实际旋转角度（度），未知返回 -1。 */
    private static int viewportDeg(Context ctx, int displayId) {
        Vp vp = viewports(ctx).get(displayId);
        return vp == null || vp.rotation < 0 ? -1 : vp.rotation * 90;
    }

    /** 旧版旋转命令能否指定显示屏。null = 还没探测过。 */
    private static Boolean legacyPerDisplay;

    /**
     * 本机的旋转命令能否作用到指定显示屏。
     *
     * <p>Android 12+ 恒为 true。Android 11 及以下去 {@code cmd window -h} 的帮助里
     * 看 {@code set-user-rotation} 那一行有没有 {@code -d DISPLAY_ID}
     * （R 才有，Q 没有）。读不到帮助时**不缓存**、本次返回 false，
     * 免得 Shizuku 尚未授权就把结果永久钉死。
     */
    public static boolean canRotateDisplay(Context ctx, int displayId) {
        if (modernRotationCmd()) {
            return true;
        }
        Boolean cached = legacyPerDisplay;
        if (cached != null) {
            return cached;
        }
        String out = ShizukuCmd.execLenient(ctx, "cmd window -h");
        if (out == null) {
            return false;
        }
        Boolean capable = null;
        for (String line : out.split("\n")) {
            // 用正则锚定命令名，避免匹配到 set-fix-to-user-rotation
            if (Pattern.compile("^\\s*set-user-rotation\\b").matcher(line).find()) {
                capable = line.contains("-d");
                break;
            }
        }
        if (capable == null) {
            return false;
        }
        legacyPerDisplay = capable;
        return capable;
    }

    /**
     * 设置指定屏的旋转角度。lockDeg 取 0/90/180/270，传 null 表示恢复自动旋转。
     * 返回实际执行成功的命令（便于日志展示）；失败或该屏不支持时返回 null。
     *
     * <p>旧版的参数顺序是 {@code set-user-rotation lock -d N X}——{@code -d} 必须在
     * 模式**之后**，写成 {@code -d N lock X} 会被当成模式解析并报错。
     */
    public static String setRotation(Context ctx, int displayId, Integer lockDeg) {
        if (!canRotateDisplay(ctx, displayId)) {
            return null;
        }
        if (modernRotationCmd()) {
            String cmd = "cmd window user-rotation -d " + displayId + " "
                    + (lockDeg == null ? "free" : "lock " + (lockDeg / 90));
            return ShizukuCmd.exec(ctx, cmd) != null ? cmd : null;
        }
        // 旧版：set-user-rotation [free|lock] [-d DISPLAY_ID] [rotation]
        String head = lockDeg == null ? "free" : "lock";
        String tail = lockDeg == null ? "" : " " + (lockDeg / 90);
        String cmd = "cmd window set-user-rotation " + head + " -d " + displayId + tail;
        return ShizukuCmd.exec(ctx, cmd) != null ? cmd : null;
    }

    /**
     * 换宽高：把目标屏当前的逻辑尺寸对调后写回 {@code wm size} 覆盖值。
     * 返回执行的命令，失败返回 null。
     *
     * <p>和 {@link #setRotation} 的区别：旋转是让内容转向，屏幕的逻辑尺寸只跟着转；
     * 这里是真的把屏幕做成另一个长宽比（比如把 1920x1080 的屏变成 1080x1920），
     * 适合在旧系统上、或想要一块竖屏形状的虚拟屏时用。{@code wm size} 的覆盖值
     * 是「自然方向」坐标，90°/270° 旋转下宽高是反的，所以奇数旋转时要按原始顺序写。
     */
    public static String swapOrientation(Context ctx, int displayId) {
        Vp vp = viewports(ctx).get(displayId);
        if (vp == null || vp.logicalW <= 0 || vp.logicalH <= 0) {
            return null;
        }
        boolean odd = Math.abs(vp.rotation) % 2 == 1;
        int w = odd ? vp.logicalW : vp.logicalH;
        int h = odd ? vp.logicalH : vp.logicalW;
        String cmd = "wm size " + w + "x" + h + " -d " + displayId;
        return ShizukuCmd.exec(ctx, cmd) != null ? cmd : null;
    }

    // ------------------------------------------------------------------ 方向忽略开关

    /**
     * 读取「忽略应用方向请求」：1=开启 0=关闭 -1=未知。
     * Android 12+ 走 get-ignore-orientation-request；旧版没有该命令，
     * 改读 dumpsys window displays 里每个显示屏的 mFixedToUserRotation。
     */
    public static int readIgnoreOrientation(Context ctx, int displayId) {
        if (modernRotationCmd()) {
            String out = ShizukuCmd.exec(ctx,
                    "cmd window get-ignore-orientation-request -d " + displayId);
            if (out != null) {
                if (out.contains(": true")) {
                    return 1;
                }
                if (out.contains(": false")) {
                    return 0;
                }
            }
            return -1;
        }
        String out = ShizukuCmd.exec(ctx, "dumpsys window displays");
        if (out == null) {
            return -1;
        }
        for (String section : out.split("Display: mDisplayId=")) {
            Matcher hm = Pattern.compile("^(\\d+)").matcher(section);
            if (!hm.find() || parseInt(hm.group(1), -1) != displayId) {
                continue;
            }
            Matcher fm = Pattern.compile("mFixedToUserRotation=(true|false)").matcher(section);
            if (fm.find()) {
                return "true".equals(fm.group(1)) ? 1 : 0;
            }
        }
        return -1;
    }

    /** 切换「忽略应用方向请求」，返回执行成功的命令；失败返回 null。 */
    public static String setIgnoreOrientation(Context ctx, int displayId, boolean on) {
        if (modernRotationCmd()) {
            String cmd = "cmd window set-ignore-orientation-request -d " + displayId + " " + on;
            if (ShizukuCmd.exec(ctx, cmd) != null) {
                return cmd;
            }
        }
        // Android 13 之前的等价命令叫 set-fix-to-user-rotation
        String legacy = "cmd window set-fix-to-user-rotation -d " + displayId
                + (on ? " enabled" : " disabled");
        return ShizukuCmd.exec(ctx, legacy) != null ? legacy : null;
    }

    // ------------------------------------------------------------------ 其它

    /** 解析包名的启动 Activity，返回 "pkg/activity"，失败返回 null。 */
    public static String resolveLauncher(Context ctx, String pkg) {
        String out = ShizukuCmd.exec(ctx,
                "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "
                        + pkg);
        if (out == null) {
            return null;
        }
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (s.contains("/") && s.startsWith(pkg)) {
                return s;
            }
        }
        return null;
    }

    private static String group(String text, String regex, String def) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : def;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
