@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.test.ExperimentalTestApi::class,
)

package dev.nucleusframework.application.contextmenu

import androidx.compose.foundation.ContextMenuState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.nucleusframework.core.runtime.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContextMenuFlyoutComposeTest {
    @Test
    fun `adwaita flyout renders items and invokes the click handler`() {
        runComposeUiTest {
            var clicks = 0
            var dismissed = 0
            setContent {
                ContextMenuFlyout(
                    status = ContextMenuState.Status.Open(Rect(8f, 8f, 24f, 24f)),
                    entries =
                        listOf(
                            ContextMenuEntry.Item(
                                label = "Copy",
                                enabled = true,
                                icon = ContextMenuIcon.Copy,
                                onClick = { clicks++ },
                                shortcut = "Ctrl+C",
                            ),
                            ContextMenuEntry.Separator,
                            ContextMenuEntry.Item(
                                label = "Disabled",
                                enabled = false,
                                icon = null,
                                onClick = { error("disabled") },
                            ),
                            ContextMenuEntry.Submenu(
                                label = "More",
                                items =
                                    listOf(
                                        ContextMenuEntry.Item(
                                            label = "Nested",
                                            enabled = true,
                                            icon = ContextMenuIcon.Folder,
                                            onClick = {},
                                        ),
                                    ),
                            ),
                        ),
                    theme = AdwaitaMenuTheme,
                    onDismiss = { dismissed++ },
                )
            }
            waitForIdle()
            onNodeWithText("Copy").assertExists()
            onNodeWithText("Disabled").assertExists()
            onNodeWithText("More").assertExists()
            onNodeWithText("Copy").performClick()
            waitForIdle()
            assertEquals(1, clicks)
            assertTrue(dismissed >= 1)
        }
    }

    @Test
    fun `fluent and breeze themes keep a usable min width`() {
        assertTrue(FluentMenuTheme.minWidth.value > 0f)
        assertTrue(BreezeMenuTheme.minWidth.value > 0f)
        assertTrue(AdwaitaMenuTheme.minWidth.value > 0f)
        assertTrue(FluentMenuTheme.hasIcon(ContextMenuIcon.Copy))
        assertTrue(BreezeMenuTheme.hasIcon(ContextMenuIcon.Delete))
        assertTrue(!AdwaitaMenuTheme.hasIcon(ContextMenuIcon.Copy) || AdwaitaMenuTheme.showIcons)
    }

    @Test
    fun `linux representation opens the desktop flyout`() {
        assumeTrue(
            "macOS Representation uses NSMenu, not the Compose flyout",
            Platform.Current != Platform.MacOS,
        )
        runComposeUiTest {
            val state = ContextMenuState()
            state.status = ContextMenuState.Status.Open(Rect(4f, 4f, 16f, 16f))
            setContent {
                NativeContextMenuRepresentation.Representation(state) {
                    listOf(
                        NucleusContextMenuItem(
                            label = "Paste",
                            icon = ContextMenuIcon.Paste,
                            onClick = {},
                        ),
                        NucleusContextMenuDivider,
                        NucleusContextMenuItem("About", enabled = false, onClick = {}),
                    )
                }
            }
            waitForIdle()
            onNodeWithText("Paste").assertExists()
            onNodeWithText("About").assertExists()
        }
    }

    @Test
    fun `separator normalization drops leading trailing and duplicate rules`() {
        val copy = ContextMenuEntry.Item("Copy", true, ContextMenuIcon.Copy, {})
        val nested =
            ContextMenuEntry.Submenu(
                "More",
                listOf(ContextMenuEntry.Separator, copy, ContextMenuEntry.Separator),
            )
        val normalized =
            listOf(
                ContextMenuEntry.Separator,
                copy,
                ContextMenuEntry.Separator,
                ContextMenuEntry.Separator,
                nested,
                ContextMenuEntry.Separator,
            ).withNormalizedSeparators()
        assertEquals(3, normalized.size)
        assertTrue(normalized[0] is ContextMenuEntry.Item)
        assertTrue(normalized[1] is ContextMenuEntry.Separator)
        val sub = normalized[2] as ContextMenuEntry.Submenu
        assertEquals(1, sub.items.size)
        assertTrue(sub.items[0] is ContextMenuEntry.Item)
    }
}
