package dev.nucleusframework.updater.provider

import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.delta.DeltaFixtures
import dev.nucleusframework.updater.exception.NetworkException
import dev.nucleusframework.updater.internal.PlatformInfo
import dev.nucleusframework.updater.internal.delta.UpdateCache
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PrivateGitHubProvider] against a real private repository on github.com. Opt-in: skipped unless
 * `-Dnucleus.e2e.githubRepo=<owner>/<repo>` and the `NUCLEUS_E2E_GITHUB_TOKEN` environment variable
 * (a token with read access to the repository's contents) are set.
 *
 * The repository's releases are the files [dumpReleaseAssets] writes:
 * - `v2.0.0`: the stable update, artifact, block map and signature;
 * - `v2.1.0-beta.1` (pre-release): the `beta` channel's.
 */
class PrivateGitHubLiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val repo: String? = System.getProperty("nucleus.e2e.githubRepo")
    private val token: String? = System.getenv("NUCLEUS_E2E_GITHUB_TOKEN")

    /** Writes both releases' files under `-Dnucleus.e2e.githubAssetsDir`, one directory per tag. */
    @Test
    fun dumpReleaseAssets() {
        val dir = System.getProperty("nucleus.e2e.githubAssetsDir")?.let(::File)
        assumeTrue("nucleus.e2e.githubAssetsDir not set", dir != null)
        val artifact = DeltaFixtures.v2()
        val platform = PlatformInfo.currentPlatform()

        File(dir, "v2.0.0").apply {
            mkdirs()
            resolve(STABLE_FILE).writeBytes(artifact)
            resolve("$STABLE_FILE.blockmap").writeBytes(DeltaFixtures.blockMapGzip("v2"))
            resolve("$STABLE_FILE.asc").writeText(SIGNATURE)
            resolve(
                GitHubReleases.metadataFileName("latest", platform),
            ).writeText(manifest("2.0.0", STABLE_FILE, artifact))
        }
        File(dir, "v2.1.0-beta.1").apply {
            mkdirs()
            resolve(BETA_FILE).writeBytes(artifact)
            resolve(
                GitHubReleases.metadataFileName("beta", platform),
            ).writeText(manifest("2.1.0-beta.1", BETA_FILE, artifact))
        }
    }

    @Test
    fun `the stable update downloads differentially from the private repository`() {
        val (owner, name) = coordinates()
        DeltaFixtures.verify()
        val cacheDir = tmp.newFolder("update-cache")
        val previous = File(tmp.newFolder(), "MyApp-1.0.0.zip").apply { writeBytes(DeltaFixtures.v1()) }
        UpdateCache(
            cacheDir,
        ).store(previous, previous.name, version = "1.0.0", blockMapGzip = DeltaFixtures.blockMapGzip("v1"))

        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = PrivateGitHubProvider(owner, name, token!!)
                executableType = "zip"
                this.cacheDir = cacheDir
            }
        val progress =
            runBlocking {
                val result = updater.checkForUpdates()
                assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
                assertEquals("2.0.0", (result as UpdateResult.Available).info.version)
                updater.downloadUpdate(result.info).toList()
            }.last()
        val file = requireNotNull(progress.file)
        try {
            assertTrue("the update must be differential", progress.isDifferential)
            assertEquals(DeltaFixtures.EXPECTED_DELTA_BYTES, progress.bytesDownloaded)
            assertTrue("the artifact must be byte-identical", DeltaFixtures.v2().contentEquals(file.readBytes()))
            assertEquals(SIGNATURE, File(file.parentFile, "${file.name}.asc").readText())
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `the beta channel resolves the pre-release`() {
        val (owner, name) = coordinates()
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = PrivateGitHubProvider(owner, name, token!!)
                channel = "beta"
                allowPrerelease = true
                executableType = "zip"
            }
        val result = runBlocking { updater.checkForUpdates() }
        assertTrue("an update must be offered, got $result", result is UpdateResult.Available)
        assertEquals("2.1.0-beta.1", (result as UpdateResult.Available).info.version)
    }

    @Test
    fun `a rejected token is reported as such`() {
        val (owner, name) = coordinates()
        val updater =
            NucleusUpdater {
                currentVersion = "1.0.0"
                provider = PrivateGitHubProvider(owner, name, "github_pat_not_a_real_token")
                executableType = "zip"
            }
        val result = runBlocking { updater.checkForUpdates() }
        assertTrue("an error must be reported, got $result", result is UpdateResult.Error)
        val error = (result as UpdateResult.Error).exception
        assertTrue("$error", error is NetworkException && error.message!!.contains("the token was rejected (HTTP 401)"))
    }

    private fun coordinates(): Pair<String, String> {
        assumeTrue("nucleus.e2e.githubRepo / NUCLEUS_E2E_GITHUB_TOKEN not set", repo != null && token != null)
        return repo!!.substringBefore('/') to repo.substringAfter('/')
    }

    private fun manifest(
        version: String,
        fileName: String,
        artifact: ByteArray,
    ): String {
        val sha512 = DeltaFixtures.sha512Base64(artifact)
        return """
            version: $version
            files:
              - url: $fileName
                sha512: $sha512
                size: ${artifact.size}
            path: $fileName
            sha512: $sha512
            releaseDate: '2026-01-01T00:00:00.000Z'
            """.trimIndent() + "\n"
    }

    private companion object {
        const val STABLE_FILE = "MyApp-2.0.0.zip"
        const val BETA_FILE = "MyApp-2.1.0-beta.1.zip"
        const val SIGNATURE = "signature"
    }
}
