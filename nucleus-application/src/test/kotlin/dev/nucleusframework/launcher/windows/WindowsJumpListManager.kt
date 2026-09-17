package dev.nucleusframework.launcher.windows

/**
 * Test double for [primePlatformIntegrations]. Mirrors the reflective
 * `INSTANCE` + `setProcessAppId(String)` surface of the optional
 * `launcher-windows` module.
 */
object WindowsJumpListManager {
    @JvmField
    @Volatile
    var lastAumid: String? = UNSET

    @JvmField
    @Volatile
    var calls: Int = 0

    @JvmStatic
    fun reset() {
        lastAumid = UNSET
        calls = 0
    }

    @JvmStatic
    fun setProcessAppId(aumid: String?): Boolean {
        lastAumid = aumid
        calls++
        return true
    }

    const val UNSET: String = "<unset>"
}
