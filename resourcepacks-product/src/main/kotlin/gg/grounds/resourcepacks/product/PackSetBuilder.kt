package gg.grounds.resourcepacks.product

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.nio.file.Path

/**
 * Builds all release bytes in descriptor-owned siblings and publishes one verified directory.
 *
 * The release parent and held sources are a trusted-single-writer namespace from transaction
 * directory creation through the successful no-replace rename. Cooperative builders are serialized
 * with an advisory lock on the held parent fd. An uncooperative same-UID process can ignore that
 * lock; active namespace or source mutation by such a process is outside this API's threat model.
 * Accidental changes and all in-model collisions still fail closed before commit.
 */
internal object PackSetBuilder {
    fun build(inputs: ReleaseInputs): ReleaseArtifacts {
        val catalog = CatalogJarProvider.runtime()
        return buildInternal(inputs, catalog.path, PackSetBuilderHooks(), null, catalog.expectation)
    }

    @JvmSynthetic
    internal fun build(inputs: ReleaseInputs, catalogJar: Path): ReleaseArtifacts =
        build(inputs, catalogJar, PackSetBuilderHooks())

    @JvmSynthetic
    internal fun build(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
    ): ReleaseArtifacts =
        buildInternal(inputs, catalogJar, hooks, null, CatalogJarProvider.expectation())

    @JvmSynthetic
    internal fun build(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
        packs: List<PhysicalPack>,
    ): ReleaseArtifacts =
        buildInternal(inputs, catalogJar, hooks, packs.toList(), CatalogJarProvider.expectation())

    private fun buildInternal(
        inputs: ReleaseInputs,
        catalogJar: Path,
        hooks: PackSetBuilderHooks,
        suppliedPacks: List<PhysicalPack>?,
        expectedCatalog: CatalogArtifactExpectation,
    ): ReleaseArtifacts {
        validateInputs(inputs)
        val output = validateDestination(inputs.outputDirectory)
        val parent =
            output.parent ?: throw IllegalArgumentException("Release output must have a parent.")
        val writerLease = TrustedSingleWriterLease.acquire(parent)
        val stageDirectory =
            try {
                SecureOwnedDirectory.create(
                    parent,
                    ".packset-stage-",
                    hooks.afterOwnedDirectoryIdentityCapturedBeforeParentValidation,
                    output.fileName,
                )
            } catch (failure: Throwable) {
                try {
                    writerLease.close()
                } catch (close: Throwable) {
                    failure.addSuppressed(close)
                }
                throw failure
            }
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
                    scratch,
                    PackComposerHooks(afterComposeBeforeWrite = hooks.afterComposeBeforePackWrite),
                )
            hooks.afterComposition()
            val content = builtPacks.single { it.pack.role == PackRole.CONTENT }
            val platform = builtPacks.single { it.pack.role == PackRole.PLATFORM }
            val contentScratch = content.scratchFile
            val platformScratch = platform.scratchFile
            val contentSnapshot =
                scratch.readRelativeRegularFile(
                    contentScratch,
                    requireNotNull(content.pack.definition.policy.limits.maxArtifactBytes),
                ) {
                    hooks.afterArtifactHashFirstChunk(scratch.stablePath.resolve(contentScratch))
                }
            val platformSnapshot =
                scratch.readRelativeRegularFile(
                    platformScratch,
                    requireNotNull(platform.pack.definition.policy.limits.maxArtifactBytes),
                ) {
                    hooks.afterArtifactHashFirstChunk(scratch.stablePath.resolve(platformScratch))
                }
            requireComposerDigest(content, contentSnapshot)
            requireComposerDigest(platform, platformSnapshot)
            val contentFile =
                stage.resolve(
                    PackSetObjectLayout.content(inputs.publication, content.digests.sha1).fileName
                )
            val platformFile =
                stage.resolve(
                    PackSetObjectLayout.platform(inputs.publication, platform.digests.sha1).fileName
                )
            scratch.openRelativeRegularFile(contentScratch).use { source ->
                stageDirectory.copyRegularFile(
                    contentFile.fileName,
                    source,
                    contentSnapshot.digests,
                    requireNotNull(content.pack.definition.policy.limits.maxArtifactBytes),
                )
            }
            scratch.openRelativeRegularFile(platformScratch).use { source ->
                stageDirectory.copyRegularFile(
                    platformFile.fileName,
                    source,
                    platformSnapshot.digests,
                    requireNotNull(platform.pack.definition.policy.limits.maxArtifactBytes),
                )
            }

            val catalogFile =
                stage.resolve(PackSetObjectLayout.catalog(inputs.publication).fileName)
            val catalogPath = validateCatalogPath(inputs, catalogJar)
            catalogSource = HeldSourceFile.capture(catalogPath, expectedCatalog.size)
            val heldCatalog = requireNotNull(catalogSource)
            if (
                heldCatalog.digests.size != expectedCatalog.size ||
                    heldCatalog.digests.sha256 != expectedCatalog.sha256
            ) {
                throw IOException("Catalog JAR is not the exact current Gradle artifact.")
            }
            stageDirectory.copyRegularFile(
                catalogFile.fileName,
                heldCatalog.regularHandle,
                heldCatalog.digests,
                expectedCatalog.size,
            )
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
            val manifestBytes = CanonicalManifestJson.write(manifest)
            stageDirectory.writeRegularFile(
                manifestFile.fileName,
                manifestBytes,
                MAX_MANIFEST_BYTES,
            )
            hooks.afterStageWrite()
            hooks.beforeManifestValidation(manifestFile)
            validateManifest(manifestBytes, catalogFile, contentFile, platformFile)

