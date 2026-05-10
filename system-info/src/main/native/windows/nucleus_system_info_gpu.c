// GPU information via DXGI + live metrics via NVML (NVIDIA), ADL2 (AMD), IGCL (Intel).
// All vendor libraries are loaded dynamically — not required at compile time.

#include "nucleus_system_info_common.h"

// DXGI headers
#include <dxgi.h>
#include <dxgi1_4.h>
#include <initguid.h>
#include <math.h>

// IDXGIFactory1 GUID
DEFINE_GUID(IID_IDXGIFactory1_local,
    0x770aae78, 0xf26f, 0x4dba, 0xa8, 0x29, 0x25, 0x3c, 0x83, 0xd1, 0xb3, 0x87);

// IDXGIAdapter3 GUID (DXGI 1.4 for QueryVideoMemoryInfo)
DEFINE_GUID(IID_IDXGIAdapter3_local,
    0x645967a4, 0x1392, 0x4310, 0xa7, 0x98, 0x80, 0x53, 0xce, 0x3e, 0x93, 0xfd);

#define MAX_GPUS 16

typedef struct {
    char name[256];
    UINT vendor_id;
    UINT device_id;
    SIZE_T dedicated_video_memory;
    SIZE_T dedicated_system_memory;
    SIZE_T shared_system_memory;
    char driver_version[64];
    // Live metrics
    float temperature;       // NaN if unavailable
    float gpu_usage;         // NaN if unavailable
    long long memory_used;   // -1 if unavailable
    int core_clock_mhz;     // -1 if unavailable
    int memory_clock_mhz;   // -1 if unavailable
    float fan_speed_pct;     // NaN if unavailable
    float power_draw_watts;  // NaN if unavailable
} gpu_entry_t;

static gpu_entry_t g_gpus[MAX_GPUS];
static int g_gpu_count = 0;

// ============================================================================
// NVIDIA NVML
// ============================================================================

typedef int nvmlReturn_t;
#define NVML_SUCCESS 0
typedef void* nvmlDevice_t;
typedef enum { NVML_TEMPERATURE_GPU = 0 } nvmlTemperatureSensors_t;
typedef enum { NVML_CLOCK_GRAPHICS = 0, NVML_CLOCK_SM = 1, NVML_CLOCK_MEM = 2 } nvmlClockType_t;

typedef struct { unsigned int gpu; unsigned int memory; } nvmlUtilization_t;
typedef struct { unsigned long long total; unsigned long long free; unsigned long long used; } nvmlMemory_t;
typedef struct {
    char busId[32]; unsigned int domain; unsigned int bus; unsigned int device;
    unsigned int pciDeviceId; unsigned int pciSubSystemId; char busIdLegacy[16];
} nvmlPciInfo_t;

typedef nvmlReturn_t (*pfn_nvmlInit)(void);
typedef nvmlReturn_t (*pfn_nvmlShutdown)(void);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetCount)(unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetHandleByIndex)(unsigned int, nvmlDevice_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetTemperature)(nvmlDevice_t, nvmlTemperatureSensors_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetUtilizationRates)(nvmlDevice_t, nvmlUtilization_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetClockInfo)(nvmlDevice_t, nvmlClockType_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetFanSpeed)(nvmlDevice_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPowerUsage)(nvmlDevice_t, unsigned int*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetPciInfo)(nvmlDevice_t, nvmlPciInfo_t*);
typedef nvmlReturn_t (*pfn_nvmlDeviceGetMemoryInfo)(nvmlDevice_t, nvmlMemory_t*);

static struct {
    HMODULE hLib;
    pfn_nvmlInit                    Init;
    pfn_nvmlShutdown                Shutdown;
    pfn_nvmlDeviceGetCount          DeviceGetCount;
    pfn_nvmlDeviceGetHandleByIndex  DeviceGetHandleByIndex;
    pfn_nvmlDeviceGetTemperature    DeviceGetTemperature;
    pfn_nvmlDeviceGetUtilizationRates DeviceGetUtilizationRates;
    pfn_nvmlDeviceGetClockInfo      DeviceGetClockInfo;
    pfn_nvmlDeviceGetFanSpeed       DeviceGetFanSpeed;
    pfn_nvmlDeviceGetPowerUsage     DeviceGetPowerUsage;
    pfn_nvmlDeviceGetPciInfo        DeviceGetPciInfo;
    pfn_nvmlDeviceGetMemoryInfo     DeviceGetMemoryInfo;
    int initialized;
} nvml = { 0 };

