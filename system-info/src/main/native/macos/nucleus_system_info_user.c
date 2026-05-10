// User information.
// Sources: getpwent(), getgrent(), getgrouplist()

#include "nucleus_system_info_common.h"
#include <pwd.h>
#include <grp.h>

#define MAX_USERS 1024

typedef struct {
    char name[128];
    char uid[32];
    char gid[32];
    char groups[1024]; // Comma-separated group names
} user_entry_t;

static int g_user_count = 0;
static user_entry_t g_users[MAX_USERS];

static void get_user_groups(const char *username, gid_t primary_gid, char *buf, size_t bufsize) {
    buf[0] = '\0';
    int ngroups = 64;
    gid_t *groups = (gid_t *)malloc(ngroups * sizeof(gid_t));
    if (!groups) return;
    if (getgrouplist(username, (int)primary_gid, (int *)groups, &ngroups) == -1) {
        gid_t *tmp = (gid_t *)realloc(groups, ngroups * sizeof(gid_t));
        if (!tmp) { free(groups); return; }
        groups = tmp;
        getgrouplist(username, (int)primary_gid, (int *)groups, &ngroups);
    }
    size_t offset = 0;
    for (int i = 0; i < ngroups && offset < bufsize - 1; i++) {
        struct group *gr = getgrgid(groups[i]);
        const char *gname = gr ? gr->gr_name : "";
        if (i > 0 && offset < bufsize - 1) buf[offset++] = ',';
        size_t len = strlen(gname);
        if (offset + len >= bufsize) break;
        memcpy(buf + offset, gname, len);
        offset += len;
    }
    buf[offset] = '\0';
    free(groups);
}

static void refresh_users(void) {
    g_user_count = 0;
    setpwent();
    struct passwd *pw;
    while ((pw = getpwent()) && g_user_count < MAX_USERS) {
        // Skip system users (UID < 500 on macOS) except root
        if (pw->pw_uid != 0 && pw->pw_uid < 500) continue;
        // Skip nologin/false shell users
        if (pw->pw_shell && (strstr(pw->pw_shell, "nologin") || strstr(pw->pw_shell, "/false"))) continue;

        user_entry_t *u = &g_users[g_user_count];
        strncpy(u->name, pw->pw_name, sizeof(u->name) - 1);
        snprintf(u->uid, sizeof(u->uid), "%u", pw->pw_uid);
        snprintf(u->gid, sizeof(u->gid), "%u", pw->pw_gid);
        get_user_groups(pw->pw_name, pw->pw_gid, u->groups, sizeof(u->groups));
        g_user_count++;
    }
    endpwent();
}

JNIEXPORT jint JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUserCount(
    JNIEnv *env, jclass clazz) {
    refresh_users();
    return (jint)g_user_count;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUserNames(
    JNIEnv *env, jclass clazz) {
    if (g_user_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_user_count * sizeof(char *));
    for (int i = 0; i < g_user_count; i++) arr[i] = g_users[i].name;
    jobjectArray result = to_string_array(env, arr, g_user_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUserIds(
    JNIEnv *env, jclass clazz) {
    if (g_user_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_user_count * sizeof(char *));
    for (int i = 0; i < g_user_count; i++) arr[i] = g_users[i].uid;
    jobjectArray result = to_string_array(env, arr, g_user_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUserGroupIds(
    JNIEnv *env, jclass clazz) {
    if (g_user_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_user_count * sizeof(char *));
    for (int i = 0; i < g_user_count; i++) arr[i] = g_users[i].gid;
    jobjectArray result = to_string_array(env, arr, g_user_count);
    free(arr);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_macos_NativeMacOsSystemInfoBridge_nativeUserGroups(
    JNIEnv *env, jclass clazz) {
    if (g_user_count <= 0) return NULL;
    const char **arr = (const char **)malloc(g_user_count * sizeof(char *));
    for (int i = 0; i < g_user_count; i++) arr[i] = g_users[i].groups;
    jobjectArray result = to_string_array(env, arr, g_user_count);
    free(arr);
    return result;
}
