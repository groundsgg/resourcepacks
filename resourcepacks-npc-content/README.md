# NPC model content

Data-only product artifact containing the existing compiled numeric model index,
model resources and pack digests. It does not provide an evaluator or renderer.

The host supplies these bytes to the standalone NPC core and validates the exact
catalogue, bounds, clips and sound references. Existing resource paths and schema
version 1 remain unchanged.

Default builds contain a neutral empty index. Private builds use the existing
npcSourceDirectory/grounds.npc.sources input. Purchased model payload must never be
published to a Maven repository: verifyNeutralNpcPublication gates publishing.
Sources archives exclude all JSON payload. Keep private content and public engine
release decisions separate.

The existing tagged PackSet release workflow stages exactly the JAR, source JAR,
POM and Gradle module metadata, compares immutable GitHub Packages publication
bytes, then publishes only neutral content. It preserves the old runtime
publication for existing consumers. This module does not activate private NPCs.
