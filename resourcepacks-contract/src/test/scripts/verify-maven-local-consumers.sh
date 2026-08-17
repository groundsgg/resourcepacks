#!/usr/bin/env bash
set -euo pipefail

source_root=${1:-$(git rev-parse --show-toplevel)}
gradle_command=${2:-"$source_root/gradlew"}
version=$(tr -d '\n' < "$source_root/version.txt")
scratch=$(mktemp -d /tmp/resourcepacks-contract-consumers-XXXXXX)
trap 'rm -rf -- "$scratch"' EXIT

"$gradle_command" -p "$source_root" --offline :resourcepacks-contract:publishToMavenLocal

mkdir -p "$scratch/src/main/java/consumer" "$scratch/src/main/kotlin/consumer"
cat > "$scratch/settings.gradle.kts" <<'EOF'
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenLocal {
      content {
        includeGroup("gg.grounds")
      }
    }
    mavenCentral { content { excludeGroup("gg.grounds") } }
  }
}
rootProject.name = "resourcepacks-contract-consumers"
EOF
cat > "$scratch/build.gradle.kts" <<EOF
import org.gradle.api.JavaVersion
import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { kotlin("jvm") version "2.2.20" }

dependencies { implementation("gg.grounds:resourcepacks-contract:$version") }

kotlin { jvmToolchain(25) }

java {
  sourceCompatibility = JavaVersion.VERSION_24
  targetCompatibility = JavaVersion.VERSION_24
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions.jvmTarget.set(JvmTarget.JVM_24)
}

configurations.configureEach {
  attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
}

tasks.register("verifyContractComesFromMavenLocal") {
  dependsOn(tasks.named("compileJava"), tasks.named("compileKotlin"))
  doLast {
    val selected = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
    check(
      selected.any {
        it.moduleVersion?.let { module ->
          module.group == "gg.grounds" &&
            module.name == "resourcepacks-contract" &&
            module.version == "$version"
        } == true
      }
    ) { "The Maven Local resourcepacks-contract publication was not selected." }
    check(
      selected.any {
        it.moduleVersion?.let { module ->
          module.group == "tools.jackson.core" && module.name == "jackson-core"
        } == true
      }
    ) { "Jackson must resolve from Maven Central because Maven Local excludes public groups." }
  }
}
EOF
cat > "$scratch/src/main/java/consumer/JavaConsumer.java" <<'EOF'
package consumer;

import gg.grounds.resourcepacks.contract.ManifestDecodeResult;
import gg.grounds.resourcepacks.contract.PackSetContractJson;
import gg.grounds.resourcepacks.contract.ChannelDecodeResult;

public final class JavaConsumer {
  public static ManifestDecodeResult decode(byte[] bytes) {
    return PackSetContractJson.INSTANCE.decodeManifest(bytes);
  }
  public static ChannelDecodeResult decodeChannel(byte[] bytes) {
    return PackSetContractJson.INSTANCE.decodeChannel(bytes);
  }
}
EOF
cat > "$scratch/src/main/kotlin/consumer/KotlinConsumer.kt" <<'EOF'
package consumer

import gg.grounds.resourcepacks.contract.ManifestDecodeResult
import gg.grounds.resourcepacks.contract.PackSetContractJson
import gg.grounds.resourcepacks.contract.ChannelDecodeResult

fun decode(bytes: ByteArray): ManifestDecodeResult = PackSetContractJson.decodeManifest(bytes)
fun decodeChannel(bytes: ByteArray): ChannelDecodeResult = PackSetContractJson.decodeChannel(bytes)
EOF

"$gradle_command" -p "$scratch" clean verifyContractComesFromMavenLocal
echo "Maven Local JDK25 Java/Kotlin consumers verified for $version"
