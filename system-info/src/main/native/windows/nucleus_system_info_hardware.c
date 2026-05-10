// Hardware information: motherboard and product info via SMBIOS.
// Sources: GetSystemFirmwareTable (RSMB provider)

#include "nucleus_system_info_common.h"

// SMBIOS structures
#pragma pack(push, 1)
typedef struct {
    BYTE Used20CallingMethod;
    BYTE SMBIOSMajorVersion;
    BYTE SMBIOSMinorVersion;
    BYTE DmiRevision;
    DWORD Length;
    BYTE TableData[1]; // Variable length
} RawSMBIOSData;

typedef struct {
    BYTE Type;
    BYTE Length;
    WORD Handle;
} SMBIOSHeader;
#pragma pack(pop)

// SMBIOS Type 1: System Information
// Offsets after header (4 bytes):
//   0x04: Manufacturer (string index)
//   0x05: Product Name (string index)
//   0x06: Version (string index)
//   0x07: Serial Number (string index)
//   0x08-0x17: UUID (16 bytes)
//   0x18: Wake-up Type
//   0x19: SKU Number (string index) - SMBIOS 2.4+
//   0x1A: Family (string index) - SMBIOS 2.4+

// SMBIOS Type 2: Baseboard Information
// Offsets after header (4 bytes):
//   0x04: Manufacturer (string index)
//   0x05: Product (string index)
//   0x06: Version (string index)
//   0x07: Serial Number (string index)
//   0x08: Asset Tag (string index)

static BYTE *g_smbios_data = NULL;
static DWORD g_smbios_len = 0;

static void ensure_smbios(void) {
    if (g_smbios_data) return;

    // 'RSMB' = 0x52534D42
    DWORD size = GetSystemFirmwareTable('RSMB', 0, NULL, 0);
    if (size == 0) return;

    g_smbios_data = (BYTE *)malloc(size);
    if (!g_smbios_data) return;

    DWORD ret = GetSystemFirmwareTable('RSMB', 0, g_smbios_data, size);
    if (ret == 0) {
        free(g_smbios_data);
        g_smbios_data = NULL;
        return;
    }
    g_smbios_len = ret;
}

// Get the Nth string (1-based) from the SMBIOS string table after a structure
static const char *smbios_get_string(const BYTE *struct_start, BYTE struct_length, int index) {
    if (index == 0) return NULL;
    const char *p = (const char *)(struct_start + struct_length);
    for (int i = 1; i < index; i++) {
        while (*p) p++;
        p++; // skip null
        if (*p == '\0') return NULL; // double null = end of strings
    }
    return (*p) ? p : NULL;
}

// Find an SMBIOS structure of given type
static const BYTE *find_smbios_struct(BYTE type, BYTE *out_length) {
    ensure_smbios();
    if (!g_smbios_data || g_smbios_len < sizeof(RawSMBIOSData)) return NULL;

    RawSMBIOSData *raw = (RawSMBIOSData *)g_smbios_data;
    const BYTE *p = raw->TableData;
    const BYTE *end = raw->TableData + raw->Length;

    while (p < end) {
        SMBIOSHeader *hdr = (SMBIOSHeader *)p;
        if (hdr->Length < 4) break; // invalid
        if (p + hdr->Length > end) break;

        if (hdr->Type == type) {
            if (out_length) *out_length = hdr->Length;
            return p;
        }

        // Skip to next structure: past formatted area, then past string table
        const char *strings = (const char *)(p + hdr->Length);
        while (strings < (const char *)end) {
            if (strings[0] == '\0') {
                if (strings + 1 >= (const char *)end || strings[1] == '\0') {
                    strings += 2; // double null = end
                    break;
                }
            }
            strings++;
        }
        p = (const BYTE *)strings;
    }
    return NULL;
}

// --- Motherboard (Type 2) ---

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeMotherboardName(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(2, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[5])); // Product
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeMotherboardVendor(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(2, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[4])); // Manufacturer
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeMotherboardVersion(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(2, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[6])); // Version
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeMotherboardSerial(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(2, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[7])); // Serial
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeMotherboardAssetTag(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(2, &len);
    if (!s || len < 9) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[8])); // Asset Tag
}

// --- Product (Type 1) ---

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductName(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[5])); // Product Name
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductFamily(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 0x1B) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[0x1A])); // Family
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductSerial(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 8) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[7])); // Serial Number
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductSku(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 0x1A) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[0x19])); // SKU Number
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductUuid(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 0x18) return NULL;

    // UUID is at offset 0x08, 16 bytes
    const BYTE *uuid = s + 0x08;

    // Check for all-zero or all-FF (not present)
    int all_zero = 1, all_ff = 1;
    for (int i = 0; i < 16; i++) {
        if (uuid[i] != 0x00) all_zero = 0;
        if (uuid[i] != 0xFF) all_ff = 0;
    }
    if (all_zero || all_ff) return NULL;

    // SMBIOS UUID format: first 3 fields are little-endian
    // time_low (4 bytes), time_mid (2 bytes), time_hi_and_version (2 bytes)
    // rest is big-endian
    char buf[40];
    snprintf(buf, sizeof(buf),
        "%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X",
        uuid[3], uuid[2], uuid[1], uuid[0], // time_low LE
        uuid[5], uuid[4],                     // time_mid LE
        uuid[7], uuid[6],                     // time_hi LE
        uuid[8], uuid[9],                     // clock_seq BE
        uuid[10], uuid[11], uuid[12], uuid[13], uuid[14], uuid[15]); // node BE

    return to_jstring(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductVersion(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 7) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[6])); // Version
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeProductVendor(
    JNIEnv *env, jclass clazz) {
    BYTE len;
    const BYTE *s = find_smbios_struct(1, &len);
    if (!s || len < 5) return NULL;
    return to_jstring(env, smbios_get_string(s, len, s[4])); // Manufacturer
}
