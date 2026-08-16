package gg.grounds.resourcepacks.product

typealias PublicationType = gg.grounds.resourcepacks.contract.PublicationType

data class PublicationIdentity(
    val type: PublicationType,
    val id: String,
    val version: String,
    val commit: String,
)
