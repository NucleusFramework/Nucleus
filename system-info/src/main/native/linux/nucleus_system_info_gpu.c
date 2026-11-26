// GPU information gathering for Linux via sysfs + NVML (dlopen).
// Supports NVIDIA (via NVML runtime loading), AMD (amdgpu sysfs), and Intel (i915/xe sysfs).

#include "nucleus_system_info_common.h"

#include <dirent.h>
#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <sys/types.h>

// ---------------------------------------------------------------------------
// NVML types and function pointers (loaded at runtime via dlopen)
// ---------------------------------------------------------------------------

typedef enum {
    NVML_SUCCESS = 0,
} nvmlReturn_t;

typedef void *nvmlDevice_t;

typedef struct {
    unsigned long long total;
    unsigned long long free;
    unsigned long long used;
} nvmlMemory_t;

typedef struct {
    unsigned int gpu;
    unsigned int memory;
} nvmlUtilization_t;

typedef enum {
    NVML_TEMPERATURE_GPU = 0,
} nvmlTemperatureSensors_t;

typedef enum {
    NVML_CLOCK_GRAPHICS = 0,
    NVML_CLOCK_MEM = 2,
} nvmlClockType_t;

typedef struct {
    char busId[32];
    unsigned int domain;
    unsigned int bus;
    unsigned int device;
    unsigned int pciDeviceId;
    unsigned int pciSubSystemId;
} nvmlPciInfo_t;

// NVML function pointer typedefs
typedef nvmlReturn_t (*pfn_nvmlInit)(void);
typedef nvmlReturn_t (*pfn_nvmlShutdown)(void);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetCount)(unsigned int *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetHandleByIndex)(unsigned int, nvmlDevice_t *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetName)(nvmlDevice_t, char *, unsigned int);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetMemoryInfo)(nvmlDevice_t, nvmlMemory_t *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetUtilizationRates)(nvmlDevice_t, nvmlUtilization_t *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetTemperature)(nvmlDevice_t, nvmlTemperatureSensors_t, unsigned int *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetClockInfo)(nvmlDevice_t, nvmlClockType_t, unsigned int *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetFanSpeed)(nvmlDevice_t, unsigned int *);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPowerUsage)(nvmlDevice_t, unsigned int *);  // milliwatts
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPciInfo)(nvmlDevice_t, nvmlPciInfo_t *);
typedef nvmlReturn_t (*pfn_nvmlSystemGetDriverVersion)(char *, unsigned int);
typedef const char *(*pfn_nvmlErrorString)(nvmlReturn_t);

static struct {
    void *lib;
    pfn_nvmlInit Init;
    pfn_nvmlShutdown Shutdown;
    pfn_nvmlDeviceGetCount DeviceGetCount;
    pfn_nvmlDeviceGetHandleByIndex DeviceGetHandleByIndex;
    pfn_nvmlDeviceGetName DeviceGetName;
    pfn_nvmlDeviceGetMemoryInfo DeviceGetMemoryInfo;
    pfn_nvmlDeviceGetUtilizationRates DeviceGetUtilizationRates;
    pfn_nvmlDeviceGetTemperature DeviceGetTemperature;
    pfn_nvmlDeviceGetClockInfo DeviceGetClockInfo;
    pfn_nvmlDeviceGetFanSpeed DeviceGetFanSpeed;
    pfn_nvmlDeviceGetPowerUsage DeviceGetPowerUsage;
    pfn_nvmlDeviceGetPciInfo DeviceGetPciInfo;
    pfn_nvmlSystemGetDriverVersion SystemGetDriverVersion;
} nvml = {0};

static int nvml_loaded = 0;

