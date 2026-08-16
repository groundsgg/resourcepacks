package gg.grounds.resourcepacks.product

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackSetObjectLayoutTest {
    private val commit = "1969c1e6a3799e976de46eab019a16b2ee257ea7"
    private val contentSha1 = "0123456789abcdef0123456789abcdef01234567"
    private val platformSha1 = "89abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `release locations use the approved readable versioned paths`() {
        val release = PublicationIdentity(PublicationType.RELEASE, "v0.1.2", "0.1.2", commit)

        assertEquals(
            ArtifactLocation(
                "grounds-content-pack-v0.1.2.zip",
                "resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-content-pack-v0.1.2.zip",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-content-pack-v0.1.2.zip",
            ),
            PackSetObjectLayout.content(release, contentSha1),
        )
        assertEquals(
            ArtifactLocation(
                "grounds-platform-pack-v0.1.2.zip",
                "resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-platform-pack-v0.1.2.zip",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-platform-pack-v0.1.2.zip",
            ),
            PackSetObjectLayout.platform(release, platformSha1),
        )
        assertEquals(
            ArtifactLocation(
                "grounds-resourcepack-catalog-v0.1.2.jar",
                "resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-resourcepack-catalog-v0.1.2.jar",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/grounds-resourcepack-catalog-v0.1.2.jar",
            ),
            PackSetObjectLayout.catalog(release),
        )
        assertEquals(
            ArtifactLocation(
                "manifest.json",
                "resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/releases/v0.1.2/manifest.json",
            ),
            PackSetObjectLayout.manifest(release),
        )
    }

    @Test
    fun `build locations use the approved short commit Edge filenames`() {
        val build =
            PublicationIdentity(
                PublicationType.BUILD,
                commit,
                "0.0.0-edge.42.g1969c1e6a379",
                commit,
            )

        assertEquals(
            ArtifactLocation(
                "grounds-content-pack-edge-1969c1e6a379.zip",
                "resourcepacks/packsets/grounds-global/builds/$commit/grounds-content-pack-edge-1969c1e6a379.zip",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/$commit/grounds-content-pack-edge-1969c1e6a379.zip",
            ),
            PackSetObjectLayout.content(build, contentSha1),
        )
        assertEquals(
            ArtifactLocation(
                "grounds-platform-pack-edge-1969c1e6a379.zip",
                "resourcepacks/packsets/grounds-global/builds/$commit/grounds-platform-pack-edge-1969c1e6a379.zip",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/$commit/grounds-platform-pack-edge-1969c1e6a379.zip",
            ),
            PackSetObjectLayout.platform(build, platformSha1),
        )
        assertEquals(
            ArtifactLocation(
                "grounds-resourcepack-catalog-edge-1969c1e6a379.jar",
                "resourcepacks/packsets/grounds-global/builds/$commit/grounds-resourcepack-catalog-edge-1969c1e6a379.jar",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/$commit/grounds-resourcepack-catalog-edge-1969c1e6a379.jar",
            ),
            PackSetObjectLayout.catalog(build),
        )
        assertEquals(
            ArtifactLocation(
                "manifest.json",
                "resourcepacks/packsets/grounds-global/builds/$commit/manifest.json",
                "https://cdn.grounds.gg/resourcepacks/packsets/grounds-global/builds/$commit/manifest.json",
            ),
            PackSetObjectLayout.manifest(build),
        )
    }

    @Test
    fun `layout rejects hostile publication identity and digest inputs`() {
        val valid = PublicationIdentity(PublicationType.RELEASE, "v0.1.2", "0.1.2", commit)
        listOf(
                PublicationIdentity(PublicationType.RELEASE, "v1.2", "1.2", commit),
                PublicationIdentity(PublicationType.RELEASE, "v0.1.2", "0.1.2", "A".repeat(40)),
                PublicationIdentity(PublicationType.RELEASE, "release/v0.1.2", "0.1.2", commit),
                PublicationIdentity(
                    PublicationType.BUILD,
                    commit,
                    "0.0.0-edge.42.g1969c1e6a379",
                    "b".repeat(40),
                ),
                PublicationIdentity(
                    PublicationType.BUILD,
                    "build\\$commit",
                    "0.0.0-edge.42.g1969c1e6a379",
                    commit,
                ),
                PublicationIdentity(
                    PublicationType.BUILD,
                    "build\u0000$commit",
                    "0.0.0-edge.42.g1969c1e6a379",
                    commit,
                ),
                PublicationIdentity(
                    PublicationType.BUILD,
                    commit,
                    "0.0.0-edge.42.g1969c1e6a37A",
                    commit,
                ),
            )
            .forEach { identity ->
                assertFailsWith<IllegalArgumentException> { PackSetObjectLayout.manifest(identity) }
            }
        listOf("", "A".repeat(40), "a".repeat(39), "a".repeat(41), "a/" + "a".repeat(38)).forEach {
            sha1 ->
            assertFailsWith<IllegalArgumentException> { PackSetObjectLayout.content(valid, sha1) }
        }
        assertFailsWith<IllegalArgumentException> {
            PackSetObjectLayout.locationFor("grounds-other", valid, "manifest.json")
        }
    }
}
