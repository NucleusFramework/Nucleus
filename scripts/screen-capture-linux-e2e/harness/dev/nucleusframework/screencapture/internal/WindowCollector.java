package dev.nucleusframework.screencapture.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class WindowCollector {
    public static final class W {
        public long id, pid; public String title, app; public int x, y, w, h;
        public String toString() { return Long.toHexString(id) + "[" + title + "/" + app + " pid=" + pid + " " + x + "," + y + " " + w + "x" + h + "]"; }
    }
    public final List<W> list = new ArrayList<>();
    public void add(long id, byte[] title, byte[] app, long pid, int x, int y, int w, int h) {
        W v = new W(); v.id = id; v.title = new String(title, StandardCharsets.UTF_8); v.app = new String(app, StandardCharsets.UTF_8);
        v.pid = pid; v.x = x; v.y = y; v.w = w; v.h = h;
        list.add(v);
    }
}
