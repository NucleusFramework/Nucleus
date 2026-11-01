// Disk information: mounted volumes with space, kind, removable, read-only, connection
// type and volume id, plus a volume monitor and an eject approval hook.
//
// Local volumes come from FindFirstVolumeW (only those with a mount point); mapped network
// drives from the DRIVE_REMOTE drive letters, which the volume manager does not know about.
// Removable-media drives without media (empty card reader, CD drive) are left out, as on
// Linux and macOS where nothing is mounted there. Every call runs with
// SEM_FAILCRITICALERRORS, so a missing medium never pops a "Insert a disk" dialog, and
// network drives are probed on a throwaway thread with a 1 s timeout.
//
// Connection type: the volume is mapped to its disks (IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS,
// or IOCTL_STORAGE_GET_DEVICE_NUMBER for optical drives), and each disk is classified:
//   - BusTypeVirtual / BusTypeFileBackedVirtual, or a "Msft Virtual ..." device -> VIRTUAL
//     (VHD/VHDX, mounted ISOs)
//   - BusTypeSd -> EXTERNAL (BusTypeMmc is usually soldered eMMC and falls through)
//   - a devnode ancestor with CM_DEVCAP_REMOVABLE and not CM_DEVCAP_DOCKDEVICE -> EXTERNAL.
//     This is the "Safely Remove Hardware" logic (Far Manager far/hotplug.cpp, BSD-3), and
//     it covers USB, UASP (which enumerates under SCSI, not USBSTOR), eSATA and Thunderbolt.
//   - otherwise INTERNAL
// A volume spanning several disks keeps a class only when all its disks agree, otherwise
// it is INTERNAL. DRIVE_REMOTE drives are NETWORK.
//
// Volume id: the file system serial from FILE_ID_INFO, which is the full 64-bit serial on
// NTFS / ReFS (formatted as 16 hex digits, like blkid) and the 32-bit one elsewhere
// (XXXX-XXXX, like blkid for FAT / exFAT). Network drives use their UNC path.
//
// Monitor and eject approval share one thread owning a hidden top-level window: volume
// arrival / removal is broadcast (WM_DEVICECHANGE, DBT_DEVTYP_VOLUME) to top-level windows
// only, never to HWND_MESSAGE windows. A CM_Register_Notification on the volume interface
// class also catches volumes mounted without a drive letter.
//
// Eject approval: each removable or external volume gets a handle on its root
// directory (access 0) with a CM_NOTIFY_FILTER_TYPE_DEVICEHANDLE notification. On
// QUERYREMOVE the app is asked; if it allows the eject we close our handle, otherwise we
// would be the one blocking it, and if it refuses we answer ERROR_CANCELLED. The handle is
// reopened when the removal fails, and closed while another program locks or dismounts the
// volume (format, chkdsk), so we never block those. Only a PnP eject (Explorer's Eject,
// "Safely Remove Hardware", CM_Request_Device_Eject) asks: a forced dismount and a surprise
// removal do not, and disk images cannot be PnP-ejected at all (Windows answers
// PNP_VetoIllegalDeviceRequest), they only detach through a forced dismount.

#define WINVER 0x0A00
#define _WIN32_WINNT 0x0A00
#define NTDDI_VERSION 0x0A000000

#include "nucleus_system_info_common.h"
#include "../../../../../native-common/nucleus_jni.h"

#include <winioctl.h>
#include <setupapi.h>
#include <cfgmgr32.h>
#include <dbt.h>
#include <winnetwk.h>
#include <initguid.h>
#include <ioevent.h>

#define MAX_DISKS 128
#define MAX_DISK_EXTENTS 16
#define MAX_STORAGE_DEVICES 128
#define REMOTE_PROBE_TIMEOUT_MS 1000
#define REOPEN_RETRY_MS 5000

// DiskConnection ordinals, mirrored in DiskConnection.kt.
enum {
    CONNECTION_UNKNOWN = 0,
    CONNECTION_INTERNAL,
    CONNECTION_EXTERNAL,
    CONNECTION_NETWORK,
    CONNECTION_VIRTUAL,
};

// Device interface classes, defined here rather than through winioctl.h, whose
// DEFINE_GUIDs were already expanded as extern declarations by windows.h.
static const GUID DISK_INTERFACE = { 0x53f56307, 0xb6bf, 0x11d0, { 0x94, 0xf2, 0x00, 0xa0, 0xc9, 0x1e, 0xfb, 0x8b } };
static const GUID CDROM_INTERFACE = { 0x53f56308, 0xb6bf, 0x11d0, { 0x94, 0xf2, 0x00, 0xa0, 0xc9, 0x1e, 0xfb, 0x8b } };
static const GUID VOLUME_INTERFACE = { 0x53f5630d, 0xb6bf, 0x11d0, { 0x94, 0xf2, 0x00, 0xa0, 0xc9, 0x1e, 0xfb, 0x8b } };

typedef struct {
    char name[256];
    char file_system[64];
    char mount_point[512];
    ULONGLONG total_space;
    ULONGLONG available_space;
    char kind[16]; // "SSD", "HDD", "Unknown"
    BOOL removable;
    BOOL read_only;
    char volume_id[512];
    int connection;
} disk_entry_t;

static disk_entry_t g_disks[MAX_DISKS];
static int g_disk_count = 0;

static void copy_utf8(char *out, size_t out_size, const wchar_t *wstr) {
    out[0] = '\0';
    char *utf8 = wchar_to_utf8(wstr);
    if (!utf8) return;
    strncpy(out, utf8, out_size - 1);
    out[out_size - 1] = '\0';
    free(utf8);
}

// "\\?\Volume{GUID}\" -> "\\?\Volume{GUID}", the form CreateFileW opens as a device.
static void volume_device_path(const wchar_t *volume_name, wchar_t *out, size_t out_len) {
    wcsncpy(out, volume_name, out_len - 1);
    out[out_len - 1] = L'\0';
    size_t len = wcslen(out);
    if (len > 0 && out[len - 1] == L'\\') out[len - 1] = L'\0';
}

