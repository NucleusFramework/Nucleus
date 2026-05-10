// Battery information via Windows Battery IOCTL API.
// Logic follows starship/rust-battery for consistency across platforms.

#include "nucleus_system_info_common.h"
#include <setupapi.h>
#include <batclass.h>
#include <devguid.h>
#include <math.h>

// Link against setupapi for SetupDi* functions
#pragma comment(lib, "setupapi.lib")

// Battery state flags from WinNT.h / batclass.h
#ifndef BATTERY_UNKNOWN_CAPACITY
#define BATTERY_UNKNOWN_CAPACITY 0xFFFFFFFF
#endif
#ifndef BATTERY_UNKNOWN_VOLTAGE
#define BATTERY_UNKNOWN_VOLTAGE  0xFFFFFFFF
#endif
#ifndef BATTERY_UNKNOWN_RATE
#define BATTERY_UNKNOWN_RATE     0x80000000
#endif

// Cached battery state populated by query_battery().
typedef struct {
    BOOL valid;
    // BATTERY_INFORMATION fields
    ULONG designed_capacity_mwh;
    ULONG full_charged_capacity_mwh;
    ULONG cycle_count;
    ULONG capabilities;
    // BATTERY_STATUS fields
    ULONG power_state;
    ULONG capacity_mwh;
    ULONG voltage_mv;
    LONG  rate_mw; // absolute value of rate in mW, -1 if unknown
    // Temperature in decikelvin, 0 if unavailable
    ULONG temperature_dk;
    BOOL  temperature_valid;
    // Strings (wide, stack buffers)
    WCHAR manufacturer[128];
    WCHAR device_name[128];
    WCHAR serial_number[128];
} BatteryData;

