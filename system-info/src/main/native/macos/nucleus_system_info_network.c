// Network interface information.
// Sources: getifaddrs(), AF_LINK for MAC addresses and traffic stats

#include "nucleus_system_info_common.h"
#include <ifaddrs.h>
#include <net/if.h>
#include <net/if_dl.h>
#include <net/if_types.h>

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

// Find an existing network entry by name, or return -1
static int find_net_entry(const char *name) {
    for (int i = 0; i < g_net_count; i++) {
        if (strcmp(g_nets[i].name, name) == 0) return i;
    }
    return -1;
}

static void refresh_networks(void) {
    g_net_count = 0;
    struct ifaddrs *addrs = NULL;
    if (getifaddrs(&addrs) != 0) return;

    for (struct ifaddrs *ifa = addrs; ifa != NULL && g_net_count < MAX_NETWORKS; ifa = ifa->ifa_next) {
        if (!ifa->ifa_addr) continue;

        // Only process link-layer addresses (AF_LINK) — they have traffic stats and MAC
        if (ifa->ifa_addr->sa_family != AF_LINK) continue;

        // Skip loopback
        if (ifa->ifa_flags & IFF_LOOPBACK) continue;

        int idx = find_net_entry(ifa->ifa_name);
        net_entry_t *n;
        if (idx >= 0) {
            n = &g_nets[idx];
        } else {
            n = &g_nets[g_net_count];
            memset(n, 0, sizeof(net_entry_t));
            strncpy(n->name, ifa->ifa_name, sizeof(n->name) - 1);
            g_net_count++;
        }

        struct sockaddr_dl *sdl = (struct sockaddr_dl *)ifa->ifa_addr;

        // Extract MAC address (6 bytes at sdl_data + sdl_nlen)
        if (sdl->sdl_alen == 6) {
            unsigned char *mac = (unsigned char *)(sdl->sdl_data + sdl->sdl_nlen);
            snprintf(n->mac, sizeof(n->mac), "%02x:%02x:%02x:%02x:%02x:%02x",
                     mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        }

        // Extract traffic stats from ifa_data (struct if_data)
        if (ifa->ifa_data) {
            struct if_data *ifd = (struct if_data *)ifa->ifa_data;
            n->rx_bytes   = ifd->ifi_ibytes;
            n->tx_bytes   = ifd->ifi_obytes;
            n->rx_packets = ifd->ifi_ipackets;
            n->tx_packets = ifd->ifi_opackets;
            n->rx_errors  = ifd->ifi_ierrors;
            n->tx_errors  = ifd->ifi_oerrors;
            n->mtu        = ifd->ifi_mtu;
        }
    }

    freeifaddrs(addrs);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeNetworkCount(
    JNIEnv *env, jclass clazz) {
    refresh_networks();
    return (jint)g_net_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeNetworkNames(
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
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_##jni_name( \
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
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeNetworkMacAddresses(
    JNIEnv *env, jclass clazz) {
    if (g_net_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_net_count * sizeof(char *));
    for (int i = 0; i < g_net_count; i++) arr[i] = g_nets[i].mac;
    jobjectArray result = to_string_array(env, arr, g_net_count);
    free(arr);
    return result;
}
