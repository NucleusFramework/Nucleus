package androidx.compose.ui.node;

/**
 * Compile-time stand-in for the class the Nucleus Gradle plugin generates into
 * Compose's {@code ui-desktop} jar ({@code LayerDamageTransform}, #755). This
 * source set is a {@code compileOnly} dependency of {@code main} and is never
 * packaged: at run time the class comes from the patched jar, or is absent —
 * which {@code TaoLayerTreeAccess.contentVersion} reports as "no versions".
 */
public final class NucleusLayerDamage {
    private NucleusLayerDamage() {
    }

    /**
     * The patched layer's content version, bumped whenever it is invalidated
     * or repainted; {@code -1} for anything that is not a patched layer.
     *
     * @param layer an {@code OwnedLayer}
     * @return the version, or {@code -1}
     */
    public static int contentVersion(Object layer) {
        throw new UnsupportedOperationException("stub");
    }
}
