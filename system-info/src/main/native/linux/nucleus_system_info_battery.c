// Battery information via sysfs /sys/class/power_supply/ on Linux.

#include "nucleus_system_info_common.h"
#include <dirent.h>
#include <math.h>
#include <limits.h>

// Path to power supply sysfs directory
#define POWER_SUPPLY_DIR "/sys/class/power_supply"

// Read an integer from a sysfs file. Returns fallback on failure.
static long read_sysfs_long(const char *base, const char *file, long fallback) {
    char path[512];
    char buf[64];
    snprintf(path, sizeof(path), "%s/%s", base, file);
    if (read_file_line(path, buf, sizeof(buf)) < 0) return fallback;
    char *end = NULL;
    long val = strtol(buf, &end, 10);
    if (end == buf) return fallback;
    return val;
}

// Read a string from a sysfs file. Returns malloc'd string or NULL.
static char *read_sysfs_string(const char *base, const char *file) {
    char path[512];
    char buf[256];
    snprintf(path, sizeof(path), "%s/%s", base, file);
    if (read_file_line(path, buf, sizeof(buf)) < 0) return NULL;
    if (buf[0] == '\0') return NULL;
    return strdup(buf);
}

// Find the first battery device path in /sys/class/power_supply/.
// Looks for entries with type=Battery and scope=System (or no scope file, which defaults to System).
// Returns a static buffer with the path, or NULL if not found.
static const char *find_battery_path(void) {
    static char bat_path[512];
    DIR *dir = opendir(POWER_SUPPLY_DIR);
    if (!dir) return NULL;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;

        char candidate[512];
        snprintf(candidate, sizeof(candidate), "%s/%s", POWER_SUPPLY_DIR, entry->d_name);

        // Check type == Battery
        char type_path[512];
        snprintf(type_path, sizeof(type_path), "%s/type", candidate);
        char type_val[64];
        if (read_file_line(type_path, type_val, sizeof(type_val)) < 0) continue;
        if (strcmp(type_val, "Battery") != 0) continue;

        // Check scope == System (or missing, which defaults to System)
        char scope_path[512];
        snprintf(scope_path, sizeof(scope_path), "%s/scope", candidate);
        char scope_val[64];
        if (read_file_line(scope_path, scope_val, sizeof(scope_val)) >= 0) {
            if (strcmp(scope_val, "System") != 0 && strcmp(scope_val, "") != 0) continue;
        }

        strncpy(bat_path, candidate, sizeof(bat_path) - 1);
        bat_path[sizeof(bat_path) - 1] = '\0';
        closedir(dir);
        return bat_path;
    }

    closedir(dir);
    return NULL;
}

// Check if any AC/mains power supply is online.
static int is_ac_online(void) {
    DIR *dir = opendir(POWER_SUPPLY_DIR);
    if (!dir) return 0;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;

        char candidate[512];
        snprintf(candidate, sizeof(candidate), "%s/%s", POWER_SUPPLY_DIR, entry->d_name);

        char type_val[64];
        char type_path[512];
        snprintf(type_path, sizeof(type_path), "%s/type", candidate);
        if (read_file_line(type_path, type_val, sizeof(type_val)) < 0) continue;
        if (strcmp(type_val, "Mains") != 0) continue;

        long online = read_sysfs_long(candidate, "online", 0);
        if (online == 1) {
            closedir(dir);
            return 1;
        }
    }

    closedir(dir);
    return 0;
}

// Get voltage in µV. Tries voltage_now, then voltage_avg.
static long get_voltage_uv(const char *bat) {
    long v = read_sysfs_long(bat, "voltage_now", -1);
    if (v >= 0) return v;
    return read_sysfs_long(bat, "voltage_avg", -1);
}

// Get design voltage in µV for energy/charge conversions.
static long get_design_voltage_uv(const char *bat) {
    long v = read_sysfs_long(bat, "voltage_max_design", -1);
    if (v > 0) return v;
    v = read_sysfs_long(bat, "voltage_min_design", -1);
    if (v > 0) return v;
    return get_voltage_uv(bat);
}

// Convert µWh to mAh using voltage in µV: mAh = µWh * 1000 / µV
static int uwh_to_mah(long uwh, long uv) {
    if (uv <= 0) return 0;
    return (int)((uwh * 1000L) / uv);
}

// Get current capacity in mAh. Handles both energy-based and charge-based batteries.
static int get_capacity_mah(const char *bat, const char *energy_file, const char *charge_file) {
    long energy = read_sysfs_long(bat, energy_file, -1);
    if (energy >= 0) {
        long voltage = get_design_voltage_uv(bat);
        return uwh_to_mah(energy, voltage);
    }
    long charge = read_sysfs_long(bat, charge_file, -1);
    if (charge >= 0) {
        return (int)(charge / 1000L); // µAh to mAh
    }
    return 0;
}

// Get power draw in µW for time calculations.
static long get_power_uw(const char *bat) {
    long power = read_sysfs_long(bat, "power_now", -1);
    if (power > 0) return power;

    long current = read_sysfs_long(bat, "current_now", -1);
    if (current > 0) {
        // Check if this is a charge-based battery (has charge_full)
        long charge_full = read_sysfs_long(bat, "charge_full", -1);
        if (charge_full >= 0) {
            // current_now is in µA, convert to µW: P = I * V
            long voltage = get_voltage_uv(bat);
            if (voltage > 0) return (current * voltage) / 1000000L;
        } else {
            // Energy-only battery: current_now is actually µW (legacy)
            return current;
        }
    }
    return -1;
}

