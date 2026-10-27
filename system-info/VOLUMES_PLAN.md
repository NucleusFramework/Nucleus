# Volume identity, classification, events and eject requests (#750)

> **Temporary working document: delete this file once all three platforms are implemented,
> before the PR is merged.** It coordinates the agents implementing each platform on the
> `feat/volume-info` branch. Tick the boxes as you go and add any trap you hit under your
> platform, so the next agent does not rediscover it.

Issue: https://github.com/NucleusFramework/Nucleus/issues/750. The prior-art survey
(sysinfo, lfs-core, drivelist, Chromium storage_monitor, GLib/gvfs, Qt, Far Manager, Rufus) is in
https://github.com/NucleusFramework/Nucleus/issues/750#issuecomment-6450242271. Read it
before starting a platform.

Scope: one PR for all three platforms. The counterfeit-drive probe (issue point 4) is out
of scope.

## Shared contract (common Kotlin, done in the Linux pass)

- [x] `model/DiskConnection`: `UNKNOWN, INTERNAL, EXTERNAL, NETWORK, VIRTUAL`. The native side
  sends the **ordinal** (`DiskConnection.entries.getOrElse(code) { UNKNOWN }`), so never
  reorder the entries.
- [x] `DiskInfo` gets two new trailing fields with defaults: `volumeId: String? = null` and
  `connection: DiskConnection = UNKNOWN`.
  - `isRemovable` now means "hot-pluggable or removable media". It is true for any `EXTERNAL`
    disk, plus card readers / optical drives whose media can be removed.
  - The space fields are `0` when unknown, e.g. a network mount that did not answer in time.
- [x] Every disk scan runs under `diskScanLock` (`DiskMonitor.kt`): `SystemInfo.disks()` and the
  monitor's rescans. Each platform's native `disks()` keeps a static snapshot read by N JNI
  calls, and the monitor now rescans from a background thread, so the lock keeps those calls
  consistent. No native change is needed for that.
- [x] `SystemInfo.disksFlow(): Flow<List<DiskInfo>>` is backed by the common `DiskMonitor`:
  - reference-counted: the first collector starts the native monitor, the last one stops it;
  - the native side only signals "something changed" (no payload; bursts and spurious
    signals are fine);
  - Kotlin debounces (100 ms), rescans on `Dispatchers.IO`, and emits only when the set of
    volumes changes (free space is ignored for deduplication).
- [x] `SystemInfo.onEjectRequest(handler: (DiskInfo) -> Boolean): AutoCloseable` is backed by
  the common `EjectArbiter`:
  - The native approval hook is registered while at least one handler exists.
  - The native callback thread calls `arbiter.decide(disk)`. Handlers run on a worker
    thread with a **3 s total budget**. On timeout or exception the eject is allowed. If any
    handler returns `false`, the eject is denied.
  - Linux has no such hook: `onEjectRequest` returns a no-op registration there.
- [x] `PlatformSystemInfo` gains `diskChanges()` and `ejectRequests(handler)` with defaults
  (a one-shot snapshot and a no-op). A platform that is not implemented yet still compiles
  and behaves sanely.

### Per-platform wiring checklist (same shape as connectivity)

Each platform must provide the following:
1. Bridge `external fun nativeStartDiskMonitor(): Boolean` / `nativeStopDiskMonitor()`, plus a
   `@JvmStatic fun onDisksChanged()` that calls `XxxSystemInfo.diskMonitor.onChanged()`.
2. For eject (Windows/macOS only): bridge `nativeStartEjectApproval(): Boolean` /
   `nativeStopEjectApproval()`, plus a `@JvmStatic fun onEjectRequest(mountPoint: String): Boolean`
   that returns `XxxSystemInfo.ejectArbiter.decide(...)`.
3. `reachability-metadata.json`: declare the new JNI-called methods (`onDisksChanged`,
   `onEjectRequest`) on the bridge type, otherwise native-image drops them silently.
4. In `XxxSystemInfo`, mirror `LinuxSystemInfo`:
   ```kotlin
   override fun diskChanges(): Flow<List<DiskInfo>> = if (ready()) diskMonitor.changes() else emptyFlow()
   val diskMonitor = DiskMonitor(start = bridge::nativeStartDiskMonitor, stop = bridge::nativeStopDiskMonitor, scan = ::disks)

   override fun ejectRequests(handler: (DiskInfo) -> Boolean): AutoCloseable =
       if (ready()) ejectArbiter.register(handler) else AutoCloseable { }
   val ejectArbiter = EjectArbiter(start = bridge::nativeStartEjectApproval, stop = bridge::nativeStopEjectApproval)
   ```
   In `onEjectRequest(mountPoint)`, build the `DiskInfo` from
   `synchronized(diskScanLock) { disks() }.firstOrNull { it.mountPoint == mountPoint }`. Fall back
   to a minimal `DiskInfo(name = mountPoint, …, mountPoint = mountPoint)` when the volume is
   already half gone.
   Also fill `volumeId` and `connection` in `disks()`, with two new parallel arrays as on Linux:
   `nativeDiskVolumeIds()` (empty string = none) and `nativeDiskConnections()` (ordinals).
