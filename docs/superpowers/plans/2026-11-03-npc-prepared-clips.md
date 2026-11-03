# Prepared NPC Clips Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline; primary implements, one fresh final review.

**Goal:** Remove repeated channel lookup and constant-track matrix construction without changing animations.
**Architecture:** Immutable per-model prepared clip arrays, bounded constant local matrices, unchanged dynamic sampler and owned result matrices.
**Tech Stack:** Kotlin/JDK25, Resourcepacks NPC runtime1.3, Minestom Lobby, existing frozen Java/protocol777 fixtures.
**Spec:** docs/superpowers/specs/2026-11-03-npc-prepared-clips-design.md

## Global Constraints

Preserve exact numerical operation order; no sampling quantization or cursor state; retain current SOLID, range/rates, lighting, catalogue/addon and independent Announcements. No Production/release/Maven publication. Cache at most1024constant bone/clips per model with correct fallback.

## Review Focus

- Constant tracked head with changing look: head descendants follow while body remains stable.
- Mutating a returned matrix cannot poison another result/model/cache.
- Two identical nonzero keys retain floating interpolation, never incorrectly classified constant.
- Unknown/null clips, before-first/after-last, loops/seek/switch preserve existing behavior.
- Cache overflow falls back to normal evaluation and does not allocate unbounded matrices.

### Task1: characterize and prepare clips

Files: resourcepacks-npc-runtime/src/main/kotlin/gg/grounds/resourcepacks/npc/NpcModel.kt and PreparedNpcClip.kt; tests/NpcPreparedClipTest.kt.
- [x] Baseline runtime tests, then add literal head/child and result ownership/rounding characterization. Add12bone/50part real constant-track allocation test; observe current behavior fails20KB/pose budget after1000warmups.
- [x] Prepare bone offsets/channel arrays; cache constant local transforms with head prefix/scale and1024limit. Existing dynamic interpolation/math remains verbatim. Unknown/null clips use bind path.
- [x] Full runtime tests/formatting and commit; record measured allocation and memory/preparation cost. Behavioral refactor tests may already pass old code; no fabricated RED for an internal cache.

### Task2: exact integration and measurement
- [ ] Build private licensed Host variant using existing composite init, all Host/runtime tests, freeze. Unchanged723456state golden/addon983files; verify only intended runtime class entries change.
- [ ] Exact currentStage base plus reviewed runtime classes (precheck original bytes), preserve every other physical entry/duplicate, exact-stage tests/classorigin proof. Measure startup/component and20/60connected motion/readmission, retaining all adverse windows.
- [ ] Fresh final source/artifact review; address blockers and record minor deferrals.

### Task3: qualified integration and Stage
- [ ] SourcePR and exact-headCI; update backlog and full report. If qualified, private image hash/config/visibility checks and narrow Stage pin PR on actual activebase. Verify parsed non-image/Production equality, tests and exact-headCI, source/deploy merge, guarded current Stage revision update.
- [ ] Both actual ready images/prelitmap/catalogue/SOLID/ownership proof, optional native-client test, owned test cleanup and clean worktrees; final handoff.

## Ledger

Ruling: use stateless prepared constants/channel bindings first because current data has median5keys/track but110constant tracked bone/clips. Cursor implementation stays deferred until measured search cost warrants its lifecycle complexity.
Ruling: allocation budget is an external resource contract, not a cache implementation test; other semantic characterization is expected to pass baseline and candidate.

Task1: baseline original8tests pass. New4test characterization:3pass baseline; allocation contract RED30544bytes/pose versus20000budget. Candidate full12tests pass,0skips/failures/errors. Initial non-escalated Gradle could not load native cache; retry with ordinary authorized cache access succeeded. This was a harness permission failure, not a product failure.
Ruling: cache only0/1-key channels, never collapse equal multi-key values; preserve prior NaN-time behavior by falling through to the original sampler. Constant head caches retain prefix/scale privately; all public results remain owned.1024constant budget tested through5clips×256bones and correct late-clip results.

Review fix: valid finite vectors can overflow only when an unused clip is played. Eager preparation initially moved this failure into model construction. Two parsed-model regressions reproduce RED (rotation and position); finite-sum eligibility leaves these bones dynamic and preserves lazy errors. Full14runtime tests now pass with0failures/errors/skips. Head test now includes nonuniform(2,1,1)scale to pin Look*Scale order; explicit prepared-constant count pins1024resource budget (large-model output alone did not prove the cap). No other new ABI/ownership/thread-safety finding.
Ruling: cache-count assertion verifies the explicit resource budget rather than mirroring interpolation logic; all returned pose geometry is still tested independently. No new public method/version or Maven publication is needed for this private Stage runtime variant; normal future Maven release adoption remains separate.
