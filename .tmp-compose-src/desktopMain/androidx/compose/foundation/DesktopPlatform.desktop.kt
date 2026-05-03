/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation

import org.jetbrains.annotations.VisibleForTesting

internal enum class DesktopPlatform {
    Linux,
    Windows,
    MacOS,
    Unknown;

    companion object {
        private var overriddenCurrent: DesktopPlatform? = null

        private val _current: DesktopPlatform by lazy {
            val name = System.getProperty("os.name")
            when {
                name?.startsWith("Linux") == true -> Linux
                name?.startsWith("Win") == true -> Windows
                name == "Mac OS X" -> MacOS
                else -> Unknown
            }
        }

        /**
         * Identify OS on which the application is currently running.
         */
        val Current get() = overriddenCurrent ?: _current

        /**
         * Override [DesktopPlatform.Current] during [body] execution
         */
        @VisibleForTesting
        inline fun <T> withOverriddenCurrent(newCurrent: DesktopPlatform, body: () -> T): T {
            try {
                overriddenCurrent = newCurrent
                return body()
            } finally {
                overriddenCurrent = null
            }
        }
    }
}