5. `./gradlew :system-info:apiDump` if the public API moved, then `:system-info:detekt` and
   `ktlintCheck`.

## Linux

- [x] Parse `/proc/self/mountinfo` instead of `/proc/mounts`. That gives `major:minor`,
  decoded `\040` escapes, and read-only from the mount options. When a mount point appears
  twice, the later entry wins (over-mount).
- [x] Resolve the block device from `/sys/dev/block/M:m`. Fall back to `stat(source).st_rdev`
  for btrfs (anonymous `0:NN`).
  - partition → disk via the `partition` file, never by parsing names; this fixes `nvme0n1` →
    `nvme0n`;
  - recurse `slaves/` (dm, LUKS, LVM, md, bcache).
- [x] Classification of the whole-disk leaves:
  - `loop`, `zram`, `ram` and anything under `/devices/virtual/` → `VIRTUAL`; `nbd` → `NETWORK`;
  - `mmcblk` → `device/type` `SD`/`SDIO` gives `EXTERNAL`, `MMC` (eMMC) gives `INTERNAL`;
  - device-chain `removable` attribute (util-linux `sysfs_devchain_is_removable`, public
    domain): the closest `fixed`/`removable` ancestor decides;
  - when the chain says nothing, a `usb`/`firewire` ancestor → `EXTERNAL`;
  - otherwise `INTERNAL`;
  - several leaves: they must all agree, otherwise `INTERNAL`.
- [x] Network: fs types `nfs*`, `cifs`, `smb3`, `smbfs`, `ncpfs`, `afs`, `ceph`, `glusterfs`, `9p`,
  `davfs`, `fuse.sshfs` / `fuse.rclone` / `fuse.s3fs`, and any `fuse.*` whose source looks remote
  (`host:` or `//`).
- [x] `volumeId`: `/dev/disk/by-uuid` (then `by-partuuid`) symlink matching the block name. Network
  volumes use the mount source. Read sysfs and `/dev/disk` directly, not the udev db, which is
  absent in Flatpak.
- [x] `kind` (HDD/SSD) is read from the resolved leaf (fixes NVMe and dm).
- [x] `statvfs` runs on a throwaway thread with a 1 s timeout. A mount whose probe is still
  stuck is not probed again until that probe returns, so a dead NFS server does not leak a
  thread per scan.
- [x] Monitor: a dedicated thread runs `poll(POLLPRI)` on `/proc/self/mountinfo` plus an `eventfd`
  used for stop, and calls `onDisksChanged`. udev is not needed: it does not see mounts.