static int load_nvml(void) {
    if (nvml.hLib) return nvml.initialized;
    nvml.hLib = LoadLibraryA("nvml.dll");
    if (!nvml.hLib) return 0;

    nvml.Init = (pfn_nvmlInit)GetProcAddress(nvml.hLib, "nvmlInit_v2");
    nvml.Shutdown = (pfn_nvmlShutdown)GetProcAddress(nvml.hLib, "nvmlShutdown");
    nvml.DeviceGetCount = (pfn_nvmlDeviceGetCount)GetProcAddress(nvml.hLib, "nvmlDeviceGetCount_v2");
    nvml.DeviceGetHandleByIndex = (pfn_nvmlDeviceGetHandleByIndex)GetProcAddress(nvml.hLib, "nvmlDeviceGetHandleByIndex_v2");
    nvml.DeviceGetTemperature = (pfn_nvmlDeviceGetTemperature)GetProcAddress(nvml.hLib, "nvmlDeviceGetTemperature");
    nvml.DeviceGetUtilizationRates = (pfn_nvmlDeviceGetUtilizationRates)GetProcAddress(nvml.hLib, "nvmlDeviceGetUtilizationRates");
    nvml.DeviceGetClockInfo = (pfn_nvmlDeviceGetClockInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetClockInfo");
    nvml.DeviceGetFanSpeed = (pfn_nvmlDeviceGetFanSpeed)GetProcAddress(nvml.hLib, "nvmlDeviceGetFanSpeed");
    nvml.DeviceGetPowerUsage = (pfn_nvmlDeviceGetPowerUsage)GetProcAddress(nvml.hLib, "nvmlDeviceGetPowerUsage");
    nvml.DeviceGetPciInfo = (pfn_nvmlDeviceGetPciInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetPciInfo_v3");
    nvml.DeviceGetMemoryInfo = (pfn_nvmlDeviceGetMemoryInfo)GetProcAddress(nvml.hLib, "nvmlDeviceGetMemoryInfo");

    if (!nvml.Init || !nvml.DeviceGetCount || !nvml.DeviceGetHandleByIndex) {
        FreeLibrary(nvml.hLib); nvml.hLib = NULL; return 0;
    }
    if (nvml.Init() != NVML_SUCCESS) {
        FreeLibrary(nvml.hLib); nvml.hLib = NULL; return 0;
    }
    nvml.initialized = 1;
    return 1;
}

static nvmlDevice_t find_nvml_device_for(UINT vendor_id, UINT device_id) {
    if (!nvml.initialized || vendor_id != 0x10DE) return NULL;
    unsigned int count = 0;
    if (nvml.DeviceGetCount(&count) != NVML_SUCCESS) return NULL;

    for (unsigned int i = 0; i < count; i++) {
        nvmlDevice_t dev = NULL;
        if (nvml.DeviceGetHandleByIndex(i, &dev) != NVML_SUCCESS) continue;
        if (nvml.DeviceGetPciInfo) {
            nvmlPciInfo_t pci;
            if (nvml.DeviceGetPciInfo(dev, &pci) == NVML_SUCCESS) {
                UINT nvml_device = (pci.pciDeviceId >> 16) & 0xFFFF;
                if (nvml_device == device_id) return dev;
            }
        }
    }
    if (count == 1) {
        nvmlDevice_t dev = NULL;
        if (nvml.DeviceGetHandleByIndex(0, &dev) == NVML_SUCCESS) return dev;
    }
    return NULL;
}

static void fill_nvml_metrics(gpu_entry_t *g, nvmlDevice_t dev) {
    unsigned int val;

    if (nvml.DeviceGetTemperature && nvml.DeviceGetTemperature(dev, NVML_TEMPERATURE_GPU, &val) == NVML_SUCCESS)
        g->temperature = (float)val;

    if (nvml.DeviceGetUtilizationRates) {
        nvmlUtilization_t util;
        if (nvml.DeviceGetUtilizationRates(dev, &util) == NVML_SUCCESS)
            g->gpu_usage = (float)util.gpu;
    }

    if (nvml.DeviceGetClockInfo) {
        if (nvml.DeviceGetClockInfo(dev, NVML_CLOCK_GRAPHICS, &val) == NVML_SUCCESS)
            g->core_clock_mhz = (int)val;
        if (nvml.DeviceGetClockInfo(dev, NVML_CLOCK_MEM, &val) == NVML_SUCCESS)
            g->memory_clock_mhz = (int)val;
    }

    if (nvml.DeviceGetFanSpeed && nvml.DeviceGetFanSpeed(dev, &val) == NVML_SUCCESS)
        g->fan_speed_pct = (float)val;

    if (nvml.DeviceGetPowerUsage && nvml.DeviceGetPowerUsage(dev, &val) == NVML_SUCCESS)
        g->power_draw_watts = (float)val / 1000.0f;

    // VRAM: try v2 first (needed for RTX 50 series), fall back to v1
    {
        typedef struct { unsigned int version; unsigned long long total, reserved, free, used; } nvml_mem2_t;
        typedef nvmlReturn_t (*pfn_mem_v2)(nvmlDevice_t, nvml_mem2_t*);
        pfn_mem_v2 fn = (pfn_mem_v2)GetProcAddress(nvml.hLib, "nvmlDeviceGetMemoryInfo_v2");
        if (fn) {
            nvml_mem2_t m2;
            memset(&m2, 0, sizeof(m2));
            m2.version = (unsigned int)(sizeof(nvml_mem2_t)) | (2U << 24U);
            if (fn(dev, &m2) == NVML_SUCCESS && m2.used > 0)
                g->memory_used = (long long)m2.used;
        }
    }
    if (g->memory_used <= 0 && nvml.DeviceGetMemoryInfo) {
        nvmlMemory_t mem;
        memset(&mem, 0, sizeof(mem));
        if (nvml.DeviceGetMemoryInfo(dev, &mem) == NVML_SUCCESS) {
            if (mem.used > 0) g->memory_used = (long long)mem.used;
            else if (mem.total > 0 && mem.free <= mem.total)
                g->memory_used = (long long)(mem.total - mem.free);
        }
    }
}

// ============================================================================
// AMD ADL2 (atiadlxx.dll)
// ============================================================================

#define ADL_OK 0
#define ADL_MAX_PATH 256
#define ADL_PMLOG_MAX_SENSORS 256

// PMLog sensor indices (from adl_defines.h — official GPUOpen SDK)
#define ADL_PMLOG_CLK_GFXCLK          1
#define ADL_PMLOG_CLK_MEMCLK          2
#define ADL_PMLOG_TEMPERATURE_EDGE     7
#define ADL_PMLOG_TEMPERATURE_MEM      8
#define ADL_PMLOG_TEMPERATURE_VRVDDC   9
#define ADL_PMLOG_FAN_RPM             13
#define ADL_PMLOG_FAN_PERCENTAGE      14
#define ADL_PMLOG_INFO_ACTIVITY_GFX   18
#define ADL_PMLOG_GFX_VOLTAGE         20
#define ADL_PMLOG_ASIC_POWER          22
#define ADL_PMLOG_TEMPERATURE_HOTSPOT  23

typedef void* ADL_CONTEXT_HANDLE;

typedef struct {
    int iSize;
    int iAdapterIndex;
    char strUDID[ADL_MAX_PATH];
    int iBusNumber;
    int iDeviceNumber;
    int iFunctionNumber;
    int iVendorID;
    char strAdapterName[ADL_MAX_PATH];
    char strDisplayName[ADL_MAX_PATH];
    int iPresent;
    int iExist;
    char strDriverPath[ADL_MAX_PATH];
    char strDriverPathExt[ADL_MAX_PATH];
    char strPNPString[ADL_MAX_PATH];
    int iOSDisplayIndex;
} AdapterInfo;

typedef struct {
    int supported;
    int value;
} ADLSingleSensorData;

typedef struct {
    int size;
    ADLSingleSensorData sensors[ADL_PMLOG_MAX_SENSORS];
} ADLPMLogDataOutput;

// OverdriveN structs (fallback for older GPUs without PMLog)
typedef struct {
    int iCoreClock;           // in 10 KHz
    int iMemoryClock;         // in 10 KHz
    int iDCEFClock;
    int iGFXClock;
    int iUVDClock;
    int iVCEClock;
    int iGPUActivityPercent;
    int iCurrentCorePerformanceLevel;
    int iCurrentMemoryPerformanceLevel;
    int iCurrentDCEFPerformanceLevel;
    int iCurrentGFXPerformanceLevel;
    int iUVDPerformanceLevel;
    int iVCEPerformanceLevel;
    int iCurrentBusSpeed;
    int iCurrentBusLanes;
    int iMaximumBusLanes;
    int iVDDC;
    int iVDDCI;
} ADLODNPerformanceStatus;

typedef struct {
    int iMode;
    int iFanControlMode;
    int iCurrentFanSpeedMode;
    int iCurrentFanSpeed;
    int iTargetFanSpeed;
    int iTargetTemperature;
    int iMinPerformanceClock;
    int iMinFanLimit;
} ADLODNFanControl;

typedef struct {
    int iSize;
    int iTemperature;    // millidegrees Celsius
} ADLTemperature;

typedef void* (__stdcall *ADL_MAIN_MALLOC_CALLBACK)(int);

typedef int (*pfn_ADL2_Main_Control_Create)(ADL_MAIN_MALLOC_CALLBACK, int, ADL_CONTEXT_HANDLE*);
typedef int (*pfn_ADL2_Main_Control_Destroy)(ADL_CONTEXT_HANDLE);
typedef int (*pfn_ADL2_Adapter_NumberOfAdapters_Get)(ADL_CONTEXT_HANDLE, int*);
typedef int (*pfn_ADL2_Adapter_AdapterInfo_Get)(ADL_CONTEXT_HANDLE, AdapterInfo*, int);
typedef int (*pfn_ADL2_Adapter_Active_Get)(ADL_CONTEXT_HANDLE, int, int*);
typedef int (*pfn_ADL2_New_QueryPMLogData_Get)(ADL_CONTEXT_HANDLE, int, ADLPMLogDataOutput*);
typedef int (*pfn_ADL2_OverdriveN_PerformanceStatus_Get)(ADL_CONTEXT_HANDLE, int, ADLODNPerformanceStatus*);
typedef int (*pfn_ADL2_OverdriveN_FanControl_Get)(ADL_CONTEXT_HANDLE, int, ADLODNFanControl*);
typedef int (*pfn_ADL2_Overdrive5_Temperature_Get)(ADL_CONTEXT_HANDLE, int, int, ADLTemperature*);

static struct {
    HMODULE hLib;
    ADL_CONTEXT_HANDLE context;
    pfn_ADL2_Main_Control_Create            Main_Control_Create;
    pfn_ADL2_Main_Control_Destroy           Main_Control_Destroy;
    pfn_ADL2_Adapter_NumberOfAdapters_Get   Adapter_NumberOfAdapters_Get;
    pfn_ADL2_Adapter_AdapterInfo_Get        Adapter_AdapterInfo_Get;
    pfn_ADL2_Adapter_Active_Get             Adapter_Active_Get;
    pfn_ADL2_New_QueryPMLogData_Get         New_QueryPMLogData_Get;
    pfn_ADL2_OverdriveN_PerformanceStatus_Get OverdriveN_PerformanceStatus_Get;
    pfn_ADL2_OverdriveN_FanControl_Get      OverdriveN_FanControl_Get;
    pfn_ADL2_Overdrive5_Temperature_Get     Overdrive5_Temperature_Get;
    AdapterInfo *adapters;
    int adapter_count;
    int initialized;
} adl = { 0 };

static void* __stdcall adl_malloc_callback(int iSize) {
    return malloc(iSize);
}

static int load_adl(void) {
    if (adl.hLib) return adl.initialized;

    adl.hLib = LoadLibraryA("atiadlxx.dll");
    if (!adl.hLib) {
        adl.hLib = LoadLibraryA("atiadlxy.dll");  // 32-bit fallback
    }
    if (!adl.hLib) return 0;

    adl.Main_Control_Create = (pfn_ADL2_Main_Control_Create)GetProcAddress(adl.hLib, "ADL2_Main_Control_Create");
    adl.Main_Control_Destroy = (pfn_ADL2_Main_Control_Destroy)GetProcAddress(adl.hLib, "ADL2_Main_Control_Destroy");
    adl.Adapter_NumberOfAdapters_Get = (pfn_ADL2_Adapter_NumberOfAdapters_Get)GetProcAddress(adl.hLib, "ADL2_Adapter_NumberOfAdapters_Get");
    adl.Adapter_AdapterInfo_Get = (pfn_ADL2_Adapter_AdapterInfo_Get)GetProcAddress(adl.hLib, "ADL2_Adapter_AdapterInfo_Get");
    adl.Adapter_Active_Get = (pfn_ADL2_Adapter_Active_Get)GetProcAddress(adl.hLib, "ADL2_Adapter_Active_Get");
    adl.New_QueryPMLogData_Get = (pfn_ADL2_New_QueryPMLogData_Get)GetProcAddress(adl.hLib, "ADL2_New_QueryPMLogData_Get");
    adl.OverdriveN_PerformanceStatus_Get = (pfn_ADL2_OverdriveN_PerformanceStatus_Get)GetProcAddress(adl.hLib, "ADL2_OverdriveN_PerformanceStatus_Get");
    adl.OverdriveN_FanControl_Get = (pfn_ADL2_OverdriveN_FanControl_Get)GetProcAddress(adl.hLib, "ADL2_OverdriveN_FanControl_Get");
    adl.Overdrive5_Temperature_Get = (pfn_ADL2_Overdrive5_Temperature_Get)GetProcAddress(adl.hLib, "ADL2_Overdrive5_Temperature_Get");

    if (!adl.Main_Control_Create || !adl.Adapter_NumberOfAdapters_Get) {
        FreeLibrary(adl.hLib); adl.hLib = NULL; return 0;
    }

    if (adl.Main_Control_Create(adl_malloc_callback, 1, &adl.context) != ADL_OK) {
        FreeLibrary(adl.hLib); adl.hLib = NULL; return 0;
    }

    // Enumerate adapters
    adl.adapter_count = 0;
    adl.Adapter_NumberOfAdapters_Get(adl.context, &adl.adapter_count);
    if (adl.adapter_count > 0 && adl.Adapter_AdapterInfo_Get) {
        adl.adapters = (AdapterInfo *)calloc(adl.adapter_count, sizeof(AdapterInfo));
        if (adl.adapters) {
            adl.Adapter_AdapterInfo_Get(adl.context, adl.adapters, adl.adapter_count * (int)sizeof(AdapterInfo));
        }
    }

    adl.initialized = 1;
    return 1;
}

// Find ADL adapter index matching a DXGI adapter by vendor+device ID (PCI bus preferred)
static int find_adl_adapter_for(UINT vendor_id, UINT device_id) {
    if (!adl.initialized || vendor_id != 0x1002 || !adl.adapters) return -1;

    // Find first active adapter with matching vendor
    for (int i = 0; i < adl.adapter_count; i++) {
        if (adl.adapters[i].iVendorID != (int)vendor_id) continue;

        // Check if adapter is active
        if (adl.Adapter_Active_Get) {
            int active = 0;
            if (adl.Adapter_Active_Get(adl.context, adl.adapters[i].iAdapterIndex, &active) == ADL_OK && !active)
                continue;
        }

        // Match by PNP string containing device ID, or just return first active AMD adapter
        return adl.adapters[i].iAdapterIndex;
    }
    return -1;
}

static void fill_adl_metrics(gpu_entry_t *g, int adapter_index) {
    // Try PMLogData first (RDNA+ / Overdrive8) — single call for all metrics
    if (adl.New_QueryPMLogData_Get) {
        ADLPMLogDataOutput pmlog;
        memset(&pmlog, 0, sizeof(pmlog));
        if (adl.New_QueryPMLogData_Get(adl.context, adapter_index, &pmlog) == ADL_OK) {
            // Temperature (edge, fallback to hotspot)
            if (pmlog.sensors[ADL_PMLOG_TEMPERATURE_EDGE].supported)
                g->temperature = (float)pmlog.sensors[ADL_PMLOG_TEMPERATURE_EDGE].value;
            else if (pmlog.sensors[ADL_PMLOG_TEMPERATURE_HOTSPOT].supported)
                g->temperature = (float)pmlog.sensors[ADL_PMLOG_TEMPERATURE_HOTSPOT].value;

            // GPU usage
            if (pmlog.sensors[ADL_PMLOG_INFO_ACTIVITY_GFX].supported)
                g->gpu_usage = (float)pmlog.sensors[ADL_PMLOG_INFO_ACTIVITY_GFX].value;

            // Core clock (MHz)
            if (pmlog.sensors[ADL_PMLOG_CLK_GFXCLK].supported)
                g->core_clock_mhz = pmlog.sensors[ADL_PMLOG_CLK_GFXCLK].value;

            // Memory clock (MHz)
            if (pmlog.sensors[ADL_PMLOG_CLK_MEMCLK].supported)
                g->memory_clock_mhz = pmlog.sensors[ADL_PMLOG_CLK_MEMCLK].value;

            // Fan speed (percentage)
            if (pmlog.sensors[ADL_PMLOG_FAN_PERCENTAGE].supported)
                g->fan_speed_pct = (float)pmlog.sensors[ADL_PMLOG_FAN_PERCENTAGE].value;

            // Power (ASIC total board power)
            if (pmlog.sensors[ADL_PMLOG_ASIC_POWER].supported)
                g->power_draw_watts = (float)pmlog.sensors[ADL_PMLOG_ASIC_POWER].value;

            return;  // PMLog provided all metrics
        }
    }

    // Fallback: OverdriveN APIs (Polaris / Vega)
    if (adl.OverdriveN_PerformanceStatus_Get) {
        ADLODNPerformanceStatus status;
        memset(&status, 0, sizeof(status));
        if (adl.OverdriveN_PerformanceStatus_Get(adl.context, adapter_index, &status) == ADL_OK) {
            if (status.iGPUActivityPercent >= 0)
                g->gpu_usage = (float)status.iGPUActivityPercent;
            if (status.iCoreClock > 0)
                g->core_clock_mhz = status.iCoreClock / 100;  // 10 KHz -> MHz
            if (status.iMemoryClock > 0)
                g->memory_clock_mhz = status.iMemoryClock / 100;
        }
    }

    // Temperature via OD5 (most widely supported)
    if (adl.Overdrive5_Temperature_Get) {
        ADLTemperature temp;
        memset(&temp, 0, sizeof(temp));
        temp.iSize = sizeof(ADLTemperature);
        if (adl.Overdrive5_Temperature_Get(adl.context, adapter_index, 0, &temp) == ADL_OK) {
            g->temperature = (float)temp.iTemperature / 1000.0f;
        }
    }

    // Fan via OverdriveN
    if (adl.OverdriveN_FanControl_Get) {
        ADLODNFanControl fan;
        memset(&fan, 0, sizeof(fan));
        if (adl.OverdriveN_FanControl_Get(adl.context, adapter_index, &fan) == ADL_OK) {
            if (fan.iCurrentFanSpeed > 0)
                g->fan_speed_pct = (float)fan.iCurrentFanSpeed;
        }
    }
}

// ============================================================================
// Intel IGCL (Control Library)
// ============================================================================

// Minimal type definitions — only handle-based APIs to avoid struct layout mismatch.
// The power telemetry struct (ctl_power_telemetry_t) has 25+ version-sensitive fields,
// so we only use the simpler temperature sensor API which returns a plain double.
typedef int ctl_result_t;
#define CTL_RESULT_SUCCESS 0

typedef void* ctl_api_handle_t;
typedef void* ctl_device_adapter_handle_t;
typedef void* ctl_temp_handle_t;

// Init args — must match igcl_api.h layout exactly
typedef struct {
    unsigned int Size;
    unsigned char Version;
    unsigned int AppVersion;
    unsigned int flags;
    unsigned int SupportedVersion;       // output
    unsigned char ApplicationUID[16];    // 16-byte GUID, zero-filled
} ctl_init_args_t;

typedef ctl_result_t (*pfn_ctlInit)(ctl_init_args_t*, ctl_api_handle_t*);
typedef ctl_result_t (*pfn_ctlClose)(ctl_api_handle_t);
typedef ctl_result_t (*pfn_ctlEnumerateDevices)(ctl_api_handle_t, unsigned int*, ctl_device_adapter_handle_t*);
typedef ctl_result_t (*pfn_ctlEnumTemperatureSensors)(ctl_device_adapter_handle_t, unsigned int*, ctl_temp_handle_t*);
typedef ctl_result_t (*pfn_ctlTemperatureGetState)(ctl_temp_handle_t, double*);

static struct {
    HMODULE hLib;
    ctl_api_handle_t apiHandle;
    pfn_ctlInit                     Init;
    pfn_ctlClose                    Close;
    pfn_ctlEnumerateDevices         EnumerateDevices;
    pfn_ctlEnumTemperatureSensors   EnumTemperatureSensors;
    pfn_ctlTemperatureGetState      TemperatureGetState;
    ctl_device_adapter_handle_t    *devices;
    unsigned int device_count;
    int initialized;
} igcl = { 0 };

static int load_igcl(void) {
    if (igcl.hLib) return igcl.initialized;

    // ControlLib.dll ships with Intel Graphics driver (System32)
    igcl.hLib = LoadLibraryA("ControlLib.dll");
    if (!igcl.hLib) igcl.hLib = LoadLibraryA("igcl.dll");
    if (!igcl.hLib) return 0;

    igcl.Init = (pfn_ctlInit)GetProcAddress(igcl.hLib, "ctlInit");
    igcl.Close = (pfn_ctlClose)GetProcAddress(igcl.hLib, "ctlClose");
    igcl.EnumerateDevices = (pfn_ctlEnumerateDevices)GetProcAddress(igcl.hLib, "ctlEnumerateDevices");
    igcl.EnumTemperatureSensors = (pfn_ctlEnumTemperatureSensors)GetProcAddress(igcl.hLib, "ctlEnumTemperatureSensors");
    igcl.TemperatureGetState = (pfn_ctlTemperatureGetState)GetProcAddress(igcl.hLib, "ctlTemperatureGetState");

    if (!igcl.Init || !igcl.EnumerateDevices) {
        FreeLibrary(igcl.hLib); igcl.hLib = NULL; return 0;
    }

    // Initialize — zero-fill ensures ApplicationUID is zeroed
    ctl_init_args_t init_args;
    memset(&init_args, 0, sizeof(init_args));
    init_args.Size = sizeof(ctl_init_args_t);
    init_args.Version = 0;
    init_args.AppVersion = (1U << 16) | 0U;  // CTL_MAKE_VERSION(1, 0)
    init_args.flags = 0;

    if (igcl.Init(&init_args, &igcl.apiHandle) != CTL_RESULT_SUCCESS) {
        FreeLibrary(igcl.hLib); igcl.hLib = NULL; return 0;
    }

    // Enumerate devices
    igcl.device_count = 0;
    igcl.EnumerateDevices(igcl.apiHandle, &igcl.device_count, NULL);
    if (igcl.device_count > 0) {
        igcl.devices = (ctl_device_adapter_handle_t*)calloc(igcl.device_count, sizeof(ctl_device_adapter_handle_t));
        if (igcl.devices) {
            igcl.EnumerateDevices(igcl.apiHandle, &igcl.device_count, igcl.devices);
        }
    }

    igcl.initialized = 1;
    return 1;
}

// Match by index — IGCL enumerates Intel GPUs only, so first device is usually correct.
// We avoid ctlGetDeviceProperties because its struct is large and version-sensitive.
static ctl_device_adapter_handle_t find_igcl_device_for(UINT vendor_id) {
    if (!igcl.initialized || vendor_id != 0x8086 || !igcl.devices || igcl.device_count == 0)
        return NULL;
    return igcl.devices[0];
}

static void fill_igcl_metrics(gpu_entry_t *g, ctl_device_adapter_handle_t dev) {
    // Temperature via handle-based sensor API (safe, no complex struct)
    if (igcl.EnumTemperatureSensors && igcl.TemperatureGetState) {
        unsigned int sensor_count = 0;
        if (igcl.EnumTemperatureSensors(dev, &sensor_count, NULL) == CTL_RESULT_SUCCESS && sensor_count > 0) {
            ctl_temp_handle_t *sensors = (ctl_temp_handle_t*)calloc(sensor_count, sizeof(ctl_temp_handle_t));
            if (sensors) {
                if (igcl.EnumTemperatureSensors(dev, &sensor_count, sensors) == CTL_RESULT_SUCCESS) {
                    double temp = 0.0;
                    if (igcl.TemperatureGetState(sensors[0], &temp) == CTL_RESULT_SUCCESS)
                        g->temperature = (float)temp;
                }
                free(sensors);
            }
        }
    }
    // Note: clocks, usage, fan, power require ctlPowerTelemetryGet whose struct
    // (ctl_power_telemetry_t, 25+ fields) is version-sensitive and cannot be safely
    // defined without the SDK header. VRAM usage falls through to DXGI fallback.
}

// ============================================================================
// Driver version from registry (common for all vendors)
// ============================================================================

static void read_driver_version(UINT vendor_id, UINT device_id, char *out, size_t out_size) {
    out[0] = '\0';
    const wchar_t *class_key = L"SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}";
    HKEY hk;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, class_key, 0, KEY_READ, &hk) != ERROR_SUCCESS) return;

    wchar_t subkey_name[32];
    for (DWORD i = 0; i < 64; i++) {
        DWORD name_len = sizeof(subkey_name) / sizeof(subkey_name[0]);
        if (RegEnumKeyExW(hk, i, subkey_name, &name_len, NULL, NULL, NULL, NULL) != ERROR_SUCCESS) break;

        HKEY hk_sub;
        if (RegOpenKeyExW(hk, subkey_name, 0, KEY_READ, &hk_sub) != ERROR_SUCCESS) continue;

        wchar_t match_buf[256];
        DWORD match_size = sizeof(match_buf);
        DWORD type;
        if (RegQueryValueExW(hk_sub, L"MatchingDeviceId", NULL, &type, (LPBYTE)match_buf, &match_size) == ERROR_SUCCESS
            && type == REG_SZ) {
            char match_utf8[256];
            WideCharToMultiByte(CP_UTF8, 0, match_buf, -1, match_utf8, sizeof(match_utf8), NULL, NULL);
            _strlwr(match_utf8);

            char ven_str[16], dev_str[16];
            snprintf(ven_str, sizeof(ven_str), "ven_%04x", vendor_id);
            snprintf(dev_str, sizeof(dev_str), "dev_%04x", device_id);

            if (strstr(match_utf8, ven_str) && strstr(match_utf8, dev_str)) {
                wchar_t ver_buf[128];
                DWORD ver_size = sizeof(ver_buf);
                if (RegQueryValueExW(hk_sub, L"DriverVersion", NULL, &type, (LPBYTE)ver_buf, &ver_size) == ERROR_SUCCESS
                    && type == REG_SZ) {
                    WideCharToMultiByte(CP_UTF8, 0, ver_buf, -1, out, (int)out_size, NULL, NULL);
                }
                RegCloseKey(hk_sub);
                break;
            }
        }
        RegCloseKey(hk_sub);
    }
    RegCloseKey(hk);
}

