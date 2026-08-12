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
import java.nio.file.attribute.BasicFileAttributes
import java.util.jar.JarFile

/**
 * Builds all release bytes in one descriptor-owned sibling and publishes one verified directory.
 */
internal object PackSetBuilder {
    fun build(inputs: ReleaseInputs): ReleaseArtifacts =
        build(inputs, CatalogJarProvider.runtime(), PackSetBuilderHooks())

    @JvmSynthetic
    internal fun build(inputs: ReleaseInputs, catalogJar: Path): ReleaseArtifacts =
        build(inputs, catalogJar, PackSetBuilderHooks())

    @JvmSynthetic
    internal fun build(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
    ): ReleaseArtifacts {
        validateInputs(inputs)
        val output = validateDestination(inputs.outputDirectory)
        val parent =
            output.parent ?: throw IllegalArgumentException("Release output must have a parent.")
        val owned = SecureOwnedDirectory.create(parent, ".packset-stage-")
        val stage = owned.stablePath
        var complete = false
        var primaryFailure: Throwable? = null
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
            val catalogSource = validateCatalogJar(inputs, catalogJar)
            val catalogBefore = sourceState(catalogSource)
            Files.copy(catalogSource, catalogFile, COPY_ATTRIBUTES)
            if (sourceState(catalogSource) != catalogBefore) {
                throw IOException("Catalog JAR changed while copying it.")
            }
            val catalogDigest = ArtifactDigests.readRegularFile(catalogFile)
            val manifest = manifest(inputs, catalogFile, catalogDigest, contentFile, platformFile)
            val manifestFile = stage.resolve("manifest.json")
            val manifestBytes = PackSetManifestJson.encode(manifest)
            Files.write(manifestFile, manifestBytes)
            validateManifest(manifestBytes, catalogFile, contentFile, platformFile)

            val expectedNames =
                setOf(
                    contentFile.fileName.toString(),
                    platformFile.fileName.toString(),
                    catalogFile.fileName.toString(),
                    manifestFile.fileName.toString(),
                )
            hooks.beforePrePublishVerification(owned.path)
            val prepublish = owned.snapshot(expectedNames)
            validateManifest(
                prepublish.files.getValue("manifest.json").bytes,
                stage.resolve(catalogFile.fileName),
                stage.resolve(contentFile.fileName),
                stage.resolve(platformFile.fileName),
            )
            val validatedPrepublish = owned.snapshot(expectedNames)
            if (!prepublish.sameBytesDigestsAndIdentities(validatedPrepublish)) {
                throw IOException("Release staging bytes changed during manifest validation.")
            }

            owned.publish(output.fileName, validatedPrepublish, hooks, hooks.rename)
            hooks.afterRenameBeforeOutputOpen(output)
            if (!owned.verify()) throw IOException("Published release directory identity changed.")

            val publishedStable = owned.publishedStablePath()
            val postpublish =
                owned.snapshot(expectedNames) { hooks.afterOutputOpenedBeforeVerification(output) }
            if (!validatedPrepublish.sameBytesDigestsAndIdentities(postpublish)) {
                throw IOException(
                    "Published release bytes differ from the verified staging snapshot."
                )
            }
            validateManifest(
                postpublish.files.getValue("manifest.json").bytes,
                publishedStable.resolve(catalogFile.fileName),
                publishedStable.resolve(contentFile.fileName),
                publishedStable.resolve(platformFile.fileName),
            )
            val finalSnapshot = owned.snapshot(expectedNames)
            if (!validatedPrepublish.sameBytesDigestsAndIdentities(finalSnapshot)) {
                throw IOException("Published release changed during manifest validation.")
            }
            if (!owned.displayParentStillHeldIdentity() || !owned.verify()) {
                throw IOException("Published release path identity is ambiguous.")
            }

            val artifacts =
                releaseArtifacts(
                    output,
                    contentFile,
                    platformFile,
                    catalogFile,
                    validatedPrepublish,
                )
            complete = true
            return artifacts
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!complete) {
                try {
                    owned.deleteOwned(hooks)
                } catch (cleanup: Throwable) {
                    primaryFailure?.addSuppressed(cleanup)
                }
            }
            try {
                owned.close()
            } catch (close: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(close) else throw close
            }
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
        require(output.parent != null && output != output.root && output.fileName != null) {
            "Release output path is unsafe."
        }
        require(!Files.exists(output, NOFOLLOW_LINKS)) {
            "Release output must be absent (including an empty directory)."
        }
        val parent = output.parent!!
        require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
            "Release output parent must be a real directory."
        }
        return output
    }

    private fun validateCatalogJar(inputs: ReleaseInputs, configured: Path): Path {
        val jar = configured.toAbsolutePath().normalize()
        require(jar.fileName.toString() == "resourcepacks-catalog-${inputs.version}.jar") {
            "Catalog JAR filename/version mismatch."
        }
        if (!Files.isRegularFile(jar, NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) {
            throw IOException("Current catalog JAR is missing or unsafe: $jar")
        }
        verifyCatalogJarBytes(jar)
        return jar
    }

    private fun verifyCatalogJarBytes(jar: Path) {
        val classEntries =
            setOf(
                "gg/grounds/resourcepacks/catalog/GroundsAssetCatalog.class",
                "gg/grounds/resourcepacks/catalog/GroundsAssets.class",
                "gg/grounds/resourcepacks/catalog/GroundsGuiIds.class",
                "gg/grounds/resourcepacks/catalog/GroundsGuiTheme.class",
            )
        val expectedEntries =
            setOf(
                "META-INF/",
                "META-INF/MANIFEST.MF",
                "META-INF/resourcepacks-catalog.kotlin_module",
                "gg/",
                "gg/grounds/",
                "gg/grounds/resourcepacks/",
                "gg/grounds/resourcepacks/catalog/",
            ) + classEntries
        try {
            JarFile(jar.toFile(), true).use { archive ->
                val actualEntries = archive.entries().asSequence().map { it.name }.toSet()
                if (actualEntries != expectedEntries) {
                    throw IOException("Catalog JAR entry set does not match the compiled catalog.")
                }
                val loader = GroundsAssetCatalog::class.java.classLoader
                classEntries.forEach { name ->
                    val expected =
                        loader.getResourceAsStream(name)?.use { it.readAllBytes() }
                            ?: throw IOException("Compiled catalog class is unavailable: $name")
                    val entry =
                        archive.getJarEntry(name)
                            ?: throw IOException("Catalog JAR class is missing: $name")
                    val actual = archive.getInputStream(entry).use { it.readAllBytes() }
                    if (!expected.contentEquals(actual)) {
                        throw IOException("Catalog JAR bytes are stale: $name")
                    }
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Catalog JAR cannot be validated.", failure)
        }
    }

    private fun sourceState(path: Path): SourceState {
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attrs.isRegularFile || attrs.fileKey() == null) {
            throw IOException("Catalog JAR is not a stable regular file: $path")
        }
        return SourceState(
            attrs.fileKey(),
            attrs.creationTime(),
            attrs.lastModifiedTime(),
            ArtifactDigests.readRegularFile(path),
        )
    }

    private fun validateManifest(bytes: ByteArray, catalog: Path, content: Path, platform: Path) {
        val validation =
            PackSetManifestJson.decodeAndValidate(
                bytes,
                ManifestArtifacts(
                    catalog,
                    mapOf(PackRole.CONTENT to content, PackRole.PLATFORM to platform),
                ),
            )
        if (!validation.isValid) {
            throw IOException("Generated manifest failed validation: ${validation.problems}")
        }
    }

    private fun releaseArtifacts(
        output: Path,
        contentFile: Path,
        platformFile: Path,
        catalogFile: Path,
        snapshot: DirectorySnapshot,
    ): ReleaseArtifacts {
        fun artifact(name: String): ReleaseArtifact {
            val digest = snapshot.files.getValue(name).digests
            return ReleaseArtifact(output.resolve(name), digest.sha1, digest.sha256, digest.size)
        }
        return ReleaseArtifacts(
            artifact(contentFile.fileName.toString()),
            artifact(platformFile.fileName.toString()),
            artifact(catalogFile.fileName.toString()),
            artifact("manifest.json"),
        )
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
        if (!Files.isRegularFile(to, NOFOLLOW_LINKS) || Files.isSymbolicLink(to)) {
            throw IOException("Staged artifact is not a regular file: $to")
        }
    }

    private fun removeEmptyOwnedDirectory(directory: Path) {
        if (Files.isDirectory(directory, NOFOLLOW_LINKS)) Files.delete(directory)
    }
}

internal data class SourceState(
    val fileKey: Any?,
    val creationTime: java.nio.file.attribute.FileTime,
    val lastModifiedTime: java.nio.file.attribute.FileTime,
    val digests: ArtifactDigests,
)

/** Build wiring locates the exact catalog artifact; the public CLI never accepts this path. */
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

internal data class PackSetBuilderHooks(
    val beforePrePublishVerification: (stage: Path) -> Unit = {},
    val immediatelyBeforeRename: (stage: Path) -> Unit = {},
    val afterPreRenameIdentityVerified: (stage: Path) -> Unit = {},
    val afterRenameBeforeOutputOpen: (output: Path) -> Unit = {},
    val afterOutputOpenedBeforeVerification: (output: Path) -> Unit = {},
    val afterCleanupDirectoryClassified: (directory: Path) -> Unit = {},
    val rename: SecureRename = SecureRename { parent, from, to -> parent.renameNoReplace(from, to) },
)

/** Linux `renameat2(RENAME_NOREPLACE)` compatibility seam retained for focused native tests. */
internal object AtomicNoReplaceRename {
    private const val AT_FDCWD = -100
    private const val RENAME_NOREPLACE = 1

    fun publish(stage: Path, output: Path) {
        if (System.getProperty("os.name").lowercase() != "linux") {
            throw IOException("Atomic no-replace directory publication is supported only on Linux.")
        }
        if (stage.parent != output.parent) throw IOException("Stage and output must be siblings.")
        try {
            Arena.ofConfined().use { arena ->
                val linker = Linker.nativeLinker()
                val symbol = linker.defaultLookup().find("renameat2").orElseThrow()
                val options = Linker.Option.captureCallState("errno")
                val handle =
                    linker.downcallHandle(
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
                        RENAME_NOREPLACE,
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
