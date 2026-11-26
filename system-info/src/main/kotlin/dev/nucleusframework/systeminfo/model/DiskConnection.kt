package dev.nucleusframework.systeminfo.model

/**
 * How the storage behind a volume is attached to the machine, see [DiskInfo.connection].
 *
 * Declaration order is part of the contract: the native sides return the ordinal.
 */
public enum class DiskConnection {
    /** The platform could not tell. */
    UNKNOWN,

    /** Built into the machine: SATA, NVMe, eMMC, … behind a port the firmware reports as fixed. */
    INTERNAL,

    /**
     * Attached through a hot-pluggable port or slot: USB (including UASP enclosures), Thunderbolt / USB4,
     * FireWire, SD / MMC cards. A Thunderbolt NVMe enclosure is only reported here when the firmware marks
     * its port as removable.
     */
    EXTERNAL,

    /** A network file system: NFS, SMB / CIFS, SSHFS, WebDAV, … */
    NETWORK,

    /** Backed by a file or by memory: disk images, loop devices, VHDs, RAM disks. */
    VIRTUAL,
}
