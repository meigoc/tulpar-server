# libAPG compatibility matrix

Tulpar Server targets **libAPG v2.5.0**, commit
`1ecebf7c7fb567740126bed483779f536b57c36a`
(<https://github.com/NurOS-Linux/libAPG>, mirror of `git.nuros.org/core/libapg`).

This matrix records, for every behavior on the conformance surface: what
libAPG does (with source references at the pinned commit), what this server
does, which test pins the behavior, and the alignment status.

Status values:
- **identical** — same observable behavior as libAPG;
- **stricter** — the server rejects/limits more than libAPG (allowed by the
  compatibility invariant: never looser);
- **not applicable** — client/install-time behavior with no server-side
  meaning (recorded for completeness).

The authoritative proof is differential: `conformance/` holds a C oracle
linking libAPG and a 42-case corpus; goldens recorded from the oracle are
replayed hermetically by `GoldenCorpusTest` on every `./gradlew test`, and
re-verified against a live oracle build by `./gradlew conformanceTest`.

## 1. Archive format and compression filters

libAPG: `archive_read_support_filter_{gzip,xz,zstd}` +
`archive_read_support_format_tar` (src/archive.c:61-64). libarchive also
accepts uncompressed ("raw") tar through the same reader. Verified with the
oracle: raw tar and gzip accepted; **bzip2 rejected**.

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| tar.xz | accepted | accepted | corpus `01-normal` | identical |
| tar.zst | accepted | accepted (zstd-jni 1.5.7-18) | corpus `30-zstd` | identical |
| tar.gz | accepted | accepted | corpus `29-gz` | identical |
| raw tar (no filter) | accepted | accepted (ustar magic required) | corpus `28-raw-tar` | identical |
| tar.bz2, lz4, lzma, snappy, ... | rejected | rejected with explicit error | corpus `31-bz2`, `ApgArchiveTest` | identical |
| non-tar / random bytes | rejected | rejected (magic + ustar preflight) | `ApgArchiveTest` | identical |

## 2. Entry types and path safety

libAPG extracts with `ARCHIVE_EXTRACT_SECURE_NODOTDOT | SECURE_SYMLINKS |
UNLINK` (src/archive.c:67-70), rejects `..` segments in entry paths and
hardlink targets (`is_safe_relative_path`, src/archive.c:29-47), caps joined
paths at `PATH_MAX` 4096 (src/archive.c:15,86-93), and fails extraction of
device nodes (unprivileged `mknod`) and hardlinks whose target has not
already been extracted (oracle-verified).

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| `..` segment in entry path | reject | reject | corpus `02`,`03` | identical |
| absolute entry path (`/etc/x`) | accepted (re-rooted under dest) | accepted, normalized to relative | corpus `04` | identical |
| `./` prefix | accepted | accepted, normalized | corpus `05` | identical |
| symlink, any target (rel/abs/`..`/dangling) | accepted | accepted, recorded not followed | corpus `06`,`07`,`08`,`36`,`42` | identical |
| hardlink to earlier regular file | accepted | accepted | corpus `09`,`40` | identical |
| hardlink target with `..` (incl. `foo/../bar`) | reject | reject | corpus `10`,`13` | identical |
| hardlink target absolute | reject | reject | corpus `11` | identical |
| hardlink target missing / appears later | reject | reject (order tracked) | corpus `12`,`41` | identical |
| char/block device node | reject (extract fails) | reject at read time | corpus `14`,`15` | identical |
| FIFO | accepted | accepted | corpus `16` | identical |
| path component ≥ 256 bytes (NAME_MAX) | reject (write_header fails) | reject | corpus `37`(254 ok),`38`(255 ok),`39`(256 rej) | identical |
| total joined path ≥ 4096 (PATH_MAX) | reject | reject (cap 4095 on raw+normalized) | corpus `27` | identical |
| entry count | unbounded | ≤ 200 000 | `ApgReadLimits` | stricter (DoS bound) |
| decompressed total | unbounded | ≤ 8 GiB | `ApgReadLimits` | stricter (DoS bound) |
| single entry size | unbounded | ≤ 4 GiB | `ApgReadLimits` | stricter (DoS bound) |
| xz decoder memory | libarchive default | ≤ 64 MiB (`memoryLimitKiB`) | `ApgReadLimits` | stricter (decoder-bomb bound) |
| zstd window | libarchive default | ≤ 2^27 (`setLongMax`; prod pkgs use 2^23) | `ApgReadLimits` | stricter (decoder-bomb bound) |
| script execution | runs `scripts/*` sandboxed | never executes anything | by design | not applicable |

## 3. Metadata

libAPG: `parse_package` reads exactly `metadata.json` from the archive root
(src/package.c:241); parsed by yyjson with **default flags** (strict;
src/json.c:149,195,210); fields copied only when the JSON value is a string
(`yyjson_is_str`, src/json.c:104-141); arrays keep only string items
(src/json.c:29-33,55-60); unknown fields ignored; no field is *required* —
`{}` parses and yields NULL name/version (oracle corpus `18`).

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| metadata file name | `metadata.json` only | same; `meta.json` not read (presence → warning) | corpus `17` | identical |
| strict JSON (no comments) | reject | reject | corpus `21` | identical |
| trailing comma | reject | reject | corpus `22` | identical |
| UTF-8 BOM | reject | reject | corpus `20` | identical |
| lone surrogate escape | reject | reject | corpus `33` | identical |
| raw control char in string | reject | reject | corpus `34` | identical |
| `NaN` literal | reject | reject | corpus `35` | identical |
| duplicate keys | first value wins | first value wins | corpus `24` | identical |
| non-string scalar field (`"version": 1.0`) | field stays NULL | field stays null (RawMetadata) | corpus `23` | identical |
| non-string array items | dropped (NULL slots ignored) | dropped | corpus `25` | identical |
| empty strings (`""`) | kept as "" | kept as "" | corpus `26` | identical |
| unknown top-level fields | ignored | ignored | corpus `32` | identical |
| empty object `{}` | accepted, NULL fields | parse OK (RawMetadata all-null) | corpus `18` | identical |
| name/version NULL → installable? | accepted by parse; db/tooling unusable | NOT indexable (pool path needs them) | `ApgValidatorTest` | stricter (structural necessity) |
| JSON nesting depth | iterative parser (heap) | ≤ 256 (stack guard) | `ApgJson.MAX_DEPTH` | stricter |
| invalid UTF-8 bytes | yyjson rejects | rejects | `ApgJson` (decodeToString strict) | identical |

## 4. Checksum files (`md5sums`, `crc32sums`, `sha256sums`)

libAPG **never reads** checksum files (no reference anywhere in src/ or
include/; grep-verified at the pinned commit). `apgv2.md` documents `md5sums`
as part of the package layout; production pool packages (network1, 95 pkgs)
carry **no** sums files at all.

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| sums files present or absent | ignored either way | absence → warning only (never a rejection) | `ApgValidatorTest` | identical (acceptance) |
| sums mismatch vs data/ | ignored | publish error under `validate=true`; `check` reports policy issue, verdict unchanged | `ApgValidatorTest` | stricter (integrity policy) |
| line format `HASH  PATH` / `PATH HASH` | n/a | both accepted (tolerant reader) | `ChecksumsTest` | not applicable |

## 5. Scripts and `home/`

libAPG: `run_script` matches names under `scripts/` case-insensitively with
`-`/`_` stripped (src/install/scripts.c:32-44,276-311); names
`pre-install`, `post-install` at install time; non-executable scripts skipped;
`home/` copied into `$HOME` (src/install/install.c:31-50).

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| `scripts/` recognized | executed sandboxed at install | listed (`scriptNames()`), never executed | `ApgArchiveTest` (script listing) | not applicable (server never installs) |
| name normalization (`pre_install` ≡ `pre-install`) | yes | yes (same rule) for listing/warnings | `ApgArchiveTest` | identical (inspection only) |
| `home/` | copied to `$HOME` at install | passed through in archive contents; never extracted | — | not applicable |
| `data/` required for install | `install_data_dir` fails without it | required for libAPG-compat verdict and indexing | corpus `19`, `ApgValidatorTest` | identical |

## 6. Signatures

libAPG: detached signature at `<pkg>.sig` = **raw 64-byte Ed25519ph**
signature (RFC 8032 *prehashed* Ed25519: the signed message is SHA-512(pkg)
under the `dom2(1,"")` separator) over the exact bytes of the package file
(streamed; src/sign/sodium/keyring.c:93-133). libAPG uses libsodium's
streaming API `crypto_sign_init/update/final_verify`, which libsodium aliases
to `crypto_sign_ed25519ph_*` (libsodium crypto_sign.h:23, sign_ed25519.c:61-97,
ref10/sign.c DOM2PREFIX). **A plain-Ed25519 verifier rejects every genuine
libAPG signature** — discovered differentially and recorded as
`.agent`-side decision D-015; upstream issue drafted (libAPG docs say only
"Ed25519").
Keyring: directory (default `/etc/apg/trusted.d`, meson option `keyring_dir`),
every file ending in `.key`, **first 32 bytes** read as the public key; files
shorter than 32 bytes are skipped; longer files are silently truncated
(src/sign/sodium/keyring.c:38-82 — a quirk; upstream issue drafted). Any key
in the keyring may sign (try-all, keyring.c:126-131). Verifier is libsodium
`crypto_sign_final_verify` (strict: canonical S, small-order R/A rejected;
ref10/open.c:26-38). `sign_verify`'s single-key path uses
`APG_KEYS_DIR/public.key` / `secret.key` (src/sign/sodium/sodium.c:11,36,79).

Server: JDK 21 `Signature("Ed25519")` + `EdDSAParameterSpec(prehash=true)`
(JDK-native Ed25519ph; hashes the message internally), public keys decoded
from the raw 32-byte little-endian libsodium format via `EdECPublicKeySpec`.
Vectors in `src/test/resources/conformance/signatures/` are signed by
`conformance/oracle/apg_sigtool.c`, which calls libAPG's exact libsodium
sequence; `SignatureConformanceTest` asserts the JVM verifier's verdict equals
the oracle's `keyring_verify` for all 11 scenarios.

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| signature scheme | Ed25519ph (SHA-512 prehash, dom2(1,"")) | same, via JDK prehash=true | `SignatureConformanceTest` (valid-keyA) | identical |
| what is signed | exact package file bytes | same (bytes served verbatim, never repackaged) | `PublishServiceTest` | identical |
| sig file format | raw 64 bytes | raw 64 bytes required; other lengths → MALFORMED | `SignatureConformanceTest` (short-sig) | identical |
| keyring dir semantics | `*.key`, first 32 bytes = pubkey | same loader semantics | `keyring loader mirrors libAPG byte handling` | identical |
| key file < 32 bytes | skipped | skipped | (short-key-skipped) | identical |
| key file > 32 bytes | truncated to 32 | truncated to 32 (quirk reproduced) | (long-key-truncated) | identical |
| any-key-may-sign | try every key | try every key | (any-key-signs) | identical |
| empty/missing keyring | verifies nothing | EMPTY_KEYRING (fail-closed) | (empty-keyring, missing-keyring) | identical |
| malleable signature (S+L) | rejected (canonical-S check) | rejected | (malleable-S) | identical |
| tampered payload | rejected | rejected | (tampered-payload) | identical |
| empty payload (0 bytes) | verifiable | verifiable | (empty-payload) | identical |
| plain-Ed25519 verifier accepts libAPG sigs | n/a (would reject) | asserted rejected (regression guard) | `genuine libAPG signatures are Ed25519ph` | identical |
| missing sig, require_signature | refuse install | refuse publish (`requireSignature=true`) | `PublishRoutesTest` | identical (policy parity) |
| invalid sig, signatures optional | client's choice | **rejected at publish** (never stored) | `PublishRoutesTest`, `PublishServiceTest` | stricter |
| key identity in .sig | none (no key ID) | none | — | identical |

## 7. Version comparison and dependency constraints

libAPG `ver_compare` (src/version/version.c:121-186): optional `N:` epoch
(strtol), '.'-skipping, strtol digit runs (leading whitespace/sign accepted,
LONG_MAX/LONG_MIN clamp), non-numeric sides compared as unsigned bytes with
'\0' = 0 terminator, mixed numeric/non-numeric treats the non-numeric side as
0 without advancing. `dep_constraint_parse` (version.c:30-72): operator table
probed in fixed order `>=, <=, ==, !=, >, <`, **first table match anywhere in
the string wins** (strstr), name/version trimmed of space/tab only.

| Behavior | libAPG | Server | Test | Status |
|---|---|---|---|---|
| numeric segment compare | strtol runs | same | goldens-vercmp (982 pairs) | identical |
| epoch prefix `N:` | strtol before first ':' | same | goldens-vercmp | identical |
| strtol whitespace/sign skipping | accepted inside versions | same | goldens-vercmp | identical |
| LONG overflow clamp | LONG_MAX/LONG_MIN | same | goldens-vercmp | identical |
| EOF byte value | '\0' (0), not space | same (old ' ' padding bug fixed) | goldens-vercmp, `VersionTest` | identical |
| `2` vs `2.0` | equal | equal | goldens-vercmp | identical |
| dep operator table order | `>=,<=,==,!=,>,<` first match | same | goldens-depparse (25), incl. `weird<1.0>2.0` → GT | identical |
| canonical form | `name op version`, single spaces | same | goldens-depparse | identical |
| "latest" resolution | client takes first satisfying build | server sorts builds latest-first with ver_compare | `RepositoryTest` (ordering tests); full client contract test lands in Phase 4 | identical (contract) |

## 8. Error classification

| Failure | libAPG | Server | Status |
|---|---|---|---|
| unreadable/rejected archive | fatal for that package (parse_package → NULL) | publish: reject (422/400); reindex: warn + exclude from index | identical (per-package fatal, non-fatal overall) |
| bad metadata JSON | fatal for that package | same | identical |
| missing data/ | install fails | not indexed / publish rejected | identical |
| sums mismatch | ignored | publish error under validate=true | stricter |
| missing sig (policy off) | fine | fine | identical |
| invalid sig (policy off) | client's choice | rejected at publish | stricter |

## Deviations summary (all "stricter", none looser)

1. Size/count/decoder-memory caps (§2) — DoS bounds libAPG does not need as a
   local tool but a public server does.
2. name/version required for indexing (§3) — the pool layout
   `pool/<channel>/<name>/<arch>/<name>-<version>-<arch>.apg` cannot exist
   without them.
3. Checksum integrity policy (§4) — opt-in via `publish.validate`; production
   currently runs `validate=false`, where behavior equals libAPG's (ignore).
4. Present-but-invalid signature rejected at publish (§6) — a server storing a
   package whose attached signature does not verify would be laundering
   tampered artifacts.
5. JSON nesting depth cap (§3) — stack-safety for a network-facing parser.

Each deviation is pinned by a test named in the tables above and none changes
the accept-set for packages libAPG accepts.
