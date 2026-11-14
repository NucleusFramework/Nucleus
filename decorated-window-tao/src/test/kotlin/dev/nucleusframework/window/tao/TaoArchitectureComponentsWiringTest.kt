package dev.nucleusframework.window.tao

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Each of the three scene hosts drives its window's
 * `TaoArchitectureComponents` by hand, from its own attach, focus, minimize
 * and detach paths. `TaoArchitectureComponentsTest` covers the class but not
 * that wiring, and a host that drops a call fails quietly: its windows stay
 * `CREATED`, or never clear their view models. So check the calls are there,
 * and that the scene is closed between the two detach calls.
 */
class TaoArchitectureComponentsWiringTest {
    @Test
    fun `every scene host drives its architecture components`() {
        val problems =
            HOSTS.flatMap { name ->
                val source = hostSource(name).readText()
                val missing =
                    REQUIRED_CALLS
                        .filterNot { it in source }
                        .map { "$name: missing `$it`" }
                val detaching = source.indexOf("architectureComponents.onDetaching()")
                val sceneClosed = source.indexOf("sceneBundle?.close()", startIndex = maxOf(detaching, 0))
                val detached = source.indexOf("architectureComponents.onDetached()")
                val ordered = detaching in 0 until sceneClosed && sceneClosed < detached
                missing +
                    if (ordered) emptyList() else listOf("$name: onDetaching, scene close, onDetached out of order")
            }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    /**
     * On Linux a focus-out during a compositor move or resize grab is masked
     * from the lifecycle; clearing the mask anywhere but `endCompositorGrab`
     * would leave the lifecycle on the masked focus after the grab.
     */
    @Test
    fun `the linux host resyncs the lifecycle focus when a compositor grab ends`() {
        val source = hostSource("TaoComposeSceneHostLinux.kt").readText()
        val clears = Regex("""(?<!var )compositorDragActive\s*=\s*false""").findAll(source).count()
        assertTrue(clears == 1, "compositorDragActive cleared $clears times; clear it only through endCompositorGrab()")
        assertTrue(
            "architectureComponents.onFocusChanged(windowInfo.isWindowFocused)" in source,
            "endCompositorGrab() must hand the lifecycle the real focus",
        )
    }

    private fun hostSource(name: String): File {
        val relative = "src/main/kotlin/dev/nucleusframework/window/tao/scene/$name"
        // Module directory first (Gradle), then the repository root (IDE).
        val candidates = listOf(File(relative), File("decorated-window-tao", relative))
        return candidates.firstOrNull { it.isFile }
            ?: fail("cannot find $relative from ${File("").absolutePath} (tried ${candidates.map { it.path }})")
    }

    private companion object {
        val HOSTS =
            listOf(
                "TaoComposeSceneHost.kt",
                "TaoComposeSceneHostLinux.kt",
                "TaoComposeSceneHostWindows.kt",
            )
        val REQUIRED_CALLS =
            listOf(
                "architectureComponentsOwner = architectureComponents.owner",
                "architectureComponents.onAttached(",
                "architectureComponents.onFocusChanged(",
                "architectureComponents.onMinimizedChanged(",
                "architectureComponents.onDetaching()",
                "architectureComponents.onDetached()",
            )
    }
}
