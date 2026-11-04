# NPC bone composite implementation plan

> **For agentic workers:** use superpowers:executing-plans in the existing isolated worktree; obtain independent final review.

**Goal:** Reduce decorative NPC displays through an opt-in, geometry-preserving compiler mode.

**Architecture:** Preserve rigid leaf models, compose their bone-relative matrices
inside a modern item model, and extend the digest-bound NPC pack reference check.
Current runtime schema/sampling remains unchanged.

**Tech Stack:** Python compiler/unittest, Kotlin/Gradle product validation, official
26.3 Java client geometry classes, existing native Host/client load harness.

**Spec:** `docs/superpowers/specs/2026-11-04-npc-composite-models-design.md`

## Global Constraints

- Default rigid mode byte-identical; composite mode explicitly enabled.
- Preserve leaf bytes, signed scale, UVs, bones, clips, bounds, head layer and gameplay.
- Row-major child matrix A=T(.5)L T(-.5); fresh content-addressed roots.
- No Production, release, Maven publication or reuse of prior native acceptance.

## Review Focus

- Client centering and 180-degree item correction cannot be applied twice.
- Negative determinant transforms retain winding, normals, texture orientation and light behavior.
- Root-level parts and singleton bones remain correct through nonuniform and zero scaling.
- New roots cannot be shadowed by old packs; missing, duplicated and nonfinite graph data fail closed.
- Resource reload/build modes invalidate Gradle outputs; Stage host/addon remain an exact matched pair.

### Task 1: Compiler and secure pack graph

**Files:** `tools/compile_npc_models.py`, new `tools/test_npc_composites.py`,
`build.gradle.kts`, `resourcepacks-product/.../NpcBundle.kt` and its tests.

**Interfaces:** optional `geometry_mode='rigid'` on compile_model/compile_archives;
CLI `--geometry-mode rigid|composite` plus optional `--composite-policy`; Gradle
properties `npcGeometryMode` and `npcCompositePolicy`. Policy tracks file contents.
Bounds-center roots preserve fixed geometry; source/compiler-bound admission
retains 37 disqualified bone groups as rigid parts. Actual licensed count 348.
The initial 276-display prototype is rejected by large dynamic scaling tests.

- [x] Add failing compiler tests for two pivoted/rotated/mirrored leaves sharing one bone; default and singleton behavior retained.
- [x] Implement matrix composition and content-addressed shallow composites without touching leaf files.
- [x] Add failing pack-reference tests; implement renderer item/native leaf distinction with strict graph validation.
- [x] Run both compiler modes, formatter, licensed product/runtime tests and independent client codec/geometry checks.
- [x] Commit and independently review source, tests, graph security and error bounds.

### Task 2: Matched artifact qualification and Stage

**Files:** qualification report/backlog; exact private Host/addon artifacts and Stage image pin.

**Interfaces:** unchanged schema1 catalogue; existing pack-digests contract and native Scene.

- [x] Build exact accepted Host with composite catalogue, prove unchanged assets/clips/bounds and complete geometry.
- [x] Preserve unrelated accepted Stage entries, test exact jar/pack pair and reject mismatched old addon.
- [ ] Compare owned real-client steady/movement/readmission/cold admission; retain adverse windows.
- [ ] Integrate only qualified source/pair, preserve concurrent deployment pins; verify Stage and request fresh native acceptance.

Independent review found and reproduced five issues: large-pivot cancellation,
actual captured-byte digest validation, canonical JSON paths, explicit null policy
fail-open, and source-order hashing. Fixes have focused RED/GREEN evidence.
Final both-mode suites, exact artifact tests, connected tests and rollout remain
qualification gates. Dense and heldout native interpolation retain no violations
for the filtered artifact; the original full consolidation remains rejected.

The new Host SVD algorithm remains isolated: the native compatibility probe found
a 0.505-block interpolation regression in Arcane Artificer. The Stage candidate
therefore changes only 16 catalogue resources in the accepted Host JAR. Both
compiler modes pass 160 Product and 8 runtime tests; default generated output is
byte-identical. Native dense/heldout tests with the accepted math pass the new
error gates, retaining the baseline singular group and existing undefined values.

Final native qualification preserves 348 displays, all 765 cubes and all 983
original files. Dense/static heldout and grow/shrink heldout each pass, including
0.5→2.0 and 2.0→0.5 personal scale during animation. Final matched artifact and
connected comparison use this exact filtered catalogue, not the rejected prototype.
