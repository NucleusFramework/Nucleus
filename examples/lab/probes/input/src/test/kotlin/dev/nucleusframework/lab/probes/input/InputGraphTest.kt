package dev.nucleusframework.lab.probes.input

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import dev.nucleusframework.lab.core.Domain
import dev.nucleusframework.lab.core.Probe
import dev.nucleusframework.lab.core.fixture.Fixture
import dev.nucleusframework.lab.probes.input.a11y.A11ySurfaceFixture
import dev.nucleusframework.lab.probes.input.a11y.A11yViewModel
import dev.nucleusframework.lab.probes.input.clipboard.ClipboardViewModel
import dev.nucleusframework.lab.probes.input.dnd.DndViewModel
import dev.nucleusframework.lab.probes.input.focus.FocusViewModel
import dev.nucleusframework.lab.probes.input.gestures.GesturesViewModel
import dev.nucleusframework.lab.probes.input.keyboard.KeyboardViewModel
import dev.nucleusframework.lab.probes.input.pointer.PointerViewModel
import dev.nucleusframework.lab.probes.input.scroll.ScrollViewModel
import dev.nucleusframework.lab.probes.input.spellcheck.SpellcheckViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The slice's contributions assembled exactly as the Lab app graph assembles them. */
@DependencyGraph(AppScope::class)
interface InputTestGraph : ViewModelGraph {
    @Multibinds(allowEmpty = true)
    val probes: Set<Probe>

    @Multibinds(allowEmpty = true)
    val fixtures: Set<Fixture>
}

@OptIn(ExperimentalCoroutinesApi::class)
class InputGraphTest {
    @BeforeTest
    fun mainDispatcher() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetDispatcher() = Dispatchers.resetMain()

    private val graph = createGraph<InputTestGraph>()

    @Test
    fun `every input probe is contributed with a unique id and its checks`() {
        val ids = graph.probes.map { it.descriptor.id.value }
        assertEquals(
            setOf(
                "input.a11y",
                "input.scroll",
                "input.gestures",
                "input.pointer",
                "input.keyboard",
                "input.focus",
                "input.dnd",
                "input.clipboard",
                "input.spellcheck",
            ),
            ids.toSet(),
        )
        assertEquals(ids.size, ids.toSet().size)
        graph.probes.forEach { probe ->
            assertEquals(Domain.Input, probe.descriptor.domain)
            assertTrue(
                probe.descriptor.checks.size in 3..7,
                "${probe.descriptor.id} has ${probe.descriptor.checks.size} checks",
            )
            assertEquals(
                probe.descriptor.checks.size,
                probe.descriptor.checks
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun `the a11y surface fixture is contributed under the id CI launches`() {
        assertTrue(graph.fixtures.any { it.id == A11ySurfaceFixture.ID && it.id == "a11y-surface" })
    }

    @Test
    fun `every ViewModel is built by the Metro factory`() {
        val classes: List<KClass<out ViewModel>> =
            listOf(
                A11yViewModel::class,
                ScrollViewModel::class,
                GesturesViewModel::class,
                PointerViewModel::class,
                KeyboardViewModel::class,
                FocusViewModel::class,
                DndViewModel::class,
                ClipboardViewModel::class,
                SpellcheckViewModel::class,
            )
        classes.forEach { type ->
            val vm = graph.metroViewModelFactory.create(type, CreationExtras.Empty)
            assertTrue(type.isInstance(vm))
        }
    }
}
