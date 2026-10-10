// CI only (pre-merge's `gradle` job): moves the local build cache to $NUCLEUS_BUILD_CACHE_DIR.
//
// Its default home, ~/.gradle/caches/build-cache-1, is inside what setup-gradle's basic
// provider saves — under a key hashed from the build files, written once and never
// overwritten — so task outputs stored there would freeze at the first push after a
// build-script change. Out here the job caches the directory itself: restored from the
// newest entry, saved per commit on the base branch.
//
// buildSrc and the included plugin-build use the root build's build cache configuration,
// so this one directory serves all three. The job turns the cache on with --build-cache
// rather than gradle.properties: every other job would then fill build-cache-1 inside its
// own setup-gradle entry.
settingsEvaluated {
    val dir = System.getenv("NUCLEUS_BUILD_CACHE_DIR")
    if (!dir.isNullOrBlank()) {
        buildCache {
            local {
                directory = File(dir)
            }
        }
    }
}
