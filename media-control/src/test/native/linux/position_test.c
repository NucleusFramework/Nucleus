#include <gio/gio.h>
#include <jni.h>

static gint64 now_us;
static int seeked_count;
static gint64 last_seeked_us;
static int status_changed_count;

static gint64 test_monotonic_time(void) {
    return now_us;
}

static gboolean capture_signal(
    GDBusConnection *connection, const gchar *destination, const gchar *path,
    const gchar *interface, const gchar *signal, GVariant *parameters, GError **error)
{
    (void)connection; (void)destination; (void)path; (void)interface; (void)error;
    g_variant_ref_sink(parameters);
    if (g_str_equal(signal, "Seeked")) {
        seeked_count++;
        g_variant_get(parameters, "(x)", &last_seeked_us);
    } else {
        GVariant *changed = g_variant_get_child_value(parameters, 1);
        GVariant *position = g_variant_lookup_value(changed, "Position", NULL);
        g_assert_null(position);
        GVariant *status = g_variant_lookup_value(changed, "PlaybackStatus", NULL);
        if (status) {
            status_changed_count++;
            g_variant_unref(status);
        }
        g_variant_unref(changed);
    }
    g_variant_unref(parameters);
    return TRUE;
}

static void ignore_method_reply(GDBusMethodInvocation *invocation, GVariant *parameters) {
    (void)invocation; (void)parameters;
}

#define g_get_monotonic_time test_monotonic_time
#define g_dbus_connection_emit_signal capture_signal
#define g_dbus_method_invocation_return_value ignore_method_reply
#include "../../../main/native/linux/nucleus_media_control_linux.c"
#undef g_get_monotonic_time
#undef g_dbus_connection_emit_signal
#undef g_dbus_method_invocation_return_value

static jint unavailable_env(JavaVM *vm, void **env, jint version) {
    (void)vm; (void)version;
    *env = NULL;
    return JNI_ERR;
}

static void drain_signals(void) {
    while (g_main_context_iteration(g_ctx, FALSE)) { }
}

static void publish(gint status, jlong position_ms) {
    Java_dev_nucleusframework_media_control_linux_NativeLinuxBridge_nativeSetPlaybackState(
        NULL, NULL, status, position_ms);
    drain_signals();
}

static gint64 read_position(void) {
    GVariant *value = g_variant_ref_sink(player_get_property(
        NULL, NULL, NULL, NULL, "Position", NULL, NULL));
    gint64 position = g_variant_get_int64(value);
    g_variant_unref(value);
    return position;
}

static void request_seek(const char *method, GVariant *parameters) {
    g_variant_ref_sink(parameters);
    player_handle_method(NULL, NULL, NULL, NULL, method, parameters, NULL, NULL);
    g_variant_unref(parameters);
    drain_signals();
}

static void reset(void) {
    now_us = 1000000;
    g_position_us = 0;
    g_status = STATUS_STOPPED;
    g_duration_us = -1;
    seeked_count = 0;
    last_seeked_us = -1;
    status_changed_count = 0;
}

static void position_advances_without_publications(void) {
    reset();
    publish(STATUS_PLAYING, 137);
    seeked_count = 0;
    now_us += 250000;
    g_assert_cmpint(read_position(), ==, 387000);
    now_us += 10000000;
    g_assert_cmpint(read_position(), ==, 10387000);
    drain_signals();
    g_assert_cmpint(seeked_count, ==, 0);
}

static void periodic_publications_do_not_seek(void) {
    reset();
    publish(STATUS_PLAYING, 0);
    for (int i = 1; i <= 40; i++) {
        now_us += 250000;
        publish(STATUS_PLAYING, i * 250 + i % 2);
    }
    g_assert_cmpint(read_position(), ==, 10000000);
    g_assert_cmpint(seeked_count, ==, 0);
    g_assert_cmpint(status_changed_count, ==, 1);
}

