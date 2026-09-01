package androidx.compose.ui.window.v2;

import androidx.compose.ui.unit.DpRect;
import androidx.compose.ui.window.WindowPlacement;
import kotlinx.coroutines.channels.Channel;

/**
 * Friend-package accessor for Compose Multiplatform 1.12 window API v2.
 *
 * <p>{@code WindowState} / {@code DialogState} request channels and observed
 * fields are {@code internal} to {@code compose-ui}. Kotlin in another module
 * cannot see them; Java in this package can, because {@code internal} compiles
 * to public JVM members with a {@code $ui} name suffix.
 *
 * <p>Same pattern as {@code androidx.compose.ui.draganddrop.TaoTransferableAccess}:
 * static dispatch only — no reflection, no extra GraalVM metadata.
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

    /**
     * Evaluates providers that ignore the geometry scope (e.g.
     * {@code WindowBoundsProvider.Absolute}). Returns {@code null} when the
     * provider needs live window metrics.
     *
     * <p>Only a {@link NullPointerException} that comes from the {@code null}
     * scope we pass in is treated as "needs live metrics". An exception raised
     * by the provider's own body is propagated so a real bug does not turn into
     * a silently dropped geometry request.
     */
    public static DpRect constantBoundsOrNull(WindowBoundsProvider provider) {
        try {
            return provider.getBounds(null);
        } catch (NullPointerException e) {
            if (isAbsentScopeDereference(e)) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Whether {@code e} was raised by dereferencing the {@code null}
     * {@code WindowGeometryProviderScope} rather than by the provider itself.
     *
     * <p>Three shapes count: Kotlin's non-null parameter assertion (thrown
     * before the body runs), a helpful NPE naming a scope or window-metrics
     * member, and a message-less NPE — the last one because
     * {@code -XX:-ShowCodeDetailsInExceptionMessages} leaves nothing to
     * inspect, and dropping the request is safer there than crashing.
     */
    private static boolean isAbsentScopeDereference(NullPointerException e) {
        String message = e.getMessage();
        if (message == null) {
            return true;
        }
        return message.startsWith("Parameter specified as non-null is null")
                || message.contains("WindowGeometryProviderScope")
                || message.contains("WindowMetrics");
    }
}
