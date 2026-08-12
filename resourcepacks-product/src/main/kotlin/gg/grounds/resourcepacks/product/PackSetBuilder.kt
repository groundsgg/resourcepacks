package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.BasicFileAttributes

/** Builds all release bytes in an owned sibling and publishes them as one directory rename. */
internal object PackSetBuilder {
    fun build(inputs: ReleaseInputs): ReleaseArtifacts {
        validateInputs(inputs)
        val output = validateDestination(inputs.outputDirectory)
        val parent =
            output.parent ?: throw IllegalArgumentException("Release output must have a parent.")
        val stage = Files.createTempDirectory(parent, ".packset-stage-")
        var published = false
        try {
            val packs = PackComposer.build(ProductGraph, stage)
            val content = packs.single { it.pack.role == PackRole.CONTENT }
            val platform = packs.single { it.pack.role == PackRole.PLATFORM }
            val contentFile = stage.resolve("grounds-content-${content.sha1}.zip")
            val platformFile = stage.resolve("grounds-platform-${platform.sha1}.zip")
            moveExact(content.file, contentFile)
            moveExact(platform.file, platformFile)
            removeEmptyOwnedDirectory(content.file.parent)

            val catalogFile = stage.resolve("grounds-resourcepacks-catalog-${inputs.version}.jar")
            val catalogSource = catalogJar(inputs.version)
            Files.copy(catalogSource, catalogFile, COPY_ATTRIBUTES)
            val catalogDigest = ArtifactDigests.readRegularFile(catalogFile)
            val manifest = manifest(inputs, catalogFile, catalogDigest, contentFile, platformFile)
            val manifestFile = stage.resolve("manifest.json")
            val manifestBytes = PackSetManifestJson.encode(manifest)
            Files.write(manifestFile, manifestBytes)
            val validation =
                PackSetManifestJson.decodeAndValidate(
                    manifestBytes,
                    ManifestArtifacts(
                        catalogFile,
                        mapOf(PackRole.CONTENT to contentFile, PackRole.PLATFORM to platformFile),
                    ),
                )
            if (!validation.isValid)
                throw IOException("Generated manifest failed validation: ${validation.problems}")
            verifyStage(stage, setOf(contentFile, platformFile, catalogFile, manifestFile))
            try {
                Files.move(stage, output, ATOMIC_MOVE)
            } catch (failure: Exception) {
                throw IOException("Atomic release directory publication failed.", failure)
            }
            published = true
            return ReleaseArtifacts(
                artifact(output.resolve(contentFile.fileName)),
                artifact(output.resolve(platformFile.fileName)),
                artifact(output.resolve(catalogFile.fileName)),
                output.resolve("manifest.json"),
            )
        } finally {
            if (!published) deleteOwnedStage(stage)
        }
    }

    private fun validateInputs(inputs: ReleaseInputs) {
        require(inputs.version == GroundsAssetCatalog.catalog.version) {
            "Version must equal the compiled catalog version."
        }
        require(Regex("[0-9a-f]{40}").matches(inputs.provenanceCommit)) {
            "Commit must be lowercase 40-hex."
        }
        require(inputs.provenanceTag == "v${inputs.version}") { "Tag must equal v<version>." }
    }

    private fun validateDestination(raw: Path): Path {
        require(raw.isAbsolute) { "Release output must be an absolute path." }
        val output = raw.normalize()
        require(output.parent != null && output != output.root) { "Release output path is unsafe." }
        require(!Files.exists(output, NOFOLLOW_LINKS)) {
            "Release output must be absent (including an empty directory)."
        }
        val parent = output.parent!!
        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "Release output parent must be a real directory."
        }
        return output
    }

    private fun catalogJar(version: String): Path {
        val location =
            Path.of(GroundsAssetCatalog::class.java.protectionDomain.codeSource.location.toURI())
                .toAbsolutePath()
                .normalize()
        if (Files.isRegularFile(location, NOFOLLOW_LINKS)) return location
        var cursor: Path? = location
        while (cursor != null && cursor.fileName?.toString() != "resourcepacks-catalog") cursor =
            cursor.parent
        val module = cursor ?: throw IOException("Cannot locate the catalog module output.")
        val jar = module.resolve("build/libs/resourcepacks-catalog-$version.jar")
        if (!Files.isRegularFile(jar, NOFOLLOW_LINKS) || Files.isSymbolicLink(jar))
            throw IOException("Current catalog JAR is missing or unsafe: $jar")
        return jar
    }

    private fun manifest(
        inputs: ReleaseInputs,
        catalogFile: Path,
        catalog: ArtifactDigests,
        contentFile: Path,
        platformFile: Path,
    ): PackSetManifest {
        fun pack(order: Int, role: PackRole, file: Path): PackManifest {
            val digest = ArtifactDigests.readRegularFile(file)
            val id = if (role == PackRole.CONTENT) "grounds-content" else "grounds-platform"
            val uuid =
                if (role == PackRole.CONTENT) PackSetConstants.contentUuid
                else PackSetConstants.platformUuid
            return PackManifest(
                order,
                role.name.lowercase(),
                id,
                uuid,
                true,
                "https://cdn.grounds.gg/resourcepacks/${role.name.lowercase()}/${digest.sha1}.zip",
                digest.sha1,
                digest.sha256,
                digest.size,
                PackSetConstants.FORMAT,
            )
        }
        return PackSetManifest(
            inputs.version,
            MinecraftManifest("26.2", PackSetConstants.FORMAT),
            CatalogManifest(
                "grounds:resourcepacks",
                inputs.version,
                "gg.grounds:resourcepacks-catalog:${inputs.version}",
                catalogFile.fileName.toString(),
                catalog.sha256,
                catalog.size,
            ),
            listOf(
                pack(0, PackRole.CONTENT, contentFile),
                pack(1, PackRole.PLATFORM, platformFile),
            ),
            ProvenanceManifest(
                "groundsgg/resourcepacks",
                inputs.provenanceCommit,
                inputs.provenanceTag,
            ),
        )
    }

    private fun moveExact(from: Path, to: Path) {
        Files.move(from, to, ATOMIC_MOVE)
        if (!Files.isRegularFile(to, NOFOLLOW_LINKS) || Files.isSymbolicLink(to))
            throw IOException("Staged artifact is not a regular file: $to")
    }

    private fun artifact(file: Path): ReleaseArtifact {
        val digest = ArtifactDigests.readRegularFile(file)
        return ReleaseArtifact(file, digest.sha1, digest.sha256, digest.size)
    }

    private fun verifyStage(stage: Path, expected: Set<Path>) {
        val entries = Files.list(stage).use { it.toList() }.toSet()
        if (
            entries != expected ||
                entries.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
        )
            throw IOException(
                "Release staging directory does not contain exactly four regular files."
            )
    }

    private fun removeEmptyOwnedDirectory(directory: Path) {
        if (Files.isDirectory(directory, NOFOLLOW_LINKS)) Files.delete(directory)
    }

    private fun deleteOwnedStage(stage: Path) {
        if (!Files.exists(stage, NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            stage,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    failure: IOException?,
                ): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
