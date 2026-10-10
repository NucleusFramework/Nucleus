package androidx.compose.ui.scene;

import androidx.compose.runtime.ProvidableCompositionLocal;

/**
 * Friend-package accessor for Compose's {@code LocalComposeSceneContext}, the
 * composition local {@code Popup} / {@code Dialog} read to decide which
 * {@link ComposeSceneContext} creates their layer. It is declared
 * {@code internal} in the Kotlin module {@code compose-ui} and therefore
 * unreachable from another Kotlin module — but Java does not honour Kotlin's
 * {@code internal} visibility, and the getter of a top-level property is not
 * name-mangled, so a Java file in the same package can call it directly.
 *
 * <p>No reflection: this is a static call that compiles cleanly under GraalVM
 * native-image with zero reachability metadata.
 */
public final class TaoComposeSceneContextAccess {
    private TaoComposeSceneContextAccess() {
    }

    /**
     * Returns Compose's {@code LocalComposeSceneContext}.
     *
     * @return the composition local a scene provides for its own
     *         {@link ComposeSceneContext}; its current value may be
     *         {@code null} outside any scene
     */
    public static ProvidableCompositionLocal<ComposeSceneContext> localComposeSceneContext() {
        return ComposeSceneContext_skikoKt.getLocalComposeSceneContext();
    }
}
