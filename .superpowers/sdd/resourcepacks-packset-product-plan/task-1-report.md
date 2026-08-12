# Task 1 report — repository foundation and version source

## Scope delivered

- Added the locked two-project Gradle shape: `resourcepacks-catalog` and `resourcepacks-product`.
- Added Gradle 9.5.1 wrapper, Kotlin 2.2.20, Grounds conventions 0.8.0, JDK 25 toolchain, and Kotlin JVM target 24.
- Added ASCII SemVer validation and the bootstrap `version.txt` value `0.0.0`.
- Added catalog Maven publication `gg.grounds:resourcepacks-catalog:0.0.0`; the product has no publishing plugin.
- Added declared dependencies, dependency locking, UTF-8 JVM encoding, and reproducible archives (sorted entries and no timestamps).
- Added TestKit boundary tests for project shape, catalog coordinate, product non-publication, product-to-catalog linkage, and forbidden catalog runtime-graph families.

## RED evidence

Before build files existed, the failing test command was:

```text
/home/lukas/grounds/library-gui/gradlew -p . test --no-daemon
```

It failed as expected because the worktree had neither `settings.gradle.kts` nor `build.gradle.kts`:

```text
Directory '.../feat-packset-product' does not contain a Gradle build.
```

The boundary-test production breaks are explicit: an added project, wrong catalog coordinate/version, any product publication task, missing product-to-catalog dependency, or a prohibited catalog runtime dependency causes a failure.

## GREEN evidence

Fresh verification commands and results:

```text
./gradlew --rerun-tasks test --no-daemon
BUILD SUCCESSFUL in 20s (10 actionable tasks)

./gradlew --rerun-tasks test --no-daemon
BUILD SUCCESSFUL in 17s (10 actionable tasks)

./gradlew --no-build-cache clean build --no-daemon
BUILD SUCCESSFUL in 21s (24 actionable tasks)

./gradlew :resourcepacks-catalog:publishToMavenLocal --no-daemon
BUILD SUCCESSFUL in 5s (6 actionable tasks)
```

The JDK emitted its known native-access warning while Gradle loaded native-platform; no task failed.

## Files

- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `version.txt`, `.gitignore`, `README.md`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- `resourcepacks-catalog/build.gradle.kts`
- `resourcepacks-product/build.gradle.kts`
- `resourcepacks-catalog/src/test/kotlin/gg/grounds/resourcepacks/catalog/BuildBoundaryTest.kt`
- `resourcepacks-product/src/test/kotlin/gg/grounds/resourcepacks/product/BuildBoundaryTest.kt`

## Self-review

- `git diff --check` is clean.
- Catalog runtime graph was inspected and contains only the intended `library-gui` and `scene-format` families plus their non-platform transitive libraries; boundary tests reject Paper, Bukkit, Minestom, Velocity, Grounds config, portal, test frameworks, and the product builder.
- Product applies only `application`; it does not apply `maven-publish` and its task list is tested to contain no publishing/POM tasks.
- Root version is read from the exact trimmed ASCII SemVer in `version.txt`; the build rejects invalid values.

## Open point

Creating the private `groundsgg/resourcepacks` GitHub remote remains controller-owned and pending because the `gh` login is expired. It was intentionally not attempted here.
