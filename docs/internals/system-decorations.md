# System decorations (#841)

`nucleus-application`'s `Window` / `DialogWindow` (and Tao's `DecoratedWindow(systemDecorations = true)` /
`DecoratedDialog(systemDecorations = true)`) let the platform draw the title bar and frame instead of
the Compose chrome. The flag is creation-time, read as `TaoWindow.hasSystemDecorations`, and ignored
with `undecorated`.

## What draws the frame

| Platform | Decorations | How Nucleus gets out of the way |
|---|---|---|
| macOS | Standard AppKit title bar | `nativeConfigureSystemChrome` instead of `nativeConfigureChrome`: no full-size content view, no transparent / hidden title, window stays movable. Marks the window (`kTaoSystemChromeKey`) so `NucleusTaoFSObserver` keeps only reattach, contents gravity and the fullscreen prepare. No large-corner toolbar, no `nativeApplyButtonLayout` / RTL layout / fullscreen replacement buttons. |
| Windows | Win32 caption and frame | `nativeInstallSystemFrame` instead of `nativeInstallDecoration`: `DecoState.nativeFrame` hands `WM_NCCALCSIZE` (except the fullscreen `return 0`), `WM_NCHITTEST` and the NC mouse messages to Tao's proc (`DefWindowProc` for a decorated window: native Snap Layouts, drag, double-click, resize). Alt+Space reaches the system menu (a bare Alt / F10 stays suppressed); no `WS_SYSMENU` strip under a backdrop; DWM keeps its default caption / border colours and corners; no 1px frame extension. `estimateRestoredClientSize` and the maximized first frame (`nativeGetMaximizedClientSize`: work area minus caption, since the hidden window is not maximized yet when it renders) account for the caption. |
| Linux X11 | Window manager | `decorations = true` (GTK `set_decorated`), no hidden-titlebar CSD. |
| Linux Wayland | KWin: server-side; GNOME: GTK's default title bar | Tao patch `0018-linux-system-decorations`: no `WlHeader`. A custom `gtk_window_set_titlebar` would latch client-side decorations for good, so KWin could never serve its own; without it GTK asks through `org_kde_kwin_server_decoration` and draws its default title bar where the compositor refuses. The #816 server-decoration watcher is not attached (`server` is the wanted answer here). Sizes and pointer positions are reported in content space (`reports_content_space`) — under GTK's title bar the content starts below it — and a non-resizable window's size request goes on the content box like any non-resizable window's (patch 0017), since a toplevel request counts the decorations. Pointer events that land on GTK's own title bar, shadow or resize strips (`is_on_system_decorations`) are let through to GtkWindow's class handlers, which run after Tao's and drive the move, the double-click maximize and the edge resize; a press there is not forwarded, since the compositor keeps the release of a move / resize grab. |

On every platform the host skips its own chrome: no caption height (`titleBarHeightDpState` 0), no
`rememberUndecoratedWindowBorder`. On Linux also no resize band (`currentResizeDirection`), corner
radius 0 (no carving, square opaque region, no GTK CSD radius), and `parentObscured()` is always true,
so a resize never takes the #444 in-frame path: a GTK title bar keeps part of the toplevel visible,
and under server-side decorations the content covers the whole toplevel at any size — neither fits
that path's premise.

## Title bars inside

`TitleBar`, `DialogTitleBar`, `WindowControls` and `WindowScaffold` treat `hasSystemDecorations` like
the #816 suppressed state — no window controls, no room reserved, collapse when empty — and never
drive native chrome: no `heightHolder` publish, no Windows caption push, no macOS traffic-light layout,
no fullscreen overlay, no large-corner toolbar.

## Not offered

`DialogWindow` has no `undecorated` / `transparent` / `alwaysOnTop`: the Tao dialog path does not
support them, and a parameter that silently does nothing is worse than none.

## Verification

`SystemDecorationsHeadfulCases` (`taoHeadfulTest`, filter `system decorations`): the content must have
the requested size, the outer frame must be at least a title bar taller than it wherever the
decorations are part of the client (GNOME's GTK title bar, macOS, Windows, an X11 window manager's
frame), and a `TitleBar` inside must not claim a caption height. Under KWin's server-side decorations
the client surface is the content, so the outer frame equals it there. The title-bar gestures on GNOME
(move, double-click, edge resize) go through GTK and the compositor, so in-process injection cannot
reach them: check them with a real pointer.
The Lab probe `window.system-decorations` covers the visual checks per OS (native bar, drag, resize,
content origin under the bar, fullscreen, Snap Layouts / Alt+Space, dialog).
