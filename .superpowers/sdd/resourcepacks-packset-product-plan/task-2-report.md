# Task 2 report — typed catalog public boundary

## Outcome

Implemented the generated catalog version declaration, the four public catalog objects, and the complete final Grounds GUI `Theme` declaration. The bootstrap catalog contains no assets and exposes immutable Java-compatible collection snapshots.

## TDD evidence

* RED: `./gradlew :resourcepacks-catalog:test --tests 'gg.grounds.resourcepacks.catalog.CatalogApiTest' --tests 'gg.grounds.resourcepacks.catalog.CatalogImmutabilityTest' --tests 'gg.grounds.resourcepacks.catalog.CatalogJavaApiTest'` failed at Kotlin test compilation because the four public objects did not exist.
* GREEN: the same focused suite passed after implementation.
* Final fresh focused runs: `./gradlew --rerun-tasks :resourcepacks-catalog:test` passed twice (each recompiled and reran all catalog tests).

## Boundary gates

* `CatalogBuildInfo.kt` is emitted under `build/generated/sources/catalog/kotlin` solely from root `version.txt`; no Git or environment lookup is used.
* `CatalogAbiManifestTest` loads the built catalog classes and compares every effectively public field, method, and constructor to the checked-in manifest. Its control mutations prove rejection of an extra public owner and an extra public member.
* `CatalogJarBoundaryTest` reads the final catalog JAR and permits only catalog declarations plus its Kotlin metadata; it rejects Jackson and product-class entries.
* Existing resolved-runtime gate remains exercised by `BuildBoundaryTest`; the upstream `scene-format` API transitively brings Jackson at runtime, so the catalog JAR (not the upstream runtime graph) is the correct Jackson product-boundary gate.
* Kotlin and Java tests both attempt to mutate public asset collections and require `UnsupportedOperationException`.

## Publication and consumer evidence

* `./gradlew --no-build-cache :resourcepacks-catalog:publishToMavenLocal` passed.
* A fresh external Gradle project in `/tmp/resourcepacks-catalog-consumer.0bTGNS` resolved `gg.grounds:resourcepacks-catalog:0.0.0` from Maven Local and ran Java and Kotlin consumers using Temurin JDK 25. Kotlin correctly emitted JVM 24 bytecode, matching the repository's locked Kotlin-target policy while resolving the catalog's JVM-25 runtime variant.

## Notes

The JVM emits Gradle native-platform restricted-access warnings under JDK 25 during tests. They are emitted by Gradle's native-platform library, not catalog code, and do not affect test success.
