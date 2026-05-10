package dev.nucleusframework.nucleus.aot.runtime

public enum class AotRuntimeMode {
    OFF,
    TRAINING,
    RUNTIME,
}

public object AotRuntime {
    private const val MODE_PROPERTY = "nucleus.aot.mode"

    @JvmStatic
    public fun mode(): AotRuntimeMode = parseModeProperty(System.getProperty(MODE_PROPERTY)) ?: AotRuntimeMode.OFF

    @JvmStatic
    public fun isRuntime(): Boolean = mode() == AotRuntimeMode.RUNTIME

    @JvmStatic
    public fun isTraining(): Boolean = mode() == AotRuntimeMode.TRAINING

    internal fun parseModeProperty(rawValue: String?): AotRuntimeMode? =
        when (rawValue?.trim()?.lowercase()) {
            null, "" -> null
            "train", "training" -> AotRuntimeMode.TRAINING
            "runtime", "run", "on", "use", "enabled" -> AotRuntimeMode.RUNTIME
            "off", "disabled", "none" -> AotRuntimeMode.OFF
            else -> null
        }
}