// Get total energy now in µWh (for time calculations).
static long get_energy_now_uwh(const char *bat) {
    long energy = read_sysfs_long(bat, "energy_now", -1);
    if (energy >= 0) return energy;

    long charge = read_sysfs_long(bat, "charge_now", -1);
    if (charge >= 0) {
        long voltage = get_design_voltage_uv(bat);
        if (voltage > 0) return (charge * voltage) / 1000000L;
    }
    return -1;
}

// Get total energy full in µWh (for time calculations).
static long get_energy_full_uwh(const char *bat) {
    long energy = read_sysfs_long(bat, "energy_full", -1);
    if (energy >= 0) return energy;

    long charge = read_sysfs_long(bat, "charge_full", -1);
    if (charge >= 0) {
        long voltage = get_design_voltage_uv(bat);
        if (voltage > 0) return (charge * voltage) / 1000000L;
    }
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryPresent(
    JNIEnv *env, jclass clazz) {
    return find_battery_path() != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryExternalConnected(
    JNIEnv *env, jclass clazz) {
    return is_ac_online() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryIsCharging(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return JNI_FALSE;
    char path[512];
    snprintf(path, sizeof(path), "%s/status", bat);
    char status[64];
    if (read_file_line(path, status, sizeof(status)) < 0) return JNI_FALSE;
    return (strcmp(status, "Charging") == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryFullyCharged(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return JNI_FALSE;
    char path[512];
    snprintf(path, sizeof(path), "%s/status", bat);
    char status[64];
    if (read_file_line(path, status, sizeof(status)) < 0) return JNI_FALSE;
    return (strcmp(status, "Full") == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryCurrentCapacity(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;
    return (jint)get_capacity_mah(bat, "energy_now", "charge_now");
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryMaxCapacity(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;
    return (jint)get_capacity_mah(bat, "energy_full", "charge_full");
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryDesignCapacity(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;
    return (jint)get_capacity_mah(bat, "energy_full_design", "charge_full_design");
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryCycleCount(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;
    long cycle = read_sysfs_long(bat, "cycle_count", 0);
    // Some drivers create the file with 0 even for old batteries; treat as unknown
    return (jint)cycle;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryVoltage(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;
    long uv = get_voltage_uv(bat);
    if (uv < 0) return 0;
    return (jint)(uv / 1000L); // µV to mV
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryAmperage(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return 0;

    // Try current_now first (µA)
    long current = read_sysfs_long(bat, "current_now", -1);
    if (current >= 0) {
        long ma = current / 1000L;
        return (jint)(ma < 0 ? -ma : ma); // absolute value
    }

    // Fallback: derive from power_now / voltage_now
    long power = read_sysfs_long(bat, "power_now", -1);
    long voltage = get_voltage_uv(bat);
    if (power > 0 && voltage > 0) {
        long ma = (power * 1000L) / voltage; // µW * 1000 / µV = mA
        return (jint)(ma < 0 ? -ma : ma);
    }

    return 0;
}

JNIEXPORT jfloat JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryTemperature(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return (jfloat)NAN;
    long raw = read_sysfs_long(bat, "temp", LONG_MIN);
    if (raw == LONG_MIN) return (jfloat)NAN;
    // sysfs temp is in 1/10 °C
    return (jfloat)(raw / 10.0f);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryTimeRemaining(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return -1;

    // Read status to determine direction
    char path[512];
    snprintf(path, sizeof(path), "%s/status", bat);
    char status[64];
    if (read_file_line(path, status, sizeof(status)) < 0) return -1;

    long power = get_power_uw(bat);
    if (power <= 0) return -1;

    long energy_now = get_energy_now_uwh(bat);
    if (energy_now < 0) return -1;

    if (strcmp(status, "Discharging") == 0) {
        // Time to empty (minutes)
        long minutes = (energy_now * 60L) / power;
        // Sanity: cap at 10 days (same as rust-battery)
        if (minutes > 14400) return -1;
        return (jint)minutes;
    } else if (strcmp(status, "Charging") == 0) {
        // Time to full (minutes)
        long energy_full = get_energy_full_uwh(bat);
        if (energy_full <= 0) return -1;
        long remaining = energy_full - energy_now;
        if (remaining <= 0) return 0;
        long minutes = (remaining * 60L) / power;
        // Sanity: cap at 10 hours (same as rust-battery)
        if (minutes > 600) return -1;
        return (jint)minutes;
    }

    return -1;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryManufacturer(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return NULL;
    char *val = read_sysfs_string(bat, "manufacturer");
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatteryModelName(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return NULL;
    char *val = read_sysfs_string(bat, "model_name");
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeBatterySerialNumber(
    JNIEnv *env, jclass clazz) {
    const char *bat = find_battery_path();
    if (!bat) return NULL;
    char *val = read_sysfs_string(bat, "serial_number");
    jstring result = to_jstring(env, val);
    free(val);
    return result;
}
