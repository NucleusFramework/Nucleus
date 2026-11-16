/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.sandbox;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

/**
 * Runtime shim injected by the Nucleus sandboxed packaging pipeline so that third-party libraries
 * which use the extract-and-load pattern ({@code getResourceAsStream} a native lib, copy it to a
 * temp file, then {@code System.load(tempFile)}) keep working in store/sandboxed distributions
 * (macOS App Store, Windows Store, Flatpak) where temp-extracted native libraries cannot be
 * loaded.
 *
 * <p>At build time the pipeline replaces each native-lib entry inside dependency JARs with a small
 * unique <em>marker</em> file (at the same resource path), rewrites every {@code System.load} /
 * {@code Runtime.load} call site to {@link #load(String)}, and emits a manifest mapping
 * {@code sha256(marker) -> bundled lib filename}, plus {@code sha256(original lib) -> bundled lib
 * filename} for loaders that cache by content and may hand over a real copy an earlier, unstripped
 * build extracted into the app container. The real native libraries are extracted flat into
 * the app resources (signed and, on macOS, moved to {@code Contents/Frameworks} and re-signed by
 * Apple on distribution).
 *
 * <p>At runtime a loader like zstd-kmp's runs unchanged: it extracts the marker to temp and calls
 * {@code System.load} — which is now this shim. The shim hashes the requested file, looks it up in
 * the manifest, and loads the properly signed bundled copy instead. Anything not managed by the
 * pipeline (unknown hashes, missing manifest) falls through to the real {@code System.load}, so
 * behavior is unchanged for code that was not rewritten.
 *
 * <p>Pure JDK only ({@link Properties}, {@link MessageDigest} SHA-256, NIO): no reflection, no
 * external dependencies, GraalVM native-image friendly. The rewritten call sites reference this
 * class directly, so it is statically reachable.
 */
public final class NucleusSandboxLoader {

    /** Resource/system-property name for an explicit manifest path override. */
    public static final String MANIFEST_PATH_PROPERTY = "nucleus.sandbox.manifest";

    /** System property set by the jpackage launcher pointing at the packaged resources directory. */
    public static final String APP_RESOURCES_DIR_PROPERTY = "compose.application.resources.dir";

    /** Bundled manifest filename, placed next to the extracted native libs at build time. */
    public static final String MANIFEST_FILENAME = "nucleus-sandbox-manifest.properties";

    private NucleusSandboxLoader() {}

    private static volatile Properties cachedManifest;
    private static volatile File cachedManifestSource;

    /**
     * Replacement for {@code System.load(String)} / {@code Runtime.load(String)}.
     *
     * <p>If the requested file is a known marker, loads the matching bundled (signed) library
     * instead. Otherwise calls {@code System.load(path)} unchanged.
     */
    public static void load(String path) {
        File bundled = resolveBundled(path);
        System.load(bundled != null ? bundled.getAbsolutePath() : path);
    }

    /**
     * Resolves the signed bundled library that should be loaded for the given (possibly marker)
     * path, or {@code null} when the path is not managed by the sandbox pipeline (passthrough).
     *
     * @param requestedPath the absolute path passed to {@code System.load}
     * @return the bundled library file to load, or {@code null} to fall through to the real load
     */
    static File resolveBundled(String requestedPath) {
        if (requestedPath == null) {
            return null;
        }
        File requested = new File(requestedPath);
        if (!requested.isFile()) {
            return null;
        }
        String shaHex;
        try {
            shaHex = sha256Hex(requested);
        } catch (IOException e) {
            return null;
        }
        Properties manifest = manifest();
        if (manifest == null) {
            return null;
        }
        String bundledName = manifest.getProperty(shaHex);
        if (bundledName == null || bundledName.isEmpty()) {
            return null;
        }
        return findBundled(bundledName);
    }

    /** Searches the union of {@code java.library.path} and the app resources dir for the lib. */
    private static File findBundled(String bundledName) {
        for (File dir : searchDirs()) {
            File candidate = new File(dir, bundledName);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /** Directories to search for both the manifest and the bundled libraries. */
    private static File[] searchDirs() {
        java.util.List<File> dirs = new java.util.ArrayList<File>();
        String libPath = System.getProperty("java.library.path");
        if (libPath != null && !libPath.isEmpty()) {
            for (String entry : libPath.split(File.pathSeparator)) {
                if (!entry.isEmpty()) {
                    dirs.add(new File(entry));
                }
            }
        }
        String appResources = System.getProperty(APP_RESOURCES_DIR_PROPERTY);
        if (appResources != null && !appResources.isEmpty()) {
            File appResDir = new File(appResources);
            if (!dirs.contains(appResDir)) {
                dirs.add(appResDir);
            }
        }
        return dirs.toArray(new File[0]);
    }

    private static Properties manifest() {
        File manifestFile = locateManifest();
        if (manifestFile == null) {
            return null;
        }
        Properties cached = cachedManifest;
        File cachedSource = cachedManifestSource;
        if (cached != null && cachedSource != null && cachedSource.equals(manifestFile)) {
            return cached;
        }
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(manifestFile)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        cachedManifest = props;
        cachedManifestSource = manifestFile;
        return props;
    }

    private static File locateManifest() {
        String explicit = System.getProperty(MANIFEST_PATH_PROPERTY);
        if (explicit != null && !explicit.isEmpty()) {
            File f = new File(explicit);
            if (f.isFile()) {
                return f;
            }
        }
        for (File dir : searchDirs()) {
            File candidate = new File(dir, MANIFEST_FILENAME);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static String sha256Hex(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        byte[] buffer = new byte[8192];
        try (FileInputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
        }
        return toHex(md.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}