static void nvml_load(void) {
    if (nvml.lib) return;

    nvml.lib = dlopen("libnvidia-ml.so.1", RTLD_LAZY);
    if (!nvml.lib) nvml.lib = dlopen("libnvidia-ml.so", RTLD_LAZY);
    if (!nvml.lib) return;

#define LOAD_SYM(name, symbol) \
    nvml.name = (pfn_nvml##name)dlsym(nvml.lib, symbol); \
    if (!nvml.name) { dlclose(nvml.lib); nvml.lib = NULL; return; }

    LOAD_SYM(Init, "nvmlInit_v2")
    LOAD_SYM(Shutdown, "nvmlShutdown")
    LOAD_SYM(DeviceGetCount, "nvmlDeviceGetCount_v2")
    LOAD_SYM(DeviceGetHandleByIndex, "nvmlDeviceGetHandleByIndex_v2")
    LOAD_SYM(DeviceGetName, "nvmlDeviceGetName")
    LOAD_SYM(DeviceGetMemoryInfo, "nvmlDeviceGetMemoryInfo")
    LOAD_SYM(DeviceGetUtilizationRates, "nvmlDeviceGetUtilizationRates")
    LOAD_SYM(DeviceGetTemperature, "nvmlDeviceGetTemperature")
    LOAD_SYM(DeviceGetClockInfo, "nvmlDeviceGetClockInfo")
    LOAD_SYM(DeviceGetFanSpeed, "nvmlDeviceGetFanSpeed")
    LOAD_SYM(DeviceGetPowerUsage, "nvmlDeviceGetPowerUsage")
    LOAD_SYM(DeviceGetPciInfo, "nvmlDeviceGetPciInfo_v3")
    LOAD_SYM(SystemGetDriverVersion, "nvmlSystemGetDriverVersion")
#undef LOAD_SYM

    if (nvml.Init() != NVML_SUCCESS) {
        dlclose(nvml.lib);
        nvml.lib = NULL;
        return;
    }
    nvml_loaded = 1;
}

// ---------------------------------------------------------------------------
// GPU data structure
// ---------------------------------------------------------------------------

#define MAX_GPUS 16
#define GPU_NAME_LEN 256
#define GPU_DRIVER_LEN 64
#define GPU_PCI_LEN 16

typedef enum {
    GPU_DRIVER_UNKNOWN = 0,
    GPU_DRIVER_NVIDIA,
    GPU_DRIVER_AMDGPU,
    GPU_DRIVER_INTEL,
} gpu_driver_type_t;

typedef struct {
    char name[GPU_NAME_LEN];
    uint32_t vendor_id;
    uint32_t device_id;
    int64_t vram_total;      // dedicated video memory (bytes)
    int64_t sys_mem_dedicated; // dedicated system memory (0 for discrete)
    int64_t sys_mem_shared;  // shared system memory (GTT for AMD)
    char driver_version[GPU_DRIVER_LEN];

    // Dynamic metrics (NaN/sentinel = unavailable)
    float temperature;
    float gpu_usage;
    int64_t memory_used;
    int32_t core_clock_mhz;
    int32_t mem_clock_mhz;
    float fan_speed_pct;
    float power_draw_watts;

    // Internal
    gpu_driver_type_t driver_type;
    int card_index;
    char pci_slot[GPU_PCI_LEN]; // e.g. "0000:03:00.0"
    nvmlDevice_t nvml_device;
    char hwmon_path[256]; // path to hwmon directory for this GPU
} gpu_entry_t;

static gpu_entry_t gpu_list[MAX_GPUS];
static int gpu_count = 0;
static int gpus_initialized = 0;

// ---------------------------------------------------------------------------
// sysfs helpers
// ---------------------------------------------------------------------------

static int64_t read_sysfs_long(const char *path) {
    char buf[64];
    if (read_file_line(path, buf, sizeof(buf)) <= 0) return -1;
    char *end;
    long long val = strtoll(buf, &end, 10);
    if (end == buf) return -1;
    return (int64_t)val;
}

static float read_sysfs_float(const char *path) {
    char buf[64];
    if (read_file_line(path, buf, sizeof(buf)) <= 0) return NAN;
    char *end;
    float val = strtof(buf, &end);
    if (end == buf) return NAN;
    return val;
}

// Find the hwmon directory under a device path
// e.g. /sys/class/drm/card0/device/hwmon/hwmon3
static int find_hwmon_path(const char *device_path, char *out, size_t out_size) {
    char hwmon_dir[512];
    snprintf(hwmon_dir, sizeof(hwmon_dir), "%s/hwmon", device_path);

    DIR *dir = opendir(hwmon_dir);
    if (!dir) return 0;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        if (strncmp(entry->d_name, "hwmon", 5) == 0) {
            snprintf(out, out_size, "%s/%s", hwmon_dir, entry->d_name);
            closedir(dir);
            return 1;
        }
    }
    closedir(dir);
    return 0;
}

// Parse PCI_ID from uevent: "PCI_ID=VVVV:DDDD"
static int parse_pci_ids(const char *uevent_path, uint32_t *vendor, uint32_t *device) {
    size_t len = 0;
    char *content = read_file_contents(uevent_path, &len);
    if (!content) return 0;

    int found = 0;
    char *line = content;
    while (line && *line) {
        if (strncmp(line, "PCI_ID=", 7) == 0) {
            unsigned int v, d;
            if (sscanf(line + 7, "%x:%x", &v, &d) == 2) {
                *vendor = v;
                *device = d;
                found = 1;
            }
            break;
        }
        line = strchr(line, '\n');
        if (line) line++;
    }

    free(content);
    return found;
}

