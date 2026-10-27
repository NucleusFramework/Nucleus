package androidx.compose.ui.graphics.layer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Compile-time stand-in for the hooks the Nucleus Gradle plugin generates into
 * Compose's {@code ui-graphics-desktop} jar ({@code LayerDamageTransform},
 * #755): {@code GraphicsLayer.record} calls {@link #onRecordStart} /
 * {@link #onRecordEnd}, {@code GraphicsLayer.draw} calls {@link #onDraw}.
 * Never packaged — see {@code androidx.compose.ui.node.NucleusLayerDamage}.
 */
public final class NucleusGraphicsLayerHooks {
    /** Told each layer that starts recording. */
    public static volatile Consumer<Object> recordStart;

    /** Told each layer that finished recording. */
    public static volatile Consumer<Object> recordEnd;

    /** Told each layer drawn, and the layer it is drawn into ({@code null} outside any). */
    public static volatile BiConsumer<Object, Object> draw;

    private NucleusGraphicsLayerHooks() {
    }

    /** @param layer the layer starting to record */
    public static void onRecordStart(Object layer) {
        throw new UnsupportedOperationException("stub");
    }

    /** @param layer the layer done recording */
    public static void onRecordEnd(Object layer) {
        throw new UnsupportedOperationException("stub");
    }

    /**
     * @param layer the layer drawn
     * @param parent the layer it is drawn into, or {@code null}
     */
    public static void onDraw(Object layer, Object parent) {
        throw new UnsupportedOperationException("stub");
    }
}