            val expectedFiles =
                mapOf(
                    contentFile.fileName.toString() to contentSnapshot.digests,
                    platformFile.fileName.toString() to platformSnapshot.digests,
                    catalogFile.fileName.toString() to heldCatalog.digests,
                    manifestFile.fileName.toString() to ArtifactDigests.fromBytes(manifestBytes),
                )
            hooks.beforePrePublishVerification(stageDirectory.path)
            val prepublish = stageDirectory.snapshot(expectedFiles)
            validateManifest(
                stageDirectory.readRelativeRegularFileBytes(
                    Path.of("manifest.json"),
                    MAX_MANIFEST_BYTES,
                ),
                stage.resolve(catalogFile.fileName),
                stage.resolve(contentFile.fileName),
                stage.resolve(platformFile.fileName),
            )
            val validatedPrepublish = stageDirectory.snapshot(expectedFiles)
            if (!prepublish.sameDigestsAndIdentities(validatedPrepublish)) {
                throw IOException("Release staging bytes changed during manifest validation.")
            }

            val artifacts =
                releaseArtifacts(
                    inputs.publication,
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
            closeResource(committed, primaryFailure) { writerLease.close() }
        }
    }

    private fun validateInputs(inputs: ReleaseInputs) {
        require(inputs.version == GroundsAssetCatalog.catalog.version) {
            "Version must equal the compiled catalog version."
        }
        require(Regex("[0-9a-f]{40}").matches(inputs.provenanceCommit)) {
            "Commit must be lowercase 40-hex."
        }
        PackSetObjectLayout.manifest(inputs.publication)
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

    private fun validateManifest(bytes: ByteArray, catalog: Path, content: Path, platform: Path) {
        val validation = gg.grounds.resourcepacks.contract.PackSetContractJson.decodeManifest(bytes)
        if (validation !is gg.grounds.resourcepacks.contract.ManifestDecodeResult.Success)
            throw IOException("Generated manifest failed validation: $validation")
    }

    private fun releaseArtifacts(
        publication: PublicationIdentity,
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
            publication,
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
    ): gg.grounds.resourcepacks.contract.PackSetManifest {
        fun pack(
            order: Int,
            role: PackRole,
            file: Path,
            digest: ArtifactDigests,
        ): gg.grounds.resourcepacks.contract.ManifestPack {
            val id = if (role == PackRole.CONTENT) "grounds-content" else "grounds-platform"
            val uuid =
                if (role == PackRole.CONTENT) PackSetConstants.contentUuid
                else PackSetConstants.platformUuid
            val location =
                if (role == PackRole.CONTENT)
                    PackSetObjectLayout.content(inputs.publication, digest.sha1)
                else PackSetObjectLayout.platform(inputs.publication, digest.sha1)
            return gg.grounds.resourcepacks.contract.ManifestPack(
                order,
                role.name.lowercase(),
                id,
                uuid,
                true,
                location.publicUrl,
                digest.sha1,
                digest.sha256,
                digest.size,
                PackSetConstants.FORMAT,
            )
        }
        return gg.grounds.resourcepacks.contract.PackSetManifest(
            2,
            PackSetObjectLayout.PACK_SET_ID,
            gg.grounds.resourcepacks.contract.ManifestPublication(
                inputs.publication.type,
                inputs.publication.id,
            ),
            inputs.version,
            gg.grounds.resourcepacks.contract.ManifestMinecraft("26.2", PackSetConstants.FORMAT),
            gg.grounds.resourcepacks.contract.ManifestCatalog(
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
            gg.grounds.resourcepacks.contract.ManifestProvenance(
                "groundsgg/resourcepacks",
                inputs.provenanceCommit,
            ),
        )
    }

    private fun requireComposerDigest(pack: BuiltReleasePack, snapshot: SecureFileSnapshot) {
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

    private const val MAX_MANIFEST_BYTES = 64L * 1024
}

/** Build wiring locates the exact catalog artifact; the public CLI never accepts this path. */
internal object CatalogJarProvider {
    fun runtime(): CatalogArtifactSource =
        CatalogArtifactSource(
            Path.of(
                gg.grounds.resourcepacks.catalog.GroundsAssetCatalog::class
                    .java
                    .protectionDomain
                    .codeSource
                    .location
                    .toURI()
            ),
            expectation(),
        )

    fun expectation(): CatalogArtifactExpectation =
        CatalogArtifactExpectation(
            GeneratedCatalogArtifactExpectation.SIZE,
            GeneratedCatalogArtifactExpectation.SHA256,
        )
}

internal data class CatalogArtifactSource(
    val path: Path,
    val expectation: CatalogArtifactExpectation,
)

internal data class CatalogArtifactExpectation(val size: Long, val sha256: String)

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