// Parse DRIVER= from uevent
static gpu_driver_type_t parse_driver(const char *uevent_path) {
    size_t len = 0;
    char *content = read_file_contents(uevent_path, &len);
    if (!content) return GPU_DRIVER_UNKNOWN;

    gpu_driver_type_t driver = GPU_DRIVER_UNKNOWN;
    char *line = content;
    while (line && *line) {
        if (strncmp(line, "DRIVER=", 7) == 0) {
            if (strncmp(line + 7, "nvidia", 6) == 0) {
                driver = GPU_DRIVER_NVIDIA;
            } else if (strncmp(line + 7, "amdgpu", 6) == 0) {
                driver = GPU_DRIVER_AMDGPU;
            } else if (strncmp(line + 7, "i915", 4) == 0 ||
                       strncmp(line + 7, "xe", 2) == 0) {
                driver = GPU_DRIVER_INTEL;
            }
            break;
        }
        line = strchr(line, '\n');
        if (line) line++;
    }

    free(content);
    return driver;
}

// Parse PCI slot from uevent: "PCI_SLOT_NAME=0000:03:00.0"
static int parse_pci_slot(const char *uevent_path, char *out, size_t out_size) {
    size_t len = 0;
    char *content = read_file_contents(uevent_path, &len);
    if (!content) return 0;

    int found = 0;
    char *line = content;
    while (line && *line) {
        if (strncmp(line, "PCI_SLOT_NAME=", 14) == 0) {
            char *val = line + 14;
            char *nl = strchr(val, '\n');
            size_t vlen = nl ? (size_t)(nl - val) : strlen(val);
            if (vlen >= out_size) vlen = out_size - 1;
            memcpy(out, val, vlen);
            out[vlen] = '\0';
            found = 1;
            break;
        }
        line = strchr(line, '\n');
        if (line) line++;
    }

    free(content);
    return found;
}

// Read GPU device name from DRM sysfs or product file
static void read_gpu_name_sysfs(const char *device_path, char *out, size_t out_size) {
    char path[512];

    // Try vendor-specific name files
    snprintf(path, sizeof(path), "%s/product_name", device_path);
    if (read_file_line(path, out, out_size) > 0) return;

    // Try reading from PCI device label
    snprintf(path, sizeof(path), "%s/label", device_path);
    if (read_file_line(path, out, out_size) > 0) return;

    out[0] = '\0';
}

// Parse active clock from pp_dpm_sclk/pp_dpm_mclk (AMD)
// Format: "0: 500Mhz\n1: 800Mhz *\n2: 1200Mhz\n"
// Line ending with " *" is the active frequency.
static int32_t parse_amd_active_clock(const char *path) {
    size_t len = 0;
    char *content = read_file_contents(path, &len);
    if (!content) return -1;

    int32_t result = -1;
    char *line = content;
    while (line && *line) {
        char *nl = strchr(line, '\n');
        size_t line_len = nl ? (size_t)(nl - line) : strlen(line);

        // Check if line ends with " *" (active entry)
        if (line_len >= 2 && line[line_len - 1] == '*' && line[line_len - 2] == ' ') {
            // Parse "N: XXXMhz *" — find the frequency value
            char *colon = strchr(line, ':');
            if (colon) {
                unsigned int mhz;
                if (sscanf(colon + 1, " %uMhz", &mhz) == 1 ||
                    sscanf(colon + 1, " %uMHz", &mhz) == 1) {
                    result = (int32_t)mhz;
                }
            }
        }

        if (!nl) break;
        line = nl + 1;
    }

    free(content);
    return result;
}

// ---------------------------------------------------------------------------
// GPU enumeration
// ---------------------------------------------------------------------------

