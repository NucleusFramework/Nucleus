// Test helper: paints known content on an X server.
//   xtool pattern                 full-screen window painted with pattern(x, y); prints XID; runs until killed
//   xtool window X Y W H RRGGBB   solid window; prints XID; runs until killed
//   xtool monitors                defines RandR monitors LEFT (x<half) and RIGHT (primary)
//   xtool warp X Y                moves the pointer
//   xtool redirect                XCompositeRedirectSubwindows(root, automatic); runs until killed
//   xtool destroyed               creates + destroys a window, prints its (now dead) XID
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/extensions/Xrandr.h>
#include <X11/extensions/Xcomposite.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static unsigned pattern(int x, int y) {
    unsigned r = x & 255, g = y & 255, b = ((x >> 8) * 64 + (y >> 8) * 16 + ((x + y) & 15)) & 255;
    return (r << 16) | (g << 8) | b;
}

static int ctz(unsigned long m) { int s = 0; while (m && !((m >> s) & 1)) s++; return s; }
static int bits(unsigned long m) { int s = ctz(m), b = 0; while ((m >> (s + b)) & 1) b++; return b; }

static unsigned long to_pixel(Visual *v, unsigned rgb) {
    unsigned long p = 0;
    unsigned long masks[3] = {v->red_mask, v->green_mask, v->blue_mask};
    unsigned vals[3] = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
    for (int i = 0; i < 3; i++) {
        int s = ctz(masks[i]), b = bits(masks[i]);
        unsigned q = b >= 8 ? vals[i] << (b - 8) : vals[i] >> (8 - b);
        p |= ((unsigned long)q << s) & masks[i];
    }
    return p;
}

static Window make(Display *d, int x, int y, int w, int h) {
    XSetWindowAttributes a;
    a.override_redirect = True;
    a.background_pixmap = None;
    Window win = XCreateWindow(d, DefaultRootWindow(d), x, y, w, h, 0, CopyFromParent, InputOutput, CopyFromParent,
                               CWOverrideRedirect | CWBackPixmap, &a);
    XSelectInput(d, win, ExposureMask);
    XMapRaised(d, win);
    return win;
}

int main(int argc, char **argv) {
    Display *d = XOpenDisplay(NULL);
    if (!d) { fprintf(stderr, "no display\n"); return 1; }
    int scr = DefaultScreen(d);
    Visual *vis = DefaultVisual(d, scr);
    int depth = DefaultDepth(d, scr);
    if (argc >= 2 && strcmp(argv[1], "pattern") == 0) {
        int w = DisplayWidth(d, scr), h = DisplayHeight(d, scr);
        Window win = make(d, 0, 0, w, h);
        XImage *img = XCreateImage(d, vis, depth, ZPixmap, 0, NULL, w, h, 32, 0);
        img->data = malloc((size_t)img->bytes_per_line * h);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) XPutPixel(img, x, y, to_pixel(vis, pattern(x, y)));
        GC gc = XCreateGC(d, win, 0, NULL);
        printf("%lu\n", win); fflush(stdout);
        for (;;) {
            XEvent e;
            XPutImage(d, win, gc, img, 0, 0, 0, 0, w, h);
            XSync(d, False);
            XNextEvent(d, &e);
        }
    } else if (argc >= 7 && strcmp(argv[1], "window") == 0) {
        int x = atoi(argv[2]), y = atoi(argv[3]), w = atoi(argv[4]), h = atoi(argv[5]);
        unsigned rgb = (unsigned)strtoul(argv[6], NULL, 16);
        Window win = make(d, x, y, w, h);
        unsigned long px;
        if (vis->class == TrueColor) px = to_pixel(vis, rgb);
        else {
            XColor c; c.red = ((rgb >> 16) & 255) * 257; c.green = ((rgb >> 8) & 255) * 257; c.blue = (rgb & 255) * 257;
            XAllocColor(d, DefaultColormap(d, scr), &c); px = c.pixel;
        }
        GC gc = XCreateGC(d, win, 0, NULL);
        XSetForeground(d, gc, px);
        printf("%lu\n", win); fflush(stdout);
        for (;;) {
            XEvent e;
            XFillRectangle(d, win, gc, 0, 0, w, h);
            XSync(d, False);
            XNextEvent(d, &e);
        }
    } else if (argc >= 2 && strcmp(argv[1], "monitors") == 0) {
        int w = DisplayWidth(d, scr), h = DisplayHeight(d, scr);
        XRRMonitorInfo *m = XRRAllocateMonitor(d, 0);
        m->name = XInternAtom(d, "LEFT", False); m->x = 0; m->y = 0; m->width = w / 2; m->height = h;
        m->mwidth = 300; m->mheight = 300; m->primary = False; m->automatic = False; m->noutput = 0;
        XRRSetMonitor(d, DefaultRootWindow(d), m);
        m->name = XInternAtom(d, "RIGHT", False); m->x = w / 2; m->width = w - w / 2; m->primary = True;
        XRRSetMonitor(d, DefaultRootWindow(d), m);
        XSync(d, False);
        int n; XRRMonitorInfo *all = XRRGetMonitors(d, DefaultRootWindow(d), True, &n);
        printf("monitors=%d\n", n);
        XRRFreeMonitors(all);
    } else if (argc >= 4 && strcmp(argv[1], "warp") == 0) {
        XWarpPointer(d, None, DefaultRootWindow(d), 0, 0, 0, 0, atoi(argv[2]), atoi(argv[3]));
        XSync(d, False);
    } else if (argc >= 2 && strcmp(argv[1], "redirect") == 0) {
        XCompositeRedirectSubwindows(d, DefaultRootWindow(d), CompositeRedirectAutomatic);
        XSync(d, False);
        printf("redirected\n"); fflush(stdout);
        for (;;) pause();
    } else if (argc >= 2 && strcmp(argv[1], "destroyed") == 0) {
        Window win = XCreateSimpleWindow(d, DefaultRootWindow(d), 0, 0, 10, 10, 0, 0, 0);
        XSync(d, False);
        XDestroyWindow(d, win);
        XSync(d, False);
        printf("%lu\n", win);
    } else {
        fprintf(stderr, "usage\n");
        return 2;
    }
    XCloseDisplay(d);
    return 0;
}
