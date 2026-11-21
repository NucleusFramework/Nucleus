package androidx.compose.ui.platform;

import androidx.compose.runtime.ProvidableCompositionLocal;

/**
 * Friend-package accessor for Compose's {@code LocalGraphicsResourceCache}
 * (Compose 1.13+). It is {@code internal} in the Kotlin module, but the getter
 * of a top-level property is not name-mangled, so Java in the same package can
 * reach it — same pattern as {@code TaoComposeSceneContextAccess}.
 *
 * <p>Its default reads {@code LocalOwner}, which the Tao application-level
 * composition does not have: without an explicit {@code null} default,
 * {@code rememberVectorPainter} at application scope (a window or tray icon)
 * throws {@code CompositionLocal LocalOwner not present}.
 */
public final class TaoGraphicsResourceCacheAccess {
    private TaoGraphicsResourceCacheAccess() {
    }

    /**
     * Returns Compose's {@code LocalGraphicsResourceCache}.
     *
     * @return the composition local vector painters read their shared draw cache from
     */
    public static ProvidableCompositionLocal<GraphicsResourceCache> localGraphicsResourceCache() {
        return CompositionLocalsKt.getLocalGraphicsResourceCache();
    }
}
