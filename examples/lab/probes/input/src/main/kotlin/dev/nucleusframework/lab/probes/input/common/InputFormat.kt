package dev.nucleusframework.lab.probes.input.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import dev.nucleusframework.lab.core.format.fmt

/** `(x, y)` in `Locale.ROOT`, like every number the Lab prints. */
fun Offset.fmt(decimals: Int = 2): String = "(${x.fmt(decimals)}, ${y.fmt(decimals)})"

fun PointerKeyboardModifiers.describe(): String =
    buildList {
        if (isCtrlPressed) add("Ctrl")
        if (isAltPressed) add("Alt")
        if (isShiftPressed) add("Shift")
        if (isMetaPressed) add("Meta")
    }.joinToString("+").ifEmpty { "—" }

fun PointerButtons.describe(): String =
    buildList {
        if (isPrimaryPressed) add("primary")
        if (isSecondaryPressed) add("secondary")
        if (isTertiaryPressed) add("tertiary")
        if (isBackPressed) add("back")
        if (isForwardPressed) add("forward")
    }.joinToString("+").ifEmpty { "none" }
