# NPC resource isolation hotfix

Stage users reported missing and displaced limbs/body pieces on nearly every NPC, including
at close range. The grouped addon and the older global Edge49 content pack use the same
471 part paths.185 of those native models have different geometry. If the global pack wins,
294 cubes disappear and the surviving original cubes use the grouped part centers.

The fix gives each PNG a SHA256-qualified texture path and gives each final native mesh and
its item-model definition a SHA256-qualified part path. References follow the new identities.
The ordinal remains in each part ID, preserving uniqueness even for equal meshes. Existing
legacy keys are still accepted by the strict product validators. No renderer, animation,
asset catalog identity, logical body key, source model, pose or grouping behavior changes.

Verification:

- The two new compiler regressions fail before the fix: an old global pack overwrites a
  grouped model, and mesh/material changes incorrectly retain their resource identities.
-15 Python compiler tests and327 JVM/product tests pass, including hashed-path parity.
- All six orders of actual Edge49 content/platform packs plus the new addon resolve15models,
  471 displays and765 textured cubes. Every mesh/sprite key matches the content digest.
- Independent original-versus-fixed cube-corner/material/UV audit retains765 cubes with
  maximum error2.22e-16 blocks.984 client-pack files contain only the same already published
  geometry, PNG and OGG content with renamed references; purchased authoring data stays private.
- Released Host2.15 is rebuilt only with the private data variant. All78 production classes
  are byte-identical to public2.15.0,159 tests pass with no skips, and the real Map9/e7 loader
  plus983 asset digests and both full ZIP hashes accept the exact candidate.

The paired addon/private Host and Editor variants must activate together through GitOps.
Preserve global Edge49/fonts, Map9, native SOLID and existing buildserver workspace recovery.
The unshipped follow-up performance batch is intentionally excluded from this hotfix.
