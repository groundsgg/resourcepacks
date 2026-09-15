package gg.grounds.resourcepacks.client

import gg.grounds.resourcepacks.contract.ChannelDocument
import gg.grounds.resourcepacks.contract.ChannelTarget
import gg.grounds.resourcepacks.contract.PackSetChannel
import gg.grounds.resourcepacks.contract.PublicationType

sealed interface PackSetSelection {
    data class Channel(val channel: PackSetChannel) : PackSetSelection

    data class Release(val id: String) : PackSetSelection {
        init {
            ChannelTarget(PublicationType.RELEASE, id)
        }
    }
}

sealed interface ResolvedPackSetTarget {
    data class Channel(val document: ChannelDocument) : ResolvedPackSetTarget

    data class Release(val id: String) : ResolvedPackSetTarget
}
