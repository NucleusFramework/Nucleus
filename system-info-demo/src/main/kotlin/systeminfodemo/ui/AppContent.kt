@file:Suppress("TooManyFunctions", "MagicNumber", "MatchingDeclarationName", "MaxLineLength", "LongMethod")

package systeminfodemo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.nucleus.window.AwtDecoratedWindowScope
import io.github.kdroidfilter.nucleus.window.jewel.JewelTitleBar
import io.github.kdroidfilter.nucleus.window.macOSLargeCornerRadius
import io.github.kdroidfilter.nucleus.window.newFullscreenControls
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.typography
import org.jetbrains.skiko.hostOs
import systeminfodemo.ui.panels.BatteryPanel
import systeminfodemo.ui.panels.CpuPanel
import systeminfodemo.ui.panels.DisksPanel
import systeminfodemo.ui.panels.GpuPanel
import systeminfodemo.ui.panels.HardwarePanel
import systeminfodemo.ui.panels.MemoryPanel
import systeminfodemo.ui.panels.NetworkPanel
import systeminfodemo.ui.panels.OverviewPanel
import systeminfodemo.ui.panels.ProcessesPanel
import systeminfodemo.ui.panels.SensorsPanel
import systeminfodemo.viewmodel.SystemInfoState
import systeminfodemo.viewmodel.SystemInfoViewModel

enum class NavItem(
    val label: String,
) {
    Overview("Overview"),
    Cpu("CPU"),
    Gpu("GPU"),
    Memory("Memory"),
    Disks("Disks"),
    Network("Network"),
    Sensors("Sensors"),
    Battery("Battery"),
    Processes("Processes"),
    Hardware("Hardware"),
}

@Composable
fun AwtDecoratedWindowScope.AppTitleBar() {
    val state by SystemInfoViewModel.state.collectAsState()
    val startPadding = if (hostOs.isMacOS) 80.dp else 8.dp

    JewelTitleBar(
        Modifier.newFullscreenControls().macOSLargeCornerRadius(),
        gradientStartColor = titleBarGradientColor(),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Start).padding(start = startPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nucleus System Info", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            val cpu = state.cpuInfo?.globalCpuUsage ?: 0f
            val mem = state.memoryInfo
            val memPct = if (mem != null && mem.totalMemory > 0) mem.usedMemory * 100f / mem.totalMemory else 0f
            StatusPill("CPU %.0f%%".format(cpu), accentGreen(cpu))
            StatusPill("RAM %.0f%%".format(memPct), accentBlue(memPct))
            state.osInfo?.let { StatusPill(formatDuration(it.uptime), Color(0xFF7A7E85)) }
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    color: Color,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    }
}

