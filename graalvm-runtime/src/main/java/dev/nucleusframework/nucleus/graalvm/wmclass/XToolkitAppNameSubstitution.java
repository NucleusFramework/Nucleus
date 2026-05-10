package dev.nucleusframework.nucleus.graalvm.wmclass;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image substitution for {@code sun.awt.X11.XToolkit.getAWTAppClassName}.
 * <p>
 * In standard JDK/JBR, {@code XToolkit} derives the WM_CLASS from the class name
 * at the bottom of the stack trace during toolkit construction. In a native image,
 * this bottom frame is often an internal SubstrateVM entry point such as
 * {@code java.lang.invoke.LambdaForm$DMH/...}, which produces a nonsensical
 * WM_CLASS like {@code java-lang-invoke-LambdaForm$DMH/sa346b79c} in GNOME.
 * <p>
 * This substitution replaces {@code getAWTAppClassName()} so it returns the real
 * application name via {@link AppNameResolver}.
 */
@TargetClass(className = "sun.awt.X11.XToolkit", onlyWith = IsLinux.class)
final class Target_sun_awt_X11_XToolkit {

    @Substitute
    static String getAWTAppClassName() {
        return AppNameResolver.resolve();
    }
}
