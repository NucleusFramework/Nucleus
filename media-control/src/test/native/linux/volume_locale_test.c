#include "../../../main/native/linux/nucleus_media_control_linux.c"
#include <locale.h>

static char *event_json;

static jstring JNICALL capture_string(JNIEnv *env, const char *value) {
    (void)env;
    g_free(event_json);
    event_json = g_strdup(value);
    return (jstring)event_json;
}

static void JNICALL receive_event(JNIEnv *env, jclass cls, jmethodID method, ...) {
    (void)env; (void)cls; (void)method;
}

static void JNICALL delete_ref(JNIEnv *env, jobject ref) {
    (void)env; (void)ref;
}

static jboolean JNICALL exception_check(JNIEnv *env) {
    (void)env;
    return JNI_FALSE;
}

static const struct JNINativeInterface_ jni = {
    .NewStringUTF = capture_string,
    .CallStaticVoidMethod = receive_event,
    .DeleteLocalRef = delete_ref,
    .ExceptionCheck = exception_check,
};
static JNIEnv test_env = &jni;

static jint JNICALL get_test_env(JavaVM *vm, void **env, jint version) {
    (void)vm; (void)version;
    *env = &test_env;
    return JNI_OK;
}

static const struct JNIInvokeInterface_ vm_functions = { .GetEnv = get_test_env };
static JavaVM test_vm = &vm_functions;

int main(int argc, char **argv) {
    const char *numeric_locale = argc > 1 ? argv[1] : "C";
    g_assert_nonnull(setlocale(LC_NUMERIC, numeric_locale));
    JNI_OnLoad(&test_vm, NULL);
    g_bridge_class = (jclass)&test_env;
    g_on_event_method = (jmethodID)&test_env;

    const double volumes[] = { 0.5, 0.15, 0.0, 1.0, 0.0000001 };
    const char *prefix = "{\"type\":\"set_volume\",\"volume\":";
    for (size_t i = 0; i < G_N_ELEMENTS(volumes); i++) {
        GVariant *value = g_variant_ref_sink(g_variant_new_double(volumes[i]));
        g_assert_true(player_set_property(NULL, NULL, NULL, NULL, "Volume", value, NULL, NULL));
        g_variant_unref(value);
        g_assert_true(g_str_has_prefix(event_json, prefix));
        char *end = NULL;
        double decoded = g_ascii_strtod(event_json + strlen(prefix), &end);
        g_assert_cmpstr(end, ==, "}");
        g_assert_cmpfloat(decoded, ==, volumes[i]);
        g_assert_cmpstr(setlocale(LC_NUMERIC, NULL), ==, numeric_locale);
    }
    g_free(event_json);
    g_print("Volume events passed in %s\n", numeric_locale);
    return 0;
}