static void enumerate_drm_gpus(void) {
    DIR *dir = opendir("/sys/class/drm");
    if (!dir) return;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && gpu_count < MAX_GPUS) {
        // Match "card0", "card1", etc. (not "card0-DP-1" etc.)
        if (strncmp(entry->d_name, "card", 4) != 0) continue;
        const char *num_start = entry->d_name + 4;
        char *end;
        long card_idx = strtol(num_start, &end, 10);
        if (*end != '\0') continue; // skip "cardN-xxx" entries

        char device_path[512];
        snprintf(device_path, sizeof(device_path), "/sys/class/drm/%s/device", entry->d_name);

        char uevent_path[512];
        snprintf(uevent_path, sizeof(uevent_path), "%s/uevent", device_path);

        // Must have uevent with PCI_ID to be a real GPU
        uint32_t vendor_id = 0, device_id = 0;
        if (!parse_pci_ids(uevent_path, &vendor_id, &device_id)) continue;

        gpu_driver_type_t driver = parse_driver(uevent_path);

        // Skip if no recognized GPU driver
        if (driver == GPU_DRIVER_UNKNOWN) continue;

        gpu_entry_t *gpu = &gpu_list[gpu_count];
        memset(gpu, 0, sizeof(gpu_entry_t));

        gpu->vendor_id = vendor_id;
        gpu->device_id = device_id;
        gpu->driver_type = driver;
        gpu->card_index = (int)card_idx;
        gpu->temperature = NAN;
        gpu->gpu_usage = NAN;
        gpu->memory_used = -1;
        gpu->core_clock_mhz = -1;
        gpu->mem_clock_mhz = -1;
        gpu->fan_speed_pct = NAN;
        gpu->power_draw_watts = NAN;
        gpu->vram_total = 0;
        gpu->sys_mem_dedicated = 0;
        gpu->sys_mem_shared = 0;

        parse_pci_slot(uevent_path, gpu->pci_slot, sizeof(gpu->pci_slot));
        find_hwmon_path(device_path, gpu->hwmon_path, sizeof(gpu->hwmon_path));

        // Read static info based on driver
        if (driver == GPU_DRIVER_AMDGPU) {
            char path[512];

            // VRAM total
            snprintf(path, sizeof(path), "%s/mem_info_vram_total", device_path);
            int64_t vram = read_sysfs_long(path);
            if (vram > 0) gpu->vram_total = vram;

            // Shared memory (GTT)
            snprintf(path, sizeof(path), "%s/mem_info_gtt_total", device_path);
            int64_t gtt = read_sysfs_long(path);
            if (gtt > 0) gpu->sys_mem_shared = gtt;

            // Device name from product_name or hwmon name
            read_gpu_name_sysfs(device_path, gpu->name, sizeof(gpu->name));
            if (gpu->name[0] == '\0' && gpu->hwmon_path[0]) {
                snprintf(path, sizeof(path), "%s/name", gpu->hwmon_path);
                read_file_line(path, gpu->name, sizeof(gpu->name));
            }
        } else if (driver == GPU_DRIVER_INTEL) {
            read_gpu_name_sysfs(device_path, gpu->name, sizeof(gpu->name));

            // Intel integrated GPUs share system memory
            // Try to read local memory if available (Intel discrete)
            char path[512];
            snprintf(path, sizeof(path), "%s/lmem_total_bytes", device_path);
            int64_t lmem = read_sysfs_long(path);
            if (lmem > 0) {
                gpu->vram_total = lmem;
            }
        }
        // NVIDIA name is filled later via NVML

        gpu_count++;
    }

    closedir(dir);
}

static void init_nvidia_gpus(void) {
    if (!nvml_loaded) return;

    // Get driver version
    char driver_ver[GPU_DRIVER_LEN] = {0};
    nvml.SystemGetDriverVersion(driver_ver, sizeof(driver_ver));

    unsigned int nvml_count = 0;
    if (nvml.DeviceGetCount(&nvml_count) != NVML_SUCCESS) return;

    for (unsigned int i = 0; i < nvml_count; i++) {
        nvmlDevice_t device;
        if (nvml.DeviceGetHandleByIndex(i, &device) != NVML_SUCCESS) continue;

        nvmlPciInfo_t pci_info;
        if (nvml.DeviceGetPciInfo(device, &pci_info) != NVML_SUCCESS) continue;

        // Match NVML device to our enumerated GPU by PCI bus ID
        for (int g = 0; g < gpu_count; g++) {
            if (gpu_list[g].driver_type != GPU_DRIVER_NVIDIA) continue;

            // Compare PCI slot IDs (case-insensitive)
            if (strcasecmp(gpu_list[g].pci_slot, pci_info.busId) == 0) {
                gpu_list[g].nvml_device = device;

                char name[GPU_NAME_LEN];
                if (nvml.DeviceGetName(device, name, sizeof(name)) == NVML_SUCCESS) {
                    strncpy(gpu_list[g].name, name, GPU_NAME_LEN - 1);
                }

                strncpy(gpu_list[g].driver_version, driver_ver, GPU_DRIVER_LEN - 1);

                // VRAM total
                nvmlMemory_t mem;
                if (nvml.DeviceGetMemoryInfo(device, &mem) == NVML_SUCCESS) {
                    gpu_list[g].vram_total = (int64_t)mem.total;
                }
                break;
            }
        }
    }
}

