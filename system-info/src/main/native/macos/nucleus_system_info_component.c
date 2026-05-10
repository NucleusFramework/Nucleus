// Temperature sensor (component) information via Apple SMC.
// Uses IOKit to communicate with the AppleSMC kernel extension.
// Key list sourced from exelban/stats (MIT) — covers Intel, M1–M5 chips.
// Detects chip generation to register only relevant sensor keys.

#include "nucleus_system_info_common.h"
#include <IOKit/IOKitLib.h>

#define MAX_COMPONENTS 256
#define KERNEL_INDEX_SMC 2
#define SMC_CMD_READ_KEYINFO 9
#define SMC_CMD_READ_BYTES 5

// SMC data structures (matches the AppleSMC kernel interface)
typedef struct {
    char major;
    char minor;
    char build;
    char reserved[1];
    uint16_t release;
} SMCKeyData_vers_t;

typedef struct {
    uint16_t version;
    uint16_t length;
    uint32_t cpuPLimit;
    uint32_t gpuPLimit;
    uint32_t memPLimit;
} SMCKeyData_pLimitData_t;

typedef struct {
    uint32_t dataSize;
    uint32_t dataType;
    char dataAttributes;
} SMCKeyData_keyInfo_t;

typedef struct {
    uint32_t key;
    SMCKeyData_vers_t vers;
    SMCKeyData_pLimitData_t pLimitData;
    SMCKeyData_keyInfo_t keyInfo;
    uint8_t result;
    uint8_t status;
    uint8_t data8;
    uint32_t data32;
    uint8_t bytes[32];
} SMCKeyData_t;

#define DATATYPE_SP78 0x73703738
#define DATATYPE_FLT  0x666C7420
#define DATATYPE_SP3C 0x73703363
#define DATATYPE_SP4B 0x73703462
#define DATATYPE_SP5A 0x73703561

static uint32_t chars_to_key(const char *str) {
    return ((uint32_t)(unsigned char)str[0] << 24) |
           ((uint32_t)(unsigned char)str[1] << 16) |
           ((uint32_t)(unsigned char)str[2] << 8)  |
           (uint32_t)(unsigned char)str[3];
}

typedef struct {
    char label[128];
    float temperature;
    float max;
    float critical;
} component_entry_t;

static int g_component_count = 0;
static component_entry_t g_components[MAX_COMPONENTS];
static io_connect_t g_smc_conn = 0;

static int smc_open(void) {
    if (g_smc_conn) return 0;
    io_service_t service = IOServiceGetMatchingService(kIOMainPortDefault,
                                                       IOServiceMatching("AppleSMC"));
    if (!service) return -1;
    kern_return_t kr = IOServiceOpen(service, mach_task_self(), 0, &g_smc_conn);
    IOObjectRelease(service);
    return (kr == KERN_SUCCESS) ? 0 : -1;
}

static int smc_read_key(uint32_t key, uint32_t *data_type, uint8_t *bytes, uint32_t *data_size) {
    SMCKeyData_t input, output;
    memset(&input, 0, sizeof(input));
    memset(&output, 0, sizeof(output));

    input.key = key;
    input.data8 = SMC_CMD_READ_KEYINFO;
    size_t out_size = sizeof(output);
    kern_return_t kr = IOConnectCallStructMethod(g_smc_conn, KERNEL_INDEX_SMC,
                                                  &input, sizeof(input),
                                                  &output, &out_size);
    if (kr != KERN_SUCCESS) return -1;

    uint32_t size = output.keyInfo.dataSize;
    uint32_t type = output.keyInfo.dataType;
    if (size == 0 || size > 32) return -1;

    memset(&input, 0, sizeof(input));
    memset(&output, 0, sizeof(output));
    input.key = key;
    input.keyInfo.dataSize = size;
    input.data8 = SMC_CMD_READ_BYTES;
    out_size = sizeof(output);
    kr = IOConnectCallStructMethod(g_smc_conn, KERNEL_INDEX_SMC,
                                    &input, sizeof(input),
                                    &output, &out_size);
    if (kr != KERN_SUCCESS) return -1;

    *data_type = type;
    *data_size = size;
    memcpy(bytes, output.bytes, size);
    return 0;
}

