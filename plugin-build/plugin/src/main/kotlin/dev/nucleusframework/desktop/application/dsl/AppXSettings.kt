/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/** Windows AppX / MSIX package options (identity, publisher, tiles, auto-launch extension). */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class AppXSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Application user model ID */
    @get:Input
    @get:Optional
    var applicationId: String? = null

    /** Publisher display name */
    @get:Input
    @get:Optional
    var publisherDisplayName: String? = null

    /** Display name for the Windows Store */
    @get:Input
    @get:Optional
    var displayName: String? = null

    /** Publisher identity (e.g., "CN=MyCompany") */
    @get:Input
    @get:Optional
    var publisher: String? = null

    /** Identity name (e.g., "MyCompany.MyApp") */
    @get:Input
    @get:Optional
    var identityName: String? = null

    /** Languages supported (e.g., "en-US") */
    @get:Input
    @get:Optional
    var languages: List<String>? = null

    /** Add auto-launch on startup capability. Default: false */
    @get:Input
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
    @get:Input
    @get:Optional
    var startupTaskId: String? = null

    /** Background color of the app tile (e.g. "#464646"). Default: null */
    @get:Input
    @get:Optional
    var backgroundColor: String? = null

    /** Whether to overlay the app name on tile images. Default: false */
    @get:Input
    var showNameOnTiles: Boolean = false

    /** Whether to set the build number. Default: false */
    @get:Input
    var setBuildNumber: Boolean = false

    /** MinVersion for the manifest (e.g. "10.0.14316.0"). Default: null */
    @get:Input
    @get:Optional
    var minVersion: String? = null

    /** MaxVersionTested for the manifest (e.g. "10.0.16299.0"). Default: null */
    @get:Input
    @get:Optional
    var maxVersionTested: String? = null

    /** AppX capabilities (e.g. "runFullTrust"). Default: null */
    @get:Input
    @get:Optional
    var capabilities: List<String>? = null

    /** Store tile logo (mapped as `StoreLogo.png`) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val storeLogo: RegularFileProperty = objects.fileProperty()

    /** Small tile logo (mapped as `Square44x44Logo.png`) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val square44x44Logo: RegularFileProperty = objects.fileProperty()

    /** Medium tile logo (mapped as `Square150x150Logo.png`) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val square150x150Logo: RegularFileProperty = objects.fileProperty()

    /** Wide tile logo (mapped as `Wide310x150Logo.png`) */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    val wide310x150Logo: RegularFileProperty = objects.fileProperty()
}