static HANDLE open_device(const wchar_t *path) {
    return CreateFileW(path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       NULL, OPEN_EXISTING, 0, NULL);
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

// Storage devices (disks and optical drives) with their devnode, built once per scan.
typedef struct {
    DWORD type;   // FILE_DEVICE_DISK or FILE_DEVICE_CD_ROM
    DWORD number; // the N of \\.\PhysicalDriveN / \\.\CdRomN
    DEVINST devinst;
} StorageNode;

typedef struct {
    StorageNode nodes[MAX_STORAGE_DEVICES];
    int count;
    BOOL built;
} StorageIndex;

static void index_interface_class(StorageIndex *index, const GUID *interface_class) {
    HDEVINFO set = SetupDiGetClassDevsW(interface_class, NULL, NULL, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (set == INVALID_HANDLE_VALUE) return;

    SP_DEVICE_INTERFACE_DATA iface = { sizeof(SP_DEVICE_INTERFACE_DATA) };
    for (DWORD i = 0; index->count < MAX_STORAGE_DEVICES &&
                      SetupDiEnumDeviceInterfaces(set, NULL, interface_class, i, &iface); i++) {
        DWORD size = 0;
        SetupDiGetDeviceInterfaceDetailW(set, &iface, NULL, 0, &size, NULL);
        if (size == 0) continue;
        SP_DEVICE_INTERFACE_DETAIL_DATA_W *detail = (SP_DEVICE_INTERFACE_DETAIL_DATA_W *)malloc(size);
        if (!detail) continue;
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);
        SP_DEVINFO_DATA info = { sizeof(SP_DEVINFO_DATA) };
        if (SetupDiGetDeviceInterfaceDetailW(set, &iface, detail, size, NULL, &info)) {
            HANDLE device = open_device(detail->DevicePath);
            if (device != INVALID_HANDLE_VALUE) {
                STORAGE_DEVICE_NUMBER number;
                DWORD returned = 0;
                if (DeviceIoControl(device, IOCTL_STORAGE_GET_DEVICE_NUMBER, NULL, 0,
                                    &number, sizeof(number), &returned, NULL)) {
                    StorageNode *node = &index->nodes[index->count++];
                    node->type = number.DeviceType;
                    node->number = number.DeviceNumber;
                    node->devinst = info.DevInst;
                }
                CloseHandle(device);
            }
        }
        free(detail);
    }
    SetupDiDestroyDeviceInfoList(set);
}

static DEVINST find_devinst(StorageIndex *index, DWORD type, DWORD number) {
    if (!index->built) {
        index_interface_class(index, &DISK_INTERFACE);
        index_interface_class(index, &CDROM_INTERFACE);
        index->built = TRUE;
    }
    for (int i = 0; i < index->count; i++) {
        if (index->nodes[i].type == type && index->nodes[i].number == number) return index->nodes[i].devinst;
    }
    return 0;
}

// Whether the device or one of its ancestors is hot-pluggable, the way "Safely Remove
// Hardware" decides which devices it lists.
static BOOL has_hotplug_ancestor(DEVINST devinst) {
    DEVINST node = devinst;
    for (int depth = 0; node && depth < 32; depth++) {
        DWORD caps = 0;
        ULONG size = sizeof(caps);
        if (CM_Get_DevNode_Registry_PropertyW(node, CM_DRP_CAPABILITIES, NULL, &caps, &size, 0) == CR_SUCCESS &&
            (caps & CM_DEVCAP_REMOVABLE) && !(caps & CM_DEVCAP_DOCKDEVICE))
            return TRUE;
        DEVINST parent = 0;
        if (CM_Get_Parent(&parent, node, 0) != CR_SUCCESS) break;
        node = parent;
    }
    return FALSE;
}

static BOOL descriptor_string_starts_with(const STORAGE_DEVICE_DESCRIPTOR *desc, DWORD size, DWORD offset,
                                          const char *prefix) {
    if (offset == 0 || offset >= size) return FALSE;
    const char *value = (const char *)desc + offset;
    while (*value == ' ') value++;
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

static int classify_storage_device(StorageIndex *index, DWORD type, DWORD number, BOOL *removable_media) {
    wchar_t path[64];
    swprintf(path, 64, type == FILE_DEVICE_CD_ROM ? L"\\\\.\\CdRom%lu" : L"\\\\.\\PhysicalDrive%lu", number);
    HANDLE device = open_device(path);
    if (device == INVALID_HANDLE_VALUE) return CONNECTION_UNKNOWN;

    STORAGE_PROPERTY_QUERY query = { StorageDeviceProperty, PropertyStandardQuery };
    BYTE buffer[1024];
    DWORD returned = 0;
    STORAGE_BUS_TYPE bus = BusTypeUnknown;
    BOOL virtual_device = FALSE;
    if (DeviceIoControl(device, IOCTL_STORAGE_QUERY_PROPERTY, &query, sizeof(query),
                        buffer, sizeof(buffer), &returned, NULL) &&
        returned >= offsetof(STORAGE_DEVICE_DESCRIPTOR, RawPropertiesLength)) {
        STORAGE_DEVICE_DESCRIPTOR *desc = (STORAGE_DEVICE_DESCRIPTOR *)buffer;
        bus = desc->BusType;
        if (desc->RemovableMedia) *removable_media = TRUE;
        // "Msft" / "Virtual Disk" (VHD, ImDisk), "Virtual DVD-ROM" (mounted ISO).
        virtual_device = descriptor_string_starts_with(desc, returned, desc->VendorIdOffset, "Msft") &&
                         descriptor_string_starts_with(desc, returned, desc->ProductIdOffset, "Virtual");
    }
    // The adapter knows the bus when the device descriptor does not.
    if (bus == BusTypeUnknown) {
        query.PropertyId = StorageAdapterProperty;
        STORAGE_ADAPTER_DESCRIPTOR adapter;
        if (DeviceIoControl(device, IOCTL_STORAGE_QUERY_PROPERTY, &query, sizeof(query),
                            &adapter, sizeof(adapter), &returned, NULL) &&
            returned >= offsetof(STORAGE_ADAPTER_DESCRIPTOR, BusType) + sizeof(adapter.BusType))
            bus = (STORAGE_BUS_TYPE)adapter.BusType;
    }
    CloseHandle(device);

    if (virtual_device || bus == BusTypeVirtual || bus == BusTypeFileBackedVirtual) return CONNECTION_VIRTUAL;
    if (bus == BusTypeSd) return CONNECTION_EXTERNAL;
    DEVINST devinst = find_devinst(index, type, number);
    if (devinst && has_hotplug_ancestor(devinst)) return CONNECTION_EXTERNAL;
    return CONNECTION_INTERNAL;
}

// Classifies a local volume from its disks. A volume spanning several disks keeps a
// class only when all of them agree.
static int classify_volume(StorageIndex *index, HANDLE volume, BOOL *removable_media) {
    BYTE buffer[sizeof(VOLUME_DISK_EXTENTS) + (MAX_DISK_EXTENTS - 1) * sizeof(DISK_EXTENT)];
    VOLUME_DISK_EXTENTS *extents = (VOLUME_DISK_EXTENTS *)buffer;
    DWORD returned = 0;
    int connection = -1;
    if (DeviceIoControl(volume, IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS, NULL, 0,
                        buffer, sizeof(buffer), &returned, NULL)) {
        for (DWORD i = 0; i < extents->NumberOfDiskExtents && i < MAX_DISK_EXTENTS; i++) {
            DWORD disk = extents->Extents[i].DiskNumber;
            BOOL seen = FALSE;
            for (DWORD j = 0; j < i; j++) seen |= extents->Extents[j].DiskNumber == disk;
            if (seen) continue;
            int disk_connection = classify_storage_device(index, FILE_DEVICE_DISK, disk, removable_media);
            connection = connection < 0 || connection == disk_connection ? disk_connection : CONNECTION_INTERNAL;
        }
    } else {
        // Optical drives and some removable media have no extents but a device number.
        STORAGE_DEVICE_NUMBER number;
        if (DeviceIoControl(volume, IOCTL_STORAGE_GET_DEVICE_NUMBER, NULL, 0,
                            &number, sizeof(number), &returned, NULL))
            connection = classify_storage_device(index, number.DeviceType, number.DeviceNumber, removable_media);
    }
    return connection < 0 ? CONNECTION_UNKNOWN : connection;
}

// QueryDosDevice target of a volume: \Device\HarddiskVolumeN, \Device\CdRomN, \Device\Floppy0...
static BOOL is_floppy(const wchar_t *volume_name) {
    wchar_t dos_name[MAX_PATH];
    volume_device_path(volume_name, dos_name, MAX_PATH);
    const wchar_t *bare = wcsncmp(dos_name, L"\\\\?\\", 4) == 0 ? dos_name + 4 : dos_name;
    wchar_t target[MAX_PATH];
    if (!QueryDosDeviceW(bare, target, MAX_PATH)) return FALSE;
    return wcsncmp(target, L"\\Device\\Floppy", 14) == 0;
}

static BOOL has_media(HANDLE volume) {
    DWORD returned = 0;
    return DeviceIoControl(volume, IOCTL_STORAGE_CHECK_VERIFY2, NULL, 0, NULL, 0, &returned, NULL);
}

// The facts the eject approval needs to decide whether to watch a volume.
typedef struct {
    int connection;
    BOOL removable;
    BOOL has_media;
} VolumeTraits;

static void read_volume_traits(StorageIndex *index, HANDLE volume, UINT drive_type, VolumeTraits *out) {
    BOOL removable_media = drive_type == DRIVE_REMOVABLE || drive_type == DRIVE_CDROM;
    out->has_media = !removable_media || has_media(volume);
    out->connection = classify_volume(index, volume, &removable_media);
    // Same rule as Linux and macOS: an image is never "removable", whatever its media says.
    out->removable = out->connection == CONNECTION_EXTERNAL ||
                     (out->connection != CONNECTION_VIRTUAL && removable_media);
}

// ---------------------------------------------------------------------------
// Volume id and kind
// ---------------------------------------------------------------------------

static void format_volume_id(char *out, size_t out_size, const wchar_t *file_system, ULONGLONG serial) {
    out[0] = '\0';
    if (serial == 0) return;
    if (serial > 0xFFFFFFFFULL || _wcsicmp(file_system, L"NTFS") == 0 || _wcsicmp(file_system, L"ReFS") == 0) {
        snprintf(out, out_size, "%016llX", serial);
    } else {
        snprintf(out, out_size, "%04X-%04X", (unsigned)(serial >> 16) & 0xFFFF, (unsigned)serial & 0xFFFF);
    }
}

// The 64-bit NTFS / ReFS serial; GetVolumeInformationW only gives its low 32 bits.
static ULONGLONG read_volume_serial(const wchar_t *volume_name, DWORD fallback) {
    HANDLE root = CreateFileW(volume_name, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                              NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (root == INVALID_HANDLE_VALUE) return fallback;
    FILE_ID_INFO info;
    ULONGLONG serial = fallback;
    if (GetFileInformationByHandleEx(root, FileIdInfo, &info, sizeof(info)) && info.VolumeSerialNumber)
        serial = info.VolumeSerialNumber;
    CloseHandle(root);
    return serial;
}

// SSD vs HDD from the seek penalty property.
static const char *detect_disk_kind(HANDLE volume) {
    STORAGE_PROPERTY_QUERY query = { StorageDeviceSeekPenaltyProperty, PropertyStandardQuery };
    DEVICE_SEEK_PENALTY_DESCRIPTOR desc;
    DWORD returned = 0;
    if (DeviceIoControl(volume, IOCTL_STORAGE_QUERY_PROPERTY, &query, sizeof(query),
                        &desc, sizeof(desc), &returned, NULL) &&
        returned >= sizeof(desc))
        return desc.IncursSeekPenalty ? "HDD" : "SSD";
    return "Unknown";
}

// ---------------------------------------------------------------------------
// Network drives: probed on a throwaway thread with a timeout
// ---------------------------------------------------------------------------

typedef struct {
    wchar_t root[4]; // "X:\"
    volatile LONG refs;
    HANDLE done;
    BOOL has_info;
    wchar_t file_system[64];
    DWORD flags;
    BOOL has_space;
    ULARGE_INTEGER total;
    ULARGE_INTEGER available;
} RemoteProbe;

// A probe still stuck on a dead server, per drive letter. That drive is not probed again
// until it returns, so a dead server does not cost a thread per scan. Guarded by the
// Kotlin diskScanLock, like the rest of the scan.
static RemoteProbe *g_stuck_probes[26];

static void probe_release(RemoteProbe *probe) {
    if (InterlockedDecrement(&probe->refs) > 0) return;
    CloseHandle(probe->done);
    free(probe);
}

static DWORD WINAPI remote_probe_thread(LPVOID arg) {
    RemoteProbe *probe = (RemoteProbe *)arg;
    SetThreadErrorMode(SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX, NULL);
    probe->has_info = GetVolumeInformationW(probe->root, NULL, 0, NULL, NULL, &probe->flags,
                                            probe->file_system, 64);
    ULARGE_INTEGER free_total;
    probe->has_space = GetDiskFreeSpaceExW(probe->root, &probe->available, &probe->total, &free_total);
    SetEvent(probe->done);
    probe_release(probe);
    return 0;
}

static void probe_remote(int letter, disk_entry_t *d) {
    RemoteProbe *stuck = g_stuck_probes[letter];
    if (stuck) {
        if (WaitForSingleObject(stuck->done, 0) != WAIT_OBJECT_0) return;
        g_stuck_probes[letter] = NULL;
        probe_release(stuck);
    }

    RemoteProbe *probe = (RemoteProbe *)calloc(1, sizeof(RemoteProbe));
    if (!probe) return;
    swprintf(probe->root, 4, L"%c:\\", L'A' + letter);
    probe->refs = 2;
    probe->done = CreateEventW(NULL, TRUE, FALSE, NULL);
    HANDLE thread = probe->done ? CreateThread(NULL, 0, remote_probe_thread, probe, 0, NULL) : NULL;
    if (!thread) {
        if (probe->done) CloseHandle(probe->done);
        free(probe);
        return;
    }
    CloseHandle(thread);

    if (WaitForSingleObject(probe->done, REMOTE_PROBE_TIMEOUT_MS) != WAIT_OBJECT_0) {
        g_stuck_probes[letter] = probe; // keeps our reference until it returns
        return;
    }
    if (probe->has_info) {
        copy_utf8(d->file_system, sizeof(d->file_system), probe->file_system);
        d->read_only = (probe->flags & FILE_READ_ONLY_VOLUME) ? TRUE : FALSE;
    }
    if (probe->has_space) {
        d->total_space = probe->total.QuadPart;
        d->available_space = probe->available.QuadPart;
    }
    probe_release(probe);
}

static void add_network_drives(void) {
    DWORD letters = GetLogicalDrives();
    for (int letter = 0; letter < 26 && g_disk_count < MAX_DISKS; letter++) {
        if (!(letters & (1u << letter))) continue;
        wchar_t root[4];
        swprintf(root, 4, L"%c:\\", L'A' + letter);
        if (GetDriveTypeW(root) != DRIVE_REMOTE) continue;

        disk_entry_t *d = &g_disks[g_disk_count];
        memset(d, 0, sizeof(*d));
        copy_utf8(d->mount_point, sizeof(d->mount_point), root);
        strcpy(d->kind, "Unknown");
        d->connection = CONNECTION_NETWORK;

        // The redirector's local connection table: no network round trip.
        wchar_t device[3] = { root[0], L':', L'\0' };
        wchar_t remote[MAX_PATH];
        DWORD remote_len = MAX_PATH;
        if (WNetGetConnectionW(device, remote, &remote_len) == NO_ERROR) {
            copy_utf8(d->name, sizeof(d->name), remote);
            copy_utf8(d->volume_id, sizeof(d->volume_id), remote);
        }
        probe_remote(letter, d);
        g_disk_count++;
    }
}

// ---------------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------------

static void add_local_volume(StorageIndex *index, const wchar_t *volume_name) {
    disk_entry_t *d = &g_disks[g_disk_count];
    memset(d, 0, sizeof(*d));

    // Volumes without a mount point (recovery, EFI) are not reported.
    wchar_t paths[512];
    DWORD paths_len = 0;
    if (!GetVolumePathNamesForVolumeNameW(volume_name, paths, 512, &paths_len) || paths[0] == L'\0') return;
    UINT drive_type = GetDriveTypeW(volume_name);
    if (drive_type == DRIVE_NO_ROOT_DIR || drive_type == DRIVE_UNKNOWN) return;
    copy_utf8(d->mount_point, sizeof(d->mount_point), paths);
    strcpy(d->kind, "Unknown");

    // A floppy drive spins up for every query: report it as it is.
    if (is_floppy(volume_name)) {
        d->removable = TRUE;
        d->connection = CONNECTION_INTERNAL;
        g_disk_count++;
        return;
    }

    wchar_t device_path[MAX_PATH];
    volume_device_path(volume_name, device_path, MAX_PATH);
    HANDLE volume = open_device(device_path);
    if (volume != INVALID_HANDLE_VALUE) {
        VolumeTraits traits;
        read_volume_traits(index, volume, drive_type, &traits);
        if (!traits.has_media) {
            CloseHandle(volume);
            return;
        }
        d->connection = traits.connection;
        d->removable = traits.removable;
        strcpy(d->kind, detect_disk_kind(volume));
        CloseHandle(volume);
    } else {
        d->removable = drive_type == DRIVE_REMOVABLE || drive_type == DRIVE_CDROM;
    }

    wchar_t label[MAX_PATH + 1], file_system[64];
    DWORD serial = 0, max_component = 0, flags = 0;
    if (GetVolumeInformationW(volume_name, label, MAX_PATH + 1, &serial, &max_component, &flags,
                              file_system, 64)) {
        copy_utf8(d->name, sizeof(d->name), label);
        copy_utf8(d->file_system, sizeof(d->file_system), file_system);
        d->read_only = (flags & FILE_READ_ONLY_VOLUME) ? TRUE : FALSE;
        format_volume_id(d->volume_id, sizeof(d->volume_id), file_system,
                         read_volume_serial(volume_name, serial));
    }

    ULARGE_INTEGER available, total, free_total;
    if (GetDiskFreeSpaceExW(volume_name, &available, &total, &free_total)) {
        d->total_space = total.QuadPart;
        d->available_space = available.QuadPart;
    }
    g_disk_count++;
}

static void refresh_disks(void) {
    g_disk_count = 0;
    DWORD previous_mode = 0;
    SetThreadErrorMode(SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX, &previous_mode);

    StorageIndex *index = (StorageIndex *)calloc(1, sizeof(StorageIndex));
    wchar_t volume_name[MAX_PATH];
    HANDLE find = index ? FindFirstVolumeW(volume_name, MAX_PATH) : INVALID_HANDLE_VALUE;
    if (find != INVALID_HANDLE_VALUE) {
        do {
            if (g_disk_count >= MAX_DISKS) break;
            add_local_volume(index, volume_name);
        } while (FindNextVolumeW(find, volume_name, MAX_PATH));
        FindVolumeClose(find);
    }
    free(index);
    add_network_drives();

    SetThreadErrorMode(previous_mode, NULL);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskCount(
    JNIEnv *env, jclass clazz) {
    refresh_disks();
    return g_disk_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) names[i] = g_disks[i].name;
    return to_string_array(env, names, g_disk_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskFileSystems(
    JNIEnv *env, jclass clazz) {
    const char *fs[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) fs[i] = g_disks[i].file_system;
    return to_string_array(env, fs, g_disk_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskMountPoints(
    JNIEnv *env, jclass clazz) {
    const char *mps[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) mps[i] = g_disks[i].mount_point;
    return to_string_array(env, mps, g_disk_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskTotalSpaces(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_disk_count);
    jlong vals[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].total_space;
    (*env)->SetLongArrayRegion(env, arr, 0, g_disk_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskAvailableSpaces(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_disk_count);
    jlong vals[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jlong)g_disks[i].available_space;
    (*env)->SetLongArrayRegion(env, arr, 0, g_disk_count, vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskKinds(
    JNIEnv *env, jclass clazz) {
    const char *kinds[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) kinds[i] = g_disks[i].kind;
    return to_string_array(env, kinds, g_disk_count);
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskRemovable(
    JNIEnv *env, jclass clazz) {
    jbooleanArray arr = (*env)->NewBooleanArray(env, g_disk_count);
    jboolean vals[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].removable ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, arr, 0, g_disk_count, vals);
    return arr;
}

JNIEXPORT jbooleanArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskReadOnly(
    JNIEnv *env, jclass clazz) {
    jbooleanArray arr = (*env)->NewBooleanArray(env, g_disk_count);
    jboolean vals[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) vals[i] = g_disks[i].read_only ? JNI_TRUE : JNI_FALSE;
    (*env)->SetBooleanArrayRegion(env, arr, 0, g_disk_count, vals);
    return arr;
}

// Empty strings for volumes without an id.
JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskVolumeIds(
    JNIEnv *env, jclass clazz) {
    const char *ids[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) ids[i] = g_disks[i].volume_id;
    return to_string_array(env, ids, g_disk_count);
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeDiskConnections(
    JNIEnv *env, jclass clazz) {
    jintArray arr = (*env)->NewIntArray(env, g_disk_count);
    jint vals[MAX_DISKS];
    for (int i = 0; i < g_disk_count; i++) vals[i] = (jint)g_disks[i].connection;
    (*env)->SetIntArrayRegion(env, arr, 0, g_disk_count, vals);
    return arr;
}

// ---------------------------------------------------------------------------
// Volume service: the hidden window thread behind the monitor and the eject approval
// ---------------------------------------------------------------------------

#define WM_VOLUMES_CHANGED (WM_APP + 1) // posted by the volume interface notification
#define WM_SET_APPROVAL (WM_APP + 2)    // sent by start/stop eject approval, wParam = on
#define WM_RESYNC_WATCHES (WM_APP + 3)  // posted by the per-volume notifications
#define WM_STOP_SERVICE (WM_APP + 4)
#define REOPEN_TIMER_ID 1

typedef enum {
    WATCH_OPEN,
    WATCH_RELEASED, // closed for an eject we allowed: removal or QUERYREMOVEFAILED follows
    WATCH_LOCKED,   // closed while another program locks or dismounts the volume
    WATCH_REOPEN,   // to reopen on the service thread
} WatchState;

typedef struct Service Service;

// One watched volume. Only the service thread creates, reopens and frees watches; the
// notification callbacks (thread pool) only close the handle and change the state, under
// the service's lock. A watch is freed only after CM_Unregister_Notification returned,
// which waits for its callbacks in flight.
typedef struct Watch {
    Service *service;
    wchar_t volume_name[MAX_PATH]; // "\\?\Volume{GUID}\", the key
    char mount_point[512];         // UTF-8, as reported in DiskInfo.mountPoint
    HANDLE handle;
    HCMNOTIFICATION notification;
    WatchState state;
    ULONGLONG locked_at;
    BOOL seen;
    struct Watch *next;
} Watch;

struct Service {
    JavaVM *jvm;
    jclass bridge; // global ref
    jmethodID on_disks_changed;
    jmethodID on_eject_request;
    JNIEnv *env; // the service thread's
    HANDLE thread;
    HANDLE ready;
    HWND hwnd;
    BOOL started;
    HCMNOTIFICATION volume_notification;
    volatile LONG monitoring;
    BOOL approving; // service thread only
    CRITICAL_SECTION lock;
    Watch *watches; // service thread only, except handle / state
};

static Service *service;
static SRWLOCK service_lock = SRWLOCK_INIT; // the monitor and the approval start and stop independently

// --- Eject approval ---

static BOOL ask_eject(Service *s, const char *mount_point) {
    JNIEnv *env = NULL;
    BOOL attached = FALSE;
    jint got = (*s->jvm)->GetEnv(s->jvm, (void **)&env, JNI_VERSION_1_6);
    if (got == JNI_EDETACHED) {
        JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-eject-approval", NULL };
        if ((*s->jvm)->AttachCurrentThreadAsDaemon(s->jvm, (void **)&env, &args) != JNI_OK) return TRUE;
        attached = TRUE;
    } else if (got != JNI_OK) {
        return TRUE;
    }

    jboolean allowed = JNI_TRUE;
    jstring jmount = (*env)->NewStringUTF(env, mount_point);
    if (jmount) {
        allowed = (*env)->CallStaticBooleanMethod(env, s->bridge, s->on_eject_request, jmount);
        if (nucleus_jni_clear_exception(env)) allowed = JNI_TRUE;
        (*env)->DeleteLocalRef(env, jmount);
    } else {
        nucleus_jni_clear_exception(env);
    }
    // Thread-pool threads are recycled: never leave one attached.
    if (attached) (*s->jvm)->DetachCurrentThread(s->jvm);
    return allowed ? TRUE : FALSE;
}

static void watch_close_handle(Watch *w, WatchState state) {
    Service *s = w->service;
    EnterCriticalSection(&s->lock);
    if (w->handle != INVALID_HANDLE_VALUE) CloseHandle(w->handle);
    w->handle = INVALID_HANDLE_VALUE;
    w->state = state;
    if (state == WATCH_LOCKED) w->locked_at = GetTickCount64();
    LeaveCriticalSection(&s->lock);
}

static void watch_request_reopen(Watch *w) {
    Service *s = w->service;
    EnterCriticalSection(&s->lock);
    w->state = WATCH_REOPEN;
    LeaveCriticalSection(&s->lock);
    PostMessageW(s->hwnd, WM_RESYNC_WATCHES, 0, 0);
}

// Runs on a thread-pool thread. Never unregisters: CM_Unregister_Notification from inside
// its own callback deadlocks.
static DWORD CALLBACK on_watch_event(HCMNOTIFICATION notification, PVOID context, CM_NOTIFY_ACTION action,
                                     PCM_NOTIFY_EVENT_DATA data, DWORD size) {
    (void)notification;
    (void)size;
    Watch *w = (Watch *)context;
    switch (action) {
    case CM_NOTIFY_ACTION_DEVICEQUERYREMOVE:
        if (!ask_eject(w->service, w->mount_point)) return ERROR_CANCELLED;
        // Otherwise our own handle would block the eject we just allowed.
        watch_close_handle(w, WATCH_RELEASED);
        return ERROR_SUCCESS;
    case CM_NOTIFY_ACTION_DEVICEQUERYREMOVEFAILED:
        watch_request_reopen(w);
        break;
    case CM_NOTIFY_ACTION_DEVICEREMOVEPENDING:
    case CM_NOTIFY_ACTION_DEVICEREMOVECOMPLETE:
        watch_close_handle(w, WATCH_RELEASED);
        PostMessageW(w->service->hwnd, WM_RESYNC_WATCHES, 0, 0);
        break;
    case CM_NOTIFY_ACTION_DEVICECUSTOMEVENT: {
        const GUID *event = &data->u.DeviceHandle.EventGuid;
        // Format, chkdsk and Dismount-DiskImage lock the volume, then dismount it by force
        // when the lock fails: they cannot be refused (the notification is asynchronous
        // anyway), and our handle would only make their lock fail.
        if (IsEqualGUID(event, &GUID_IO_VOLUME_LOCK) || IsEqualGUID(event, &GUID_IO_VOLUME_DISMOUNT)) {
            watch_close_handle(w, WATCH_LOCKED);
            // SetTimer only works on the window's own thread: the resync arms the retry.
            PostMessageW(w->service->hwnd, WM_RESYNC_WATCHES, 0, 0);
        } else if (IsEqualGUID(event, &GUID_IO_VOLUME_UNLOCK) || IsEqualGUID(event, &GUID_IO_VOLUME_LOCK_FAILED) ||
                   IsEqualGUID(event, &GUID_IO_VOLUME_DISMOUNT_FAILED) || IsEqualGUID(event, &GUID_IO_VOLUME_MOUNT)) {
            watch_request_reopen(w);
        }
        break;
    }
    default:
        break;
    }
    return ERROR_SUCCESS;
}

static void watch_unregister(Watch *w) {
    if (w->notification) {
        CM_Unregister_Notification(w->notification);
        w->notification = NULL;
    }
    watch_close_handle(w, WATCH_RELEASED);
}

static BOOL watch_open(Watch *w) {
    watch_unregister(w);
    // The root directory, access 0: enough to be told about the volume, never enough to
    // block a reader or writer.
    HANDLE handle = CreateFileW(w->volume_name, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (handle == INVALID_HANDLE_VALUE) return FALSE;

    CM_NOTIFY_FILTER filter;
    memset(&filter, 0, sizeof(filter));
    filter.cbSize = sizeof(filter);
    filter.FilterType = CM_NOTIFY_FILTER_TYPE_DEVICEHANDLE;
    filter.u.DeviceHandle.hTarget = handle;
    HCMNOTIFICATION notification = NULL;
    if (CM_Register_Notification(&filter, w, on_watch_event, &notification) != CR_SUCCESS) {
        CloseHandle(handle);
        return FALSE;
    }
    EnterCriticalSection(&w->service->lock);
    w->handle = handle;
    w->notification = notification;
    w->state = WATCH_OPEN;
    LeaveCriticalSection(&w->service->lock);
    return TRUE;
}

static void drop_watches(Service *s) {
    while (s->watches) {
        Watch *w = s->watches;
        s->watches = w->next;
        watch_unregister(w);
        free(w);
    }
    KillTimer(s->hwnd, REOPEN_TIMER_ID);
}

// Removable and external volumes with media can be ejected. Internal fixed volumes never
// get a removal request, and disk images refuse a PnP eject.
static BOOL is_ejectable(StorageIndex *index, const wchar_t *volume_name) {
    UINT drive_type = GetDriveTypeW(volume_name);
    if (drive_type == DRIVE_NO_ROOT_DIR || drive_type == DRIVE_UNKNOWN || drive_type == DRIVE_REMOTE) return FALSE;
    if (is_floppy(volume_name)) return FALSE;
    wchar_t device_path[MAX_PATH];
    volume_device_path(volume_name, device_path, MAX_PATH);
    HANDLE volume = open_device(device_path);
    if (volume == INVALID_HANDLE_VALUE) return FALSE;
    VolumeTraits traits;
    read_volume_traits(index, volume, drive_type, &traits);
    CloseHandle(volume);
    return traits.has_media && traits.removable;
}

static Watch *find_watch(Service *s, const wchar_t *volume_name) {
    for (Watch *w = s->watches; w; w = w->next) {
        if (_wcsicmp(w->volume_name, volume_name) == 0) return w;
    }
    return NULL;
}

// Brings the watches in line with the mounted volumes: new ejectable volumes get one,
// vanished volumes lose theirs, and watches waiting for a reopen get it.
static void resync_watches(Service *s) {
    if (!s->approving) return;
    for (Watch *w = s->watches; w; w = w->next) w->seen = FALSE;

    StorageIndex *index = (StorageIndex *)calloc(1, sizeof(StorageIndex));
    wchar_t volume_name[MAX_PATH];
    HANDLE find = index ? FindFirstVolumeW(volume_name, MAX_PATH) : INVALID_HANDLE_VALUE;
    if (find != INVALID_HANDLE_VALUE) {
        do {
            wchar_t paths[512];
            DWORD paths_len = 0;
            if (!GetVolumePathNamesForVolumeNameW(volume_name, paths, 512, &paths_len) || paths[0] == L'\0')
                continue;
            Watch *w = find_watch(s, volume_name);
            if (w) {
                w->seen = TRUE;
                continue;
            }
            if (!is_ejectable(index, volume_name)) continue;
            w = (Watch *)calloc(1, sizeof(Watch));
            if (!w) continue;
            w->service = s;
            w->handle = INVALID_HANDLE_VALUE;
            wcsncpy(w->volume_name, volume_name, MAX_PATH - 1);
            copy_utf8(w->mount_point, sizeof(w->mount_point), paths);
            w->seen = TRUE;
            if (!watch_open(w)) {
                free(w);
                continue;
            }
            w->next = s->watches;
            s->watches = w;
        } while (FindNextVolumeW(find, volume_name, MAX_PATH));
        FindVolumeClose(find);
    }
    free(index);

    BOOL locked = FALSE;
    ULONGLONG now = GetTickCount64();
    for (Watch **link = &s->watches; *link;) {
        Watch *w = *link;
        if (!w->seen) {
            *link = w->next;
            watch_unregister(w);
            free(w);
            continue;
        }
        EnterCriticalSection(&s->lock);
        WatchState state = w->state;
        ULONGLONG locked_at = w->locked_at;
        LeaveCriticalSection(&s->lock);
        // A lock ends with an unlock event, but a format remounts a new file system and
        // our registration may not hear about it: retry until the volume opens again.
        if (state == WATCH_REOPEN || (state == WATCH_LOCKED && now - locked_at >= REOPEN_RETRY_MS)) {
            if (!watch_open(w)) {
                watch_close_handle(w, WATCH_LOCKED);
                locked = TRUE;
            }
        } else if (state == WATCH_LOCKED) {
            locked = TRUE;
        }
        link = &w->next;
    }
    if (locked) {
        SetTimer(s->hwnd, REOPEN_TIMER_ID, REOPEN_RETRY_MS, NULL);
    } else {
        KillTimer(s->hwnd, REOPEN_TIMER_ID);
    }
}

// --- Window ---

static void volumes_changed(Service *s) {
    if (InterlockedCompareExchange(&s->monitoring, 0, 0)) {
        (*s->env)->CallStaticVoidMethod(s->env, s->bridge, s->on_disks_changed);
        nucleus_jni_clear_exception(s->env);
    }
    resync_watches(s);
}

static LRESULT CALLBACK service_window_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    if (msg == WM_NCCREATE) {
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)((CREATESTRUCTW *)lparam)->lpCreateParams);
        return TRUE;
    }
    Service *s = (Service *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);
    if (!s) return DefWindowProcW(hwnd, msg, wparam, lparam);

    switch (msg) {
    case WM_DEVICECHANGE:
        // Drive letters, media and mapped network drives (DBTF_NET) come and go here.
        if ((wparam == DBT_DEVICEARRIVAL || wparam == DBT_DEVICEREMOVECOMPLETE) && lparam &&
            ((DEV_BROADCAST_HDR *)lparam)->dbch_devicetype == DBT_DEVTYP_VOLUME)
            volumes_changed(s);
        return TRUE; // never BROADCAST_QUERY_DENY
    case WM_VOLUMES_CHANGED:
        volumes_changed(s);
        return 0;
    case WM_RESYNC_WATCHES:
    case WM_TIMER:
        resync_watches(s);
        return 0;
    case WM_SET_APPROVAL:
        s->approving = wparam != 0;
        if (s->approving) {
            resync_watches(s);
        } else {
            drop_watches(s);
        }
        return 0;
    case WM_QUERYENDSESSION:
        return TRUE; // never holds up a shutdown
    case WM_CLOSE:
        return 0; // only WM_STOP_SERVICE destroys the window
    case WM_STOP_SERVICE:
        drop_watches(s);
        if (s->volume_notification) {
            CM_Unregister_Notification(s->volume_notification);
            s->volume_notification = NULL;
        }
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(hwnd, msg, wparam, lparam);
    }
}

static DWORD CALLBACK on_volume_interface(HCMNOTIFICATION notification, PVOID context, CM_NOTIFY_ACTION action,
                                          PCM_NOTIFY_EVENT_DATA data, DWORD size) {
    (void)notification;
    (void)data;
    (void)size;
    if (action == CM_NOTIFY_ACTION_DEVICEINTERFACEARRIVAL || action == CM_NOTIFY_ACTION_DEVICEINTERFACEREMOVAL)
        PostMessageW(((Service *)context)->hwnd, WM_VOLUMES_CHANGED, 0, 0);
    return ERROR_SUCCESS;
}

static const wchar_t SERVICE_WINDOW_CLASS[] = L"NucleusSystemInfoVolumes";

static DWORD WINAPI service_thread(LPVOID arg) {
    Service *s = (Service *)arg;
    JavaVMAttachArgs args = { JNI_VERSION_1_6, "nucleus-disk-monitor", NULL };
    if ((*s->jvm)->AttachCurrentThreadAsDaemon(s->jvm, (void **)&s->env, &args) != JNI_OK) {
        SetEvent(s->ready);
        return 0;
    }
    SetThreadErrorMode(SEM_FAILCRITICALERRORS | SEM_NOOPENFILEERRORBOX, NULL);

    HMODULE module = NULL;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)service_thread, &module);
    WNDCLASSEXW wc;
    memset(&wc, 0, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = service_window_proc;
    wc.hInstance = module;
    wc.lpszClassName = SERVICE_WINDOW_CLASS;
    if (RegisterClassExW(&wc) || GetLastError() == ERROR_CLASS_ALREADY_EXISTS) {
        // Top-level and never shown: message-only windows do not get the volume broadcasts.
        s->hwnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, SERVICE_WINDOW_CLASS, L"", WS_POPUP,
                                  0, 0, 0, 0, NULL, NULL, module, s);
    }
    if (s->hwnd) {
        CM_NOTIFY_FILTER filter;
        memset(&filter, 0, sizeof(filter));
        filter.cbSize = sizeof(filter);
        filter.FilterType = CM_NOTIFY_FILTER_TYPE_DEVICEINTERFACE;
        filter.u.DeviceInterface.ClassGuid = VOLUME_INTERFACE;
        // Optional: the broadcasts alone cover drive letters.
        if (CM_Register_Notification(&filter, s, on_volume_interface, &s->volume_notification) != CR_SUCCESS)
            s->volume_notification = NULL;
    }
    s->started = s->hwnd != NULL;
    SetEvent(s->ready);

    MSG msg;
    while (s->started && GetMessageW(&msg, NULL, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    (*s->jvm)->DetachCurrentThread(s->jvm);
    return 0;
}

static void free_service(JNIEnv *env, Service *s) {
    if (s->thread) CloseHandle(s->thread);
    if (s->ready) CloseHandle(s->ready);
    if (s->bridge) (*env)->DeleteGlobalRef(env, s->bridge);
    DeleteCriticalSection(&s->lock);
    free(s);
}

// Starts the service thread if needed. Called with service_lock held.
static Service *acquire_service(JNIEnv *env, jclass clazz) {
    if (service) return service;
    Service *s = (Service *)calloc(1, sizeof(Service));
    if (!s) return NULL;
    InitializeCriticalSection(&s->lock);
    (*env)->GetJavaVM(env, &s->jvm);
    s->on_disks_changed = (*env)->GetStaticMethodID(env, clazz, "onDisksChanged", "()V");
    s->on_eject_request = s->on_disks_changed
                              ? (*env)->GetStaticMethodID(env, clazz, "onEjectRequest", "(Ljava/lang/String;)Z")
                              : NULL;
    if (!s->on_eject_request) {
        nucleus_jni_clear_exception(env);
        free_service(env, s);
        return NULL;
    }
    s->bridge = (*env)->NewGlobalRef(env, clazz);
    s->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    s->thread = s->ready ? CreateThread(NULL, 0, service_thread, s, 0, NULL) : NULL;
    if (!s->thread) {
        free_service(env, s);
        return NULL;
    }
    WaitForSingleObject(s->ready, INFINITE);
    if (!s->started) {
        WaitForSingleObject(s->thread, INFINITE);
        free_service(env, s);
        return NULL;
    }
    service = s;
    return s;
}

// Stops the service thread once neither the monitor nor the approval needs it. Called
// with service_lock held.
static void release_service_if_idle(JNIEnv *env) {
    Service *s = service;
    if (!s || InterlockedCompareExchange(&s->monitoring, 0, 0) || s->approving) return;
    service = NULL;
    PostMessageW(s->hwnd, WM_STOP_SERVICE, 0, 0);
    WaitForSingleObject(s->thread, INFINITE);
    free_service(env, s);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStartDiskMonitor(
    JNIEnv *env, jclass clazz) {
    AcquireSRWLockExclusive(&service_lock);
    Service *s = acquire_service(env, clazz);
    if (s) InterlockedExchange(&s->monitoring, 1);
    ReleaseSRWLockExclusive(&service_lock);
    return s ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStopDiskMonitor(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    AcquireSRWLockExclusive(&service_lock);
    if (service) {
        InterlockedExchange(&service->monitoring, 0);
        release_service_if_idle(env);
    }
    ReleaseSRWLockExclusive(&service_lock);
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStartEjectApproval(
    JNIEnv *env, jclass clazz) {
    AcquireSRWLockExclusive(&service_lock);
    Service *s = acquire_service(env, clazz);
    // Synchronous, so every ejectable volume is watched once this returns.
    if (s) SendMessageW(s->hwnd, WM_SET_APPROVAL, 1, 0);
    ReleaseSRWLockExclusive(&service_lock);
    return s ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_nucleusframework_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeStopEjectApproval(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    AcquireSRWLockExclusive(&service_lock);
    if (service) {
        // Waits for a request in flight, which ends within the Kotlin budget.
        SendMessageW(service->hwnd, WM_SET_APPROVAL, 0, 0);
        release_service_if_idle(env);
    }
    ReleaseSRWLockExclusive(&service_lock);
}
