package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES

/** Builds all release bytes in an owned sibling and publishes them as one directory rename. */
internal object PackSetBuilder {
    fun build(inputs: ReleaseInputs): ReleaseArtifacts {
        return build(inputs, CatalogJarProvider.runtime())
    }

    @JvmSynthetic
    internal fun build(inputs: ReleaseInputs, catalogJar: Path): ReleaseArtifacts {
        validateInputs(inputs)
        val output = validateDestination(inputs.outputDirectory)
        val parent =
            output.parent ?: throw IllegalArgumentException("Release output must have a parent.")
        val ownedStage = SecureOwnedDirectory.create(parent, ".packset-stage-")
        val stage = ownedStage.path
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
            val catalogSource = catalogJar(inputs, catalogJar)
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
            verifyStage(ownedStage, setOf(contentFile, platformFile, catalogFile, manifestFile))
            if (!ownedStage.verify()) throw IOException("Owned staging directory identity changed.")
            AtomicNoReplaceRename.publish(stage, output)
            if (!Files.isDirectory(output, NOFOLLOW_LINKS) || Files.isSymbolicLink(output))
                throw IOException("Published release directory is unsafe.")
            published = true
            return ReleaseArtifacts(
                artifact(output.resolve(contentFile.fileName)),
                artifact(output.resolve(platformFile.fileName)),
                artifact(output.resolve(catalogFile.fileName)),
                output.resolve("manifest.json"),
            )
        } finally {
            if (!published) ownedStage.deleteOwned()
            ownedStage.close()
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

    private fun catalogJar(inputs: ReleaseInputs, configured: Path): Path {
        val jar = configured.toAbsolutePath().normalize()
        require(jar.fileName.toString() == "resourcepacks-catalog-${inputs.version}.jar") {
            "Catalog JAR filename/version mismatch."
        }
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

    private fun verifyStage(stage: SecureOwnedDirectory, expected: Set<Path>) {
        val entries = stage.entries().map { stage.path.resolve(it) }.toSet()
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
}

/** Build wiring may inject a current catalog artifact; the public CLI never accepts it. */
internal object CatalogJarProvider {
    fun runtime(): Path =
        Path.of(
            gg.grounds.resourcepacks.catalog.GroundsAssetCatalog::class
                .java
                .protectionDomain
                .codeSource
                .location
                .toURI()
        )
}

/** Linux kernel `renameat2(RENAME_NOREPLACE)` with a deliberate fail-closed fallback. */
internal object AtomicNoReplaceRename {
    private const val AT_FDCWD = -100
    private const val RENAME_NOREPLACE = 1L

    fun publish(stage: Path, output: Path) {
        if (System.getProperty("os.name").lowercase() != "linux") {
            throw IOException("Atomic no-replace directory publication is supported only on Linux.")
        }
        if (stage.parent != output.parent) throw IOException("Stage and output must be siblings.")
        try {
            Arena.ofConfined().use { arena ->
                val symbol =
                    Linker.nativeLinker().defaultLookup().find("renameat2").orElseThrow {
                        IOException("renameat2 is unavailable.")
                    }
                val options = Linker.Option.captureCallState("errno")
                val handle =
                    Linker.nativeLinker()
                        .downcallHandle(
                            symbol,
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT,
                            ),
                            options,
                        )
                val errno = arena.allocate(Linker.Option.captureStateLayout())
                val result =
                    handle.invoke(
                        errno,
                        AT_FDCWD,
                        arena.allocateFrom(stage.toString()),
                        AT_FDCWD,
                        arena.allocateFrom(output.toString()),
                        RENAME_NOREPLACE.toInt(),
                    ) as Int
                if (result != 0) {
                    val error =
                        errno.get(
                            ValueLayout.JAVA_INT,
                            Linker.Option.captureStateLayout()
                                .byteOffset(MemoryLayout.PathElement.groupElement("errno")),
                        )
                    if (error == 17) throw IOException("Release output already exists.")
                    throw IOException(
                        "Atomic no-replace directory publication failed (errno $error)."
                    )
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Throwable) {
            throw IOException("Atomic no-replace directory publication unavailable.", failure)
        }
    }
}