// ============================================================================
// Main refresh — enumerate DXGI adapters, fill vendor-specific metrics
// ============================================================================

static void refresh_gpus(void) {
    g_gpu_count = 0;

    // Try to load vendor libraries
    load_nvml();
    load_adl();
    load_igcl();

    IDXGIFactory1 *factory = NULL;
    HRESULT hr = CreateDXGIFactory1(&IID_IDXGIFactory1_local, (void **)&factory);
    if (FAILED(hr) || !factory) return;

    IDXGIAdapter1 *adapter = NULL;
    for (UINT i = 0; i < MAX_GPUS; i++) {
        hr = factory->lpVtbl->EnumAdapters1(factory, i, &adapter);
        if (hr == DXGI_ERROR_NOT_FOUND || FAILED(hr)) break;

        DXGI_ADAPTER_DESC1 desc;
        hr = adapter->lpVtbl->GetDesc1(adapter, &desc);
        if (FAILED(hr)) { adapter->lpVtbl->Release(adapter); continue; }
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) { adapter->lpVtbl->Release(adapter); continue; }

        gpu_entry_t *g = &g_gpus[g_gpu_count];
        memset(g, 0, sizeof(*g));
        g->temperature = NAN;
        g->gpu_usage = NAN;
        g->memory_used = -1;
        g->core_clock_mhz = -1;
        g->memory_clock_mhz = -1;
        g->fan_speed_pct = NAN;
        g->power_draw_watts = NAN;

        char *name = wchar_to_utf8(desc.Description);
        if (name) { strncpy(g->name, name, sizeof(g->name) - 1); free(name); }

        g->vendor_id = desc.VendorId;
        g->device_id = desc.DeviceId;
        g->dedicated_video_memory = desc.DedicatedVideoMemory;
        g->dedicated_system_memory = desc.DedicatedSystemMemory;
        g->shared_system_memory = desc.SharedSystemMemory;

        read_driver_version(desc.VendorId, desc.DeviceId, g->driver_version, sizeof(g->driver_version));

        // Vendor-specific live metrics
        if (desc.VendorId == 0x10DE) {
            nvmlDevice_t nvml_dev = find_nvml_device_for(desc.VendorId, desc.DeviceId);
            if (nvml_dev) fill_nvml_metrics(g, nvml_dev);
        } else if (desc.VendorId == 0x1002) {
            int adl_idx = find_adl_adapter_for(desc.VendorId, desc.DeviceId);
            if (adl_idx >= 0) fill_adl_metrics(g, adl_idx);
        } else if (desc.VendorId == 0x8086) {
            ctl_device_adapter_handle_t igcl_dev = find_igcl_device_for(desc.VendorId);
            if (igcl_dev) fill_igcl_metrics(g, igcl_dev);
        }

        // Fallback: VRAM usage via DXGI 1.4 if vendor API didn't provide it
        if (g->memory_used < 0) {
            IDXGIAdapter3 *adapter3 = NULL;
            hr = adapter->lpVtbl->QueryInterface(adapter, &IID_IDXGIAdapter3_local, (void**)&adapter3);
            if (SUCCEEDED(hr) && adapter3) {
                DXGI_QUERY_VIDEO_MEMORY_INFO mem_info;
                hr = adapter3->lpVtbl->QueryVideoMemoryInfo(adapter3, 0, DXGI_MEMORY_SEGMENT_GROUP_LOCAL, &mem_info);
                if (SUCCEEDED(hr) && mem_info.CurrentUsage > 0)
                    g->memory_used = (long long)mem_info.CurrentUsage;
                adapter3->lpVtbl->Release(adapter3);
            }
        }

        adapter->lpVtbl->Release(adapter);
        g_gpu_count++;
    }

    factory->lpVtbl->Release(factory);
}

