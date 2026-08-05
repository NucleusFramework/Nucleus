package dev.nucleusframework.window.tao

import dev.nucleusframework.window.TitleBarHitTestTest
import dev.nucleusframework.window.tao.a11y.TaoA11yProjectionTest
import dev.nucleusframework.window.tao.dnd.TaoSyntheticDndTest
import dev.nucleusframework.window.tao.dnd.TaoTransferableAccessGuardTest
import dev.nucleusframework.window.tao.event.TaoKeyMappingTest
import dev.nucleusframework.window.tao.event.TaoKeyboardModifiersDecodeTest
import dev.nucleusframework.window.tao.event.TaoSyntheticMouseWheelEventTest
import dev.nucleusframework.window.tao.event.TaoWheelPinchZoomTest
import dev.nucleusframework.window.tao.scene.TaoSceneAnimationTest
import dev.nucleusframework.window.tao.scene.TaoSceneKeyboardTest
import dev.nucleusframework.window.tao.scene.TaoScenePointerTest
import dev.nucleusframework.window.tao.scene.TaoScenePopupTest
import dev.nucleusframework.window.tao.scene.TaoSceneRenderTest
import dev.nucleusframework.window.tao.scene.TaoSceneScrollTest
import dev.nucleusframework.window.tao.scene.TaoSceneSemanticsTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Guards [TaoSceneTestBattery] against silent drift: the battery is a
 * hand-written, reflection-free registry (a GraalVM native-image
 * requirement), so a new `@Test` added to a stage-0/1 suite would silently
 * never run inside the native image unless it is also registered there.
 *
 * Two invariants:
 *  1. every `@Test` method of every battery class has a matching battery
 *     case named `"<SimpleClassName>: <method name>"` — and nothing more;
 *  2. every test class in this module is either part of the battery or
 *     explicitly listed as JVM-only below (with the reason it can't run in
 *     the native image).
 */
class TaoSceneTestBatteryDriftTest {
    private val batteryClasses: List<Class<*>> =
        listOf(
            TaoKeyMappingTest::class.java,
            TaoKeyboardModifiersDecodeTest::class.java,
            TaoSyntheticMouseWheelEventTest::class.java,
            TaoWheelPinchZoomTest::class.java,
            TaoWindowScrollTest::class.java,
            TaoWindowResizableTest::class.java,
            TaoSceneRenderTest::class.java,
            TaoSceneKeyboardTest::class.java,
            TaoScenePointerTest::class.java,
            TaoSceneScrollTest::class.java,
            TaoScenePopupTest::class.java,
            TaoSceneAnimationTest::class.java,
            TaoSceneSemanticsTest::class.java,
            TaoA11yProjectionTest::class.java,
            TitleBarHitTestTest::class.java,
        )

    /** Classes that must stay out of the battery, with the reason. */
    private val jvmOnlyClasses: Map<Class<*>, String> =
        mapOf(
            TaoMainDispatcherHandoffTest::class.java to "spawns real threads against the JVM dispatcher",
            TaoMainDispatcherReproTest::class.java to "spawns real threads against the JVM dispatcher",
            StandalonePanelNativeSmokeTest::class.java to "loads the Windows native popup chain",
            StandalonePanelLinuxNativeSmokeTest::class.java to "loads the Linux native popup chain",
            OutboundDragPumpNativeSmokeTest::class.java to "resolves the platform DnD JNI entry points",
            TaoRuntimeResizableSmokeTest::class.java to "opt-in headful smoke (NUCLEUS_TAO_SMOKE=1)",
            TaoSyntheticDndTest::class.java to "pins an AWT DropTarget; the no-AWT image never initialises AWT",
            TaoTransferableAccessGuardTest::class.java to "Compose interop ABI guard, not a scene behaviour",
            TaoSceneTestBatteryDriftTest::class.java to "meta-test for the battery itself",
        )

    private fun testMethodNames(cls: Class<*>): List<String> =
        cls.declaredMethods
            .filter { it.isAnnotationPresent(org.junit.Test::class.java) }
            .map { it.name }

    @Test
    fun `battery registry matches the @Test methods of its classes exactly`() {
        val expected =
            batteryClasses
                .flatMap { cls -> testMethodNames(cls).map { "${cls.simpleName}: $it" } }
                .sorted()
        val actual = TaoSceneTestBattery.runAll().map { it.name }.sorted()

        val missing = expected - actual.toSet()
        val stale = actual - expected.toSet()
        if (missing.isNotEmpty() || stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("TaoSceneTestBattery is out of sync with the @Test methods.")
                    if (missing.isNotEmpty()) {
                        appendLine("Missing from the battery (add a run(...) entry):")
                        missing.forEach { appendLine("  - $it") }
                    }
                    if (stale.isNotEmpty()) {
                        appendLine("Registered but not matching any @Test method (rename or remove):")
                        stale.forEach { appendLine("  - $it") }
                    }
                },
            )
        }
        assertEquals(expected.size, actual.size, "battery must not register duplicates")
    }

    @Test
    fun `every test class in the module is either in the battery or explicitly JVM-only`() {
        val classesRoot =
            File(
                TaoSceneTestBatteryDriftTest::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val discovered =
            classesRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" && !it.name.contains('$') }
                .map {
                    it
                        .relativeTo(classesRoot)
                        .path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                }.mapNotNull { runCatching { Class.forName(it, false, javaClass.classLoader) }.getOrNull() }
                .filter { cls -> cls.declaredMethods.any { it.isAnnotationPresent(org.junit.Test::class.java) } }
                .toSet()

        val known = batteryClasses.toSet() + jvmOnlyClasses.keys
        val unaccounted = discovered - known
        if (unaccounted.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("New test classes found that are neither in TaoSceneTestBattery nor declared JVM-only:")
                    unaccounted.forEach { appendLine("  - ${it.name}") }
                    appendLine("Register them in the battery (native-image coverage)")
                    appendLine("or add them to jvmOnlyClasses with a reason.")
                },
            )
        }
    }
}
