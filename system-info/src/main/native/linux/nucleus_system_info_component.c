// Temperature sensor (component) information from hwmon.
// Sources: /sys/class/hwmon/

#include "nucleus_system_info_common.h"
#include <dirent.h>

#define MAX_COMPONENTS 256

typedef struct {
    char label[128];
    float temperature;  // NaN if unavailable
    float max;          // NaN if unavailable
    float critical;     // NaN if unavailable
} component_entry_t;

static int g_component_count = 0;
static component_entry_t g_components[MAX_COMPONENTS];

static float read_temp_file(const char *path) {
    char buf[32];
    if (read_file_line(path, buf, sizeof(buf)) <= 0) return __builtin_nanf("");
    // hwmon temperatures are in millidegrees Celsius
    return (float)atoi(buf) / 1000.0f;
}

static void refresh_components(void) {
    g_component_count = 0;
    DIR *hwmon_dir = opendir("/sys/class/hwmon");
    if (!hwmon_dir) return;
    struct dirent *hwmon_ent;
    while ((hwmon_ent = readdir(hwmon_dir)) && g_component_count < MAX_COMPONENTS) {
        if (hwmon_ent->d_name[0] == '.') continue;
        char hwmon_path[512];
        snprintf(hwmon_path, sizeof(hwmon_path), "/sys/class/hwmon/%s", hwmon_ent->d_name);

        // Read the hwmon device name
        char device_name[128] = "";
        char name_path[300];
        snprintf(name_path, sizeof(name_path), "%s/name", hwmon_path);
        read_file_line(name_path, device_name, sizeof(device_name));

        // Scan for temp*_input files
        DIR *sensor_dir = opendir(hwmon_path);
        if (!sensor_dir) continue;
        struct dirent *sensor_ent;
        while ((sensor_ent = readdir(sensor_dir)) && g_component_count < MAX_COMPONENTS) {
            if (strncmp(sensor_ent->d_name, "temp", 4) != 0) continue;
            if (!strstr(sensor_ent->d_name, "_input")) continue;

            char input_path[512];
            snprintf(input_path, sizeof(input_path), "%s/%s", hwmon_path, sensor_ent->d_name);

            component_entry_t *c = &g_components[g_component_count];
            c->temperature = read_temp_file(input_path);

            // Extract the sensor index (e.g., "temp1_input" -> "1")
            char idx_str[8];
            strncpy(idx_str, sensor_ent->d_name + 4, sizeof(idx_str) - 1);
            char *underscore = strchr(idx_str, '_');
            if (underscore) *underscore = '\0';

            // Try to read the label
            char label_path[512];
            snprintf(label_path, sizeof(label_path), "%s/temp%s_label", hwmon_path, idx_str);
            char label_buf[128] = "";
            if (read_file_line(label_path, label_buf, sizeof(label_buf)) <= 0) {
                // Fallback: use device name + sensor index
                snprintf(label_buf, sizeof(label_buf), "%s temp%s", device_name, idx_str);
            }
            strncpy(c->label, label_buf, sizeof(c->label) - 1);

            // Max temperature
            char max_path[512];
            snprintf(max_path, sizeof(max_path), "%s/temp%s_max", hwmon_path, idx_str);
            c->max = read_temp_file(max_path);

            // Critical temperature
            char crit_path[512];
            snprintf(crit_path, sizeof(crit_path), "%s/temp%s_crit", hwmon_path, idx_str);
            c->critical = read_temp_file(crit_path);

            g_component_count++;
        }
        closedir(sensor_dir);
    }
    closedir(hwmon_dir);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeComponentCount(
    JNIEnv *env, jclass clazz) {
    refresh_components();
    return (jint)g_component_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeComponentLabels(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_component_count * sizeof(char *));
    for (int i = 0; i < g_component_count; i++) arr[i] = g_components[i].label;
    jobjectArray result = to_string_array(env, arr, g_component_count);
    free(arr);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeComponentTemperatures(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeComponentMaxTemperatures(
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
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeComponentCriticalTemperatures(
    JNIEnv *env, jclass clazz) {
    if (g_component_count <= 0) return NULL;
    jfloat *vals = (jfloat *)malloc(g_component_count * sizeof(jfloat));
    for (int i = 0; i < g_component_count; i++) vals[i] = g_components[i].critical;
    jfloatArray result = (*env)->NewFloatArray(env, g_component_count);
    (*env)->SetFloatArrayRegion(env, result, 0, g_component_count, vals);
    free(vals);
    return result;
}
