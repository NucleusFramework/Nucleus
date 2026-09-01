package dev.nucleusframework.window.tao;

import androidx.compose.ui.unit.DpRect;
import androidx.compose.ui.window.v2.WindowBoundsProvider;
import androidx.compose.ui.window.v2.WindowGeometryProviderScope;

/**
 * Provider whose body raises its own {@link NullPointerException}.
 *
 * Written in Java on purpose: a Kotlin lambda gets a non-null parameter
 * assertion on the geometry scope, so its body never runs with the {@code null}
 * scope the Tao bridge passes in. This fixture reaches the body and lets the
 * test assert that a genuine provider bug is not mistaken for "needs AWT
 * window metrics".
 */
public final class FailingBoundsProvider implements WindowBoundsProvider {
    @Override
    public DpRect getBounds(WindowGeometryProviderScope scope) {
        throw new NullPointerException("Cannot read field \"model\" because \"holder\" is null");
    }
}
