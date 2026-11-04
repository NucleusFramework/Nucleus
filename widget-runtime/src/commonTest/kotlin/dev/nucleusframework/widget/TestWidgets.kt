package dev.nucleusframework.widget

// NucleusWidgets.start only honours its first call per process: every test class declares the
// same widgets through this one entry point.
internal fun startTestWidgets() {
    NucleusWidgets.start {
        widget("Counter") { CounterWidget() }
        widget("Settings") { SettingsWidget() }
    }
}
