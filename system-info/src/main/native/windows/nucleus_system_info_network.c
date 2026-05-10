// Network interface information: names, stats, MAC, MTU.
// Sources: GetIfTable2, GetAdaptersAddresses

#include <winsock2.h>
#include <ws2tcpip.h>
#include "nucleus_system_info_common.h"
#include <iphlpapi.h>
#include <netioapi.h>

#define MAX_NETWORKS 128

typedef struct {
    char name[256];
    ULONG64 rx_bytes;
    ULONG64 tx_bytes;
    ULONG64 rx_packets;
    ULONG64 tx_packets;
    ULONG64 rx_errors;
    ULONG64 tx_errors;
    char mac_address[24];
    ULONG64 mtu;
} network_entry_t;

static network_entry_t g_nets[MAX_NETWORKS];
static int g_net_count = 0;

static void format_mac(const BYTE *addr, ULONG len, char *out, size_t out_size) {
    if (len < 6) {
        out[0] = '\0';
        return;
    }
    snprintf(out, out_size, "%02X:%02X:%02X:%02X:%02X:%02X",
             addr[0], addr[1], addr[2], addr[3], addr[4], addr[5]);
}

static void refresh_networks(void) {
    g_net_count = 0;

    // Use GetAdaptersAddresses for names and MACs
    ULONG buf_size = 0;
    GetAdaptersAddresses(AF_UNSPEC, GAA_FLAG_INCLUDE_PREFIX, NULL, NULL, &buf_size);
    if (buf_size == 0) return;

    IP_ADAPTER_ADDRESSES *addrs = (IP_ADAPTER_ADDRESSES *)malloc(buf_size);
    if (!addrs) return;
    if (GetAdaptersAddresses(AF_UNSPEC, GAA_FLAG_INCLUDE_PREFIX, NULL, addrs, &buf_size) != NO_ERROR) {
        free(addrs);
        return;
    }

    // Also get interface statistics via GetIfTable2
    MIB_IF_TABLE2 *if_table = NULL;
    GetIfTable2(&if_table);

    IP_ADAPTER_ADDRESSES *adapter = addrs;
    while (adapter && g_net_count < MAX_NETWORKS) {
        // Skip loopback and tunnel interfaces
        if (adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK ||
            adapter->IfType == IF_TYPE_TUNNEL) {
            adapter = adapter->Next;
            continue;
        }

        network_entry_t *n = &g_nets[g_net_count];
        memset(n, 0, sizeof(*n));

        // Name (friendly name)
        if (adapter->FriendlyName) {
            char *name = wchar_to_utf8(adapter->FriendlyName);
            if (name) {
                strncpy(n->name, name, sizeof(n->name) - 1);
                free(name);
            }
        }

        // MAC address
        format_mac(adapter->PhysicalAddress, adapter->PhysicalAddressLength, n->mac_address, sizeof(n->mac_address));

        // MTU
        n->mtu = adapter->Mtu;

        // Find matching interface in MIB_IF_TABLE2 for stats
        if (if_table) {
            for (ULONG i = 0; i < if_table->NumEntries; i++) {
                MIB_IF_ROW2 *row = &if_table->Table[i];
                if (row->InterfaceIndex == adapter->IfIndex) {
                    n->rx_bytes = row->InOctets;
                    n->tx_bytes = row->OutOctets;
                    n->rx_packets = row->InUcastPkts + row->InNUcastPkts;
                    n->tx_packets = row->OutUcastPkts + row->OutNUcastPkts;
                    n->rx_errors = row->InErrors;
                    n->tx_errors = row->OutErrors;
                    break;
                }
            }
        }

        g_net_count++;
        adapter = adapter->Next;
    }

    if (if_table) FreeMibTable(if_table);
    free(addrs);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkCount(
    JNIEnv *env, jclass clazz) {
    refresh_networks();
    return g_net_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_NETWORKS];
    for (int i = 0; i < g_net_count; i++) names[i] = g_nets[i].name;
    return to_string_array(env, names, g_net_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkReceivedBytes(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].rx_bytes;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkTransmittedBytes(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].tx_bytes;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkReceivedPackets(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].rx_packets;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkTransmittedPackets(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].tx_packets;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkErrorsReceived(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].rx_errors;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkErrorsTransmitted(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].tx_errors;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkMacAddresses(
    JNIEnv *env, jclass clazz) {
    const char *macs[MAX_NETWORKS];
    for (int i = 0; i < g_net_count; i++) macs[i] = g_nets[i].mac_address;
    return to_string_array(env, macs, g_net_count);
}

JNIEXPORT jlongArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeNetworkMtus(
    JNIEnv *env, jclass clazz) {
    jlongArray arr = (*env)->NewLongArray(env, g_net_count);
    jlong *vals = (jlong *)malloc(g_net_count * sizeof(jlong));
    if (!vals) return arr;
    for (int i = 0; i < g_net_count; i++) vals[i] = (jlong)g_nets[i].mtu;
    (*env)->SetLongArrayRegion(env, arr, 0, g_net_count, vals);
    free(vals);
    return arr;
}
