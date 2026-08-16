# Testing conventions

## Purpose

These conventions keep the client-rewrite suite deterministic, reproducible,
and portable across implementations.

## Test locations

| Test type | Location | Naming |
|---|---|---|
| JVM unit and contract tests | `app/src/test/` | `*Test.kt` |
| Shared deterministic fakes and fixtures | `app/src/test/**/testsupport/` | Descriptive fixture name |
| Robolectric tests | `app/src/test/` | `*RobolectricTest.kt` |
| Android instrumented tests | `app/src/androidTest/` | `*InstrumentedTest.kt` |
| Coverage-tool tests | `tools/coverage/` | `test_*.py` |
| Interoperability fixtures | `app/src/test/resources/contracts/` | Protocol and version in filename |

## Required behavior

- Tests must not use arbitrary sleeps. Advance a fake clock or coroutine test
  scheduler instead.
- Tests must not require public relays, internet access, Bluetooth hardware, or a
  user's persisted data.
- Time, randomness, dispatchers, storage, and transports must be injectable in
  code exercised by state-machine tests.
- Randomized failures must print a reproduction seed. Use `TEST_SEED` for a
  specific replay.
- Mutable byte arrays returned by fixtures and fakes must be defensively copied.
- Negative security tests must assert fail-closed behavior.
- Protocol round trips must be paired with literal golden vectors for critical
  externally visible formats.
- Asynchronous tests must have a deterministic completion condition and a
  bounded timeout.
- A fixed bug must retain its smallest reproducing input as a regression test.
- Network / P2P behavior must be exercised across the FULL BIDIRECTIONAL
  component-switch matrix: for both the local device (A) and the remote peer
  (B), each of Wi-Fi, Bluetooth, and mobile data is tested both on and off.
  Each side therefore has 2^3 = 8 states, giving 8 × 8 = 64 ordered pairs:

  ```
  State bits (per side):  Wi-Fi | Bluetooth | Mobile data   (1 = on, 0 = off)
  A: 000  001  010  011  100  101  110  111
  B: 000  001  010  011  100  101  110  111
  ```

  Every ordered pair (A,B) must be considered. At minimum the following
  representative pairs must be explicitly tested, and the transport-selection
  logic must be verifiable (injectable network-state) so any of the 64
  combinations can be exercised without physical hardware:

  | A (local) | B (remote) | Expectation |
  |---|---|---|
  | 111 all on | 100 mobile-data only | Internet P2P direct (IPv6/UDP/TCP), no local mesh |
  | 111 all on | 111 all on | Local mesh preferred; Internet P2P as fallback |
  | 100 mobile-data only | 100 mobile-data only | Internet P2P direct over cellular CGNAT |
  | 011 Wi-Fi+Bluetooth | 011 Wi-Fi+Bluetooth | Local mesh / Wi-Fi Aware preferred |
  | 000 all off | 000 all off | Nothing reachable; Nostr relay fallback only |

  Testing a single network state is not sufficient; the transport-selection
  logic must be verified for every combination it can observe.

## Naming

Test names should describe observable behavior:

```kotlin
@Test
fun `replayed ciphertext is rejected without advancing receive state`() {
    // ...
}
```

Avoid names tied to private methods or temporary implementation structure.

## Fixtures and seeds

Reusable Kotlin fixtures live under
`com.bitchat.android.testsupport`. `ReproducibleTestSeed` resolves
`TEST_SEED` and provides a reproduction hint:

```sh
TEST_SEED=12345 ./gradlew clientRewriteContractTest
```

Never use production keys, contact information, messages, or other user data in
fixtures.

## Coverage

Run the full report and non-regression floor:

```sh
./gradlew clientRewriteContractTest
```

Reports are written to:

- `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`
- `app/build/reports/jacoco/jacocoTestReport/html/`

Check executable production lines changed from the base branch:

```sh
COVERAGE_BASE_REF=origin/main ./gradlew checkChangedLineCoverage
```

Generated resource classes, Compose-generated singleton classes, platform
bridges, and vendored Noise code are excluded from first-party coverage metrics.

## Quarantine and skips

- A flaky test must be fixed, not silently retried.
- A temporary quarantine must include an issue and removal condition.
- Unexpected skips fail review. Existing skips must be restored or replaced by
  equivalent coverage.
