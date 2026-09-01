package androidx.compose.ui.window.v2;

import androidx.compose.ui.unit.Constraints;
import androidx.compose.ui.unit.DpRect;
import androidx.compose.ui.unit.IntSize;
import androidx.compose.ui.window.WindowPlacement;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.channels.Channel;

/**
 * Friend-package accessor for Compose Multiplatform 1.12 window API v2.
 *
 * <p>{@code WindowState} / {@code DialogState} request channels and observed
 * fields are {@code internal} to {@code compose-ui}. Kotlin in another module
 * cannot see them; Java in this package can, because {@code internal} compiles
 * to public JVM members with a {@code $ui} name suffix.
 *
 * <p>Same pattern as {@code androidx.compose.ui.draganddrop.TaoTransferableAccess}.
 */
public final class ComposeWindowV2Access {
    private ComposeWindowV2Access() {}

    @SuppressWarnings("unchecked")
    public static Channel<WindowScreenProvider> screenRequests(WindowState state) {
        return state.getScreenRequests$ui();
    }

    @SuppressWarnings("unchecked")
    public static Channel<WindowPlacement> placementRequests(WindowState state) {
        return state.getPlacementRequests$ui();
    }

    @SuppressWarnings("unchecked")
    public static Channel<Boolean> minimizedRequests(WindowState state) {
        return state.isMinimizedRequests$ui();
    }

    @SuppressWarnings("unchecked")
    public static Channel<WindowBoundsProvider> boundsRequests(WindowState state) {
        return state.getBoundsRequests$ui();
    }

    public static String screenIdOrNull(WindowState state) {
        return state.get_screenId$ui();
    }

    public static void setScreenId(WindowState state, String screenId) {
        state.set_screenId$ui(screenId);
    }

    public static WindowPlacement placementOrNull(WindowState state) {
        return state.get_placement$ui();
    }

    public static void setPlacement(WindowState state, WindowPlacement placement) {
        state.set_placement$ui(placement);
    }

    public static Boolean minimizedOrNull(WindowState state) {
        return state.get_isMinimized$ui();
    }

    public static void setMinimized(WindowState state, Boolean minimized) {
        state.set_isMinimized$ui(minimized);
    }

    public static DpRect boundsOrNull(WindowState state) {
        return state.get_bounds$ui();
    }

    public static void setBounds(WindowState state, DpRect bounds) {
        state.set_bounds$ui(bounds);
    }

    public static void setInitialized(WindowState state, boolean initialized) {
        state.setInitialized$ui(initialized);
    }

    public static WindowState initializedWindowState(
            String screenId,
            WindowPlacement placement,
            boolean minimized,
            DpRect bounds) {
        return new WindowState(screenId, placement, minimized, bounds);
    }

    public static DialogState initializedDialogState(String screenId, DpRect bounds) {
        return new DialogState(screenId, bounds);
    }

    @SuppressWarnings("unchecked")
    public static Channel<WindowScreenProvider> dialogScreenRequests(DialogState state) {
        return state.getScreenRequests$ui();
    }

    @SuppressWarnings("unchecked")
    public static Channel<WindowBoundsProvider> dialogBoundsRequests(DialogState state) {
        return state.getBoundsRequests$ui();
    }

    public static String dialogScreenIdOrNull(DialogState state) {
        return state.get_screenId$ui();
    }

    public static void setDialogScreenId(DialogState state, String screenId) {
        state.set_screenId$ui(screenId);
    }

    public static DpRect dialogBoundsOrNull(DialogState state) {
        return state.get_bounds$ui();
    }

    public static void setDialogBounds(DialogState state, DpRect bounds) {
        state.set_bounds$ui(bounds);
    }

    public static void setDialogInitialized(DialogState state, boolean initialized) {
        state.setInitialized$ui(initialized);
    }

    public static DpRect evaluateBounds(
            WindowBoundsProvider provider,
            Window parent,
            Window window,
            Function1<? super Constraints, IntSize> measureContent) {
        WindowGeometryProviderScope scope =
                new WindowGeometryProviderScope(parent, window, measureContent);
        return scope.getBounds$ui(provider);
    }

    public static Window createGeometryPeer(
            GraphicsConfiguration gc, Rectangle bounds, Insets insets) {
        return new GeometryPeer(gc, bounds, insets);
    }

    /**
     * Displayable-looking AWT window that never creates a native peer. Used
     * only so Compose's {@link WindowGeometryProviderScope} can evaluate a
     * {@link WindowBoundsProvider} on the Tao backend.
     */
    private static final class GeometryPeer extends Window {
        private final Rectangle bounds;
        private final Insets insets;
        private final GraphicsConfiguration gc;

        GeometryPeer(GraphicsConfiguration gc, Rectangle bounds, Insets insets) {
            super((Window) null, gc);
            this.gc = gc;
            this.bounds = new Rectangle(bounds);
            this.insets = (Insets) insets.clone();
        }

        @Override
        public boolean isDisplayable() {
            return true;
        }

        @Override
        public Rectangle getBounds() {
            return new Rectangle(bounds);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            bounds.setBounds(x, y, width, height);
        }

        @Override
        public Insets getInsets() {
            return (Insets) insets.clone();
        }

        @Override
        public GraphicsConfiguration getGraphicsConfiguration() {
            return gc;
        }
    }
}
