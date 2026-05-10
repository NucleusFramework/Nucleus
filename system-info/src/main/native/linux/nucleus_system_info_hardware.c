// Hardware information: motherboard and product details.
// Sources: /sys/class/dmi/id/

#include "nucleus_system_info_common.h"

static jstring read_dmi_field(JNIEnv *env, const char *field) {
    char path[256];
    snprintf(path, sizeof(path), "/sys/class/dmi/id/%s", field);
    char buf[256];
    if (read_file_line(path, buf, sizeof(buf)) <= 0) return NULL;
    // DMI fields sometimes return "Not Specified" or similar placeholders
    if (strcmp(buf, "Not Specified") == 0 || strcmp(buf, "Default string") == 0 ||
        strcmp(buf, "To Be Filled By O.E.M.") == 0 || strcmp(buf, "To be filled by O.E.M.") == 0) {
        return NULL;
    }
    return to_jstring(env, buf);
}

// Motherboard
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeMotherboardName(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "board_name");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeMotherboardVendor(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "board_vendor");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeMotherboardVersion(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "board_version");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeMotherboardSerial(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "board_serial");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeMotherboardAssetTag(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "board_asset_tag");
}

// Product
JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductName(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_name");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductFamily(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_family");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductSerial(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_serial");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductSku(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_sku");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductUuid(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_uuid");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductVersion(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "product_version");
}

JNIEXPORT jstring JNICALL
Java_dev_nucleusframework_nucleus_systeminfo_linux_NativeLinuxSystemInfoBridge_nativeProductVendor(
    JNIEnv *env, jclass clazz) {
    return read_dmi_field(env, "sys_vendor");
}
