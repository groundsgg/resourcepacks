package gg.grounds.resourcepacks.product

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
            require(flag in setOf("--version", "--commit", "--tag", "--output")) {
                "Unknown argument: $flag"
            }
            require(index + 1 < args.size) { "Missing value for $flag" }
            val value = args[index + 1]
            require(value.isNotEmpty() && !value.startsWith("--")) { "Missing value for $flag" }
            require(values.put(flag, value) == null) { "Duplicate argument: $flag" }
            index += 2
        }
        require(values.keys == setOf("--version", "--commit", "--tag", "--output")) {
            "Missing required release arguments."
        }
        return ReleaseInputs(
            values.getValue("--version"),
            values.getValue("--commit"),
            values.getValue("--tag"),
            Path.of(values.getValue("--output")),
            Path.of(
                System.getProperty("grounds.catalog.jar")
                    ?: error("Catalog JAR property is required.")
            ),
        )
    }
}