static void transport_changes_preserve_position(void) {
    reset();
    publish(STATUS_PLAYING, 0);
    now_us += 375000;
    publish(STATUS_PAUSED, -1);
    now_us += 10000000;
    g_assert_cmpint(read_position(), ==, 375000);
    publish(STATUS_PLAYING, -1);
    now_us += 125000;
    g_assert_cmpint(read_position(), ==, 500000);
    publish(STATUS_PAUSED, 500);
    now_us += 250000;
    publish(STATUS_PLAYING, 500);
    now_us += 250000;
    publish(STATUS_STOPPED, -1);
    now_us += 10000000;
    g_assert_cmpint(read_position(), ==, 750000);
    g_assert_cmpint(seeked_count, ==, 0);
    g_assert_cmpint(status_changed_count, ==, 6);
}

static void discontinuities_emit_once(void) {
    reset();
    publish(STATUS_PLAYING, 0);
    now_us += 250000;
    publish(STATUS_PLAYING, 350);
    g_assert_cmpint(seeked_count, ==, 0);
    now_us += 250000;
    publish(STATUS_PLAYING, 701);
    g_assert_cmpint(seeked_count, ==, 1);
    g_assert_cmpint(last_seeked_us, ==, 701000);
    now_us += 250000;
    publish(STATUS_PLAYING, 951);
    g_assert_cmpint(seeked_count, ==, 1);
    publish(STATUS_PLAYING, 10);
    g_assert_cmpint(seeked_count, ==, 2);
    g_assert_cmpint(last_seeked_us, ==, 10000);
    now_us += 50000;
    g_assert_cmpint(read_position(), ==, 60000);
    publish(STATUS_PAUSED, 60);
    publish(STATUS_PAUSED, 1000);
    g_assert_cmpint(seeked_count, ==, 3);
    g_assert_cmpint(last_seeked_us, ==, 1000000);
}

static void explicit_seeks_below_threshold_emit_once(void) {
    reset();
    publish(STATUS_PLAYING, 0);
    now_us += 250000;
    request_seek("Seek", g_variant_new("(x)", (gint64)10000));
    g_assert_cmpint(seeked_count, ==, 0);
    publish(STATUS_PLAYING, -1);
    publish(STATUS_PLAYING, 260);
    g_assert_cmpint(seeked_count, ==, 1);
    g_assert_cmpint(last_seeked_us, ==, 260000);
    publish(STATUS_PLAYING, 260);
    g_assert_cmpint(seeked_count, ==, 1);

    request_seek("SetPosition", g_variant_new("(ox)", "/track/1", (gint64)270000));
    publish(STATUS_PLAYING, 270);
    g_assert_cmpint(seeked_count, ==, 2);
    g_assert_cmpint(last_seeked_us, ==, 270000);
    g_duration_us = 1000000;
    request_seek("SetPosition", g_variant_new("(ox)", "/track/1", (gint64)-1));
    request_seek("SetPosition", g_variant_new("(ox)", "/track/1", (gint64)1000001));
    publish(STATUS_PLAYING, 270);
    g_assert_cmpint(seeked_count, ==, 2);
}

int main(int argc, char **argv) {
    g_test_init(&argc, &argv, NULL);
    static const struct JNIInvokeInterface_ vm_functions = { .GetEnv = unavailable_env };
    static JavaVM vm = &vm_functions;
    g_jvm = &vm;
    g_ctx = g_main_context_new();
    g_conn = (GDBusConnection *)1;
    g_test_add_func("/mpris/position/advances-without-publications", position_advances_without_publications);
    g_test_add_func("/mpris/position/periodic-publications", periodic_publications_do_not_seek);
    g_test_add_func("/mpris/position/transport", transport_changes_preserve_position);
    g_test_add_func("/mpris/position/discontinuities", discontinuities_emit_once);
    g_test_add_func("/mpris/position/explicit-seeks", explicit_seeks_below_threshold_emit_once);
    int result = g_test_run();
    g_conn = NULL;
    g_main_context_unref(g_ctx);
    g_ctx = NULL;
    return result;
}
