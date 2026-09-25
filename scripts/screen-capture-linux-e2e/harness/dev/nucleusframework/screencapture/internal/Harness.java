package dev.nucleusframework.screencapture.internal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** E2E + torture harness for libnucleus_screencapture.so. Exits non-zero on the first failed check. */
public final class Harness {
    static int failures = 0;
    static int depth = 24;
    static String xtool;
    // Solid window placed by the driver script: x, y, w, h, rgb, xid
    static int wx, wy, ww, wh, wrgb;
    static long wid;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static int pattern(int x, int y) {
        int r = x & 255, g = y & 255, b = ((x >> 8) * 64 + (y >> 8) * 16 + ((x + y) & 15)) & 255;
        return (r << 16) | (g << 8) | b;
    }

    static int expand(int v, int bits) {
        if (bits >= 8) return v;
        // Bit replication, as xwd / ImageMagick / pixman widen a channel.
        int q = v >> (8 - bits), out = 0, filled = 0;
        while (filled < 8) { out = (out << bits) | q; filled += bits; }
        return out >> (filled - 8);
    }

    static int quantize(int rgb) {
        if (depth == 24) return rgb | 0xFF000000;
        int r = expand((rgb >> 16) & 255, 5), g = expand((rgb >> 8) & 255, 6), b = expand(rgb & 255, 5);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** What the root window shows at (x, y). */
    static int expected(int x, int y) {
        if (wid != 0 && x >= wx && x < wx + ww && y >= wy && y < wy + wh) return quantize(wrgb);
        return quantize(pattern(x, y));
    }

    static List<DisplayCollector.D> displays() {
        DisplayCollector c = new DisplayCollector();
        String[] m = new String[1];
        int st = NativeScreenCapture.nativeListDisplays(c, m);
        check(st == 0, "listDisplays status " + st + " " + m[0]);
        return c.list;
    }

    /** Checks a capture of display d at region (clipped) against the oracle; returns mismatches. */
    static long verify(DisplayCollector.D d, int rx, int ry, int[] px, int w, int h) {
        long bad = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (px[y * w + x] != expected(d.x + rx + x, d.y + ry + y)) bad++;
        return bad;
    }

    static int[] clip(DisplayCollector.D d, long rx, long ry, long rw, long rh) {
        long l = Math.max(0, rx), t = Math.max(0, ry), r = Math.min(d.w, rx + rw), b = Math.min(d.h, ry + rh);
        if (r <= l || b <= t) return null;
        return new int[] {(int) l, (int) t, (int) (r - l), (int) (b - t)};
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        p.waitFor();
    }

    static long rssKb() throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status")))
            if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("\\D", ""));
        return -1;
    }

    static long fds() throws Exception {
        try (var s = Files.list(Path.of("/proc/self/fd"))) {
            return s.count();
        }
    }

    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("lib"));
        String mode = args[0];
        switch (mode) {
            case "x11": x11(args); break;
            case "nodisplay": noDisplay(); break;
            case "portal": portal(args); break;
            case "portal-missing": portalMissing(); break;
            case "xkill": xkill(args); break;
            case "window-only": windowOnly(args); break;
            case "smoke8": smoke8(args); break;
            default: throw new IllegalArgumentException(mode);
        }
        System.out.println(failures == 0 ? "RESULT: PASS " + mode : "RESULT: FAIL " + mode + " (" + failures + ")");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- X11

    static void x11(String[] args) throws Exception {
        depth = Integer.parseInt(args[1]);
        xtool = args[2];
        String[] win = args[3].split(",");
        wx = Integer.parseInt(win[0]); wy = Integer.parseInt(win[1]); ww = Integer.parseInt(win[2]); wh = Integer.parseInt(win[3]);
        wrgb = Integer.parseInt(win[4], 16); wid = Long.parseLong(win[5]);
        long deadWindow = Long.parseLong(args[4]);
        boolean expectMonitors = args.length > 5 && args[5].equals("monitors");
        int iterations = Integer.parseInt(System.getProperty("iterations", "5000"));
        int threads = Integer.parseInt(System.getProperty("threads", "8"));

        check(NativeScreenCapture.nativeBackend() == 4, "backend is X11");
        check(NativeScreenCapture.nativePermissionStatus() == 3, "permission NOT_REQUIRED");
        check(NativeScreenCapture.nativeRequestPermission() == 3, "request NOT_REQUIRED");

        List<DisplayCollector.D> ds = displays();
        System.out.println("displays: " + ds);
        check(!ds.isEmpty(), "at least one display");
        check(ds.stream().filter(d -> d.primary).count() == 1, "exactly one primary");
        if (expectMonitors) {
            check(ds.size() == 3, "RandR monitors: screen + LEFT + RIGHT");
            check(ds.stream().anyMatch(d -> d.id.equals("LEFT") && d.x == 0 && !d.primary), "LEFT monitor");
            check(ds.stream().anyMatch(d -> d.id.equals("RIGHT") && d.x > 0 && d.primary), "RIGHT primary monitor");
        }

        // Full capture of every display, pixel-exact against the oracle.
        for (DisplayCollector.D d : ds) {
            int[] res = new int[3]; String[] m = new String[1];
            int[] px = NativeScreenCapture.nativeCaptureDisplay(d.id, 0, 0, 0, 0, false, res, m);
            check(px != null && res[0] == 0 && res[1] == d.w && res[2] == d.h, "full capture " + d.id + " " + res[0] + " " + m[0]);
            if (px != null) {
                long bad = verify(d, 0, 0, px, res[1], res[2]);
                System.out.println("full " + d.id + " " + res[1] + "x" + res[2] + " mismatches=" + bad);
                check(bad == 0, "full capture exact " + d.id + " bad=" + bad);
            }
        }

        // Independent oracle: xwd of the root window (depth 24 only, needs ImageMagick).
        xwdCrossCheck(ds);

        // Unknown display id.
        {
            int[] res = new int[3]; String[] m = new String[1];
            int[] px = NativeScreenCapture.nativeCaptureDisplay("NOPE-1", 0, 0, 0, 0, false, res, m);
            check(px == null && res[0] == 3, "unknown display -> 3, got " + res[0]);
            px = NativeScreenCapture.nativeCaptureDisplay("", 0, 0, 0, 0, false, res, m);
            check(px == null && res[0] == 3, "empty display id -> 3, got " + res[0]);
        }

        // Edge regions.
        DisplayCollector.D d0 = ds.get(0);
        long[][] edges = {
            {-100, -100, 200, 200}, {d0.w - 10, d0.h - 10, 1000, 1000}, {d0.w, 0, 10, 10}, {0, d0.h, 10, 10},
            {-50, -50, 10, 10}, {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
            {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, {0, 0, 1, 1},
            {-1, -1, Integer.MAX_VALUE, Integer.MAX_VALUE}, {d0.w - 1, d0.h - 1, 1, 1}, {5, 5, -3, 7},
        };
        for (long[] e : edges) {
            int[] res = new int[3]; String[] m = new String[1];
            int[] px = NativeScreenCapture.nativeCaptureDisplay(d0.id, (int) e[0], (int) e[1], (int) e[2], (int) e[3], false, res, m);
            int[] c = (e[2] <= 0 || e[3] <= 0) ? new int[] {0, 0, d0.w, d0.h} : clip(d0, e[0], e[1], e[2], e[3]);
            if (c == null) {
                check(px == null && res[0] == 8, "edge " + java.util.Arrays.toString(e) + " -> INVALID_REGION, got " + res[0]);
            } else {
                check(px != null && res[0] == 0 && res[1] == c[2] && res[2] == c[3], "edge " + java.util.Arrays.toString(e) + " size " + res[1] + "x" + res[2] + " st " + res[0]);
                if (px != null) check(verify(d0, c[0], c[1], px, res[1], res[2]) == 0, "edge exact " + java.util.Arrays.toString(e));
            }
        }

        // Cursor.
        cursorCheck(d0);

        // Timing: full 1080p (or whatever the first display is).
        {
            int[] res = new int[3]; String[] m = new String[1];
            for (int i = 0; i < 5; i++) NativeScreenCapture.nativeCaptureDisplay(d0.id, 0, 0, 0, 0, false, res, m);
            long t0 = System.nanoTime();
            int n = 40;
            for (int i = 0; i < n; i++) NativeScreenCapture.nativeCaptureDisplay(d0.id, 0, 0, 0, 0, false, res, m);
            System.out.printf("timing: %dx%d full capture avg %.2f ms%n", res[1], res[2], (System.nanoTime() - t0) / 1e6 / n);
            t0 = System.nanoTime();
            for (int i = 0; i < n; i++) NativeScreenCapture.nativeCaptureDisplay(d0.id, 10, 10, 64, 64, false, res, m);
            System.out.printf("timing: 64x64 region avg %.2f ms%n", (System.nanoTime() - t0) / 1e6 / n);
        }

        // Windows.
        windowChecks(deadWindow);

        // Callback that throws must not crash nor leak an exception.
        {
            DisplayCollector c = new DisplayCollector();
            c.throwOnAdd = true;
            String[] m = new String[1];
            int st = NativeScreenCapture.nativeListDisplays(c, m);
            check(st == 6, "throwing collector -> FAILED, got " + st + " " + m[0]);
            check(dev.nucleusframework.core.runtime.JniExceptionReporter.reported == 1, "collector exception reported once");
        }

        torture(ds, deadWindow, iterations, threads);
    }

    static void xwdCrossCheck(List<DisplayCollector.D> ds) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "xwd -root -silent | convert xwd:- rgb:-").start();
        byte[] rgb;
        try (InputStream in = p.getInputStream()) { rgb = in.readAllBytes(); }
        p.waitFor();
        // The root "display" = union; capture each display and compare its slice.
        int rootW = 0, rootH = 0;
        for (DisplayCollector.D d : ds) { rootW = Math.max(rootW, d.x + d.w); rootH = Math.max(rootH, d.y + d.h); }
        if (rgb.length != (long) rootW * rootH * 3) {
            System.out.println("xwd cross-check skipped: got " + rgb.length + " bytes for " + rootW + "x" + rootH);
            return;
        }
        for (DisplayCollector.D d : ds) {
            int[] res = new int[3]; String[] m = new String[1];
            int[] px = NativeScreenCapture.nativeCaptureDisplay(d.id, 0, 0, 0, 0, false, res, m);
            long bad = 0;
            for (int y = 0; y < res[2]; y++)
                for (int x = 0; x < res[1]; x++) {
                    int o = ((d.y + y) * rootW + d.x + x) * 3;
                    int v = 0xFF000000 | ((rgb[o] & 255) << 16) | ((rgb[o + 1] & 255) << 8) | (rgb[o + 2] & 255);
                    if (px[y * res[1] + x] != v) bad++;
                }
            System.out.println("xwd cross-check " + d.id + ": mismatches=" + bad + " of " + ((long) res[1] * res[2]));
            check(bad == 0, "xwd cross-check " + d.id);
        }
    }

    static void cursorCheck(DisplayCollector.D d) throws Exception {
        int cx = d.x + 300, cy = d.y + 200;
        run(xtool, "warp", String.valueOf(cx), String.valueOf(cy));
        int[] res = new int[3]; String[] m = new String[1];
        int[] with = NativeScreenCapture.nativeCaptureDisplay(d.id, 200, 100, 200, 200, true, res, m);
        check(with != null && res[0] == 0, "cursor capture ok");
        int[] without = NativeScreenCapture.nativeCaptureDisplay(d.id, 200, 100, 200, 200, false, res, m);
        check(without != null && verify(d, 200, 100, without, 200, 200) == 0, "no-cursor capture exact");
        if (with == null) return;
        long diff = 0, outside = 0;
        for (int y = 0; y < 200; y++)
            for (int x = 0; x < 200; x++)
                if (with[y * 200 + x] != without[y * 200 + x]) {
                    diff++;
                    int ax = d.x + 200 + x, ay = d.y + 100 + y;
                    if (Math.abs(ax - cx) > 64 || Math.abs(ay - cy) > 64) outside++;
                }
        System.out.println("cursor: " + diff + " pixels changed, " + outside + " outside the hotspot box");
        check(diff > 0, "cursor drawn");
        check(outside == 0, "cursor drawn at the pointer");
        // Cursor half off the region's left/top edge must not crash.
        run(xtool, "warp", String.valueOf(d.x + 2), String.valueOf(d.y + 2));
        int[] edge = NativeScreenCapture.nativeCaptureDisplay(d.id, 0, 0, 50, 50, true, res, m);
        check(edge != null, "cursor at display edge");
    }

    static void windowChecks(long deadWindow) {
        int[] res = new int[3]; String[] m = new String[1];
        int[] px = NativeScreenCapture.nativeCaptureWindow(wid, false, res, m);
        check(px != null && res[0] == 0 && res[1] == ww && res[2] == wh, "window capture size " + res[1] + "x" + res[2] + " st " + res[0] + " " + m[0]);
        if (px != null) {
            long bad = 0;
            for (int v : px) if (v != quantize(wrgb)) bad++;
            System.out.println("window " + wid + ": " + res[1] + "x" + res[2] + " mismatches=" + bad);
            if (Boolean.getBoolean("allowOccluded")) System.out.println("(no compositor: covered pixels are the occluder's, as documented)");
            else check(bad == 0, "window capture exact");
        }
        long[] invalid = {0, -1, deadWindow, 0x1FFFFFFFFL, 12345, 0x7FFFFFFF, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long id : invalid) {
            m[0] = null;
            px = NativeScreenCapture.nativeCaptureWindow(id, true, res, m);
            check(px == null && res[0] == 4, "invalid window " + id + " -> 4, got " + res[0] + " " + m[0]);
        }
    }

    static void torture(List<DisplayCollector.D> ds, long deadWindow, int iterations, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong ops = new AtomicLong(), verified = new AtomicLong(), errors = new AtomicLong();
        int phases = Integer.getInteger("phases", 3);
        long[] rss = new long[phases], fd = new long[phases];
        Runnable round = () -> {};
        for (int phase = 0; phase < phases; phase++) {
            int n = phase == 0 ? Math.max(200, iterations / 2) : iterations;
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int seed = phase * 1000 + t;
                final int per = n / threads;
                fs.add(pool.submit(() -> {
                    Random rnd = new Random(seed);
                    int[] res = new int[3]; String[] m = new String[1];
                    for (int i = 0; i < per; i++) {
                        ops.incrementAndGet();
                        int op = rnd.nextInt(10);
                        DisplayCollector.D d = ds.get(rnd.nextInt(ds.size()));
                        if (op == 0) {
                            DisplayCollector c = new DisplayCollector(); String[] mm = new String[1];
                            if (NativeScreenCapture.nativeListDisplays(c, mm) != 0 || c.list.size() != ds.size()) errors.incrementAndGet();
                        } else if (op == 1) {
                            int[] px = NativeScreenCapture.nativeCaptureWindow(rnd.nextBoolean() ? wid : deadWindow, rnd.nextBoolean(), res, m);
                            if (px == null && res[0] != 4) errors.incrementAndGet();
                        } else if (op == 2) {
                            NativeScreenCapture.nativeCaptureDisplay("missing-" + i, 0, 0, 0, 0, false, res, m);
                            if (res[0] != 3) errors.incrementAndGet();
                        } else {
                            int rx = rnd.nextInt(d.w + 400) - 200, ry = rnd.nextInt(d.h + 400) - 200;
                            int rw = 1 + rnd.nextInt(op == 9 ? d.w + 400 : 256), rh = 1 + rnd.nextInt(op == 9 ? d.h + 400 : 256);
                            int[] px = NativeScreenCapture.nativeCaptureDisplay(d.id, rx, ry, rw, rh, false, res, m);
                            int[] c = clip(d, rx, ry, rw, rh);
                            if (c == null) {
                                if (px != null || res[0] != 8) errors.incrementAndGet();
                            } else if (px == null || res[1] != c[2] || res[2] != c[3] || verify(d, c[0], c[1], px, c[2], c[3]) != 0) {
                                errors.incrementAndGet();
                                System.out.println("torture mismatch " + d.id + " region " + rx + "," + ry + " " + rw + "x" + rh + " st=" + res[0] + " " + m[0]);
                            } else {
                                verified.incrementAndGet();
                            }
                        }
                    }
                }));
            }
            for (Future<?> f : fs) f.get(10, TimeUnit.MINUTES);
            System.gc();
            Thread.sleep(300);
            rss[phase] = rssKb();
            fd[phase] = fds();
            System.out.println("torture phase " + phase + ": ops=" + ops.get() + " verified=" + verified.get() + " errors=" + errors.get() + " rss=" + rss[phase] + "kB fds=" + fd[phase]);
        }
        pool.shutdown();
        check(errors.get() == 0, "torture errors " + errors.get());
        int a = phases - 2, b = phases - 1;
        check(fd[b] <= fd[a], "fd leak " + fd[a] + " -> " + fd[b]);
        System.out.println("torture rss growth between the last equal phases: " + (rss[b] - rss[a]) + " kB");
        check(rss[b] - rss[a] < 16 * 1024, "rss growth " + (rss[b] - rss[a]) + " kB");
    }

    static void parseWindow(String spec) {
        String[] win = spec.split(",");
        wx = Integer.parseInt(win[0]); wy = Integer.parseInt(win[1]); ww = Integer.parseInt(win[2]); wh = Integer.parseInt(win[3]);
        wrgb = Integer.parseInt(win[4], 16); wid = Long.parseLong(win[5]);
    }

    /** Window capture only (e.g. with an occluding window and a composite redirect). */
    static void windowOnly(String[] args) {
        parseWindow(args[1]);
        windowChecks(Long.parseLong(args[2]));
    }

    /** 8-bit PseudoColor: no crash, colormap resolved. */
    static void smoke8(String[] args) {
        parseWindow(args[1]);
        List<DisplayCollector.D> ds = displays();
        int[] res = new int[3]; String[] m = new String[1];
        int[] px = NativeScreenCapture.nativeCaptureDisplay(ds.get(0).id, wx, wy, ww, wh, false, res, m);
        check(px != null && res[0] == 0, "8-bit display capture " + res[0] + " " + m[0]);
        if (px != null) {
            long bad = 0;
            for (int v : px) if (v != (0xFF000000 | wrgb)) bad++;
            System.out.println("8-bit region over the solid window: mismatches=" + bad + " sample=" + Integer.toHexString(px[0]));
            check(bad == 0, "8-bit colormap lookup exact");
        }
        windowChecks(Long.parseLong(args[2]));
    }

    // ---------------------------------------------------------------- No X server

    static void noDisplay() {
        check(NativeScreenCapture.nativeBackend() == 0, "backend NONE without DISPLAY");
        DisplayCollector c = new DisplayCollector(); String[] m = new String[1];
        check(NativeScreenCapture.nativeListDisplays(c, m) == 1, "list -> UNSUPPORTED: " + m[0]);
        int[] res = new int[3];
        check(NativeScreenCapture.nativeCaptureDisplay("screen", 0, 0, 0, 0, false, res, m) == null && res[0] == 1, "capture -> UNSUPPORTED " + res[0]);
        check(NativeScreenCapture.nativeCaptureWindow(1234, false, res, m) == null && res[0] == 1, "window -> UNSUPPORTED " + res[0]);
    }

    // X server killed while captures run: must not exit the JVM.
    static void xkill(String[] args) throws Exception {
        int[] res = new int[3]; String[] m = new String[1];
        long ok = 0, failed = 0;
        long end = System.currentTimeMillis() + Long.parseLong(args[1]);
        while (System.currentTimeMillis() < end) {
            int[] px = NativeScreenCapture.nativeCaptureDisplay("screen", 0, 0, 0, 0, true, res, m);
            if (px != null) ok++; else failed++;
        }
        System.out.println("xkill: ok=" + ok + " failed=" + failed + " last=" + res[0] + " " + m[0]);
        check(ok > 0 && failed > 0, "captures before and after the server died");
    }

    // ---------------------------------------------------------------- Portal

    static void setMode(String mode) throws Exception {
        Files.writeString(Path.of(System.getProperty("portalMode")), mode);
    }

    static int[] shot(int timeout, int[] res, String[] m) {
        return NativeScreenCapture.nativePortalScreenshot(false, timeout, res, m);
    }

    static void portal(String[] args) throws Exception {
        Path shots = Path.of(System.getProperty("portalDir"));
        int[] res = new int[3]; String[] m = new String[1];

        setMode("ok");
        int[] px = shot(5000, res, m);
        check(px != null && res[0] == 0 && res[1] == 640 && res[2] == 480, "portal ok " + res[0] + " " + m[0]);
        if (px != null) {
            long bad = 0;
            for (int y = 0; y < 480; y++) for (int x = 0; x < 640; x++) if (px[y * 640 + x] != (0xFF000000 | pattern(x, y))) bad++;
            System.out.println("portal ok: 640x480 mismatches=" + bad);
            check(bad == 0, "portal pixels exact");
        }
        try (var s = Files.list(shots)) { check(s.noneMatch(f -> f.toString().endsWith(".png")), "portal file deleted after decode"); }

        setMode("ok_rgba_spaces");
        px = shot(5000, res, m);
        check(px != null && res[0] == 0 && res[1] == 640, "portal rgba + percent-encoded path " + res[0] + " " + m[0]);
        if (px != null) {
            long bad = 0;
            for (int y = 0; y < 480; y++) for (int x = 0; x < 640; x++) if (px[y * 640 + x] != (0xFF000000 | pattern(x, y))) bad++;
            check(bad == 0, "portal rgba pixels exact, alpha forced opaque (bad=" + bad + ")");
        }
        try (var s = Files.list(shots)) { check(s.noneMatch(f -> f.toString().endsWith(".png")), "rgba file deleted"); }

        setMode("cancel");
        check(shot(5000, res, m) == null && res[0] == 5, "portal cancel -> 5, got " + res[0]);
        setMode("error");
        check(shot(5000, res, m) == null && res[0] == 6, "portal error -> 6, got " + res[0] + " " + m[0]);
        setMode("silent");
        long t0 = System.nanoTime();
        check(shot(1500, res, m) == null && res[0] == 7, "portal silent -> 7, got " + res[0]);
        long took = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("portal timeout took " + took + " ms (asked 1500)");
        check(took >= 1400 && took < 3000, "timeout honoured: " + took);
        setMode("bogus_uri");
        check(shot(5000, res, m) == null && res[0] == 6, "bogus uri -> 6, got " + res[0] + " " + m[0]);
        setMode("missing_file");
        check(shot(5000, res, m) == null && res[0] == 6, "missing file -> 6, got " + res[0] + " " + m[0]);
        setMode("not_png");
        check(shot(5000, res, m) == null && res[0] == 6, "garbage file -> 6, got " + res[0] + " " + m[0]);
        setMode("no_uri");
        check(shot(5000, res, m) == null && res[0] == 6, "no uri -> 6, got " + res[0] + " " + m[0]);
        setMode("delay:800");
        check(shot(5000, res, m) != null && res[0] == 0, "delayed response ok " + res[0] + " " + m[0]);
        setMode("wrong_path");
        check(shot(1500, res, m) == null && res[0] == 7, "response on another path ignored -> 7, got " + res[0]);
        setMode("ignore_token");
        check(shot(5000, res, m) != null && res[0] == 0, "portal ignoring handle_token still answered " + res[0] + " " + m[0]);
        setMode("dbus_error");
        check(shot(5000, res, m) == null && res[0] == 6, "method error -> 6, got " + res[0] + " " + m[0]);
        setMode("access_denied");
        check(shot(5000, res, m) == null && res[0] == 2, "AccessDenied -> 2, got " + res[0] + " " + m[0]);
        setMode("early");
        check(shot(5000, res, m) != null && res[0] == 0, "response before the method reply " + res[0] + " " + m[0]);
        setMode("symlink");
        px = shot(5000, res, m);
        check(px != null && res[0] == 0, "symlinked screenshot decoded " + res[0] + " " + m[0]);
        check(Files.exists(shots.resolve("target.png")), "symlink target kept");

        // Concurrency + leaks.
        setMode("ok");
        int threads = 8, per = Integer.parseInt(System.getProperty("portalIterations", "40"));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicLong errs = new AtomicLong();
        long fd0 = 0, rss0 = 0;
        for (int phase = 0; phase < 2; phase++) {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < threads; t++) fs.add(pool.submit(() -> {
                int[] r = new int[3]; String[] mm = new String[1];
                for (int i = 0; i < per; i++) {
                    int[] p = NativeScreenCapture.nativePortalScreenshot(false, 10000, r, mm);
                    if (p == null || r[0] != 0 || p[641] != (0xFF000000 | pattern(1, 1))) {
                        errs.incrementAndGet();
                        System.out.println("portal torture: " + r[0] + " " + mm[0]);
                    }
                }
            }));
            for (Future<?> f : fs) f.get(10, TimeUnit.MINUTES);
            System.gc();
            Thread.sleep(300);
            if (phase == 0) { fd0 = fds(); rss0 = rssKb(); }
            System.out.println("portal torture phase " + phase + ": calls=" + (long) threads * per * (phase + 1) + " errors=" + errs.get() + " fds=" + fds() + " rss=" + rssKb() + "kB");
        }
        pool.shutdown();
        check(errs.get() == 0, "portal torture errors");
        check(fds() <= fd0, "portal fd leak");
        check(rssKb() - rss0 < 64 * 1024, "portal rss growth " + (rssKb() - rss0));
        try (var s = Files.list(shots)) { check(s.filter(f -> f.toString().endsWith(".png") && !f.getFileName().toString().equals("target.png")).count() == 0, "no screenshot left behind"); }
    }

    static void portalMissing() {
        int[] res = new int[3]; String[] m = new String[1];
        check(NativeScreenCapture.nativePortalScreenshot(false, 3000, res, m) == null && res[0] == 1, "no portal -> UNSUPPORTED, got " + res[0] + " " + m[0]);
        System.out.println("portal-missing message: " + m[0]);
    }
}
