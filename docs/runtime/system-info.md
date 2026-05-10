# System Info

The `system-info` module provides cross-platform system information gathering for JVM desktop applications. It exposes a unified API covering CPU, memory, disks, network, temperature sensors, GPU, processes, users, and hardware identifiers — all via JNI native bridges (no JNA) on each platform.

## Installation

```kotlin
dependencies {
    implementation("dev.nucleusframework:nucleus.system-info:<version>")
}
```

## Quick Start

```kotlin
import dev.nucleusframework.nucleus.systeminfo.SystemInfo

fun main() {
    val os = SystemInfo.osInfo()
    println("OS: ${os?.longOsVersion} (${os?.cpuArch})")

    val mem = SystemInfo.memoryInfo()
    println("Memory: ${mem?.usedMemory?.div(1024 * 1024)}MB / ${mem?.totalMemory?.div(1024 * 1024)}MB")

    val cpu = SystemInfo.cpuInfo()
    println("CPU: ${cpu?.cpus?.firstOrNull()?.brand}, ${cpu?.cpus?.size} threads")

    SystemInfo.gpus().forEach { gpu ->
        println("GPU: ${gpu.name} — ${gpu.dedicatedVideoMemory / 1024 / 1024}MB VRAM")
        println("  Temp=${gpu.temperature?.let { "${it.toInt()}C" } ?: "N/A"}, Usage=${gpu.gpuUsage?.let { "${it.toInt()}%" } ?: "N/A"}")
    }

    val battery = SystemInfo.batteryInfo()
    println("Battery: ${battery?.let { "${(it.stateOfCharge * 100).toInt()}% (${it.state})" } ?: "none"}")

    val connectivity = SystemInfo.connectivityInfo()
    println("Network: connected=${connectivity?.isConnected}, metered=${connectivity?.meteredStatus}")

    println("Idle: ${SystemInfo.idleTime()}s since last input")
}
```

## Platform Support

| Subsystem | Windows | macOS | Linux |
|-----------|---------|-------|-------|
| OS info (name, version, hostname, uptime) | DXGI / WMI | sysctl / NSProcessInfo | `/proc`, `/etc/os-release` |
| Memory (total, free, available, swap) | `GlobalMemoryStatusEx` | sysctl `hw.memsize` | `/proc/meminfo` |
| CPU (per-core usage, frequency, brand) | `GetSystemTimes` / registry | `host_processor_info` | `/proc/stat`, `/proc/cpuinfo`, `/sys/devices/system/cpu/*/cpufreq` |
| Disks (mount, space, filesystem, type) | `GetDiskFreeSpaceEx` | `statvfs` | `/proc/mounts`, `statvfs`, `/sys/block/*/queue/rotational` |
| Temperature sensors | WMI thermal zones | IOKit `AppleSMC` | `/sys/class/hwmon`, `/sys/class/thermal` |
| GPU (name, VRAM, usage, temp, clocks, fan, power) | DXGI + NVML | IOKit Metal | DRM sysfs + NVML (dlopen) |
| Network interfaces (bytes, packets, errors, MAC, MTU) | `GetIfTable2` / `GetAdaptersAddresses` | `getifaddrs` + sysctl | `/sys/class/net/*/statistics` |
| Processes (PID, name, memory, CPU, status, cmdline) | `NtQuerySystemInformation` | `proc_listallpids` / `proc_pidinfo` | `/proc/[pid]/stat`, `/proc/[pid]/status` |
| Users | `NetUserEnum` | `getpwent` | `/etc/passwd` |
| Motherboard | WMI `Win32_BaseBoard` | IOKit `IOPlatformExpertDevice` | `/sys/devices/virtual/dmi/id/board_*` |
| Product | WMI `Win32_ComputerSystemProduct` | IOKit | `/sys/devices/virtual/dmi/id/product_*` |
| Battery (charge, state, health, time estimates) | `DeviceIoControl` (IOCTL) | IOKit `AppleSmartBattery` | `/sys/class/power_supply/*/` |
| Network connectivity (connected, metered) | `NLM_CONNECTIVITY` (COM) | `NWPathMonitor` | NetworkManager D-Bus |
| Idle time (seconds since last input) | `GetLastInputInfo` | IOKit `HIDIdleTime` | D-Bus `org.freedesktop.ScreenSaver` |