// Open the first system battery device and query all data.
// Returns TRUE on success, FALSE if no battery or error.
static BOOL query_battery(BatteryData *out) {
    memset(out, 0, sizeof(*out));

    // Enumerate battery devices via SetupDi
    HDEVINFO hdev = SetupDiGetClassDevsW(
        &GUID_DEVCLASS_BATTERY, NULL, NULL,
        DIGCF_PRESENT | DIGCF_DEVICEINTERFACE);
    if (hdev == INVALID_HANDLE_VALUE) return FALSE;

    SP_DEVICE_INTERFACE_DATA iface_data;
    iface_data.cbSize = sizeof(SP_DEVICE_INTERFACE_DATA);

    // Iterate battery interfaces to find the first system battery
    for (DWORD idx = 0; ; idx++) {
        if (!SetupDiEnumDeviceInterfaces(hdev, NULL, &GUID_DEVCLASS_BATTERY, idx, &iface_data)) {
            break; // No more devices
        }

        // Get required buffer size for interface detail
        DWORD required_size = 0;
        SetupDiGetDeviceInterfaceDetailW(hdev, &iface_data, NULL, 0, &required_size, NULL);
        if (GetLastError() != ERROR_INSUFFICIENT_BUFFER || required_size == 0) continue;

        SP_DEVICE_INTERFACE_DETAIL_DATA_W *detail =
            (SP_DEVICE_INTERFACE_DETAIL_DATA_W *)malloc(required_size);
        if (!detail) continue;
        detail->cbSize = sizeof(SP_DEVICE_INTERFACE_DETAIL_DATA_W);

        if (!SetupDiGetDeviceInterfaceDetailW(hdev, &iface_data, detail, required_size, NULL, NULL)) {
            free(detail);
            continue;
        }

        // Open battery device
        HANDLE hbat = CreateFileW(
            detail->DevicePath,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        free(detail);

        if (hbat == INVALID_HANDLE_VALUE) continue;

        // Query battery tag
        ULONG battery_tag = 0;
        ULONG wait_timeout = 0;
        DWORD bytes_returned = 0;
        if (!DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_TAG,
                &wait_timeout, sizeof(wait_timeout),
                &battery_tag, sizeof(battery_tag),
                &bytes_returned, NULL) || battery_tag == 0) {
            CloseHandle(hbat);
            continue;
        }

        // Query battery information
        BATTERY_QUERY_INFORMATION bqi;
        memset(&bqi, 0, sizeof(bqi));
        bqi.BatteryTag = battery_tag;
        bqi.InformationLevel = BatteryInformation;

        BATTERY_INFORMATION binfo;
        memset(&binfo, 0, sizeof(binfo));
        if (!DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_INFORMATION,
                &bqi, sizeof(bqi),
                &binfo, sizeof(binfo),
                &bytes_returned, NULL)) {
            CloseHandle(hbat);
            continue;
        }

        // Skip relative-capacity batteries (same as rust-battery)
        if (binfo.Capabilities & BATTERY_CAPACITY_RELATIVE) {
            CloseHandle(hbat);
            continue;
        }

        out->designed_capacity_mwh = binfo.DesignedCapacity;
        out->full_charged_capacity_mwh = binfo.FullChargedCapacity;
        out->cycle_count = binfo.CycleCount;
        out->capabilities = binfo.Capabilities;

        // Query battery status
        BATTERY_WAIT_STATUS bws;
        memset(&bws, 0, sizeof(bws));
        bws.BatteryTag = battery_tag;

        BATTERY_STATUS bstatus;
        memset(&bstatus, 0, sizeof(bstatus));
        if (!DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_STATUS,
                &bws, sizeof(bws),
                &bstatus, sizeof(bstatus),
                &bytes_returned, NULL)) {
            CloseHandle(hbat);
            continue;
        }

        out->power_state = bstatus.PowerState;
        out->capacity_mwh = (bstatus.Capacity == BATTERY_UNKNOWN_CAPACITY) ? 0 : bstatus.Capacity;
        out->voltage_mv = (bstatus.Voltage == BATTERY_UNKNOWN_VOLTAGE) ? 0 : bstatus.Voltage;

        if (bstatus.Rate == (LONG)BATTERY_UNKNOWN_RATE) {
            out->rate_mw = 0; // Unknown rate treated as 0 (idle)
        } else {
            out->rate_mw = (bstatus.Rate < 0) ? -bstatus.Rate : bstatus.Rate; // absolute
        }

        // Query temperature (optional, may fail)
        bqi.InformationLevel = BatteryTemperature;
        ULONG temp_dk = 0;
        if (DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_INFORMATION,
                &bqi, sizeof(bqi),
                &temp_dk, sizeof(temp_dk),
                &bytes_returned, NULL)) {
            out->temperature_dk = temp_dk;
            out->temperature_valid = TRUE;
        }

        // Query manufacturer name (optional)
        bqi.InformationLevel = BatteryManufactureName;
        DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_INFORMATION,
            &bqi, sizeof(bqi),
            out->manufacturer, sizeof(out->manufacturer) - sizeof(WCHAR),
            &bytes_returned, NULL);

        // Query device name / model (optional)
        bqi.InformationLevel = BatteryDeviceName;
        DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_INFORMATION,
            &bqi, sizeof(bqi),
            out->device_name, sizeof(out->device_name) - sizeof(WCHAR),
            &bytes_returned, NULL);

        // Query serial number (optional)
        bqi.InformationLevel = BatterySerialNumber;
        DeviceIoControl(hbat, IOCTL_BATTERY_QUERY_INFORMATION,
            &bqi, sizeof(bqi),
            out->serial_number, sizeof(out->serial_number) - sizeof(WCHAR),
            &bytes_returned, NULL);

        CloseHandle(hbat);
        out->valid = TRUE;
        SetupDiDestroyDeviceInfoList(hdev);
        return TRUE;
    }

    SetupDiDestroyDeviceInfoList(hdev);
    return FALSE;
}

// Convert mWh to mAh: mAh = mWh * 1000 / mV
static int mwh_to_mah(ULONG mwh, ULONG mv) {
    if (mv == 0) return 0;
    return (int)(((ULONGLONG)mwh * 1000ULL) / mv);
}

// Thread-local cached battery data
static __declspec(thread) BatteryData g_battery;
static __declspec(thread) BOOL g_battery_queried = FALSE;

static BatteryData *get_battery(void) {
    if (!g_battery_queried) {
        query_battery(&g_battery);
        g_battery_queried = TRUE;
    }
    return &g_battery;
}

