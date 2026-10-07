@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package jewelsample

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.nucleusframework.application.contextmenu.ContextMenuEntry
import dev.nucleusframework.application.contextmenu.ContextMenuIcon
import dev.nucleusframework.application.contextmenu.LocalContextMenuDivider
import dev.nucleusframework.application.contextmenu.LocalContextMenuItemInterpreter
import dev.nucleusframework.application.contextmenu.stockShortcut
import dev.nucleusframework.application.spellcheck.NucleusSpellcheckInstaller
import dev.nucleusframework.application.spellcheck.SpellcheckContextMenu
import dev.nucleusframework.application.spellcheck.SpellcheckMenuPlacement
import dev.nucleusframework.spellcheck.SpellcheckMenuModel
import dev.nucleusframework.spellcheck.SpellcheckSession
import dev.nucleusframework.window.jewel.JewelContextMenuInterpreter
import dev.nucleusframework.window.jewel.ProvideJewelSpellcheckMenu
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.ContextMenuDivider
import org.jetbrains.jewel.ui.component.ContextMenuItemOption
import org.jetbrains.jewel.ui.component.ContextMenuItemOptionAction
import org.jetbrains.jewel.ui.component.TextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Locale
import java.util.UUID

class SpellcheckJewelTest {
    @Test
    fun `ProvideJewelSpellcheckMenu publishes ContextMenuDivider and the Jewel interpreter`() {
        runComposeUiTest {
            setContent {
                IntUiTheme {
                    ProvideJewelSpellcheckMenu {
                        assertSame(ContextMenuDivider, LocalContextMenuDivider.current)
                        assertSame(
                            JewelContextMenuInterpreter,
                            LocalContextMenuItemInterpreter.current,
                        )
                        SpellcheckContextMenu(text = "helo", onTextChange = {}) {}
                    }
                }
            }
        }
    }

    @Test
    fun `spellcheck draws through the app chrome instead of replacing it`() {
        SpellcheckMenuPlacement.entries.forEach { placement ->
            val probe = ProbeContextMenuRepresentation()
            runComposeUiTest {
                setContent {
                    IntUiTheme {
                        CompositionLocalProvider(LocalContextMenuRepresentation provides probe) {
                            ProvideJewelSpellcheckMenu {
                                SpellcheckContextMenu(
                                    text = "helo",
                                    onTextChange = {},
                                    menuPlacement = placement,
                                ) {
                                    // Whatever wrapping happened must end up drawing through `probe`.
                                    LocalContextMenuRepresentation.current.Representation(
                                        state = remember { ContextMenuState() },
                                        items = { listOf(ContextMenuItem("Copy") {}) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("$placement must draw through the ambient chrome", probe.drew)
            assertTrue(
                "$placement must keep the field's own items",
                probe.items.any { it.label == "Copy" },
            )
        }
    }

    @Test
    fun `Jewel cut action type maps to native stock icon`() {
        val item =
            ContextMenuItemOption(
                actionType = ContextMenuItemOptionAction.CopyMenuItemOptionAction,
                label = "Copy",
                action = {},
            )
        val entry = JewelContextMenuInterpreter.interpret(item, ContextMenuDivider)
        val typed = entry as ContextMenuEntry.Item
        assertSame(ContextMenuIcon.Copy, typed.icon)
        assertEquals(ContextMenuIcon.Copy.stockShortcut(), typed.shortcut)
        assertSame(
            ContextMenuEntry.Separator,
            JewelContextMenuInterpreter.interpret(ContextMenuDivider, ContextMenuDivider),
        )
    }

    @Test
    fun `Jewel TextField accepts SpellcheckContextMenu and menu uses ContextMenuDivider`() {
        SpellcheckSession(
            locale = Locale.US,
            userDictionaryFile = isolatedUserDict(),
        ).use { session ->
            assumeTrue("Native spellcheck + English dictionary required", session.isAvailable)
            runComposeUiTest {
                setContent {
                    IntUiTheme {
                        ProvideJewelSpellcheckMenu {
                            val state = rememberTextFieldState("helo jewel")
                            SpellcheckContextMenu(state = state, session = session) {
                                TextField(
                                    state = state,
                                    modifier = Modifier.testTag("jewel-spellcheck").width(280.dp),
                                )
                            }
                        }
                    }
                }
                onNodeWithTag("jewel-spellcheck").assertIsDisplayed()
            }
            val items =
                NucleusSpellcheckInstaller.menuItems(
                    word = "helo",
                    session = session,
                    onSuggestion = {},
                    onAddToDictionary = {},
                    separator = ContextMenuDivider,
                )
            assertTrue("expected menu items", items.isNotEmpty())
            assertSame(ContextMenuDivider, items.first())
            assertSame(ContextMenuDivider, items[items.lastIndex - 1])
            val addLabel = SpellcheckMenuModel.localizedAddToDictionaryLabel()
            val suggestions = items.filter { it !== ContextMenuDivider && it.label != addLabel }
            assertTrue("expected suggestions, got ${items.map { it.label }}", suggestions.isNotEmpty())
            assertEquals(addLabel, items.last().label)
        }
    }

    private fun isolatedUserDict() = Files.createTempFile("nucleus-spellcheck-jewel-", "-${UUID.randomUUID()}.dic")
}

private class ProbeContextMenuRepresentation : ContextMenuRepresentation {
    var drew: Boolean = false
        private set
    var items: List<ContextMenuItem> = emptyList()
        private set

    @Composable
    override fun Representation(
        state: ContextMenuState,
        items: () -> List<ContextMenuItem>,
    ) {
        drew = true
        this.items = items()
    }
}
