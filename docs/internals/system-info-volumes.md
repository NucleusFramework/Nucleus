# system-info: volume identity, classification, events and eject requests (#750)

`DiskInfo.volumeId` / `DiskInfo.connection`, `SystemInfo.disksFlow()` and
`SystemInfo.onEjectRequest()`. Prior-art survey (sysinfo, lfs-core, drivelist, Chromium
storage_monitor, GLib/gvfs, Qt, Far Manager, Rufus):
https://github.com/NucleusFramework/Nucleus/issues/750#issuecomment-6450242271

## Shared contract

- `DiskConnection` crosses JNI as its **ordinal**: never reorder the entries.
- `isRemovable` means "hot-pluggable or removable media": true for every `EXTERNAL` disk, plus
  card readers and optical drives. Disk images report removable media on every OS and are not
  removable (`VIRTUAL`).
- Space fields are `0` when unknown (a network mount that did not answer in time).
- Every scan runs under `diskScanLock`: each native `disks()` keeps one static snapshot read back
  by several JNI calls, and `DiskMonitor` rescans from a background thread.
- `DiskMonitor`: reference-counted native monitor, the native side only signals "changed"
  (bursts and spurious signals are fine); Kotlin debounces 100 ms, rescans on `Dispatchers.IO`
  and emits only when the set of volumes changes (free space is ignored).
- `EjectArbiter`: handlers share a 3 s budget on a worker thread; timeout or exception allows the
  eject, any `false` denies it. Linux has no pre-eject hook: no-op registration.
- New JNI callbacks (`onDisksChanged`, `onEjectRequest`) must be declared in
  `reachability-metadata.json`.

## Linux

- `/proc/self/mountinfo`, not `/proc/mounts` (`major:minor`, decoded `\040`, read-only flag; a
  later entry for the same mount point wins).
- Block device from `/sys/dev/block/M:m`, falling back to `stat(source).st_rdev` (btrfs `0:NN`).
  Partition → disk through the `partition` file, never by parsing names (`nvme0n1`). Recurse
  `slaves/` for dm, LUKS, LVM, md, bcache.
- Classification: `loop`/`zram`/`ram`/`/devices/virtual/` → `VIRTUAL`, `nbd` → `NETWORK`, `mmcblk`
  by `device/type` (SD/SDIO external, MMC internal), then the device chain's `removable`
  attribute (util-linux `sysfs_devchain_is_removable`), then a `usb`/`firewire` ancestor. Several
  leaves must all agree, otherwise `INTERNAL`.
- Volume ids from `/dev/disk/by-uuid` (then `by-partuuid`), read directly: the udev db is absent
  in Flatpak.
- `statvfs` on a throwaway thread, 1 s timeout; a mount whose probe is still stuck is not probed
  again until it returns (no thread leak per scan against a dead NFS server).
- Monitor: `poll(POLLPRI)` on `mountinfo` plus an `eventfd` to stop. udev does not see mounts.

## Windows

- `volumeId`: 64-bit serial from `FILE_ID_INFO` (root opened with access 0 +
  `FILE_FLAG_BACKUP_SEMANTICS`, no admin needed); never the 32-bit `dwVolumeSerialNumber`.
  Network drives: UNC path from `WNetGetConnectionW` (local table, no round trip).
- `EXTERNAL`: devnode ancestors with `CM_DEVCAP_REMOVABLE` and not `CM_DEVCAP_DOCKDEVICE` (the
  "Safely Remove" logic), mapped through `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`; do not rely on
  `USBSTOR` (UASP enumerates under `SCSI`). Bus type refines: SD/MMC → card, virtual or
  `Msft Virtual Disk` → `VIRTUAL`.
- Network drives are not volumes (`FindFirstVolumeW` skips them): they come from the
  `DRIVE_REMOTE` letters, probed on a throwaway thread with a 1 s timeout. Removable-media drives
  without media are left out.
- Monitor: a hidden **top-level** window (broadcasts never reach `HWND_MESSAGE` windows) plus
  `CM_Register_Notification(GUID_DEVINTERFACE_VOLUME)`. Answer `WM_QUERYENDSESSION` with TRUE.
  `SetTimer` only works on the window's thread, so CM callbacks post a resync.
- Eject: one device-handle notification per removable/external volume; on `QUERYREMOVE` close
  **our** handle when allowed, return `ERROR_CANCELLED` when denied, reopen on
  `QUERYREMOVEFAILED`. Never unregister from inside the callback. Lock/dismount notifications are
  asynchronous and cannot veto: they close our handle so format/chkdsk never fail.
- **Disk images cannot be refused**: `Dismount-DiskImage` locks then force-dismounts, no
  `QUERYREMOVE` is sent. The E2E test mounts an ISO built with IMAPI2 (a VHD needs elevation).
  `NUCLEUS_E2E_EJECT_DRIVE=E:` runs the refusal against a real USB drive.

## macOS

- One `DASession` on a private serial dispatch queue (no AppKit, no CFRunLoop, works with Tao),
  match = NULL so network and FUSE volumes come through; signal only once `VolumePath` is set.
  notify(3) `com.apple.system.kernel.mount` / `unmount` as an extra signal.
- Classification: `VolumeNetwork` or `!MNT_LOCAL` → `NETWORK`; protocol `Virtual Interface` or no
  `DeviceInternal` → `VIRTUAL`; `DeviceInternal == false` → `EXTERNAL` (Thunderbolt NVMe
  enclosures included); protocol `Secure Digital` → `EXTERNAL`; else `INTERNAL`. APFS synthesized
  disks inherit these from their physical store. Never trust `MediaRemovable` alone.
- Null-check every `DADiskCopyDescription` (NULL during surprise removal; drivelist #329/#353).
- `volumeId`: volume UUID, else `fstype + f_mntfromname` for network volumes, never the path.
- `MNT_DONTBROWSE` volumes are hidden: the Data volume, VM/Preboot/Update, simulator runtimes, and
  any `hdiutil attach -nobrowse` image. `/` (the sealed snapshot) is reported.
- `getfsstat(MNT_NOWAIT)`; `statfs` of network volumes on a thread with a 1 s timeout, never on
  the DA queue. Scans use an unscheduled `DASession` touched only under `diskScanLock`.
- Eject: `DARegisterDiskUnmountApprovalCallback`, dissent with `kDAReturnBusy`. The daemon gives
  up after 10 s and then stops asking this session for a while. Calling `disks()` from inside the
  approval does not deadlock. Force unmount, surprise removal and `umount(2)` bypass it.
  Teardown: unregister, `DASessionSetDispatchQueue(NULL)`, drain the queue, release.
- `hdiutil detach` is deprecated on macOS 27: tests use `diskutil eject`.

## Not verified on hardware

Linux: USB disk, SD card, LUKS/LVM, btrfs, dead NFS/CIFS server. Windows: USB refusal, SD card,
UASP/Thunderbolt enclosure, mapped drive against a dead server, ARM64. macOS: built-in SD slot,
USB stick, SMB/NFS share and its timeout, Intel Mac.
