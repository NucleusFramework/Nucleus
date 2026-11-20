package dev.nucleusframework.lab.probes.rendering.partialredraw

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.util.Properties

/** Where the partial-redraw decision came from, in the order the runtime resolves it. */
enum class SwitchSource { SystemProperty, AppProperties, Default }

/**
 * What the Tao host decided at startup. `PartialRedraw` is internal to decorated-window-tao,
 * so this re-reads the same inputs with the same precedence.
 */
@Immutable
data class PartialRedrawConfig(
    val enabled: Boolean,
    val source: SwitchSource,
    /** The plugin's Compose patch (`LayerDamageTransform`) is on the classpath. */
    val composePatched: Boolean,
    val debug: Boolean,
    val tint: Boolean,
    val verify: Boolean,
    val verifyDump: String?,
)

interface PartialRedrawGateway {
    fun read(): PartialRedrawConfig
}

@ContributesBinding(AppScope::class)
@Inject
class SystemPartialRedrawGateway : PartialRedrawGateway {
    override fun read(): PartialRedrawConfig {
        val property = System.getProperty(PROPERTY)
        val appProperty = if (property == null) appPropertiesFlag() else null
        val verify = flag("$PROPERTY.verify")
        return PartialRedrawConfig(
            enabled = property?.let { it == "true" } ?: (appProperty == true),
            source =
                when {
                    property != null -> SwitchSource.SystemProperty
                    appProperty != null -> SwitchSource.AppProperties
                    else -> SwitchSource.Default
                },
            // A resource lookup, not reflection: the generated accessor exists only when patched.
            composePatched = javaClass.classLoader?.getResource(PATCH_MARKER) != null,
            debug = flag("$PROPERTY.debug"),
            // Same rule as the runtime: verify suppresses tint.
            tint = flag("$PROPERTY.tint") && !verify,
            verify = verify,
            verifyDump = System.getProperty("$PROPERTY.verify.dump"),
        )
    }

    private fun flag(name: String) = System.getProperty(name) == "true"

    /** `null` when the app properties carry no such key. */
    private fun appPropertiesFlag(): Boolean? =
        runCatching {
            javaClass.classLoader
                ?.getResourceAsStream(APP_PROPERTIES)
                ?.use { Properties().apply { load(it) } }
                ?.getProperty(APP_PROPERTIES_KEY)
                ?.let { it == "true" }
        }.getOrNull()

    private companion object {
        const val PROPERTY = "nucleus.tao.partialRedraw"
        const val APP_PROPERTIES = "nucleus/nucleus-app.properties"
        const val APP_PROPERTIES_KEY = "optimization.partialRedraw"
        const val PATCH_MARKER = "androidx/compose/ui/node/NucleusLayerDamage.class"
    }
}
