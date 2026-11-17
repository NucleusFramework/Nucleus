/*
 * test_sni_concurrency.c – regression test for issue #405.
 *
 * Reproduces the threading race in sni.c: the event loop thread runs
 * sd_bus_process() while a second thread concurrently invokes the public
 * sni_tray mutators (set_icon, set_tooltip, item_check, ...), each of which
 * emits D-Bus signals.
 *
 * Without synchronization, concurrent access to the same sd_bus object trips
 *   Assertion '!bus->current_slot' failed at ... bus_process_internal()
 * and aborts the process (SIGABRT, exit 134).
 *
 * Expected:
 *   - BEFORE the fix: aborts (or otherwise crashes) under contention.
 *   - AFTER the fix:  runs all rounds and exits 0.
 *
 * Build & run via run_concurrency_test.sh (uses a private dbus session).
 */

#include "sni.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>

/* Minimal valid 1x1 PNG so build_pixmaps() actually decodes and rebuilds the
 * icon pixmap list on every update (matches the production "frequent icon
 * update" scenario from the bug report). */
static const unsigned char PNG_1x1[] = {
    0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
    0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x06,0x00,0x00,0x00,0x1F,0x15,0xC4,
    0x89,0x00,0x00,0x00,0x0D,0x49,0x44,0x41,0x54,0x78,0x9C,0x62,0xF8,0xCF,0xC0,0x00,
    0x00,0x00,0x03,0x00,0x01,0x73,0xF8,0x6C,0xC4,0x00,0x00,0x00,0x00,0x49,0x45,0x4E,
    0x44,0xAE,0x42,0x60,0x82
};

static volatile int g_stop = 0;

static void *loop_thread_fn(void *arg) {
    sni_tray *tray = (sni_tray *)arg;
    sni_tray_run(tray); /* blocks until sni_tray_quit() */
    return NULL;
}

int main(void) {
    sni_tray *tray = sni_tray_create(PNG_1x1, sizeof(PNG_1x1), "initial");
    if (!tray) {
        fprintf(stderr, "test: sni_tray_create failed\n");
        return 2;
    }

    /* Seed a few menu items to toggle. */
    uint32_t a = sni_tray_add_menu_item_checkbox(tray, "Item A", NULL, 0);
    uint32_t b = sni_tray_add_menu_item(tray, "Item B", NULL);

    pthread_t loop;
    if (pthread_create(&loop, NULL, loop_thread_fn, tray) != 0) {
        fprintf(stderr, "test: pthread_create failed\n");
        return 2;
    }

    /* Give the loop time to open the bus and enter sd_bus_process(). */
    struct timespec warmup = {.tv_sec = 0, .tv_nsec = 200 * 1000 * 1000};
    nanosleep(&warmup, NULL);

    /* Hammer the bus from this (foreign) thread, exactly as the JVM thread
     * does in production. High iteration count widens the race window. */
    const int ROUNDS = 200000;
    for (int i = 0; i < ROUNDS && !g_stop; i++) {
        sni_tray_set_icon(tray, PNG_1x1, sizeof(PNG_1x1));
        sni_tray_set_tooltip(tray, (i & 1) ? "tip-odd" : "tip-even");
        sni_tray_set_title(tray, "title");
        if (i & 1) sni_tray_item_check(tray, a);
        else       sni_tray_item_uncheck(tray, a);
        sni_tray_item_set_title(tray, b, "Item B'");
    }

    sni_tray_quit(tray);
    pthread_join(loop, NULL);
    sni_tray_destroy(tray);

    printf("test: completed %d rounds without crash\n", ROUNDS);
    return 0;
}
