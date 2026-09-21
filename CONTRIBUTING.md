# Contributing to Tulpar Server

Thanks for your interest! This document covers building, testing, and the
libAPG conformance workflow that every change to package handling must pass.

## Build & test

Requirements: **JDK 21+** (the build pins a Java 21 toolchain; JDK 21–25 all
work).

```bash
./gradlew build        # compile + test + shadowJar
./gradlew test         # hermetic test suite — no native dependencies
./gradlew shadowJar    # fat jar: build/libs/tulpar-server-<version>-all.jar
```

The test suite must stay hermetic: `./gradlew test` never requires libAPG,
native libraries, or network access. Conformance goldens are checked into
`src/test/resources/conformance/` for exactly this reason.

## Code style

- Kotlin, official style; four-space indent.
- Comments are documentation: KDoc on public types and non-obvious behavior,
  citing libAPG sources (`src/…, file:line`) wherever behavior is derived
  from the reference implementation. No step-by-step narration comments.
- Keep user-facing strings and comments in English.

## libAPG conformance (required for package-format changes)

Tulpar Server's contract: everything it publishes or indexes as installable
must be acceptable to **libAPG** (pinned commit in
[`docs/libapg-compat.md`](docs/libapg-compat.md)). The server may be
*stricter*, never *looser*. Any change to archive reading, metadata parsing,
version comparison, checksums, or signatures must keep the differential
tests green:

1. Build the oracle (needs a libAPG checkout at the pinned commit and
   libsodium; see [`conformance/README.md`](conformance/README.md)):

   ```bash
   cd <libapg-clone> && meson setup build --buildtype=release && meson compile -C build
   gcc -O2 -std=gnu11 -I<libapg>/include -I<sodium-prefix>/include \
       conformance/oracle/apg_oracle.c -o apg_oracle \
       -L<libapg>/build -lapg -L<sodium-prefix>/lib -lsodium \
       -Wl,-rpath,<libapg>/build -Wl,-rpath,<sodium-prefix>/lib
   ```

2. Run the live differential check:

   ```bash
   ./gradlew conformanceTest -PapgOracle=/path/to/apg_oracle
   ```

3. If you intentionally move the libAPG pin, regenerate the goldens and
   **review the diff** — every changed verdict needs a matrix update in
   `docs/libapg-compat.md`:

   ```bash
   ./gradlew regenerateConformanceGoldens -PapgOracle=/path/to/apg_oracle
   ```

4. Add new corpus cases with `conformance/corpus-generator.py` (deterministic
   fixtures) rather than committing one-off binaries.

## Tests we expect for new behavior

- Unit tests for pure logic (parsers, validators, comparators).
- Ktor `testApplication` integration tests for every route change (status
  codes, error shape `{error, detail}`).
- For anything libAPG-observable: a corpus case + golden, and a line in the
  compat matrix.

## Pull requests

1. Fork the repository and create a feature branch.
2. Small, focused commits with imperative subjects (`feat:`, `fix:`, `test:`,
   `docs:`, `build:`, `ci:`) and bodies explaining *why*.
3. Keep `./gradlew build` green at every commit where practical.
4. Update `CHANGELOG.md` for user-visible changes; note breaking changes
   explicitly.
5. Open a PR against `main`. CI runs the build/test matrix and the live
   conformance job.

## Security issues

Do not file public issues for vulnerabilities — see
[SECURITY.md](SECURITY.md).
