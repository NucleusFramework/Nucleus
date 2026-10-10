package dev.nucleusframework.lab.app.shell

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nucleusframework.lab.core.LabPaths
import dev.nucleusframework.window.tao.DockSide
import dev.nucleusframework.window.tao.SatelliteLayoutSnapshot
import dev.nucleusframework.window.tao.SatellitePlacement
import dev.nucleusframework.window.tao.SatelliteSnapshot
import dev.nucleusframework.window.tao.WindowAnchor
import dev.nucleusframework.window.tao.WindowConstraintAdjustment
import dev.nucleusframework.window.tao.WindowPositioner
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Satellite id of each shell pane in the workspace (and in the saved layout). */
val ShellPane.satelliteId: String get() = "lab.shell.${name.lowercase()}"

/** The panes a saved layout leaves open; panes it does not name keep their default (open). */
fun SatelliteLayoutSnapshot.openPanes(): Set<ShellPane> =
    ShellPane.entries.filterTo(mutableSetOf()) { satellites[it.satelliteId]?.isOpen ?: true }

/**
 * The shell's pane layout across launches, in `shell-layout.json` under [LabPaths.dataDir].
 * A missing or unreadable file means the default layout.
 */
@SingleIn(AppScope::class)
@Inject
class ShellLayoutStore {
    private val file: Path = LabPaths.dataDir.resolve("shell-layout.json")

    /** The layout saved by the previous run, read once. */
    val saved: SatelliteLayoutSnapshot? by lazy {
        if (!file.exists()) return@lazy null
        ShellLayoutCodec.decode(runCatching { file.readText() }.getOrNull().orEmpty()).also {
            if (it == null) logger.warning("Unreadable $file, starting from the default layout")
        }
    }

    /** Written beside the target then moved over it, so a crash never leaves half a file. */
    @Synchronized
    fun save(snapshot: SatelliteLayoutSnapshot) {
        runCatching {
            val temp = file.resolveSibling("${file.fileName}.tmp")
            temp.writeText(ShellLayoutCodec.encode(snapshot))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { logger.log(Level.WARNING, "Could not write $file", it) }
    }

    private companion object {
        val logger: Logger = Logger.getLogger(ShellLayoutStore::class.java.name)
    }
}

/** [SatelliteLayoutSnapshot] ⇄ JSON. Anything it cannot read decodes to `null`, never throws. */
object ShellLayoutCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    fun encode(snapshot: SatelliteLayoutSnapshot): String =
        json.encodeToString(StoredLayout.serializer(), snapshot.toStored())

    fun decode(text: String): SatelliteLayoutSnapshot? =
        runCatching { json.decodeFromString(StoredLayout.serializer(), text).toSnapshot() }.getOrNull()

    @Serializable
    private data class StoredLayout(
        val version: Int = VERSION,
        val satellites: Map<String, StoredSatellite> = emptyMap(),
        val dockExtents: Map<String, Float> = emptyMap(),
    )

    @Serializable
    private data class StoredSatellite(
        val open: Boolean,
        val docked: StoredDocked? = null,
        val floating: StoredFloating? = null,
    )

    @Serializable
    private data class StoredDocked(
        val side: String,
        val order: Int = 0,
        val extent: Float? = null,
        val weight: Float = 1f,
    )

    /** The anchor rectangle is not stored: the shell never declares one. */
    @Serializable
    private data class StoredFloating(
        val width: Float,
        val height: Float,
        val parentAnchor: String,
        val childAnchor: String,
        val x: Float,
        val y: Float,
        val flip: List<Boolean> = listOf(false, false),
        val slide: List<Boolean> = listOf(false, false),
        val resize: List<Boolean> = listOf(false, false),
    )

    private fun SatelliteLayoutSnapshot.toStored() =
        StoredLayout(
            satellites = satellites.mapValues { (_, saved) -> saved.toStored() },
            dockExtents = dockExtents.entries.associate { (side, extent) -> side.name to extent.value },
        )

    private fun SatelliteSnapshot.toStored(): StoredSatellite =
        when (val p = placement) {
            is SatellitePlacement.Docked ->
                StoredSatellite(isOpen, docked = StoredDocked(p.side.name, p.order, p.extent?.value, p.weight))
            is SatellitePlacement.Floating -> {
                val positioner = p.positioner
                val adjustment = positioner.constraintAdjustment
                StoredSatellite(
                    isOpen,
                    floating =
                        StoredFloating(
                            width = p.size.width.value,
                            height = p.size.height.value,
                            parentAnchor = positioner.parentAnchor.name,
                            childAnchor = positioner.childAnchor.name,
                            x = positioner.offset.x.value,
                            y = positioner.offset.y.value,
                            flip = listOf(adjustment.flipHorizontal, adjustment.flipVertical),
                            slide = listOf(adjustment.slideHorizontal, adjustment.slideVertical),
                            resize = listOf(adjustment.resizeHorizontal, adjustment.resizeVertical),
                        ),
                )
            }
        }

    private fun StoredLayout.toSnapshot(): SatelliteLayoutSnapshot {
        require(version == VERSION) { "layout version $version" }
        return SatelliteLayoutSnapshot(
            satellites = satellites.mapValues { (_, stored) -> stored.toSnapshot() },
            dockExtents = dockExtents.entries.associate { (side, extent) -> DockSide.valueOf(side) to extent.dp },
        )
    }

    private fun StoredSatellite.toSnapshot(): SatelliteSnapshot {
        val placement =
            docked?.let { SatellitePlacement.Docked(DockSide.valueOf(it.side), it.order, it.extent?.dp, it.weight) }
                ?: floating?.toPlacement()
                ?: error("satellite with no placement")
        return SatelliteSnapshot(placement, open)
    }

    private fun StoredFloating.toPlacement() =
        SatellitePlacement.Floating(
            positioner =
                WindowPositioner(
                    parentAnchor = WindowAnchor.valueOf(parentAnchor),
                    childAnchor = WindowAnchor.valueOf(childAnchor),
                    offset = DpOffset(x.dp, y.dp),
                    constraintAdjustment =
                        WindowConstraintAdjustment(
                            flipHorizontal = flip[0],
                            flipVertical = flip[1],
                            slideHorizontal = slide[0],
                            slideVertical = slide[1],
                            resizeHorizontal = resize[0],
                            resizeVertical = resize[1],
                        ),
                ),
            size = DpSize(width.dp, height.dp),
        )

    private const val VERSION = 1
}
