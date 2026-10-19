/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.dsl

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import javax.inject.Inject

/**
 * Where electron-builder publishes release artifacts and update metadata: GitHub Releases, S3
 * or a generic HTTP server.
 */
abstract class PublishSettings {
    @get:Inject
    internal abstract val objects: ObjectFactory

    /** Publish mode for electron-builder. Default: [PublishMode.Never] */
    @get:Input
    var publishMode: PublishMode = PublishMode.Never

    @get:Nested
    val github: GitHubPublishSettings = objects.newInstance(GitHubPublishSettings::class.java)

    /** Configures publishing to GitHub Releases. */
    fun github(fn: Action<GitHubPublishSettings>) {
        fn.execute(github)
    }

    @get:Nested
    val s3: S3PublishSettings = objects.newInstance(S3PublishSettings::class.java)

    /** Configures publishing to an S3 bucket. */
    fun s3(fn: Action<S3PublishSettings>) {
        fn.execute(s3)
    }

    @get:Nested
    val generic: GenericPublishSettings = objects.newInstance(GenericPublishSettings::class.java)

    /** Configures publishing to a generic HTTP server. */
    fun generic(fn: Action<GenericPublishSettings>) {
        fn.execute(generic)
    }
}

/** Publishing to GitHub Releases. */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class GitHubPublishSettings {
    /** Enable publishing to GitHub Releases. Default: false */
    @get:Input
    var enabled: Boolean = false

    /** GitHub repository owner */
    @get:Input
    @get:Optional
    var owner: String? = null

    /** GitHub repository name */
    @get:Input
    @get:Optional
    var repo: String? = null

    /** GitHub token (or use GITHUB_TOKEN env var) */
    @get:Input
    @get:Optional
    var token: String? = null

    /** Release channel. Default: [ReleaseChannel.Latest] */
    @get:Input
    var channel: ReleaseChannel = ReleaseChannel.Latest

    /** Release type. Default: [ReleaseType.Release] */
    @get:Input
    var releaseType: ReleaseType = ReleaseType.Release
}

/** Publishing to a generic HTTP server serving the update files from [url]. */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class GenericPublishSettings {
    /** Enable publishing via generic HTTP server. Default: false */
    @get:Input
    var enabled: Boolean = false

    /** Base URL where update files will be hosted (e.g., "https://updates.example.com/releases/") */
    @get:Input
    @get:Optional
    var url: String? = null

    /** Update channel. Default: [ReleaseChannel.Latest] */
    @get:Input
    var channel: ReleaseChannel = ReleaseChannel.Latest

    /** Use multiple range requests for differential downloads. Default: true */
    @get:Input
    var useMultipleRangeRequest: Boolean = true
}

/** Publishing to an Amazon S3 bucket. */
@Suppress("UnnecessaryAbstractClass") // Required abstract for Gradle ObjectFactory.newInstance()
abstract class S3PublishSettings {
    /** Enable publishing to S3. Default: false */
    @get:Input
    var enabled: Boolean = false

    /** S3 bucket name */
    @get:Input
    @get:Optional
    var bucket: String? = null

    /** AWS region */
    @get:Input
    @get:Optional
    var region: String? = null

    /** Path prefix within the bucket */
    @get:Input
    @get:Optional
    var path: String? = null

    /** ACL for uploaded objects (e.g., "public-read") */
    @get:Input
    @get:Optional
    var acl: String? = null
}
