# Changelog

All notable changes to this project are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] — 2026-09-21

Stable release of the 2.0 rewrite. The defining property of this release:
every package the server publishes or indexes as installable is acceptable to
**libAPG v2.5.0** (commit `1ecebf7c7fb567740126bed483779f536b57c36a`), proven
by differential testing against a C oracle linked to libAPG
(`docs/libapg-compat.md`, `conformance/`).

### Breaking changes vs 2.0 PREVIEW 1

- **`meta.json` is no longer accepted as a metadata source.** libAPG 2.x
  `parse_package` reads only `metadata.json` (src/package.c:241). Packages
  carrying only `meta.json` are now rejected at publish and excluded from the
  index with a warning. (PREVIEW 1 tolerated them, which violated the
  "never looser than libAPG" invariant.)
- **Metadata JSON is parsed strictly** (libAPG uses yyjson with default
  flags): comments, trailing commas, BOM, NaN, lone surrogates and raw
  control characters are now parse errors; non-string values (e.g.
  `"version": 1.0`) no longer populate fields; duplicate keys keep the first
  value. PREVIEW 1's lenient reader accepted all of these.
- **`check` verdict semantics changed.** Exit code now reflects
  *libAPG acceptance* (archive safety + filters + strict metadata + `data/`).
  Spec extras that libAPG ignores (missing checksum files, missing
  description/maintainer/homepage) are warnings/policy notes, not failures.
  A package with no sums files — like every package in the current
  production pool — now passes `check` (it failed before).
- **Compression filters restricted to libAPG's set**: none, gzip, xz, zstd.
  bz2/lz4/lzma/snappy archives (which PREVIEW 1's auto-detect could accept
  when the codec was on the classpath) are rejected with a clear error.
- **Present-but-invalid signatures are rejected at publish** even when
  signatures are optional (`requireSignature=false`). PREVIEW 1 stored any
  attached `.sig` without verification.
- **Builds are ordered latest-first** (by libAPG `ver_compare`) in
  `/api/v2/packages/{name}` and `repodata.json`. PREVIEW 1 sorted versions
  lexicographically ascending; the Tulpar client installs `builds[0]`, so the
  old order could serve an outdated version.
- **Duplicate publish returns 409** (was 422). Rate-limit responses are JSON
  `{error, detail}` (was plain text). Upload over the size cap returns 413.
- **`/api/v2/health`** gained a `ready` field and returns 503 with
  `status:"starting"` until the first index completes. **`/api/v2/version`**
  gained `api` and `libapgTarget` fields.
- **Startup refuses unsafe configurations** (exit code 2): publishing enabled
  without tokens, tokens shorter than 32 chars, `requireSignature` without
  `keyringDir`, `httpsRedirect` without TLS, invalid ports/limits.
- **Admin console `restart` removed** (it was a no-op); `stop` remains.
- **`repodata.json` `meta.generated_at`** is now derived from package mtimes
  (deterministic) instead of the wall clock. The Tulpar client does not read
  `meta`, so this is compat-safe; it makes the index byte-reproducible.

### Added

- **libAPG conformance, proven.** `conformance/` — C oracle
  (`apg_oracle.c`) and vector signer (`apg_sigtool.c`) linking libAPG;
  42-case adversarial corpus; recorded goldens (parse verdicts, 982
  version-compare pairs, dependency parses, 11 signature scenarios) replayed
  hermetically by `./gradlew test` and re-verified live by
  `./gradlew conformanceTest -PapgOracle=…`. Full matrix in
  `docs/libapg-compat.md`.
- **zstd support** (`com.github.luben:zstd-jni` 1.5.7-18): `tar.zst`
  packages — the format used by the entire production pool — are read,
  indexed, published and served.
- **Signature verification**: libAPG-compatible keyring (`publish.keyringDir`;
  `*.key` files, 32 raw bytes) and Ed25519ph verification (the actual scheme
  libsodium's streaming API implements — plain Ed25519 signatures never
  verify against libAPG; see `docs/libapg-compat.md` §6). JDK-native, no new
  dependencies.
- **Streaming publish**: uploads stream to a staging temp file under hard
  caps (`publish.maxUploadBytes`, default 4 GiB) with 413 on breach and a
  magic-byte preflight (400 for non-archives); packages of any allowed size
  publish under a small heap (verified: 2.05 GB-decompressed package under
  `-Xmx512m`).
- **Archive safety at read time**, mirroring libAPG's extractor: `..`
  segments, unsafe hardlink targets (absolute, `..`, out-of-order), device
  nodes rejected; NAME_MAX/PATH_MAX caps; entry-count and decompressed-size
  caps; bounded xz/zstd decoder memory.
- **Strict identifier allowlists** (channel/name/version/arch) with Windows
  reserved-name and case-collision protection; epoch (`:`) versions allowed.
- **HTTP surface**: HEAD on all GETs, Range/resume (206) for downloads,
  ETag/If-None-Match (304) for repodata and file responses.
- **Startup config validation** with actionable errors (exit 2) and
  unknown-key warnings; keystore default-password warnings.
- **Security hardening**: X-Forwarded-For rightmost-hop trust only when
  `behindProxy` (ignored otherwise); loopback exemption never applies to
  proxied addresses; stale rate-limit entries evicted (bounded memory);
  IPv4-mapped IPv6 loopback recognized; user-controlled strings sanitized
  before logging; tokens never logged; `start` exits non-zero on failure.
- **Atomic writes**: repodata.json and published packages are written via
  temp + atomic move; concurrent same-coordinate publishes are serialized.
- **Reproducible index**: identical pool state produces byte-identical
  `repodata.json`.
- **CI** (GitHub Actions): JDK 21/25 on Linux + Windows/macOS smoke; fat-jar
  artifact; conformance job building libAPG at the pinned commit and running
  the live oracle.
- **Mutation fuzz tests** for the archive reader (seeded, bounded, in the
  hermetic suite).
- `CHANGELOG.md`, `SECURITY.md`, `CONTRIBUTING.md`,
  `docs/libapg-compat.md`.

### Changed

- `ver_compare` port now matches libAPG byte-for-byte (epoch handling,
  strtol whitespace/sign/overflow semantics, NUL-terminator comparison —
  fixes the old `' '` end-padding divergence).
- Dependency constraint parsing matches libAPG exactly (operator table order,
  space/tab-only trimming).
- commons-compress 1.27.1 → 1.28.0; Netty pinned to 4.2.17.Final (zero open
  OSV advisories; Ktor pulled 4.2.7).
- `application.conf` comments are English; every key is documented in the
  README.
- Reindex excludes libAPG-incompatible pool contents with a warning instead
  of listing them; scanning streams each archive (no full in-RAM buffering).

### Compatibility with PREVIEW-1 deployments

- Pool layout, URL paths, config keys and JSON field names are unchanged;
  the PREVIEW-1 production configuration starts and serves as before.
- All 95+ zstd packages uploaded during the PREVIEW-1 phase (including via
  the production zstd-jni build) index, serve and pass `check` unchanged.
  Bytes are never rewritten, so existing detached signatures stay valid.

[2.0.0]: https://github.com/NurOS-Linux/tulpar-server/releases/tag/v2.0.0
