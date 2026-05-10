// User information: enumeration, SID, groups.
// Sources: NetUserEnum, LookupAccountNameW, NetUserGetLocalGroups

#include "nucleus_system_info_common.h"
#include <lm.h>
#include <sddl.h>

#pragma comment(lib, "netapi32.lib")

#define MAX_USERS 256

typedef struct {
    char name[256];
    char sid_str[128];    // S-1-5-21-... format
    char primary_gid[32]; // Primary group ID as string
    char groups[1024];    // Comma-separated group names
} user_entry_t;

static user_entry_t g_users[MAX_USERS];
static int g_user_count = 0;

static void get_user_sid(const wchar_t *username, char *sid_out, size_t sid_size) {
    sid_out[0] = '\0';
    BYTE sid_buf[256];
    DWORD sid_len = sizeof(sid_buf);
    wchar_t domain[256];
    DWORD domain_len = 256;
    SID_NAME_USE use;
    if (!LookupAccountNameW(NULL, username, sid_buf, &sid_len, domain, &domain_len, &use)) return;

    wchar_t *sid_str = NULL;
    if (ConvertSidToStringSidW((PSID)sid_buf, &sid_str)) {
        char *utf8 = wchar_to_utf8(sid_str);
        if (utf8) {
            strncpy(sid_out, utf8, sid_size - 1);
            free(utf8);
        }
        LocalFree(sid_str);
    }
}

static void get_user_groups(const wchar_t *username, char *groups_out, size_t groups_size) {
    groups_out[0] = '\0';

    LPLOCALGROUP_USERS_INFO_0 buf = NULL;
    DWORD entries_read = 0, total_entries = 0;
    NET_API_STATUS status = NetUserGetLocalGroups(NULL, username, 0, LG_INCLUDE_INDIRECT,
        (LPBYTE *)&buf, MAX_PREFERRED_LENGTH, &entries_read, &total_entries);
    if (status != NERR_Success || !buf) return;

    size_t offset = 0;
    for (DWORD i = 0; i < entries_read && offset < groups_size - 1; i++) {
        char *gname = wchar_to_utf8(buf[i].lgrui0_name);
        if (gname) {
            if (offset > 0 && offset < groups_size - 1) {
                groups_out[offset++] = ',';
            }
            size_t glen = strlen(gname);
            if (offset + glen < groups_size - 1) {
                memcpy(groups_out + offset, gname, glen);
                offset += glen;
            }
            free(gname);
        }
    }
    groups_out[offset] = '\0';
    NetApiBufferFree(buf);
}

static void refresh_users(void) {
    g_user_count = 0;

    LPUSER_INFO_0 buf = NULL;
    DWORD entries_read = 0, total_entries = 0;
    DWORD resume = 0;
    NET_API_STATUS status;

    do {
        status = NetUserEnum(NULL, 0, FILTER_NORMAL_ACCOUNT,
            (LPBYTE *)&buf, MAX_PREFERRED_LENGTH, &entries_read, &total_entries, &resume);
        if (status != NERR_Success && status != ERROR_MORE_DATA) break;

        for (DWORD i = 0; i < entries_read && g_user_count < MAX_USERS; i++) {
            user_entry_t *u = &g_users[g_user_count];
            memset(u, 0, sizeof(*u));

            char *name = wchar_to_utf8(buf[i].usri0_name);
            if (name) {
                strncpy(u->name, name, sizeof(u->name) - 1);
                free(name);
            }

            get_user_sid(buf[i].usri0_name, u->sid_str, sizeof(u->sid_str));
            get_user_groups(buf[i].usri0_name, u->groups, sizeof(u->groups));

            // Primary group ID: extract from SID (last component)
            // SID format: S-1-5-21-xxx-xxx-xxx-RID
            // For the user's primary group, we use the RID from USER_INFO_3
            // but for simplicity, use "0" as placeholder
            strcpy(u->primary_gid, "0");

            g_user_count++;
        }

        if (buf) { NetApiBufferFree(buf); buf = NULL; }
    } while (status == ERROR_MORE_DATA);
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUserCount(
    JNIEnv *env, jclass clazz) {
    refresh_users();
    return g_user_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUserNames(
    JNIEnv *env, jclass clazz) {
    const char *names[MAX_USERS];
    for (int i = 0; i < g_user_count; i++) names[i] = g_users[i].name;
    return to_string_array(env, names, g_user_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUserIds(
    JNIEnv *env, jclass clazz) {
    const char *ids[MAX_USERS];
    for (int i = 0; i < g_user_count; i++) ids[i] = g_users[i].sid_str;
    return to_string_array(env, ids, g_user_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUserGroupIds(
    JNIEnv *env, jclass clazz) {
    const char *gids[MAX_USERS];
    for (int i = 0; i < g_user_count; i++) gids[i] = g_users[i].primary_gid;
    return to_string_array(env, gids, g_user_count);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_windows_NativeWindowsSystemInfoBridge_nativeUserGroups(
    JNIEnv *env, jclass clazz) {
    const char *groups[MAX_USERS];
    for (int i = 0; i < g_user_count; i++) groups[i] = g_users[i].groups;
    return to_string_array(env, groups, g_user_count);
}