private fun accentGreen(pct: Float): Color =
    when {
        pct < 30f -> Color(0xFF5AB869)
        pct < 70f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

private fun accentBlue(pct: Float): Color =
    when {
        pct < 50f -> Color(0xFF3574F0)
        pct < 80f -> Color(0xFFD4A843)
        else -> Color(0xFFF75464)
    }

@Composable
fun AppContent() {
    val state by SystemInfoViewModel.state.collectAsState()
    var currentNav by remember { mutableStateOf(NavItem.Overview) }

    Row(Modifier.fillMaxSize()) {
        // Sidebar — Mission Center style with mini previews
        Column(
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(JewelTheme.globalColors.panelBackground)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SidebarItem("Overview", currentNav == NavItem.Overview, { currentNav = NavItem.Overview }) {
                Text("Dashboard", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            SidebarItem("CPU", currentNav == NavItem.Cpu, { currentNav = NavItem.Cpu }) {
                val brand =
                    state.cpuInfo
                        ?.cpus
                        ?.firstOrNull()
                        ?.brand ?: ""
                Text(
                    brand,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = JewelTheme.globalColors.text.info,
                )
                val usage = state.cpuInfo?.globalCpuUsage ?: 0f
                MiniProgressBar(usage / 100f, Color(0xFF5AB869))
                Text("%.0f%%".format(usage), fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            SidebarItem("GPU", currentNav == NavItem.Gpu, { currentNav = NavItem.Gpu }) {
                val gpus = state.gpus
                if (gpus.isNotEmpty()) {
                    Text(
                        gpus.first().name,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = JewelTheme.globalColors.text.info,
                    )
                    val usage = gpus.first().gpuUsage ?: 0f
                    MiniProgressBar(usage / 100f, Color(0xFF9C6ADE))
                    Text("%.0f%%".format(usage), fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                } else {
                    Text("No GPU", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                }
            }

            SidebarItem("Memory", currentNav == NavItem.Memory, { currentNav = NavItem.Memory }) {
                val mem = state.memoryInfo
                if (mem != null && mem.totalMemory > 0) {
                    val pct = mem.usedMemory * 100f / mem.totalMemory
                    Text(
                        "${formatBytes(mem.usedMemory)} / ${formatBytes(mem.totalMemory)}",
                        fontSize = 11.sp,
                        color = JewelTheme.globalColors.text.info,
                    )
                    MiniProgressBar(pct / 100f, Color(0xFF3574F0))
                    Text("%.0f%%".format(pct), fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                }
            }

            SidebarItem("Storage", currentNav == NavItem.Disks, { currentNav = NavItem.Disks }) {
                val totalUsed = state.disks.sumOf { it.totalSpace - it.availableSpace }
                val totalSpace = state.disks.sumOf { it.totalSpace }
                val pct = if (totalSpace > 0) totalUsed.toFloat() / totalSpace else 0f
                Text(
                    "${formatBytes(totalUsed)} / ${formatBytes(totalSpace)}",
                    fontSize = 11.sp,
                    color = JewelTheme.globalColors.text.info,
                )
                MiniProgressBar(pct, Color(0xFFD4A843))
                Text("${state.disks.size} disks", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            SidebarItem("Network", currentNav == NavItem.Network, { currentNav = NavItem.Network }) {
                state.connectivityInfo?.let { conn ->
                    Text(
                        if (conn.isConnected) "Connected" else "Disconnected",
                        fontSize = 11.sp,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
                val totalRx = state.networks.sumOf { it.receivedBytes }
                val totalTx = state.networks.sumOf { it.transmittedBytes }
                Text("TX: ${formatBytes(totalTx)}", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                Text("RX: ${formatBytes(totalRx)}", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            Divider(Orientation.Horizontal, Modifier.fillMaxWidth().padding(vertical = 4.dp))

            SidebarItem("Sensors", currentNav == NavItem.Sensors, { currentNav = NavItem.Sensors }) {
                val maxTemp = state.components.mapNotNull { it.temperature }.maxOrNull()
                if (maxTemp !=
                    null
                ) {
                    Text(
                        "%.0f\u00B0C max".format(maxTemp),
                        fontSize = 11.sp,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
                Text("${state.components.size} sensors", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            SidebarItem("Battery", currentNav == NavItem.Battery, { currentNav = NavItem.Battery }) {
                val battery = state.batteryInfo
                if (battery != null) {
                    val pct = battery.stateOfCharge * 100f
                    val color =
                        when {
                            pct < 20f -> Color(0xFFF75464)
                            pct < 50f -> Color(0xFFD4A843)
                            else -> Color(0xFF5AB869)
                        }
                    Text(
                        "%.0f%% — %s".format(pct, battery.state.name),
                        fontSize = 11.sp,
                        color = JewelTheme.globalColors.text.info,
                    )
                    MiniProgressBar(battery.stateOfCharge, color)
                } else {
                    Text("Not available", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
                }
            }

            SidebarItem("Processes", currentNav == NavItem.Processes, { currentNav = NavItem.Processes }) {
                Text("${state.processes.size} running", fontSize = 11.sp, color = JewelTheme.globalColors.text.info)
            }

            SidebarItem("Hardware", currentNav == NavItem.Hardware, { currentNav = NavItem.Hardware }) {
                val vendor = state.motherboard?.vendorName ?: state.product?.vendorName
                if (vendor !=
                    null
                ) {
                    Text(
                        vendor,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = JewelTheme.globalColors.text.info,
                    )
                }
            }
        }

        Divider(Orientation.Vertical, Modifier.fillMaxHeight())

        // Content
        VerticallyScrollableContainer(
            modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.toolwindowBackground),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(currentNav.label, style = JewelTheme.typography.h1TextStyle)
                Spacer(Modifier.height(4.dp))
                PanelContent(currentNav, state)
            }
        }
    }
}

@Composable
private fun PanelContent(
    nav: NavItem,
    state: SystemInfoState,
) {
    when (nav) {
        NavItem.Overview -> OverviewPanel(state)
        NavItem.Cpu -> CpuPanel(state)
        NavItem.Gpu -> GpuPanel(state)
        NavItem.Memory -> MemoryPanel(state)
        NavItem.Disks -> DisksPanel(state)
        NavItem.Network -> NetworkPanel(state)
        NavItem.Sensors -> SensorsPanel(state)
        NavItem.Battery -> BatteryPanel(state)
        NavItem.Processes -> ProcessesPanel(state)
        NavItem.Hardware -> HardwarePanel(state)
    }
}

private val sidebarItemShape = RoundedCornerShape(8.dp)

@Composable
private fun SidebarItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val borderColor =
        if (selected) {
            JewelTheme.globalColors.outlines.focused
        } else {
            JewelTheme.globalColors.borders.normal
                .copy(alpha = 0.5f)
        }
    val bgColor =
        if (selected) {
            JewelTheme.globalColors.outlines.focused
                .copy(alpha = 0.1f)
        } else {
            Color.Transparent
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(sidebarItemShape)
            .border(1.dp, borderColor, sidebarItemShape)
            .background(bgColor)
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun MiniProgressBar(
    progress: Float,
    color: Color,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(JewelTheme.globalColors.borders.normal),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}
