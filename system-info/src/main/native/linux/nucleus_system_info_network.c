// Network interface information.
// Sources: /proc/net/dev, /sys/class/net/

#include "nucleus_system_info_common.h"
#include <dirent.h>

#define MAX_NETWORKS 128

typedef struct {
    char name[64];
    long long rx_bytes;
    long long tx_bytes;
    long long rx_packets;
    long long tx_packets;
    long long rx_errors;
    long long tx_errors;
    char mac[20];
    long long mtu;
} net_entry_t;

static int g_net_count = 0;
static net_entry_t g_nets[MAX_NETWORKS];

static void refresh_networks(void) {
    g_net_count = 0;

    // Parse /proc/net/dev
    FILE *f = fopen("/proc/net/dev", "r");
    if (!f) return;
    char line[512];
    // Skip header lines
    if (!fgets(line, sizeof(line), f)) { fclose(f); return; }
    if (!fgets(line, sizeof(line), f)) { fclose(f); return; }

    while (fgets(line, sizeof(line), f) && g_net_count < MAX_NETWORKS) {
        net_entry_t *n = &g_nets[g_net_count];
        memset(n, 0, sizeof(net_entry_t));

        // Parse interface name
        char *colon = strchr(line, ':');
        if (!colon) continue;
        *colon = '\0';
        char *name_start = line;
        while (*name_start == ' ') name_start++;
        strncpy(n->name, name_start, sizeof(n->name) - 1);

        // Parse stats: rx_bytes rx_packets rx_errs rx_drop rx_fifo rx_frame rx_compressed rx_multicast
        //              tx_bytes tx_packets tx_errs tx_drop tx_fifo tx_colls tx_carrier tx_compressed
        long long rx_bytes, rx_packets, rx_errs, rx_drop, rx_fifo, rx_frame, rx_comp, rx_multi;
        long long tx_bytes, tx_packets, tx_errs, tx_drop, tx_fifo, tx_colls, tx_carrier, tx_comp;
        int parsed = sscanf(colon + 1,
            " %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld %lld",
            &rx_bytes, &rx_packets, &rx_errs, &rx_drop, &rx_fifo, &rx_frame, &rx_comp, &rx_multi,
            &tx_bytes, &tx_packets, &tx_errs, &tx_drop, &tx_fifo, &tx_colls, &tx_carrier, &tx_comp);
        if (parsed < 16) continue;

        n->rx_bytes = rx_bytes;
        n->tx_bytes = tx_bytes;
        n->rx_packets = rx_packets;
        n->tx_packets = tx_packets;
        n->rx_errors = rx_errs;
        n->tx_errors = tx_errs;

        // Read MAC address from /sys/class/net/<name>/address
        char mac_path[256];
        snprintf(mac_path, sizeof(mac_path), "/sys/class/net/%s/address", n->name);
        read_file_line(mac_path, n->mac, sizeof(n->mac));

        // Read MTU from /sys/class/net/<name>/mtu
        char mtu_path[256];
        snprintf(mtu_path, sizeof(mtu_path), "/sys/class/net/%s/mtu", n->name);
        char mtu_buf[32];
        if (read_file_line(mtu_path, mtu_buf, sizeof(mtu_buf)) > 0) {
            n->mtu = atoll(mtu_buf);
        }

        g_net_count++;
    }
    fclose(f);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeNetworkCount(
    JNIEnv *env, jclass clazz) {
    refresh_networks();
    return (jint)g_net_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeNetworkNames(
    JNIEnv *env, jclass clazz) {
    if (g_net_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_net_count * sizeof(char *));
    for (int i = 0; i < g_net_count; i++) arr[i] = g_nets[i].name;
    jobjectArray result = to_string_array(env, arr, g_net_count);
    free(arr);
    return result;
}

#define LONG_ARRAY_GETTER(jni_name, field) \
JNIEXPORT jlongArray JNICALL \
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_##jni_name( \
    JNIEnv *env, jclass clazz) { \
    if (g_net_count <= 0) return NULL; \
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong)); \
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].field; \
    jlongArray result = (*env)->NewLongArray(env, g_net_count); \
    (*env)->SetLongArrayRegion(env, result, 0, g_net_count, vals); \
    free(vals); \
    return result; \
}

LONG_ARRAY_GETTER(nativeNetworkReceivedBytes, rx_bytes)
LONG_ARRAY_GETTER(nativeNetworkTransmittedBytes, tx_bytes)
LONG_ARRAY_GETTER(nativeNetworkReceivedPackets, rx_packets)
LONG_ARRAY_GETTER(nativeNetworkTransmittedPackets, tx_packets)
LONG_ARRAY_GETTER(nativeNetworkErrorsReceived, rx_errors)
LONG_ARRAY_GETTER(nativeNetworkErrorsTransmitted, tx_errors)
LONG_ARRAY_GETTER(nativeNetworkMtus, mtu)

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeNetworkMacAddresses(
    JNIEnv *env, jclass clazz) {
    if (g_net_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_net_count * sizeof(char *));
    for (int i = 0; i < g_net_count; i++) arr[i] = g_nets[i].mac;
    jobjectArray result = to_string_array(env, arr, g_net_count);
    free(arr);
    return result;
}