static float smc_read_temperature(const char *key_str) {
    uint32_t key = chars_to_key(key_str);
    uint32_t data_type = 0, data_size = 0;
    uint8_t bytes[32] = {0};

    if (smc_read_key(key, &data_type, bytes, &data_size) != 0)
        return __builtin_nanf("");

    if (data_size >= 2 && (data_type == DATATYPE_SP78 ||
                           data_type == DATATYPE_SP3C ||
                           data_type == DATATYPE_SP4B ||
                           data_type == DATATYPE_SP5A)) {
        int16_t raw = (int16_t)((bytes[0] << 8) | bytes[1]);
        int frac_bits = 8;
        if (data_type == DATATYPE_SP3C) frac_bits = 12;
        else if (data_type == DATATYPE_SP4B) frac_bits = 11;
        else if (data_type == DATATYPE_SP5A) frac_bits = 10;
        return (float)raw / (float)(1 << frac_bits);
    } else if (data_type == DATATYPE_FLT && data_size >= 4) {
        float val;
        memcpy(&val, bytes, sizeof(float));
        return val;
    }

    return __builtin_nanf("");
}

static void try_add_sensor(const char *key, const char *label) {
    if (g_component_count >= MAX_COMPONENTS) return;
    float temp = smc_read_temperature(key);
    if (__builtin_isnan(temp)) return;
    if (temp <= 0.0f || temp > 150.0f) return;

    component_entry_t *c = &g_components[g_component_count];
    strncpy(c->label, label, sizeof(c->label) - 1);
    c->temperature = temp;
    c->max = __builtin_nanf("");
    c->critical = __builtin_nanf("");
    g_component_count++;
}

// Chip generation enum
typedef enum {
    CHIP_UNKNOWN = 0,
    CHIP_INTEL,
    CHIP_M1,
    CHIP_M2,
    CHIP_M3,
    CHIP_M4,
    CHIP_M5,
} chip_gen_t;

static chip_gen_t detect_chip(void) {
    char *brand = sysctl_string("machdep.cpu.brand_string");
    if (!brand) return CHIP_UNKNOWN;
    chip_gen_t gen = CHIP_UNKNOWN;
    if (strstr(brand, "Apple M5"))      gen = CHIP_M5;
    else if (strstr(brand, "Apple M4")) gen = CHIP_M4;
    else if (strstr(brand, "Apple M3")) gen = CHIP_M3;
    else if (strstr(brand, "Apple M2")) gen = CHIP_M2;
    else if (strstr(brand, "Apple M1")) gen = CHIP_M1;
    else if (strstr(brand, "Intel"))    gen = CHIP_INTEL;
    free(brand);
    return gen;
}

