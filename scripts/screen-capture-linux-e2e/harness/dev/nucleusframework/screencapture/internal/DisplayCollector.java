package dev.nucleusframework.screencapture.internal;

import java.util.ArrayList;
import java.util.List;

public final class DisplayCollector {
    public static final class D {
        public String id, name; public int x, y, w, h, pw, ph; public float scale; public boolean primary;
        public String toString() { return id + "[" + x + "," + y + " " + w + "x" + h + " px=" + pw + "x" + ph + " s=" + scale + (primary ? " primary" : "") + "]"; }
    }
    public final List<D> list = new ArrayList<>();
    public boolean throwOnAdd;
    public void add(String id, String name, int x, int y, int w, int h, int pw, int ph, float scale, boolean primary) {
        if (throwOnAdd) throw new IllegalStateException("boom from add");
        D d = new D(); d.id = id; d.name = name; d.x = x; d.y = y; d.w = w; d.h = h; d.pw = pw; d.ph = ph; d.scale = scale; d.primary = primary;
        list.add(d);
    }
}