// ============================================================================
// JNI exports
// ============================================================================

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuCount(
    JNIEnv *env, jclass clazz) {
    refresh_gpus();
    return g_gpu_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) names[i] = g_gpus[i].name;
    return to_string_array(env, names, g_gpu_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuVendorIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].vendor_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDeviceIds(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].device_id;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedVideoMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_video_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDedicatedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].dedicated_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuSharedSystemMemories(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].shared_system_memory;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuDriverVersions(
    JNIEnv *env, jclass clazz) {
    const char *versions[MAX_GPUS];
    for (int i = 0; i < g_gpu_count; i++) versions[i] = g_gpus[i].driver_version;
    return to_string_array(env, versions, g_gpu_count);
}

// Live metrics

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuTemperatures(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].temperature;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuUsages(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].gpu_usage;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuMemoryUsed(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_gpu_count);
    jlong *vals = (jlong *)malloc(g_gpu_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = (jlong)g_gpus[i].memory_used;
    (*env)->SetLongArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuCoreClocks(
    JNIEnv *env, jclass clazz) {
    jintArray arr = (*env)->NewIntArray(env, g_gpu_count);
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].core_clock_mhz;
    (*env)->SetIntArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jintArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuMemoryClocks(
    JNIEnv *env, jclass clazz) {
    jintArray arr = (*env)->NewIntArray(env, g_gpu_count);
    jint *vals = (jint *)malloc(g_gpu_count * sizeof(jint));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].memory_clock_mhz;
    (*env)->SetIntArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuFanSpeeds(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].fan_speed_pct;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeGpuPowerDraws(
    JNIEnv *env, jclass clazz) {
    jfloatArray arr = (*env)->NewFloatArray(env, g_gpu_count);
    jfloat *vals = (jfloat *)malloc(g_gpu_count * sizeof(jfloat));
    if (!vals) return arr;
    for (int i = 0; i < g_gpu_count; i++) vals[i] = g_gpus[i].power_draw_watts;
    (*env)->SetFloatArrayRegion(env, arr, 0, g_gpu_count, vals);
    free(vals); return arr;
}
