package androidx.compose.ui.node;

import androidx.compose.ui.graphics.GraphicsContext;
import androidx.compose.ui.graphics.layer.GraphicsLayer;
import androidx.compose.ui.layout.LayoutCoordinates;
import androidx.compose.ui.layout.LayoutInfo;

import java.util.List;

/**
 * Friend-package accessor for the layer tree the Tao partial redraw walks to
 * find a frame's damage (#755): a layout node's children and coordinators, the
 * render layer a coordinator owns, and the content version the Nucleus plugin
 * patches into that layer. These are Kotlin {@code internal} members of
 * {@code compose-ui}, unreachable from another Kotlin module; Java does not
 * honour {@code internal}, so a file in this package calls them through their
 * {@code $ui}-mangled JVM names.
 *
 * <p>No reflection: static calls that compile cleanly under GraalVM
 * native-image with zero reachability metadata. A Compose release that moves
 * one of these members surfaces as a {@link LinkageError}, which the caller
 * treats as "damage unknown" and answers with full repaints.
 */
public final class TaoLayerTreeAccess {
    private TaoLayerTreeAccess() {
    }

    /**
     * @param node a layout node
     * @return its children, in placement order
     */
    @SuppressWarnings("unchecked")
    public static List<LayoutInfo> children(LayoutInfo node) {
        return (List<LayoutInfo>) (List<?>) ((LayoutNode) node).getChildren$ui();
    }

    /**
     * @param node a layout node
     * @return its outermost coordinator — the first one {@link #wrapped} starts from
     */
    public static LayoutCoordinates outerCoordinator(LayoutInfo node) {
        return ((LayoutNode) node).getOuterCoordinator$ui();
    }

    /**
     * @param node a layout node
     * @return its innermost coordinator — the last one {@link #wrapped} reaches
     */
    public static LayoutCoordinates innerCoordinator(LayoutInfo node) {
        return ((LayoutNode) node).getInnerCoordinator$ui();
    }

    /**
     * @param coordinator a coordinator
     * @return the next coordinator inward, or {@code null} past the inner one
     */
    public static LayoutCoordinates wrapped(LayoutCoordinates coordinator) {
        return ((NodeCoordinator) coordinator).getWrapped$ui();
    }

    /**
     * @param coordinator a coordinator
     * @return the render layer it draws through, or {@code null}
     */
    public static Object ownedLayer(LayoutCoordinates coordinator) {
        return ((NodeCoordinator) coordinator).getLayer();
    }

    /**
     * @param ownedLayer a layer from {@link #ownedLayer}
     * @return the graphics layer behind it, or {@code null} for a layer kind
     *         the partial redraw does not know (the legacy render-node layer)
     */
    public static GraphicsLayer graphicsLayer(Object ownedLayer) {
        if (ownedLayer instanceof GraphicsLayerOwnerLayer) {
            return ((GraphicsLayerOwnerLayer) ownedLayer).getGraphicsLayer$ui();
        }
        return null;
    }

    /**
     * @param ownedLayer a layer from {@link #ownedLayer}
     * @return its content version, or {@code -1} when Compose is not patched
     *         or the layer is of another kind
     */
    public static int contentVersion(Object ownedLayer) {
        return NucleusLayerDamage.contentVersion(ownedLayer);
    }

    /**
     * @param root the root layout node of a scene owner
     * @return the graphics context every layer of that owner is created from
     */
    public static GraphicsContext graphicsContext(LayoutInfo root) {
        Owner owner = ((LayoutNode) root).getOwner$ui();
        return owner == null ? null : owner.getGraphicsContext();
    }
}