static void ensure_gpus_initialized(void) {
    if (gpus_initialized) return;
    gpus_initialized = 1;

    nvml_load();
    enumerate_drm_gpus();
    init_nvidia_gpus();

    // Fill fallback names for GPUs without a name
    for (int i = 0; i < gpu_count; i++) {
        if (gpu_list[i].name[0] == '\0') {
            const char *vendor_name;
            switch (gpu_list[i].vendor_id) {
                case 0x10DE: vendor_name = "NVIDIA"; break;
                case 0x1002: vendor_name = "AMD"; break;
                case 0x8086: vendor_name = "Intel"; break;
                default: vendor_name = "GPU"; break;
            }
            snprintf(gpu_list[i].name, GPU_NAME_LEN, "%s Graphics [%04x:%04x]",
                     vendor_name, gpu_list[i].vendor_id, gpu_list[i].device_id);
        }
    }
}

// ---------------------------------------------------------------------------
// Dynamic refresh
// ---------------------------------------------------------------------------

static void refresh_nvidia_gpu(gpu_entry_t *gpu) {
    if (!nvml_loaded || !gpu->nvml_device) return;

    unsigned int val;

    if (nvml.DeviceGetTemperature(gpu->nvml_device, NVML_TEMPERATURE_GPU, &val) == NVML_SUCCESS)
        gpu->temperature = (float)val;

    nvmlUtilization_t util;
    if (nvml.DeviceGetUtilizationRates(gpu->nvml_device, &util) == NVML_SUCCESS)
        gpu->gpu_usage = (float)util.gpu;

    nvmlMemory_t mem;
    if (nvml.DeviceGetMemoryInfo(gpu->nvml_device, &mem) == NVML_SUCCESS)
        gpu->memory_used = (int64_t)mem.used;

    if (nvml.DeviceGetClockInfo(gpu->nvml_device, NVML_CLOCK_GRAPHICS, &val) == NVML_SUCCESS)
        gpu->core_clock_mhz = (int32_t)val;

    if (nvml.DeviceGetClockInfo(gpu->nvml_device, NVML_CLOCK_MEM, &val) == NVML_SUCCESS)
        gpu->mem_clock_mhz = (int32_t)val;

    if (nvml.DeviceGetFanSpeed(gpu->nvml_device, &val) == NVML_SUCCESS)
        gpu->fan_speed_pct = (float)val;

    // Power usage is in milliwatts
    if (nvml.DeviceGetPowerUsage(gpu->nvml_device, &val) == NVML_SUCCESS)
        gpu->power_draw_watts = (float)val / 1000.0f;
}

static void refresh_amd_gpu(gpu_entry_t *gpu) {
    char path[512];
    char device_path[512];
    snprintf(device_path, sizeof(device_path), "/sys/class/drm/card%d/device", gpu->card_index);

    // Temperature from hwmon (millidegrees C)
    if (gpu->hwmon_path[0]) {
        snprintf(path, sizeof(path), "%s/temp1_input", gpu->hwmon_path);
        int64_t temp_mdeg = read_sysfs_long(path);
        if (temp_mdeg >= 0)
            gpu->temperature = (float)temp_mdeg / 1000.0f;

        // Fan speed: compute percentage from RPM and max RPM
        snprintf(path, sizeof(path), "%s/pwm1", gpu->hwmon_path);
        int64_t pwm = read_sysfs_long(path);
        snprintf(path, sizeof(path), "%s/pwm1_max", gpu->hwmon_path);
        int64_t pwm_max = read_sysfs_long(path);
        if (pwm >= 0 && pwm_max > 0) {
            gpu->fan_speed_pct = (float)pwm / (float)pwm_max * 100.0f;
        }

        // Power draw: power1_average is in microwatts
        snprintf(path, sizeof(path), "%s/power1_average", gpu->hwmon_path);
        int64_t power_uw = read_sysfs_long(path);
        if (power_uw >= 0) {
            gpu->power_draw_watts = (float)power_uw / 1000000.0f;
        }
    }

    // GPU usage percentage
    snprintf(path, sizeof(path), "%s/gpu_busy_percent", device_path);
    float usage = read_sysfs_float(path);
    if (!isnan(usage)) gpu->gpu_usage = usage;

    // VRAM used
    snprintf(path, sizeof(path), "%s/mem_info_vram_used", device_path);
    int64_t vram_used = read_sysfs_long(path);
    if (vram_used >= 0) gpu->memory_used = vram_used;

    // Core clock from pp_dpm_sclk
    snprintf(path, sizeof(path), "%s/pp_dpm_sclk", device_path);
    int32_t sclk = parse_amd_active_clock(path);
    if (sclk >= 0) gpu->core_clock_mhz = sclk;

    // Memory clock from pp_dpm_mclk
    snprintf(path, sizeof(path), "%s/pp_dpm_mclk", device_path);
    int32_t mclk = parse_amd_active_clock(path);
    if (mclk >= 0) gpu->mem_clock_mhz = mclk;
}

