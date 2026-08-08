package dev.nucleusframework.energymanager

/**
 * Scope of an awake request issued through [EnergyManager.keepAwake].
 */
public enum class AwakeMode {
    /**
     * Prevents both system sleep and display sleep: the screen stays on and no
     * screen saver kicks in for as long as the request is held.
     */
    SYSTEM_AND_DISPLAY,

    /**
     * Prevents system sleep only. The display is free to turn off and the screen
     * saver / lock screen behave as usual — suited to long background work that
     * must survive the user walking away from the machine.
     *
     * Implemented on Windows and macOS; Linux reports the request as unsupported.
     * On macOS this is what `caffeinate -i` holds, and — like every idle-sleep
     * assertion — it does not keep the machine running once the lid is closed.
     */
    SYSTEM_ONLY,
}