## API Reference

All methods are on the `SystemInfo` singleton object.

### Availability

| Function | Returns | Description |
|----------|---------|-------------|
| `isAvailable()` | `Boolean` | `true` if the native library loaded successfully on this platform. |
| `batteryInfo()` | `BatteryInfo?` | Battery charge, state, health, and time estimates. `null` if no battery. |
| `connectivityInfo()` | `ConnectivityInfo?` | Network connectivity and metered status. |
| `idleTime()` | `Long` | Seconds since last user input. `-1` if unavailable. |

### OS Info

```kotlin
val os: OsInfo? = SystemInfo.osInfo()
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String?` | OS name (e.g. "Ubuntu", "Windows 11") |
| `kernelVersion` | `String?` | Kernel version string |
| `osVersion` | `String?` | Short OS version (e.g. "24.04") |
| `longOsVersion` | `String?` | Full OS version string |
| `distributionId` | `String?` | Linux distribution ID (e.g. "ubuntu") |
| `hostName` | `String?` | Machine hostname |
| `cpuArch` | `String?` | CPU architecture (e.g. "x86_64", "aarch64") |
| `uptime` | `Long` | System uptime in seconds |
| `bootTime` | `Long` | Boot time as Unix epoch (seconds) |

### Memory Info

```kotlin
val mem: MemoryInfo? = SystemInfo.memoryInfo()
```

| Field | Type | Description |
|-------|------|-------------|
| `totalMemory` | `Long` | Total physical RAM (bytes) |
| `freeMemory` | `Long` | Free physical RAM (bytes) |
| `availableMemory` | `Long` | Available memory including caches (bytes) |
| `usedMemory` | `Long` | Used memory = total - available (bytes) |
| `totalSwap` | `Long` | Total swap space (bytes) |
| `freeSwap` | `Long` | Free swap space (bytes) |
| `usedSwap` | `Long` | Used swap = total - free (bytes) |

### CPU Info

```kotlin
val cpu: CpuGlobalInfo? = SystemInfo.cpuInfo()
```

| Field | Type | Description |
|-------|------|-------------|
| `globalCpuUsage` | `Float` | Aggregate CPU usage across all cores (0–100%) |
| `physicalCoreCount` | `Int?` | Number of physical cores (`null` if unknown) |
| `cpus` | `List<CpuInfo>` | Per-logical-CPU info |

Each `CpuInfo`:

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Logical CPU name (e.g. "cpu0") |
| `vendorId` | `String` | Vendor ID (e.g. "GenuineIntel") |
| `brand` | `String` | Brand string (e.g. "Intel Core i9-14900K") |
| `frequency` | `Long` | Current frequency in MHz |
| `cpuUsage` | `Float` | Per-core usage (0–100%) |

### Disks

```kotlin
val disks: List<DiskInfo> = SystemInfo.disks()
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Device name |
| `fileSystem` | `String` | Filesystem type (e.g. "ext4", "NTFS") |
| `mountPoint` | `String` | Mount point path |
| `totalSpace` | `Long` | Total space (bytes) |
| `availableSpace` | `Long` | Available space (bytes) |
| `kind` | `String` | Disk type: "SSD", "HDD", or "Unknown" |
| `isRemovable` | `Boolean` | Whether the disk is removable (USB) |
| `isReadOnly` | `Boolean` | Whether the disk is mounted read-only |

### Temperature Sensors (Components)

```kotlin
val sensors: List<ComponentInfo> = SystemInfo.components()
```

| Field | Type | Description |
|-------|------|-------------|
| `label` | `String` | Sensor label (e.g. "coretemp Package id 0") |
| `temperature` | `Float?` | Current temperature in Celsius |
| `max` | `Float?` | Historical max temperature |
| `critical` | `Float?` | Critical temperature threshold |

### GPU

```kotlin
val gpus: List<GpuInfo> = SystemInfo.gpus()
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | GPU device name |
| `vendorId` | `Long` | PCI vendor ID (e.g. `0x10DE` for NVIDIA) |
| `deviceId` | `Long` | PCI device ID |
| `dedicatedVideoMemory` | `Long` | Dedicated VRAM (bytes) |
| `dedicatedSystemMemory` | `Long` | Dedicated system memory (bytes) |
| `sharedSystemMemory` | `Long` | Shared system memory / GTT (bytes) |
| `driverVersion` | `String?` | Driver version string |
| `temperature` | `Float?` | GPU temperature (Celsius) |
| `gpuUsage` | `Float?` | GPU utilization (0–100%) |
| `memoryUsed` | `Long?` | VRAM currently used (bytes) |
| `coreClockMhz` | `Int?` | Current core clock (MHz) |
| `memoryClockMhz` | `Int?` | Current memory clock (MHz) |
| `fanSpeedPercent` | `Float?` | Fan speed (0–100%) |
| `powerDrawWatts` | `Float?` | Current power draw (watts) |