static void add_generic_sensors(void) {
    // System sensors present on most Macs
    try_add_sensor("Tm0P", "Mainboard");
    try_add_sensor("TB1T", "Battery 1");
    try_add_sensor("TB2T", "Battery 2");
    try_add_sensor("TW0P", "Airport/WiFi");
    try_add_sensor("TL0P", "Display");
    try_add_sensor("TH0x", "NAND");
    try_add_sensor("TaLP", "Airflow Left");
    try_add_sensor("TaRF", "Airflow Right");
    try_add_sensor("TTLD", "Thunderbolt Left");
    try_add_sensor("TTRD", "Thunderbolt Right");
    char key[5], label[48];
    for (int i = 0; i <= 3; i++) {
        snprintf(key, sizeof(key), "TI%dP", i); snprintf(label, sizeof(label), "Thunderbolt %d", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TH%dA", i); snprintf(label, sizeof(label), "Disk %d A", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TH%dB", i); snprintf(label, sizeof(label), "Disk %d B", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TH%dC", i); snprintf(label, sizeof(label), "Disk %d C", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TA%dP", i); snprintf(label, sizeof(label), "Ambient %d", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TZ%dC", i); snprintf(label, sizeof(label), "Thermal Zone %d", i);
        try_add_sensor(key, label);
    }
}

static void add_intel_sensors(void) {
    try_add_sensor("TC0P", "CPU Proximity");
    try_add_sensor("TC0D", "CPU Die");
    try_add_sensor("TC0E", "CPU Die Virtual");
    try_add_sensor("TC0F", "CPU Die Filtered");
    try_add_sensor("TC0H", "CPU Heatsink");
    try_add_sensor("TCAD", "CPU Package");
    try_add_sensor("Tp0P", "Powerboard");
    try_add_sensor("TN0D", "Northbridge Die");
    try_add_sensor("TN0H", "Northbridge Heatsink");
    try_add_sensor("TN0P", "Northbridge Proximity");
    char key[5], label[48];
    for (int i = 0; i <= 9; i++) {
        snprintf(key, sizeof(key), "TC%dc", i); snprintf(label, sizeof(label), "CPU Core %d", i);
        try_add_sensor(key, label);
        snprintf(key, sizeof(key), "TC%dC", i); snprintf(label, sizeof(label), "CPU Core %d", i);
        try_add_sensor(key, label);
    }
    try_add_sensor("TCGC", "GPU Intel Graphics");
    try_add_sensor("TG0D", "GPU Die");
    try_add_sensor("TGDD", "GPU AMD Radeon");
    try_add_sensor("TG0H", "GPU Heatsink");
    try_add_sensor("TG0P", "GPU Proximity");
}

static void add_m1_sensors(void) {
    try_add_sensor("Tp09", "CPU Efficiency Core 1");
    try_add_sensor("Tp0T", "CPU Efficiency Core 2");
    try_add_sensor("Tp01", "CPU Performance Core 1");
    try_add_sensor("Tp05", "CPU Performance Core 2");
    try_add_sensor("Tp0D", "CPU Performance Core 3");
    try_add_sensor("Tp0H", "CPU Performance Core 4");
    try_add_sensor("Tp0L", "CPU Performance Core 5");
    try_add_sensor("Tp0P", "CPU Performance Core 6");
    try_add_sensor("Tp0X", "CPU Performance Core 7");
    try_add_sensor("Tp0b", "CPU Performance Core 8");
    try_add_sensor("Tg05", "GPU 1");
    try_add_sensor("Tg0D", "GPU 2");
    try_add_sensor("Tg0L", "GPU 3");
    try_add_sensor("Tg0T", "GPU 4");
    try_add_sensor("Tm02", "Memory 1");
    try_add_sensor("Tm06", "Memory 2");
    try_add_sensor("Tm08", "Memory 3");
    try_add_sensor("Tm09", "Memory 4");
}

static void add_m2_sensors(void) {
    try_add_sensor("Tp1h", "CPU Efficiency Core 1");
    try_add_sensor("Tp1t", "CPU Efficiency Core 2");
    try_add_sensor("Tp1p", "CPU Efficiency Core 3");
    try_add_sensor("Tp1l", "CPU Efficiency Core 4");
    try_add_sensor("Tp01", "CPU Performance Core 1");
    try_add_sensor("Tp05", "CPU Performance Core 2");
    try_add_sensor("Tp09", "CPU Performance Core 3");
    try_add_sensor("Tp0D", "CPU Performance Core 4");
    try_add_sensor("Tp0X", "CPU Performance Core 5");
    try_add_sensor("Tp0b", "CPU Performance Core 6");
    try_add_sensor("Tp0f", "CPU Performance Core 7");
    try_add_sensor("Tp0j", "CPU Performance Core 8");
    try_add_sensor("Tg0f", "GPU 1");
    try_add_sensor("Tg0j", "GPU 2");
}

static void add_m3_sensors(void) {
    try_add_sensor("Te05", "CPU Efficiency Core 1");
    try_add_sensor("Te0L", "CPU Efficiency Core 2");
    try_add_sensor("Te0P", "CPU Efficiency Core 3");
    try_add_sensor("Te0S", "CPU Efficiency Core 4");
    try_add_sensor("Tf04", "CPU Performance Core 1");
    try_add_sensor("Tf09", "CPU Performance Core 2");
    try_add_sensor("Tf0A", "CPU Performance Core 3");
    try_add_sensor("Tf0B", "CPU Performance Core 4");
    try_add_sensor("Tf0D", "CPU Performance Core 5");
    try_add_sensor("Tf0E", "CPU Performance Core 6");
    try_add_sensor("Tf44", "CPU Performance Core 7");
    try_add_sensor("Tf49", "CPU Performance Core 8");
    try_add_sensor("Tf4A", "CPU Performance Core 9");
    try_add_sensor("Tf4B", "CPU Performance Core 10");
    try_add_sensor("Tf4D", "CPU Performance Core 11");
    try_add_sensor("Tf4E", "CPU Performance Core 12");
    try_add_sensor("Tf14", "GPU 1");
    try_add_sensor("Tf18", "GPU 2");
    try_add_sensor("Tf19", "GPU 3");
    try_add_sensor("Tf1A", "GPU 4");
    try_add_sensor("Tf24", "GPU 5");
    try_add_sensor("Tf28", "GPU 6");
    try_add_sensor("Tf29", "GPU 7");
    try_add_sensor("Tf2A", "GPU 8");
}

static void add_m4_sensors(void) {
    try_add_sensor("Te05", "CPU Efficiency Core 1");
    try_add_sensor("Te0S", "CPU Efficiency Core 2");
    try_add_sensor("Te09", "CPU Efficiency Core 3");
    try_add_sensor("Te0H", "CPU Efficiency Core 4");
    try_add_sensor("Tp01", "CPU Performance Core 1");
    try_add_sensor("Tp05", "CPU Performance Core 2");
    try_add_sensor("Tp09", "CPU Performance Core 3");
    try_add_sensor("Tp0D", "CPU Performance Core 4");
    try_add_sensor("Tp0V", "CPU Performance Core 5");
    try_add_sensor("Tp0Y", "CPU Performance Core 6");
    try_add_sensor("Tp0b", "CPU Performance Core 7");
    try_add_sensor("Tp0e", "CPU Performance Core 8");
    try_add_sensor("Tg0G", "GPU 1");
    try_add_sensor("Tg0H", "GPU 2");
    try_add_sensor("Tg0K", "GPU 3");
    try_add_sensor("Tg0L", "GPU 4");
    try_add_sensor("Tg0d", "GPU 5");
    try_add_sensor("Tg0e", "GPU 6");
    try_add_sensor("Tg0j", "GPU 7");
    try_add_sensor("Tg0k", "GPU 8");
    try_add_sensor("Tg1U", "GPU 9");
    try_add_sensor("Tg1k", "GPU 10");
    try_add_sensor("Tm0p", "Memory Proximity 1");
    try_add_sensor("Tm1p", "Memory Proximity 2");
    try_add_sensor("Tm2p", "Memory Proximity 3");
}

static void add_m5_sensors(void) {
    try_add_sensor("Tp00", "CPU Super Core 1");
    try_add_sensor("Tp04", "CPU Super Core 2");
    try_add_sensor("Tp08", "CPU Super Core 3");
    try_add_sensor("Tp0C", "CPU Super Core 4");
    try_add_sensor("Tp0G", "CPU Super Core 5");
    try_add_sensor("Tp0K", "CPU Super Core 6");
    try_add_sensor("Tp0O", "CPU Performance Core 1");
    try_add_sensor("Tp0R", "CPU Performance Core 2");
    try_add_sensor("Tp0U", "CPU Performance Core 3");
    try_add_sensor("Tp0X", "CPU Performance Core 4");
    try_add_sensor("Tp0a", "CPU Performance Core 5");
    try_add_sensor("Tp0d", "CPU Performance Core 6");
    try_add_sensor("Tp0g", "CPU Performance Core 7");
    try_add_sensor("Tp0j", "CPU Performance Core 8");
    try_add_sensor("Tp0m", "CPU Performance Core 9");
    try_add_sensor("Tp0p", "CPU Performance Core 10");
    try_add_sensor("Tp0u", "CPU Performance Core 11");
    try_add_sensor("Tp0y", "CPU Performance Core 12");
    try_add_sensor("Tg0U", "GPU 1");
    try_add_sensor("Tg0X", "GPU 2");
    try_add_sensor("Tg0d", "GPU 3");
    try_add_sensor("Tg0g", "GPU 4");
    try_add_sensor("Tg0j", "GPU 5");
    try_add_sensor("Tg1Y", "GPU 6");
    try_add_sensor("Tg1c", "GPU 7");
    try_add_sensor("Tg1g", "GPU 8");
}

static void refresh_components(void) {
    g_component_count = 0;
    if (smc_open() != 0) return;

    add_generic_sensors();

    chip_gen_t chip = detect_chip();
    switch (chip) {
        case CHIP_INTEL: add_intel_sensors(); break;
        case CHIP_M1:    add_m1_sensors();    break;
        case CHIP_M2:    add_m2_sensors();    break;
        case CHIP_M3:    add_m3_sensors();    break;
        case CHIP_M4:    add_m4_sensors();    break;
        case CHIP_M5:    add_m5_sensors();    break;
        case CHIP_UNKNOWN:
            // Try all generations — only keys that return valid data will be added
            add_intel_sensors();
            add_m1_sensors();
            add_m2_sensors();
            add_m3_sensors();
            add_m4_sensors();
            add_m5_sensors();
            break;
    }
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeComponentCount(
    JNIEnv *env, jclass clazz) {
    refresh_components();
    return (jint)g_component_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeComponentLabels(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_component_count * sizeof(char *));
    for (int i = 0; i < g_component_count; i++) arr[i] = g_components[i].label;
    jobjectArray result = to_string_array(env, arr, g_component_count);
    free(arr);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeComponentTemperatures(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].temperature;
    jfloatArray result = (*env)->NewFloatArray(env, g_component_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_component_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeComponentMaxTemperatures(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].max;
    jfloatArray result = (*env)->NewFloatArray(env, g_component_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_component_count, vals);
    free(vals);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeComponentCriticalTemperatures(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].critical;
    jfloatArray result = (*env)->NewFloatArray(env, g_component_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_component_count, vals);
    free(vals);
    return result;
}
