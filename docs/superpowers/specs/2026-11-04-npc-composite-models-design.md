# NPC bone composites

## Intent and scope

Reduce decorative display entities while retaining all authored geometry, UVs,
materials, pivots, signed scales, bone hierarchy, clips, head tracking and gameplay
bounds. This is the next approved optimization backlog item (N01). The user has
requested execution; routine implementation decisions remain with the implementer.

## Approach

Choose Minecraft 26.1+ `minecraft:composite` with per-child matrix transformations.
BetterModel's ModelManagerImpl also combines native models with composites; our
matrix conversion is Grounds-specific. Reject direct native element rotations for
this iteration: they also change baked face directions/shading. Keep every existing
rigid leaf model, texture, sound and leaf item definition byte-for-byte.

An explicit `--geometry-mode composite` / `-PnpcGeometryMode=composite` build option
groups existing rigid parts by bone (including null/root), without merging bones.
The default remains `rigid`, with exactly the current output bytes. Singletons keep
their existing part data. Multi-part groups get a new content-addressed item model,
and one existing-schema part with identity rotation/scale. Its anchor is the
bounds midpoint of the original leaf centers, relative to the bone origin; subtract
the representable anchor from each child matrix.
No runtime sampling/API, visibility, interaction, collision or interpolation-rate
change. Full consolidation would reduce the licensed platform from 471 displays to 151,
but is not qualified: 37 bone groups increase interpolation error beyond the gate
across animation, head movement and 0.5–2.0 growth/shrink transitions.
A separately supplied eligibility policy keeps those groups rigid, producing 348
displays while preserving all 765 cubes. Policy entries bind the exact compiler
SHA and order-preserving source serialization SHA; unknown/stale policies fail.
Explicit JSON null is rejected. The no-policy composite mode is for qualification;
arbitrary sources do not acquire a visual-quality guarantee from finite matrices.

For a leaf whose existing bone-relative matrix is
L=T(pivot-origin) Rz Ry Rx T(center-pivot) S, emit the row-major matrix
A=T(.5,.5,.5) L T(-.5,-.5,-.5), then subtract the shared anchor.
Compose L before adding the half-block offsets to preserve cancelling large pivots. The extra conjugation is required because the
actual 26.3 ItemTransform centers native vertices before applying the submodel
matrix. The official local client codec has been executed and confirms row-major
matrix decoding. Preserve negative scales in A, with the native PoseStack normal
matrix path; do not mirror/reorder vertices or UVs. Matrix values must be finite.
Reject/retain old parts on non-representable inputs rather than emitting corrupt
resources. Root identity hashes include child model IDs and all matrix values.

## Pack contract

Composite renderer item IDs need not have a native model with the same ID. The
catalogue still enumerates all file digests. NpcBundle must check every renderer
item exists and obtain native implementation paths from that digest contract.
During capture, validate the shallow supported model/composite graph: every native
leaf exists and belongs to the catalogue asset's collection/character, all matrices
remain finite affine 4x4 matrices after float conversion, duplicate leaf use and
unreferenced native files fail closed. Legacy leaf item definitions must still
self-reference. Preserve source snapshot/digest protections.
No placeholder native meshes, relaxed missing-file guard or library publication.

## Verification and integration

Unit tests first: default byte identity, differently rotated/pivoted/mirrored cubes,
independent bones, singletons, graph identities, missing/duplicate references and
nonfinite matrices. Run both geometry modes and all licensed product/runtime/Host
checks. Use the actual 26.3 transformation codec, ItemTransform and PoseStack to
compare leaf positions and normal transforms, including reflections. Compare old
and new sampled geometry over all clips/head/root scales with explicit float-error
bounds; packet bits cannot be identical because the entity partition changes.
Endpoint vertex error must be at most 0.00001 blocks in model-relative space.
Measure actual client TRSR interpolation at 0.25/0.5/0.75 of 200/250/500ms updates.
Report changes from the old chord interpolation and compare error against the
authored intermediate pose, including head turns and scaling. Test both 0.5→2.0 and 2.0→0.5 metadata
scale transitions against independently sampled authored intermediate poses,
including different start phases and native client interpolation fractions. Do not qualify a
group that increases authored-curve error by more than 0.01 model-space blocks
at any sampled vertex (normalize by the current uniform personal scale);
retain that group rigid or hold the candidate. Treat undefined normals under
one-axis/all-axis zero scale as explicit classifications, never NaN equality.

A separate Host matrix correction was evaluated and held: although its 208 tests
and endpoint checks pass, a native accepted-vs-candidate comparison worsens one
Arcane Artificer interpolation interval by 0.505 blocks. It is excluded from this
rollout. The composite catalogue is instead qualified with the unchanged accepted
Host. Its rank-deficient Arcane group remains rigid: existing undefined vertices
and authored-curve inaccuracies are explicitly retained, with no new undefined
vertex classifications. Synthetic globally zero-axis scenes remain outside this
candidate's qualification (Scene requires positive scale); this work does not
claim a general singular-matrix fix. Real authored clips and positive Scene
root/local scales are compared to the accepted runtime and independent raw poses.

Freeze an exact Stage-derived Host/pack pair; retain every unrelated jar entry and
all existing Stage feature pins. Qualify with owned 20/60-client tests, movement,
readmission and cold admission, including bytes, allocations, CPU and adverse tick
windows. Client interpolation, lighting and native appearance still need a fresh
Stage test. Do not activate a host with the old addon or reuse old acceptance.
Private licensed artifacts only; no Production, release or Maven publication.

Primary sources: https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1
and https://github.com/toxicity188/BetterModel/blob/v3/core/src/main/kotlin/kr/toxicity/model/manager/ModelManagerImpl.kt