#### GPU Backend Details

| Platform | Backend | Static Info | Live Metrics |
|----------|---------|-------------|--------------|
| **Windows** | DXGI enumeration + NVML (dlopen) | Name, VRAM, vendor/device IDs via DXGI | Temperature, usage, clocks, fan, power via NVML |
| **macOS** | IOKit + Metal | Name, VRAM, vendor/device IDs via IOKit | Temperature via SMC (when available) |
| **Linux — NVIDIA** | DRM enumeration + NVML (`libnvidia-ml.so.1`, dlopen at runtime) | Name, VRAM, driver version via NVML | Temperature, usage, VRAM used, clocks, fan, power via NVML |
| **Linux — AMD** | DRM enumeration + amdgpu sysfs | Name, VRAM (`mem_info_vram_total`), GTT (`mem_info_gtt_total`) | Temperature (`hwmon/temp1_input`), usage (`gpu_busy_percent`), VRAM used (`mem_info_vram_used`), clocks (`pp_dpm_sclk/mclk`), fan (`pwm1`), power (`power1_average`) |
| **Linux — Intel** | DRM enumeration + i915/xe sysfs | Name, local memory (`lmem_total_bytes` for discrete) | Temperature (`hwmon/temp1_input`), clock (`gt_cur_freq_mhz`), local memory used (`lmem_used_bytes`) |

!!! note
    On Linux, NVIDIA metrics require the NVIDIA driver to be installed (provides `libnvidia-ml.so.1`). AMD and Intel metrics use pure sysfs reads with no external dependencies. All `Float?`/`Int?`/`Long?` GPU fields return `null` when the metric is not available on the current hardware or driver.

### Network Interfaces

```kotlin
val nets: List<NetworkInterfaceInfo> = SystemInfo.networks()
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Interface name (e.g. "eth0", "wlan0") |
| `receivedBytes` | `Long` | Total bytes received |
| `transmittedBytes` | `Long` | Total bytes transmitted |
| `receivedPackets` | `Long` | Total packets received |
| `transmittedPackets` | `Long` | Total packets transmitted |
| `errorsOnReceived` | `Long` | Receive errors |
| `errorsOnTransmitted` | `Long` | Transmit errors |
| `macAddress` | `String` | MAC address |
| `mtu` | `Long` | Maximum transmission unit |

### Processes

```kotlin
val procs: List<ProcessInfo> = SystemInfo.processes()
val proc: ProcessInfo? = SystemInfo.process(pid = 1234L)
```

| Field | Type | Description |
|-------|------|-------------|
| `pid` | `Long` | Process ID |
| `name` | `String` | Process name |
| `exe` | `String?` | Executable path |
| `memory` | `Long` | Resident memory (bytes) |
| `virtualMemory` | `Long` | Virtual memory (bytes) |
| `cpuUsage` | `Float` | CPU usage (0–100%) |
| `status` | `String` | Status: "Run", "Sleep", "Zombie", "Stop", etc. |
| `startTime` | `Long` | Start time (Unix epoch seconds) |
| `runTime` | `Long` | Run time (seconds) |
| `parentPid` | `Long?` | Parent PID (`null` for init/system) |
| `cmd` | `List<String>` | Command line arguments |
| `cwd` | `String?` | Current working directory |
| `root` | `String?` | Root directory |

### Users

```kotlin
val users: List<UserInfo> = SystemInfo.users()
```

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String` | Username |
| `id` | `String` | User ID / SID |
| `groupId` | `String` | Primary group ID |
| `groups` | `List<String>` | Group names |