- [x] Eject: none on Linux (no system hook; GIO's `MountPreUnmount` is private). Documented in
  KDoc.
- [x] Tests:
  - mountinfo / classification logic covered through the real system (`LinuxSystemInfoNativeTest`);
  - E2E `udisksctl loop-setup` + `mount` → `disksFlow` emits a `VIRTUAL` volume with a UUID,
    then its removal (skipped when udisks/polkit are unavailable).

Verified here: NVMe root (`INTERNAL` + UUID); a UDisks loop image (`VIRTUAL` + UUID); `disksFlow`
emitting on mount and unmount (`LinuxDisksE2ETest`). Not verified on hardware: a real USB stick or
SSD, an SD card, LUKS/LVM, btrfs, the statvfs timeout against a dead NFS/CIFS server.

## Windows

- [x] `volumeId`:
  - FS id: the 64-bit NTFS serial (`FSCTL_GET_NTFS_VOLUME_DATA`, or
    `GetFileInformationByHandleEx(FileIdInfo)`), formatted the way `blkid` shows it on Linux
    (16 uppercase hex digits for NTFS, `XXXX-XXXX` for FAT/exFAT);
  - **not** the 32-bit `dwVolumeSerialNumber` (sysinfo's weakness);
  - network drives: the UNC path (`WNetGetUniversalName`);
  - keep the volume GUID path (`\\?\Volume{…}\`) as the internal key only.
- [x] `EXTERNAL`:
  - Walk the devnode ancestors (`CM_Get_Parent`) for `CM_DEVCAP_REMOVABLE` without
    `CM_DEVCAP_DOCKDEVICE`. This is the Windows "Safely Remove" logic, as in Far Manager
    `far/hotplug.cpp` (BSD-3).
  - Map volume → disk with `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`. A volume spanning several
    disks is `EXTERNAL` only if all its disks are.
  - Do not rely on `USBSTOR`: UASP enumerates under `SCSI`.
- [x] Refine with `STORAGE_ADAPTER_DESCRIPTOR.BusType` (balena drivelist, Apache-2.0):
  - `BusTypeSd` / `BusTypeMmc` → card;
  - `BusTypeVirtual` / `BusTypeFileBackedVirtual` and hardware id `Msft Virtual Disk` →
    `VIRTUAL` (VHD, ImDisk);
  - `DRIVE_REMOTE` → `NETWORK`.
- [x] Never block:
  - all metadata calls on the calling (worker) thread with
    `SetThreadErrorMode(SEM_FAILCRITICALERRORS)`;
  - no `GetVolumeInformation` / free space on floppy, empty CD or `NO_ROOT_DIR` (Chromium);
  - free space of `DRIVE_REMOTE` drives on a thread with a 1 s timeout.
- [x] Monitor:
  - A hidden **top-level** window on its own thread. Not `HWND_MESSAGE`: broadcasts never reach
    message-only windows. Watch `WM_DEVICECHANGE` / `DBT_DEVTYP_VOLUME`, including the
    `DBTF_NET` flag for network drives.
  - Add `CM_Register_Notification(GUID_DEVINTERFACE_VOLUME)`.
  - Answer `WM_QUERYENDSESSION` with TRUE so the window never blocks shutdown.
  - Each event → `onDisksChanged()`.
- [x] Eject:
  - one `CM_Register_Notification(CM_NOTIFY_FILTER_TYPE_DEVICEHANDLE)` per volume, on a handle
    opened with access 0 + `FILE_FLAG_BACKUP_SEMANTICS`;
  - on `QUERYREMOVE`, call `onEjectRequest`. If allowed, close **our** handle (otherwise we are
    the one blocking the eject) and return `ERROR_SUCCESS`; if denied, return `ERROR_CANCELLED`;
  - reopen on `QUERYREMOVEFAILED`;
  - also handle `GUID_IO_VOLUME_LOCK` / `DISMOUNT` (format, chkdsk);
  - never call `CM_Unregister_Notification` from inside the callback.
- [x] Tests on CI (the runners are admin):
  - `New-VHD` / `Mount-DiskImage` → `VIRTUAL`, `volumeId`, `disksFlow` emits arrival then
    removal;
  - ~~`Dismount-DiskImage` while a handler denies → the dismount fails~~: impossible, see below.

Notes from the Windows pass (Windows 11 24H2, x64, not elevated):
- `FILE_ID_INFO.VolumeSerialNumber` (root directory opened with access 0 +
  `FILE_FLAG_BACKUP_SEMANTICS`) gives the full 64-bit NTFS serial without admin;
  `FSCTL_GET_NTFS_VOLUME_DATA` would need a readable volume handle. Its low half is what
  `vol` shows, which the E2E test checks.
- Network drives are not volumes: `FindFirstVolumeW` never returns them, so they come from the
  `DRIVE_REMOTE` letters of `GetLogicalDrives`. The UNC path comes from `WNetGetConnectionW`
  (the redirector's local table, no network round trip) rather than `WNetGetUniversalName`.
  `GetVolumeInformationW` + free space run on a throwaway thread with a 1 s timeout; a letter
  whose probe is still stuck is not probed again until it returns.
- Removable-media drives without media (empty card reader or optical drive,
  `IOCTL_STORAGE_CHECK_VERIFY2` fails) are left out, like on Linux/macOS where nothing is
  mounted there. Floppies are reported without touching the medium.
- **Disk images cannot be refused.** `Dismount-DiskImage` (ISO and VHD) sends `GUID_IO_VOLUME_LOCK`,
  then force-dismounts when the lock fails: holding our handle only turns the lock into
  `LOCK_FAILED`, the image detaches anyway, and no `QUERYREMOVE` is ever sent. A PnP eject of the
  virtual DVD/disk is refused by Windows itself (`CM_Request_Device_Eject` → `CR_REMOVE_VETOED`,
  `PNP_VetoIllegalDeviceRequest`). Same family as macOS force unmount: documented as a bypass.
  The approval therefore watches only removable/external volumes, and a lock or dismount
  (format, chkdsk, image detach) closes our handle so we never make them fail.
- The lock / dismount notifications are asynchronous: they cannot be turned into a veto.
- `SetTimer` only works from the window's thread: the CM callbacks post a resync instead.
- The E2E test uses an ISO built with IMAPI2 (`IMAPI2FS.MsftFileSystemImage`), which a standard
  user can `Mount-DiskImage`; a VHD needs elevation.

Verified here: NVMe system volume (`INTERNAL` + 64-bit NTFS id), a mounted ISO (`VIRTUAL`, UDF,
`XXXX-XXXX` id, read-only, `disksFlow` on mount and detach), a forced image detach neither
asked nor blocked by a refusing handler. Not verified on hardware: a USB stick or disk (the
`QUERYREMOVE` refusal, `WindowsDisksE2ETest` runs it with `NUCLEUS_E2E_EJECT_DRIVE=E:`), an SD
card (`BusTypeSd`), a UASP / Thunderbolt enclosure, a mapped network drive and the probe timeout
against a dead server, ARM64.

## macOS

- [x] One `DASession` on a private serial dispatch queue (`DASessionSetDispatchQueue`): no
  AppKit, no CFRunLoop, works with Tao. Register Appeared, Disappeared and DescriptionChanged
  (watch `kDADiskDescriptionWatchVolumePath`), with match = NULL so network and FUSE volumes
  come through. Signal `onDisksChanged()` only once `VolumePath` is set (a disk appears before
  it is mounted; Chromium pattern). A cheap AppKit-free extra signal is notify(3)
  `com.apple.system.kernel.mount` / `unmount`.
- [x] Classification, in order:
  - `VolumeNetwork`, or `!(f_flags & MNT_LOCAL)` → `NETWORK`;
  - `DeviceProtocol == "Virtual Interface"`, or no `DeviceInternal` key → `VIRTUAL` (DMG,
    Time Machine images);
  - `DeviceInternal == false` → `EXTERNAL`;
  - `DeviceProtocol == "Secure Digital"` → `EXTERNAL` even when internal (built-in SD slot,
    **verify on hardware**);
  - otherwise `INTERNAL`.
  APFS synthesized disks already inherit `DeviceInternal` / `DeviceProtocol` from their physical
  store, so no manual walk is needed. Do not trust `MediaRemovable` alone.
- [x] Null-check every `DADiskCopyDescription`: it returns NULL during a surprise removal and
  crashed drivelist (#329, #353).
- [x] `volumeId`: `kDADiskDescriptionVolumeUUIDKey`, else `fstype + f_mntfromname` for network
  volumes. Never the mount path (sysinfo's bug).
- [x] Hide system volumes (`MNT_DONTBROWSE` / `NSURLVolumeIsBrowsableKey`: VM, Preboot, Update,
  snapshots). Check what `/` and `/System/Volumes/Data` look like on a real Mac.
- [x] Never block: `getfsstat(MNT_NOWAIT)`, and `statfs` of network volumes on a thread with a
  1 s timeout, never on the DA queue.
- [x] Eject: `DARegisterDiskUnmountApprovalCallback`.
  - On deny, return `DADissenterCreate(kCFAllocatorDefault, kDAReturnBusy, reason)`; returning
    NULL approves.
  - The daemon gives up after **10 s** and then stops sending this session approval requests
    until it drains its queue. Our 3 s budget keeps us well under that.
  - Force unmount, surprise removal and a direct `umount(2)` bypass it: document this.
  - Teardown order: unregister, then `DASessionSetDispatchQueue(NULL)`, then drain the queue,
    then release.
- [x] Tests on CI:
  - `hdiutil create` + `attach` → `VIRTUAL`, UUID, `disksFlow` emits;
  - `diskutil eject` while a handler denies → the eject fails ("Unmount was dissented by PID …").

Notes from the macOS pass (macOS 27.0.1, Apple Silicon):
- `/` is the sealed system snapshot (`disk3s1s1`, read-only, browsable) and is reported; the
  Data volume behind it (`/System/Volumes/Data`) is `nobrowse` and hidden with the other system
  volumes, as are simulator runtime images under `/Library/Developer/CoreSimulator/Volumes`.
- `hdiutil attach -nobrowse` mounts with `MNT_DONTBROWSE`, so `disks()` hides such an image: the
  E2E test attaches without it.
- `hdiutil detach` is deprecated on macOS 27 (it prints a warning and suggests `diskutil eject`);
  the tests use `diskutil eject`, which also goes through the approval callback.
- A disk image reports `MediaRemovable = true`; `isRemovable` ignores it for `VIRTUAL` volumes,
  as Linux does for loop devices.
- DA scans use an unscheduled `DASession` (`DADiskCreateFromBSDName` + `DADiskCopyDescription`
  need neither a run loop nor a queue), created once and only touched under `diskScanLock`.
- `disks()` is called back from inside the unmount approval (to build the `DiskInfo`) while
  diskarbitrationd waits for our answer: that does not deadlock, the daemon keeps serving
  description requests.
- A Thunderbolt NVMe enclosure reports `DeviceInternal = false`, protocol `PCI-Express` →
  `EXTERNAL`, verified here.

Verified here: internal root (`INTERNAL` + UUID), a Thunderbolt NVMe SSD (`EXTERNAL`), an
attached DMG (`VIRTUAL` + UUID, `disksFlow` on attach and eject), an eject refused by a handler.
Not verified on hardware: a built-in SD slot, a USB stick, a network share (SMB/NFS) and the
statfs timeout against a dead server, an Intel Mac.
