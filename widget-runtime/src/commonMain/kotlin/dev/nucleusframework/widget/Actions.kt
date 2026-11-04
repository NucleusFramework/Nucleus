package dev.nucleusframework.widget

/**
 * What happens when the user clicks a [Button] or a [WidgetModifier.clickable] element.
 *
 * Create one with [actionRunCallback], [actionOpenUrl], or pass a lambda directly to `onClick`.
 * After any action that runs app code, the widget is rendered again.
 */
public sealed interface Action

/** Code run by [actionRunCallback]. Like Glance's `ActionCallback`, minus the Android `Context`. */
public fun interface ActionCallback {
    /** Called when the action is invoked on the widget [id]. */
    public suspend fun onAction(
        id: WidgetId,
        parameters: ActionParameters,
    )
}

/** Values handed to an [ActionCallback]; build them with [actionParametersOf]. */
public class ActionParameters internal constructor(
    private val values: Map<Key<*>, Any>,
) {
    /** Typed key of an action parameter. */
    public class Key<T : Any>(
        public val name: String,
    ) {
        /** Pairs this key with [value], for [actionParametersOf]. */
        public infix fun to(value: T): Pair<Key<T>, T> = Pair(this, value)

        override fun equals(other: Any?): Boolean = other is Key<*> && other.name == name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = name
    }

    /** The value of [key], or `null`. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T : Any> get(key: Key<T>): T? = values[key] as T?

    /** Whether [key] has a value. */
    public operator fun contains(key: Key<*>): Boolean = key in values

    override fun toString(): String = values.toString()
}

/** Builds [ActionParameters] from `key to value` pairs. */
public fun actionParametersOf(vararg pairs: Pair<ActionParameters.Key<*>, Any>): ActionParameters =
    ActionParameters(pairs.toMap())

/**
 * Runs [callback] with [parameters] when clicked. Unlike Glance's `actionRunCallback<T>()`, the
 * callback is an instance: Nucleus stays reflection-free (GraalVM native image).
 */
public fun actionRunCallback(
    callback: ActionCallback,
    parameters: ActionParameters = actionParametersOf(),
): Action = RunCallbackAction(callback, parameters)

/** Opens [url] when clicked: a web page or the app's own deep link (`NucleusApp` scheme). */
public fun actionOpenUrl(url: String): Action = OpenUrlAction(url)

/** Leaves the widget's customization UI (see [isCustomizing]); input values are kept. */
public fun actionFinishCustomization(): Action = FinishCustomizationAction

internal object FinishCustomizationAction : Action

internal class LambdaAction(
    val block: () -> Unit,
) : Action

internal class RunCallbackAction(
    val callback: ActionCallback,
    val parameters: ActionParameters,
) : Action

internal class OpenUrlAction(
    val url: String,
) : Action
