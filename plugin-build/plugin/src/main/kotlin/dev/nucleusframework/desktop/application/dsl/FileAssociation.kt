package dev.nucleusframework.desktop.application.dsl

import java.io.File
import java.io.Serializable

@Suppress("SerialVersionUIDInSerializableClass") // Gradle plugin data class, serialVersionUID not needed
internal data class FileAssociation(
    val mimeType: String,
    val extension: String,
    val description: String,
    val iconFile: File?,
    val macOSRole: MacOSDocumentRole = MacOSDocumentRole.Editor,
    val macOSRank: MacOSHandlerRank = MacOSHandlerRank.Default,
) : Serializable

/** macOS `CFBundleTypeRole` of a file association: what the app does with the document type. */
enum class MacOSDocumentRole(
    internal val id: String,
) {
    Editor("Editor"),
    Viewer("Viewer"),
    Shell("Shell"),
    None("None"),
}

/**
 * macOS `LSHandlerRank` of a file association: how Launch Services ranks the app among the
 * handlers of the document type. App Store Connect flags a document type without it (ITMS-90788).
 */
enum class MacOSHandlerRank(
    internal val id: String,
) {
    /** The app creates this type and is its primary handler. */
    Owner("Owner"),

    /** The app is a default handler for this type, ranked by Launch Services like any other. */
    Default("Default"),

    /** The app is a secondary handler, never picked as the default one. */
    Alternate("Alternate"),

    /** The app is never offered as a handler; the type is declared for import/export only. */
    None("None"),
}