// Invalidate the cache so next call re-queries.
// Each JNI call from Kotlin is independent, but within a single batteryInfo() call
// from Kotlin, the bridge calls all 15 native methods sequentially.
// We use a simple approach: query on first access, cache for subsequent calls.
// The Kotlin side should call nativeBatteryPresent first, which triggers the query.

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryPresent(
    JNIEnv *env, jclass clazz) {
    // Re-query each time batteryPresent is called (it's always called first)
    g_battery_queried = FALSE;
    BatteryData *bat = get_battery();
    return bat->valid ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryExternalConnected(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return JNI_FALSE;
    return (bat->power_state & BATTERY_POWER_ON_LINE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryIsCharging(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return JNI_FALSE;
    return (bat->power_state & BATTERY_CHARGING) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryFullyCharged(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return JNI_FALSE;
    // Same logic as rust-battery: power on line AND not charging → full
    BOOL on_line = (bat->power_state & BATTERY_POWER_ON_LINE) != 0;
    BOOL charging = (bat->power_state & BATTERY_CHARGING) != 0;
    BOOL discharging = (bat->power_state & BATTERY_DISCHARGING) != 0;
    BOOL critical = (bat->power_state & BATTERY_CRITICAL) != 0;
    // Full = on_line && !charging && !discharging && !critical
    return (on_line && !charging && !discharging && !critical) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryCurrentCapacity(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    // Windows reports in mWh, convert to mAh
    return (jint)mwh_to_mah(bat->capacity_mwh, bat->voltage_mv);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryMaxCapacity(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    return (jint)mwh_to_mah(bat->full_charged_capacity_mwh, bat->voltage_mv);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryDesignCapacity(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    return (jint)mwh_to_mah(bat->designed_capacity_mwh, bat->voltage_mv);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryCycleCount(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    return (jint)bat->cycle_count;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryVoltage(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    return (jint)bat->voltage_mv;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryAmperage(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return 0;
    // rate_mw is absolute power in mW. Convert to mA: mA = mW * 1000 / mV
    if (bat->voltage_mv == 0 || bat->rate_mw == 0) return 0;
    return (jint)(((LONGLONG)bat->rate_mw * 1000LL) / bat->voltage_mv);
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryTemperature(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid || !bat->temperature_valid) return (jfloat)NAN;
    // Decikelvin to Celsius: °C = (dK / 10.0) - 273.15
    return (jfloat)((bat->temperature_dk / 10.0) - 273.15);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryTimeRemaining(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return -1;
    if (bat->rate_mw == 0) return -1;

    // Determine state following rust-battery logic
    BOOL charging = (bat->power_state & BATTERY_CHARGING) != 0;
    BOOL discharging = (bat->power_state & BATTERY_DISCHARGING) != 0;

    if (discharging && !charging) {
        // Time to empty: minutes = (capacity_mwh * 60) / rate_mw
        LONGLONG minutes = ((LONGLONG)bat->capacity_mwh * 60LL) / bat->rate_mw;
        // Cap at 10 days (14400 minutes), same as rust-battery
        if (minutes > 14400) return -1;
        return (jint)minutes;
    } else if (charging) {
        // Time to full: minutes = ((full - current) * 60) / rate_mw
        if (bat->full_charged_capacity_mwh <= bat->capacity_mwh) return 0;
        LONGLONG remaining = bat->full_charged_capacity_mwh - bat->capacity_mwh;
        LONGLONG minutes = (remaining * 60LL) / bat->rate_mw;
        // Cap at 10 hours (600 minutes), same as rust-battery
        if (minutes > 600) return -1;
        return (jint)minutes;
    }

    return -1;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryManufacturer(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return NULL;
    return wchar_to_jstring(env, bat->manufacturer);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatteryModelName(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return NULL;
    return wchar_to_jstring(env, bat->device_name);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeBatterySerialNumber(
    JNIEnv *env, jclass clazz) {
    BatteryData *bat = get_battery();
    if (!bat->valid) return NULL;
    return wchar_to_jstring(env, bat->serial_number);
}
