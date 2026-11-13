@file:OptIn(ExperimentalObjCName::class)

package dev.nucleusframework.widget.ios

import dev.nucleusframework.widget.WidgetActionSignatures
import dev.nucleusframework.widget.WidgetEngine
import dev.nucleusframework.widget.WidgetId
import dev.nucleusframework.widget.WidgetInstance
import dev.nucleusframework.widget.WidgetState
import dev.nucleusframework.widget.WidgetTreeEncoder
import kotlinx.coroutines.runBlocking
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * What the widget extension's Swift calls through [NucleusWidgetKitEntryPoints]. Every call blocks
 * the caller, a WidgetKit background queue, until the engine's thread is done.
 */
internal object WidgetExtensionEntryPoints {
    /**
     * The widget [kind] at [family], composed from its state (an empty one when [preview]: the
     * gallery) as the renderer's JSON tree; `null` when the kind is not declared or the widgets
     * were not started.
     */
    fun render(
        kind: String,
        family: String,
        preview: Boolean,
    ): String? {
        val host = IosWidgetHost.current ?: return null
        val id = WidgetId("$kind/$family")
        val (_, checkedFamily) = IosWidgetHost.split(id) ?: return null
        if (!WidgetEngine.isDeclared(kind)) return null
        return runBlocking {
            WidgetEngine.serialized {
                val state = if (preview) WidgetState.decode(null) else host.loadState(id)
                val instance = WidgetInstance(id, kind, IosWidgetHost.sizeOf(checkedFamily), state)
                WidgetTreeEncoder.encode(WidgetEngine.render(instance).root, host::imagePath)
            }
        }
    }

    /** Runs a click of the widget [kind] at [family] and saves its state; whether the action ran. */
    fun perform(
        kind: String,
        family: String,
        key: String,
        signature: String,
        inputs: String,
    ): Boolean {
        val host = IosWidgetHost.current ?: return false
        val id = WidgetId("$kind/$family")
        val (_, checkedFamily) = IosWidgetHost.split(id) ?: return false
        if (!WidgetEngine.isDeclared(kind)) return false
        return runBlocking {
            WidgetEngine.serialized {
                val state = host.loadState(id)
                val instance = WidgetInstance(id, kind, IosWidgetHost.sizeOf(checkedFamily), state)
                val ran = WidgetActionSignatures.run(instance, key, signature, WidgetEngine.parseInputs(inputs))
                host.saveState(id, state)
                ran
            }
        }
    }
}

/**
 * The entry points of the widget extension (`src/swift/NucleusWidgetKit.swift`), which finds this
 * object through the Objective-C runtime by its exact name: the app's framework must export
 * widget-ios (`export(project(":widget-ios"))`, or the published `nucleus.widget-ios`) for the
 * class to exist. One string request in, fields separated by [SEPARATOR]:
 *
 * - `render ␟ kind ␟ family ␟ preview (0/1)` → the tree JSON, or `null`;
 * - `perform ␟ kind ␟ family ␟ key ␟ signature ␟ inputs` → `"1"` when the action ran, else `"0"`.
 */
@ObjCName("NucleusWidgetKitEntryPoints", exact = true)
public object NucleusWidgetKitEntryPoints {
    /** The field separator of a request (ASCII unit separator): no widget text holds it. */
    public const val SEPARATOR: Char = '\u001F'

    /** Answers one request of the extension (selector `callRequest:`); see the class. */
    public fun call(request: String): String? {
        val fields = request.split(SEPARATOR)
        return when (fields.firstOrNull()) {
            "render" ->
                fields.takeIf { it.size == RENDER_FIELDS }?.let {
                    val (kind, family, preview) = it.drop(1)
                    WidgetExtensionEntryPoints.render(kind, family, preview = preview == "1")
                }
            "perform" ->
                fields.takeIf { it.size == PERFORM_FIELDS }?.let(::perform)
            else -> null
        }
    }

    // perform ␟ kind ␟ family ␟ key ␟ signature ␟ inputs
    private fun perform(fields: List<String>): String {
        val (kind, family) = fields.subList(1, KEY_FIELD)
        val (key, signature, inputs) = fields.subList(KEY_FIELD, PERFORM_FIELDS)
        return if (WidgetExtensionEntryPoints.perform(kind, family, key, signature, inputs)) "1" else "0"
    }

    private const val KEY_FIELD = 3
    private const val RENDER_FIELDS = 4
    private const val PERFORM_FIELDS = 6
}
