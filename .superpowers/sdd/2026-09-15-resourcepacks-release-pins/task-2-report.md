# Task 2 report — immutable-pin client lifecycle

## Outcome

Validated immutable release pins no longer schedule a periodic refresh after a successful
activation or unchanged reuse. Channel sources retain the existing 60-second periodic refresh.
Failures continue through the existing retry path, and source generation, cache/resolver behavior,
and public lifecycle APIs are unchanged.

## Changed files

- `resourcepacks-client/src/main/kotlin/gg/grounds/resourcepacks/client/PackSetClient.kt`
- `resourcepacks-client/src/test/kotlin/gg/grounds/resourcepacks/client/PackSetClientLifecycleTest.kt`
- `README.md`

## TDD evidence

RED, before the production scheduling change:

```text
./gradlew :resourcepacks-client:test --tests '*PackSetClientLifecycleTest'
PackSetClientLifecycleTest > validated pin does not poll when normal refresh time passes() FAILED
expected: <PT2M> but was: <PT1M>
18 tests completed, 1 failed
BUILD FAILED
```

The sandbox could not create the Gradle wrapper lock under the shared Gradle cache, so Gradle
commands were rerun with the approved shared-cache permission.

GREEN after scheduling only channel selections:

```text
./gradlew :resourcepacks-client:test --tests '*PackSetClientLifecycleTest'
BUILD SUCCESSFUL in 2s
9 actionable tasks: 4 executed, 5 up-to-date
```

The final focused lifecycle run, after boundary coverage, was also successful with 22 tests and
zero failures.

## Lifecycle coverage

- Validated pin has one manifest GET, reaches READY with the pin source, does not poll beyond the
  60-second interval, and an explicit refresh completes `Unchanged` without a second GET.
- Channel → pin → different pin → channel cancels/replaces scheduling by selection: both pins
  have no pending periodic task and the returned channel restores the 60-second task.
- A pin that has not validated yet fails through the existing 800ms retry behavior.
- A restart revalidates a persisted pinned cache offline, returns `Unchanged`, reaches READY, and
  schedules no poll. A corrupted persisted manifest fails closed, remains UNAVAILABLE, and retries.
- The existing blocked old-source activation test now uses a release pin before reconfiguration to
  a channel, preserving stale-generation completion and public future/state ordering coverage.
- Existing channel offline/degraded, retry, periodic refresh, coalescing, shutdown, and
  multi-worker ownership tests remain in the lifecycle suite.

## Documentation

README now shows both channel and release construction, describes `snapshot.target` and
`snapshot.publication`, marks legacy channel-only getters as deprecated compatibility APIs, and
documents offline cache reuse/no periodic pin polling. It also states that using these client
sources does not mutate Config Service or activate plugin/config/portal integration.

## Final verification

```text
./gradlew :resourcepacks-client:check
BUILD SUCCESSFUL in 5s

./gradlew check
BUILD SUCCESSFUL in 21s

git diff --check
exit 0
```

The client check reports all client suites green: 91 tests total, including 22 lifecycle tests.
The repository check completed all contract, client, catalog, and product checks successfully.
The full Gradle run emitted the existing JVM restricted-native-access warning from Gradle's native
platform dependency; it did not cause a failure.

## Self-review

- Acceptance: scheduling is guarded solely by `resultSource.selection is PackSetSelection.Channel`;
  successful release paths still run normal completion/listener bookkeeping, manual refresh, disk
  reuse, retries before first validation, and reconfiguration generation checks.
- Ownership/futures: no new asynchronous work or state ownership was introduced; existing retry
  cancellation, source-generation validation, `inFlight` completion, and stale-result handling are
  retained. The release stale-result latch test passes.
- YAGNI: the production change is one existing scheduling decision; no resolver/cache changes,
  selectors, plugin changes, dependencies, or public APIs were added.
- Concerns: none. The task intentionally leaves the deferred constructor/helper surface review to
  final review, as instructed.