static void refresh_intel_gpu(gpu_entry_t *gpu) {
    char path[512];

    // Temperature from hwmon
    if (gpu->hwmon_path[0]) {
        snprintf(path, sizeof(path), "%s/temp1_input", gpu->hwmon_path);
        int64_t temp_mdeg = read_sysfs_long(path);
        if (temp_mdeg >= 0)
            gpu->temperature = (float)temp_mdeg / 1000.0f;

        // Power draw
        snprintf(path, sizeof(path), "%s/power1_average", gpu->hwmon_path);
        int64_t power_uw = read_sysfs_long(path);
        if (power_uw >= 0) {
            gpu->power_draw_watts = (float)power_uw / 1000000.0f;
        } else {
            // Try energy-based calculation — not trivial for a polling JNI call, skip
        }
    }

    // Intel discrete GPU memory usage (if applicable)
    char device_path[512];
    snprintf(device_path, sizeof(device_path), "/sys/class/drm/card%d/device", gpu->card_index);
    snprintf(path, sizeof(path), "%s/lmem_used_bytes", device_path);
    int64_t lmem_used = read_sysfs_long(path);
    if (lmem_used >= 0) gpu->memory_used = lmem_used;

    // gt_cur_freq_mhz for Intel GPU clock
    snprintf(path, sizeof(path), "/sys/class/drm/card%d/gt_cur_freq_mhz", gpu->card_index);
    int64_t freq = read_sysfs_long(path);
    if (freq > 0) gpu->core_clock_mhz = (int32_t)freq;
}

