/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/** Windows AppX / MSIX package options (identity, publisher, tiles, auto-launch extension). */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class AppXSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Application user model ID */
    var applicationId: String? = null

    /** Publisher display name */
    var publisherDisplayName: String? = null

    /** Display name for the Windows Store */
    var displayName: String? = null

    /** Publisher identity (e.g., "CN=MyCompany") */
    var publisher: String? = null

    /** Identity name (e.g., "MyCompany.MyApp") */
    var identityName: String? = null

    /** Languages supported (e.g., "en-US") */
    var languages: List<String>? = null

    /** Add auto-launch on startup capability. Default: false */
    var addAutoLaunchExtension: Boolean = false

    /**
     * StartupTask TaskId used by the auto-launch extension. Exposed at runtime via
     * `NucleusApp.startupTaskId` so `AutoLaunch` can address the correct task.
     *
     * Default when [addAutoLaunchExtension] is `true`: `"SlackStartup"` — this is the
     * value electron-builder hardcodes in the generated manifest (legacy leftover from
     * its Slack origins). **Overriding this property alone does NOT change the manifest** —
     * it only changes the TaskId the runtime looks up, which would cause MSIX
     * `StartupTask.GetAsync` to fail. Only override if you are also post-processing
     * the generated `AppxManifest.xml` to match.
     */
    var startupTaskId: String? = null

    /** Background color of the app tile (e.g. "#464646"). Default: null */
    var backgroundColor: String? = null

    /** Whether to overlay the app name on tile images. Default: false */
    var showNameOnTiles: Boolean = false

    /** Whether to set the build number. Default: false */
    var setBuildNumber: Boolean = false

    /** MinVersion for the manifest (e.g. "10.0.14316.0"). Default: null */
    var minVersion: String? = null

    /** MaxVersionTested for the manifest (e.g. "10.0.16299.0"). Default: null */
    var maxVersionTested: String? = null

    /** AppX capabilities (e.g. "runFullTrust"). Default: null */
    var capabilities: List<String>? = null

    // Each logo is an image of any size, fitted and centered on a transparent canvas at every
    // scale (100 to 400 %) and, for the app icon, every target size (16 to 256 px, plated and
    // unplated) Windows asks for: give it at least 4x its size for a sharp logo. A logo left unset
    // is made from the Windows icon (`.ico` included), else the Linux one.

    /** Store logo, `StoreLogo` (50 x 50 at 100 %). */
    val storeLogo: RegularFileProperty = objects.fileProperty()

    /** App icon, `Square44x44Logo` (44 x 44 at 100 %): taskbar, Start list, Explorer. */
    val square44x44Logo: RegularFileProperty = objects.fileProperty()

    /** Medium tile, `Square150x150Logo` (150 x 150 at 100 %). */
    val square150x150Logo: RegularFileProperty = objects.fileProperty()

    /** Wide tile, `Wide310x150Logo` (310 x 150 at 100 %). */
    val wide310x150Logo: RegularFileProperty = objects.fileProperty()
}
