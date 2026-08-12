package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackFormat
import gg.grounds.resourcepack.api.PackLimits
import java.util.UUID

internal object PackSetConstants {
    const val FORMAT = 88
    val packFormat = PackFormat(FORMAT)

    val contentUuid: UUID = UUID.fromString("44591d5b-71f5-5c2a-a5b2-d3ee7be47e53")
    val platformUuid: UUID = UUID.fromString("8da7cffe-bb04-55e0-9868-7789ce5de362")

    val contentLimits = PackLimits(50_000, 512L * 1024 * 1024, 128L * 1024 * 1024)
    val platformLimits = PackLimits(4_096, 64L * 1024 * 1024, 16L * 1024 * 1024)
}