static void refresh_all_gpus(void) {
    ensure_gpus_initialized();
    for (int i = 0; i < gpu_count; i++) {
        // Reset dynamic values
        gpu_list[i].temperature = NAN;
        gpu_list[i].gpu_usage = NAN;
        gpu_list[i].memory_used = -1;
        gpu_list[i].core_clock_mhz = -1;
        gpu_list[i].mem_clock_mhz = -1;
        gpu_list[i].fan_speed_pct = NAN;
        gpu_list[i].power_draw_watts = NAN;

        switch (gpu_list[i].driver_type) {
            case GPU_DRIVER_NVIDIA: refresh_nvidia_gpu(&gpu_list[i]); break;
            case GPU_DRIVER_AMDGPU: refresh_amd_gpu(&gpu_list[i]); break;
            case GPU_DRIVER_INTEL:  refresh_intel_gpu(&gpu_list[i]); break;
            default: break;
        }
    }
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    refresh_all_gpus();
    return (jint)gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    const char *names[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) names[i] = gpu_list[i].name;
    return to_string_array(env, names, gpu_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].vendor_id;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].device_id;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].vram_total;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].sys_mem_dedicated;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].sys_mem_shared;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    const char *versions[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) versions[i] = gpu_list[i].driver_version;
    return to_string_array(env, versions, gpu_count);
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuTemperatures(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jfloat vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = gpu_list[i].temperature;
    jfloatArray arr = (*env)->NewFloatArray(env, gpu_count);
    (*env)->SetFloatArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuUsages(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jfloat vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = gpu_list[i].gpu_usage;
    jfloatArray arr = (*env)->NewFloatArray(env, gpu_count);
    (*env)->SetFloatArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuMemoryUsed(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jlong vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jlong)gpu_list[i].memory_used;
    jlongArray arr = (*env)->NewLongArray(env, gpu_count);
    (*env)->SetLongArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuCoreClocks(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jint vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jint)gpu_list[i].core_clock_mhz;
    jintArray arr = (*env)->NewIntArray(env, gpu_count);
    (*env)->SetIntArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuMemoryClocks(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jint vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = (jint)gpu_list[i].mem_clock_mhz;
    jintArray arr = (*env)->NewIntArray(env, gpu_count);
    (*env)->SetIntArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuFanSpeeds(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jfloat vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = gpu_list[i].fan_speed_pct;
    jfloatArray arr = (*env)->NewFloatArray(env, gpu_count);
    (*env)->SetFloatArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeGpuPowerDraws(
    JNIEnv *env, jclass cls) {
    (void)cls;
    ensure_gpus_initialized();
    jfloat vals[MAX_GPUS];
    for (int i = 0; i < gpu_count; i++) vals[i] = gpu_list[i].power_draw_watts;
    jfloatArray arr = (*env)->NewFloatArray(env, gpu_count);
    (*env)->SetFloatArrayRegion(env, arr, 0, gpu_count, vals);
    return arr;
}

// ============================================================================
// Render tier — what the default GL ES driver offers for 3D (RenderTier ordinal)
// ============================================================================
// A throwaway GLES context on Mesa's surfaceless platform (no window system,
// the default render node), else on the default display. libEGL is dlopen'd
// RTLD_LOCAL: no hard dependency, and no symbol interposition into the app.

#define RENDER_TIER_UNKNOWN  0
#define RENDER_TIER_SOFTWARE 1
#define RENDER_TIER_LEGACY   2
#define RENDER_TIER_CAPABLE  3

// The EGL / GL ES values used below (headers not required at build time)
#define NUC_EGL_NONE                    0x3038
#define NUC_EGL_EXTENSIONS              0x3055
#define NUC_EGL_SURFACE_TYPE            0x3033
#define NUC_EGL_RENDERABLE_TYPE         0x3040
#define NUC_EGL_OPENGL_ES2_BIT          0x0004
#define NUC_EGL_OPENGL_ES3_BIT          0x0040
#define NUC_EGL_OPENGL_ES_API           0x30A0
#define NUC_EGL_CONTEXT_MAJOR_VERSION   0x3098
#define NUC_EGL_DRAW                    0x3059
#define NUC_EGL_READ                    0x305A
#define NUC_EGL_PLATFORM_SURFACELESS    0x31DD
#define NUC_GL_RENDERER                 0x1F01

typedef void *(*pfn_eglGetProcAddress)(const char *);
typedef void *(*pfn_eglGetDisplay)(void *);
typedef void *(*pfn_eglGetPlatformDisplay)(unsigned int, void *, const intptr_t *);
typedef void *(*pfn_eglGetPlatformDisplayEXT)(unsigned int, void *, const int *);
typedef unsigned int (*pfn_eglInitialize)(void *, int *, int *);
typedef unsigned int (*pfn_eglTerminate)(void *);
typedef const char *(*pfn_eglQueryString)(void *, int);
typedef unsigned int (*pfn_eglBindAPI)(unsigned int);
typedef unsigned int (*pfn_eglQueryAPI)(void);
typedef unsigned int (*pfn_eglChooseConfig)(void *, const int *, void **, int, int *);
typedef void *(*pfn_eglCreateContext)(void *, void *, void *, const int *);
typedef unsigned int (*pfn_eglDestroyContext)(void *, void *);
typedef unsigned int (*pfn_eglMakeCurrent)(void *, void *, void *, void *);
typedef void *(*pfn_eglGetCurrentContext)(void);
typedef void *(*pfn_eglGetCurrentDisplay)(void);
typedef void *(*pfn_eglGetCurrentSurface)(int);
typedef const unsigned char *(*pfn_glGetString)(unsigned int);

static int has_word(const char *list, const char *word) {
    if (!list) return 0;
    size_t len = strlen(word);
    for (const char *p = list; (p = strstr(p, word)) != NULL; p += len) {
        if ((p == list || p[-1] == ' ') && (p[len] == ' ' || p[len] == '\0')) return 1;
    }
    return 0;
}

static int has_config(pfn_eglChooseConfig choose, void *dpy, int renderable, void **config) {
    // EGL_SURFACE_TYPE 0: any surface type, the context is only made current surfaceless
    const int attribs[] = { NUC_EGL_SURFACE_TYPE, 0, NUC_EGL_RENDERABLE_TYPE, renderable, NUC_EGL_NONE };
    int count = 0;
    return choose(dpy, attribs, config, 1, &count) && count > 0;
}

static int probe_render_tier(void) {
    void *egl = dlopen("libEGL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!egl) return RENDER_TIER_UNKNOWN;
    pfn_eglGetProcAddress getProc = (pfn_eglGetProcAddress)dlsym(egl, "eglGetProcAddress");
    pfn_eglGetDisplay getDisplay = (pfn_eglGetDisplay)dlsym(egl, "eglGetDisplay");
    pfn_eglInitialize initialize = (pfn_eglInitialize)dlsym(egl, "eglInitialize");
    pfn_eglTerminate terminate = (pfn_eglTerminate)dlsym(egl, "eglTerminate");
    pfn_eglQueryString queryString = (pfn_eglQueryString)dlsym(egl, "eglQueryString");
    pfn_eglBindAPI bindApi = (pfn_eglBindAPI)dlsym(egl, "eglBindAPI");
    pfn_eglQueryAPI queryApi = (pfn_eglQueryAPI)dlsym(egl, "eglQueryAPI");
    pfn_eglChooseConfig chooseConfig = (pfn_eglChooseConfig)dlsym(egl, "eglChooseConfig");
    pfn_eglCreateContext createContext = (pfn_eglCreateContext)dlsym(egl, "eglCreateContext");
    pfn_eglDestroyContext destroyContext = (pfn_eglDestroyContext)dlsym(egl, "eglDestroyContext");
    pfn_eglMakeCurrent makeCurrent = (pfn_eglMakeCurrent)dlsym(egl, "eglMakeCurrent");
    pfn_eglGetCurrentContext currentContext = (pfn_eglGetCurrentContext)dlsym(egl, "eglGetCurrentContext");
    pfn_eglGetCurrentDisplay currentDisplay = (pfn_eglGetCurrentDisplay)dlsym(egl, "eglGetCurrentDisplay");
    pfn_eglGetCurrentSurface currentSurface = (pfn_eglGetCurrentSurface)dlsym(egl, "eglGetCurrentSurface");
    if (!getProc || !getDisplay || !initialize || !terminate || !queryString || !bindApi || !queryApi ||
        !chooseConfig || !createContext || !destroyContext || !makeCurrent || !currentContext || !currentDisplay ||
        !currentSurface) {
        dlclose(egl);
        return RENDER_TIER_UNKNOWN;
    }

    // Surfaceless display: our own, so it can be terminated. The default one may be the app's (Tao's): left alone.
    void *dpy = NULL;
    int ownDisplay = 0;
    if (has_word(queryString(NULL, NUC_EGL_EXTENSIONS), "EGL_MESA_platform_surfaceless")) {
        pfn_eglGetPlatformDisplay platformDisplay = (pfn_eglGetPlatformDisplay)dlsym(egl, "eglGetPlatformDisplay");
        pfn_eglGetPlatformDisplayEXT platformDisplayExt =
            (pfn_eglGetPlatformDisplayEXT)getProc("eglGetPlatformDisplayEXT");
        if (platformDisplay) dpy = platformDisplay(NUC_EGL_PLATFORM_SURFACELESS, NULL, NULL);
        else if (platformDisplayExt) dpy = platformDisplayExt(NUC_EGL_PLATFORM_SURFACELESS, NULL, NULL);
        ownDisplay = dpy != NULL;
    }
    int major = 0, minor = 0;
    if (dpy && !initialize(dpy, &major, &minor)) { dpy = NULL; ownDisplay = 0; }
    if (!dpy) {
        dpy = getDisplay(NULL);
        if (dpy && !initialize(dpy, &major, &minor)) dpy = NULL;
    }
    if (!dpy) return RENDER_TIER_UNKNOWN;

    // The calling thread may hold the app's context (Tao's event loop, desktop GL): its bound API and its
    // current context are both put back after. Read before eglBindAPI, the current context is per API.
    unsigned int prevApi = queryApi();
    void *prevDpy = currentDisplay(), *prevCtx = currentContext();
    void *prevDraw = currentSurface(NUC_EGL_DRAW), *prevRead = currentSurface(NUC_EGL_READ);

    int tier = RENDER_TIER_UNKNOWN;
    void *config = NULL;
    if (bindApi(NUC_EGL_OPENGL_ES_API)) {
        if (has_config(chooseConfig, dpy, NUC_EGL_OPENGL_ES3_BIT, &config)) {
            const int ctxAttribs[] = { NUC_EGL_CONTEXT_MAJOR_VERSION, 3, NUC_EGL_NONE };
            void *ctx = createContext(dpy, config, NULL, ctxAttribs);
            if (ctx) {
                tier = RENDER_TIER_CAPABLE;
                pfn_glGetString getString = (pfn_glGetString)getProc("glGetString");
                if (getString && makeCurrent(dpy, NULL, NULL, ctx)) {
                    const char *renderer = (const char *)getString(NUC_GL_RENDERER);
                    if (renderer && (strstr(renderer, "llvmpipe") || strstr(renderer, "softpipe") ||
                                     strstr(renderer, "swrast") || strstr(renderer, "SWR"))) {
                        tier = RENDER_TIER_SOFTWARE;
                    }
                    // Released with GL ES still bound, then the caller's context made current under its own API
                    makeCurrent(dpy, NULL, NULL, NULL);
                    bindApi(prevApi);
                    if (prevCtx) makeCurrent(prevDpy, prevDraw, prevRead, prevCtx);
                }
                destroyContext(dpy, ctx);
            } else {
                tier = RENDER_TIER_LEGACY;
            }
        } else if (has_config(chooseConfig, dpy, NUC_EGL_OPENGL_ES2_BIT, &config)) {
            // GL ES 2 only: a GPU too old for GL ES 3 (Intel Ironlake and earlier, …)
            tier = RENDER_TIER_LEGACY;
        }
    }
    bindApi(prevApi);
    if (ownDisplay) terminate(dpy);
    // Not dlclose'd: EGL drivers do not support being unloaded
    return tier;
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeRenderTier(
    JNIEnv *env, jclass clazz) {
    // The default GL driver does not change while the app runs
    static volatile int cached = -1;
    if (cached < 0) cached = probe_render_tier();
    return (jint)cached;
}
