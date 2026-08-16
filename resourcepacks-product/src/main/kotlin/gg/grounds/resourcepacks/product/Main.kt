package gg.grounds.resourcepacks.product

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        PackSetBuilder.build(ReleaseCli.parse(args))
    } catch (failure: Exception) {
        System.err.println("packset: ${failure.message ?: "release build failed."}")
        exitProcess(2)
    }
}

internal object ReleaseCli {
    fun parse(args: Array<String>): ReleaseInputs {
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val flag = args[index]
            require(
                flag in
                    setOf(
                        "--publication-type",
                        "--publication-id",
                        "--version",
                        "--commit",
                        "--output",
                    )
            ) {
                "Unknown argument: $flag"
            }
            require(index + 1 < args.size) { "Missing value for $flag" }
            val value = args[index + 1]
            require(value.isNotEmpty() && !value.startsWith("--")) { "Missing value for $flag" }
            require(values.put(flag, value) == null) { "Duplicate argument: $flag" }
            index += 2
        }
        require(
            values.keys ==
                setOf("--publication-type", "--publication-id", "--version", "--commit", "--output")
        ) {
            "Missing required release arguments."
        }
        val type =
            when (values.getValue("--publication-type")) {
                "release" -> PublicationType.RELEASE
                "build" -> PublicationType.BUILD
                else -> throw IllegalArgumentException("Publication type must be release or build.")
            }
        val output = Path.of(values.getValue("--output"))
        require(output.isAbsolute && output == output.normalize() && !Files.exists(output)) {
            "Output must be an absent absolute normalized directory."
        }
        val publication =
            PublicationIdentity(
                type,
                values.getValue("--publication-id"),
                values.getValue("--version"),
                values.getValue("--commit"),
            )
        PackSetObjectLayout.manifest(publication)
        return ReleaseInputs(publication, output)
    }
}
