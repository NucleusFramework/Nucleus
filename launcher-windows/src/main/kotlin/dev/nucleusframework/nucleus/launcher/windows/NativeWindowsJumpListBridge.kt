package dev.nucleusframework.nucleus.launcher.windows

import dev.nucleusframework.nucleus.core.runtime.NativeLibraryLoader

private const val LIBRARY_NAME = "nucleus_launcher_windows"

internal object NativeWindowsJumpListBridge {
    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeWindowsJumpListBridge::class.java)

    val isLoaded: Boolean get() = loaded

    @JvmStatic
    external fun nativeSetProcessAppId(aumid: String): String?

    @JvmStatic
    external fun nativeBeginList(
        aumid: String,
        isAppx: Boolean,
    ): String?

    @JvmStatic
    external fun nativeAppendCategory(
        name: String,
        titles: Array<String>,
        arguments: Array<String>,
        descriptions: Array<String>,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
    ): String?

    @JvmStatic
    external fun nativeAppendKnownCategory(categoryId: Int): String?

    @JvmStatic
    external fun nativeAddUserTasks(
        titles: Array<String>,
        arguments: Array<String>,
        descriptions: Array<String>,
        iconTypes: IntArray,
        iconPaths: Array<String>,
        iconIndices: IntArray,
        isSeparator: BooleanArray,
    ): String?

    @JvmStatic
    external fun nativeCommitList(): String?

    @JvmStatic
    external fun nativeDeleteList(
        aumid: String,
        isAppx: Boolean,
    ): String?
}