### Hardware Info

```kotlin
val mb: MotherboardInfo? = SystemInfo.motherboard()
val prod: ProductInfo? = SystemInfo.product()
```

`MotherboardInfo`:

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String?` | Board name |
| `vendorName` | `String?` | Board vendor |
| `version` | `String?` | Board version |
| `serialNumber` | `String?` | Board serial number |
| `assetTag` | `String?` | Asset tag |

`ProductInfo`:

| Field | Type | Description |
|-------|------|-------------|
| `name` | `String?` | Product name |
| `family` | `String?` | Product family |
| `serialNumber` | `String?` | Serial number |
| `sku` | `String?` | SKU |
| `uuid` | `String?` | System UUID |
| `version` | `String?` | Product version |
| `vendorName` | `String?` | System vendor |

### Battery Info

```kotlin
val battery: BatteryInfo? = SystemInfo.batteryInfo()
```

Returns `null` when no battery is detected (desktops without a battery) or when the native library is unavailable.

| Field | Type | Description |
|-------|------|-------------|
| `stateOfCharge` | `Float` | Charge level (0.0–1.0) |
| `state` | `BatteryState` | `Charging`, `Discharging`, `Full`, or `Unknown` |
| `isPluggedIn` | `Boolean` | Whether the device is connected to external power |
| `currentCapacity` | `Int` | Current capacity (mWh) |
| `maxCapacity` | `Int` | Full charge capacity (mWh) |
| `designCapacity` | `Int` | Original design capacity (mWh) |
| `cycleCount` | `Int` | Number of charge cycles |
| `voltage` | `Int` | Current voltage (mV) |
| `amperage` | `Int` | Current amperage (mA, negative when discharging) |
| `temperature` | `Float?` | Battery temperature (°C), `null` if unavailable |
| `health` | `Float` | Battery health (0.0–1.0, `maxCapacity / designCapacity`) |
| `timeToFull` | `Int?` | Estimated seconds until full, `null` if not charging |
| `timeToEmpty` | `Int?` | Estimated seconds until empty, `null` if not discharging |
| `manufacturer` | `String?` | Battery manufacturer |
| `modelName` | `String?` | Battery model name |
| `serialNumber` | `String?` | Battery serial number |

`BatteryState` enum:

| Value | Description |
|-------|-------------|
| `Charging` | Battery is charging |
| `Discharging` | Battery is discharging (on battery power) |
| `Full` | Battery is fully charged and plugged in |
| `Unknown` | State cannot be determined |

#### Platform details

| Platform | Backend | Notes |
|----------|---------|-------|
| **macOS** | IOKit `AppleSmartBattery` | Full telemetry including temperature and time estimates. Returns `null` if `BatteryInstalled` flag is absent. |
| **Linux** | `/sys/class/power_supply/*/` (sysfs) | Reads `capacity`, `status`, `voltage_now`, `current_now`, etc. Temperature from `temp` if available. |
| **Windows** | `DeviceIoControl` with `IOCTL_BATTERY_QUERY_INFORMATION` / `IOCTL_BATTERY_STATUS` | Enumerates battery devices via Setup API. Supports multi-battery systems (reports first battery). |

### Connectivity Info

```kotlin
val connectivity: ConnectivityInfo? = SystemInfo.connectivityInfo()
```

Returns `null` when the native library is unavailable.

| Field | Type | Description |
|-------|------|-------------|
| `isConnected` | `Boolean` | Whether any network connection is active |
| `meteredStatus` | `MeteredStatus` | Whether the connection is metered |

`MeteredStatus` enum:

| Value | Description |
|-------|-------------|
| `NOT_AVAILABLE` | Not connected — metered status is irrelevant |
| `UNKNOWN` | Connected, but metered status cannot be determined |
| `UNMETERED` | Connected via an unmetered network (Wi-Fi, Ethernet) |
| `METERED` | Connected via a metered network (cellular, tethered) |

#### Platform details

| Platform | Backend | Notes |
|----------|---------|-------|
| **macOS** | `NWPathMonitor` (Network framework) | Detects connectivity and metered status via path flags. |
| **Linux** | NetworkManager D-Bus (`org.freedesktop.NetworkManager`) | Reads `Connectivity` and `Metered` properties. Falls back to `NOT_AVAILABLE` if NetworkManager is absent. |
| **Windows** | Network List Manager COM API (`INetworkListManager`) | Uses `NLM_CONNECTIVITY` flags for connectivity and `NLM_INTERNET_CONNECTIVITY` for metered detection. |

### Idle Time

```kotlin
val idleSeconds: Long = SystemInfo.idleTime()
```

Returns the number of seconds since the user's last keyboard or mouse input. Returns `-1` if the native library is unavailable or idle time cannot be determined.

#### Platform details

| Platform | Backend | Notes |
|----------|---------|-------|
| **macOS** | IOKit `HIDIdleTime` | Reads the `HIDIdleTime` property from the IOKit registry (nanoseconds, converted to seconds). |
| **Linux** | D-Bus `org.freedesktop.ScreenSaver.GetSessionIdleTime` | Queries the screensaver D-Bus interface. Returns `-1` if the D-Bus service is unavailable. |
| **Windows** | `GetLastInputInfo` | Computes idle time from the difference between current tick count and last input event. |

## How It Works

### Linux

Each subsystem reads directly from the kernel's virtual filesystems:

| Subsystem | Source |
|-----------|--------|
| OS | `/etc/os-release`, `uname()` |
| Memory | `/proc/meminfo` |
| CPU usage | `/proc/stat` (delta-based, per-core) |
| CPU frequency | `/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq`, fallback to `/proc/cpuinfo` |
| Disks | `/proc/mounts` + `statvfs()`, type from `/sys/block/*/queue/rotational` |
| Temperature | `/sys/class/hwmon/hwmon*/temp*_input` (millidegrees C), fallback to `/sys/class/thermal/thermal_zone*` |
| GPU | DRM sysfs (`/sys/class/drm/card*/device/`) + NVML dlopen for NVIDIA |
| Network | `/sys/class/net/*/statistics/{rx_bytes,tx_bytes,...}` |
| Processes | `/proc/[pid]/stat`, `/proc/[pid]/status`, `/proc/[pid]/cmdline`, `/proc/[pid]/exe` |
| Users | `getpwent()` |
| Motherboard | `/sys/devices/virtual/dmi/id/board_*` |
| Product | `/sys/devices/virtual/dmi/id/product_*` |
| Battery | `/sys/class/power_supply/*/` (capacity, status, voltage_now, current_now, etc.) |
| Connectivity | NetworkManager D-Bus (`org.freedesktop.NetworkManager`) |
| Idle time | D-Bus `org.freedesktop.ScreenSaver.GetSessionIdleTime` |

### Windows

Uses Win32 APIs (DXGI, WMI, `GetSystemTimes`, `NtQuerySystemInformation`, `GetLastInputInfo`, etc.) via JNI. GPU metrics use NVML when an NVIDIA driver is present. Battery info via `DeviceIoControl` IOCTL. Connectivity via NLM COM API.

### macOS

Uses sysctl, IOKit, `host_processor_info`, `proc_listallpids`, `NWPathMonitor`, and Metal APIs via JNI (Objective-C). Battery info via IOKit `AppleSmartBattery`. Idle time via IOKit `HIDIdleTime`.

## Native Libraries

The module ships pre-built native binaries for:

- **Windows**: `nucleus_system_info.dll` (x64 + ARM64)
- **macOS**: `libnucleus_system_info.dylib` (x64 + arm64)
- **Linux**: `libnucleus_system_info.so` (x64 + aarch64)

On Linux, the library links only against `libdl` and `libm`. NVML (`libnvidia-ml.so.1`) is loaded at runtime via `dlopen()` — the module works without NVIDIA drivers, GPU metrics for NVIDIA cards simply return `null`.

## ProGuard

When ProGuard is enabled, preserve the native bridge classes:

```proguard
-keep class dev.nucleusframework.nucleus.systeminfo.** { *; }
```
