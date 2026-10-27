package dev.nucleusframework.systeminfo.model

/**
 * A mounted volume, see `SystemInfo.disks()`.
 *
 * @property name the device or source the volume is mounted from (`/dev/sda1`, `C:\`, `//server/share`).
 * @property fileSystem the file system type (`ext4`, `NTFS`, `apfs`, `nfs4`, …).
 * @property mountPoint where the volume is mounted.
 * @property totalSpace total size in bytes, `0` when unknown (for example a network mount that did not answer in time).
 * @property availableSpace bytes available to the current user, `0` when unknown.
 * @property kind `HDD`, `SSD` or `Unknown`.
 * @property isRemovable whether the volume can go away while the machine runs: true for every
 * [DiskConnection.EXTERNAL] volume and for drives with removable media (card readers, optical drives).
 * @property isReadOnly whether the volume is mounted read-only.
 * @property volumeId a stable identifier of the volume, to find it again after its drive letter or mount point
 * changed: the file system UUID or serial for local volumes, the mount source for network volumes, `null` when
 * the volume has none. It is stable on one machine; the same drive may report a different id on another OS.
 * @property connection how the storage is attached; prefer it to [isRemovable] to tell internal from external disks.
 */
public data class DiskInfo(
    val name: String,
    val fileSystem: String,
    val mountPoint: String,
    val totalSpace: Long,
    val availableSpace: Long,
    val kind: String,
    val isRemovable: Boolean,
    val isReadOnly: Boolean,
    val volumeId: String? = null,
    val connection: DiskConnection = DiskConnection.UNKNOWN,
)
