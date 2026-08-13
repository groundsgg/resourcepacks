package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import java.util.zip.ZipInputStream

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
    ): ReleaseArtifacts = buildInternal(inputs, catalogJar, hooks, null)

    @JvmSynthetic
    internal fun build(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
        packs: List<PhysicalPack>,
    ): ReleaseArtifacts = buildInternal(inputs, catalogJar, hooks, packs.toList())

    private fun buildInternal(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
        suppliedPacks: List<PhysicalPack>?,
    ): ReleaseArtifacts {
        validateInputs(inputs)
        val output = validateDestination(inputs.outputDirectory)
        val parent =
            output.parent ?: throw IllegalArgumentException("Release output must have a parent.")
        val stageDirectory =
            SecureOwnedDirectory.create(
                parent,
                ".packset-stage-",
                hooks.afterOwnedDirectoryIdentityCapturedBeforeParentValidation,
                output.fileName,
            )
        var scratchDirectory: SecureOwnedDirectory? = null
        var sourceInputs: SecureSourceInputs? = null
        var catalogSource: HeldSourceFile? = null
        var committed = false
        var primaryFailure: Throwable? = null
        try {
            scratchDirectory = SecureOwnedDirectory.create(parent, ".packset-scratch-")
            val scratch = requireNotNull(scratchDirectory)
            val stage = stageDirectory.stablePath
            hooks.afterOwnedDirectoryCreatedBeforeComposition(stageDirectory.path)
            hooks.beforeComposition()
            sourceInputs =
                suppliedPacks?.let(SecureSourceInputs::capture)
                    ?: ProductGraph.secureReleaseInputs()
            val immutableInputs = requireNotNull(sourceInputs)
            val builtPacks =
                ReleasePackComposer.build(
                    immutableInputs.packs,
                    PackComposerHooks(afterComposeBeforeWrite = hooks.afterComposeBeforePackWrite),
                )
            hooks.afterComposition()
            val content = builtPacks.single { it.pack.role == PackRole.CONTENT }
            val platform = builtPacks.single { it.pack.role == PackRole.PLATFORM }
            val contentScratch = Path.of(".grounds-content.pending.zip")
            val platformScratch = Path.of(".grounds-platform.pending.zip")
            scratch.writeRegularFile(contentScratch, content.bytes)
            scratch.writeRegularFile(platformScratch, platform.bytes)
            val contentSnapshot =
                scratch.readRelativeRegularFile(contentScratch) {
                    hooks.afterArtifactHashFirstChunk(scratch.stablePath.resolve(contentScratch))
                }
            val platformSnapshot =
                scratch.readRelativeRegularFile(platformScratch) {
                    hooks.afterArtifactHashFirstChunk(scratch.stablePath.resolve(platformScratch))
                }
            requireComposerDigest(content, contentSnapshot)
            requireComposerDigest(platform, platformSnapshot)
            val contentFile = stage.resolve("grounds-content-${content.digests.sha1}.zip")
            val platformFile = stage.resolve("grounds-platform-${platform.digests.sha1}.zip")
            stageDirectory.writeRegularFile(contentFile.fileName, contentSnapshot.bytes)
            stageDirectory.writeRegularFile(platformFile.fileName, platformSnapshot.bytes)

            val catalogFile = stage.resolve("grounds-resourcepacks-catalog-${inputs.version}.jar")
            val catalogPath = validateCatalogPath(inputs, catalogJar)
            catalogSource = HeldSourceFile.capture(catalogPath)
            val heldCatalog = requireNotNull(catalogSource)
            verifyCatalogJarBytes(heldCatalog.bytes)
            stageDirectory.writeRegularFile(catalogFile.fileName, heldCatalog.bytes)
            hooks.afterCatalogCopy(catalogPath, catalogFile)
            val catalogDigest = heldCatalog.digests
            val manifest =
                manifest(
                    inputs,
                    catalogFile,
                    catalogDigest,
                    contentFile,
                    contentSnapshot.digests,
                    platformFile,
                    platformSnapshot.digests,
                )
            val manifestFile = stage.resolve("manifest.json")
            val manifestBytes = PackSetManifestJson.encode(manifest)
            stageDirectory.writeRegularFile(manifestFile.fileName, manifestBytes)
            hooks.afterStageWrite()
            hooks.beforeManifestValidation(manifestFile)
            validateManifest(manifestBytes, catalogFile, contentFile, platformFile)

            val expectedNames =
                setOf(
                    contentFile.fileName.toString(),
                    platformFile.fileName.toString(),
                    catalogFile.fileName.toString(),
                    manifestFile.fileName.toString(),
                )
            hooks.beforePrePublishVerification(stageDirectory.path)
            val prepublish = stageDirectory.snapshot(expectedNames)
            validateManifest(
                prepublish.files.getValue("manifest.json").bytes,
                stage.resolve(catalogFile.fileName),
                stage.resolve(contentFile.fileName),
                stage.resolve(platformFile.fileName),
            )
            val validatedPrepublish = stageDirectory.snapshot(expectedNames)
            if (!prepublish.sameBytesDigestsAndIdentities(validatedPrepublish)) {
                throw IOException("Release staging bytes changed during manifest validation.")
            }

            val artifacts =
                releaseArtifacts(
                    output,
                    contentFile,
                    platformFile,
                    catalogFile,
                    validatedPrepublish,
                )
            stageDirectory.publish(output.fileName, validatedPrepublish, hooks, hooks.rename) {
                if (!stageDirectory.displayParentStillHeldIdentity()) {
                    throw IOException("Release output parent identity changed before commit.")
                }
                immutableInputs.verifyUnchanged()
                heldCatalog.verifyUnchanged()
            }
            committed = true
            runPostCommitDiagnostic { hooks.afterRenameBeforeOutputOpen(output) }
            runPostCommitDiagnostic { hooks.afterOutputOpenedBeforeVerification(output) }
            return artifacts
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            closeResource(committed, primaryFailure) { hooks.beforeResourceClose(committed) }
            closeResource(committed, primaryFailure) { catalogSource?.close() }
            closeResource(committed, primaryFailure) { sourceInputs?.close() }
            closeResource(committed, primaryFailure) { scratchDirectory?.close() }
            closeResource(committed, primaryFailure) { stageDirectory.close() }
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
        require(raw == raw.normalize()) { "Release output path must be normalized." }
        val output = raw
        require(output.parent != null && output != output.root && output.fileName != null) {
            "Release output path is unsafe."
        }
        return output
    }

    private fun validateCatalogPath(inputs: ReleaseInputs, configured: Path): Path {
        require(configured == configured.normalize()) { "Catalog JAR path must be normalized." }
        val jar = HeldSourceFile.normalize(configured)
        require(jar.fileName.toString() == "resourcepacks-catalog-${inputs.version}.jar") {
            "Catalog JAR filename/version mismatch."
        }
        return jar
    }

    private fun verifyCatalogJarBytes(bytes: ByteArray) {
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
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entries.put(entry.name, archive.readAllBytes()) != null) {
                        throw IOException("Catalog JAR contains a duplicate entry: ${entry.name}")
                    }
                }
            }
            if (entries.keys != expectedEntries) {
                throw IOException("Catalog JAR entry set does not match the compiled catalog.")
            }
            val loader = GroundsAssetCatalog::class.java.classLoader
            classEntries.forEach { name ->
                val expected =
                    loader.getResourceAsStream(name)?.use { it.readAllBytes() }
                        ?: throw IOException("Compiled catalog class is unavailable: $name")
                if (!expected.contentEquals(entries.getValue(name))) {
                    throw IOException("Catalog JAR bytes are stale: $name")
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Catalog JAR cannot be validated.", failure)
        }
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
        contentDigest: ArtifactDigests,
        platformFile: Path,
        platformDigest: ArtifactDigests,
    ): PackSetManifest {
        fun pack(order: Int, role: PackRole, file: Path, digest: ArtifactDigests): PackManifest {
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
                pack(0, PackRole.CONTENT, contentFile, contentDigest),
                pack(1, PackRole.PLATFORM, platformFile, platformDigest),
            ),
            ProvenanceManifest(
                "groundsgg/resourcepacks",
                inputs.provenanceCommit,
                inputs.provenanceTag,
            ),
        )
    }

    private fun requireComposerDigest(pack: BuiltPhysicalPackBytes, snapshot: SecureFileSnapshot) {
        if (snapshot.digests != pack.digests) {
            throw IOException("Scratch pack bytes differ from the composer result: ${pack.pack.id}")
        }
    }

    private inline fun runPostCommitDiagnostic(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // The rename already committed exact prevalidated bytes. Diagnostics are best effort.
        }
    }

    private inline fun closeResource(
        committed: Boolean,
        primaryFailure: Throwable?,
        close: () -> Unit,
    ) {
        try {
            close()
        } catch (failure: Throwable) {
            if (!committed) primaryFailure?.addSuppressed(failure)
        }
    }
}

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
    val afterOwnedDirectoryIdentityCapturedBeforeParentValidation: (stage: Path) -> Unit = {},
    val afterOwnedDirectoryCreatedBeforeComposition: (stage: Path) -> Unit = {},
    val beforeComposition: () -> Unit = {},
    val afterComposeBeforePackWrite: (pack: PhysicalPack, pending: Path) -> Unit = { _, _ -> },
    val afterComposition: () -> Unit = {},
    val afterCatalogCopy: (source: Path, staged: Path) -> Unit = { _, _ -> },
    val afterStageWrite: () -> Unit = {},
    val beforeManifestValidation: (manifest: Path) -> Unit = {},
    val beforePrePublishVerification: (stage: Path) -> Unit = {},
    val afterArtifactHashFirstChunk: (artifact: Path) -> Unit = {},
    val immediatelyBeforeRename: (stage: Path) -> Unit = {},
    val afterPreRenameIdentityVerified: (stage: Path) -> Unit = {},
    val afterRenameBeforeOutputOpen: (output: Path) -> Unit = {},
    val afterOutputOpenedBeforeVerification: (output: Path) -> Unit = {},
    val beforeResourceClose: (committed: Boolean) -> Unit = {},
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
