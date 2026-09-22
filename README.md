<div align="center">

<h1 align="center">Tulpar Server 2.0</h1>

<p align="center">
  <a href="https://nuros.org">
    <img src="https://avatars.githubusercontent.com/u/183817345?s=200&v=4" alt="nuros logo" height="140">
  </a>
</p>

**Tulpar Server** is a free, open-source, cross-platform Kotlin server for hosting
your own **NurOS** APG package repository in minutes. It is the repository side of
the NurOS packaging ecosystem — packages it serves are consumed by
[libAPG](https://github.com/NurOS-Linux/libAPG) and the
[Tulpar](https://github.com/NurOS-Linux/Tulpar) package manager.

It features a clean REST API, security-first request handling, authenticated
package publishing, and an interactive admin console.

[![](https://img.shields.io/badge/License-AGPL%203.0-blue.svg?style=flat-square)](https://www.gnu.org/licenses/agpl-3.0)
[![](https://img.shields.io/github/issues/nuros-linux/tulpar-server?style=flat-square&logo=github)](https://github.com/nuros-linux/tulpar-server/issues)

</div>

<br>

## Compatibility promise

Everything this server publishes or indexes as installable is acceptable to
**libAPG v2.5.0** (commit `1ecebf7c7fb567740126bed483779f536b57c36a`). The
server may be stricter than libAPG, never looser. Equivalence is proven by
**differential testing**: a C oracle links libAPG and records its verdicts over
an adversarial corpus; the recorded goldens are replayed on every build, and CI
re-verifies them against a freshly built libAPG. Full behavior matrix:
[`docs/libapg-compat.md`](docs/libapg-compat.md).

Supported package compression: **tar.xz** (canonical), **tar.zst**, **tar.gz**,
and raw tar — exactly the filters libAPG accepts. Packages are stored and
served byte-for-byte, so detached `.apg.sig` signatures (libAPG Ed25519ph
keyring format) stay valid.

## 🔧 Build info

**Requirements:** JDK 21+ (the Gradle build pins a Java 21 toolchain for
compilation and tests via the foojay resolver; running Gradle itself on a
newer JDK, e.g. 25, is supported and exercised in CI, but the tests then
still execute on the pinned 21 toolchain JVM).

```bash
./gradlew shadowJar   # fat-jar with all dependencies (recommended)
./gradlew build       # all jars
./gradlew test        # run the test suite (hermetic — no libAPG needed)
```

Differential conformance against a locally built libAPG (optional, see
[`conformance/README.md`](conformance/README.md)):

```bash
./gradlew conformanceTest -PapgOracle=/path/to/apg_oracle
```

#### Libraries
- [Ktor](https://ktor.io/) — asynchronous web framework (Netty engine)
- [Clikt](https://ajalt.github.io/clikt/) — command-line parsing
- [Mordant](https://github.com/ajalt/mordant) — terminal styling
- [Hoplite](https://github.com/sksamuel/hoplite) — HOCON configuration
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) — JSON
- [Commons Compress](https://commons.apache.org/proper/commons-compress/) + [XZ](https://tukaani.org/xz/java.html) + [zstd-jni](https://github.com/luben/zstd-jni) — read `.apg` archives natively
- [Logback](https://logback.qos.ch/) — logging

## Features

- **APG-native.** Reads package metadata from inside each `.apg` exactly the
  way libAPG does — same strict JSON semantics, same archive safety rules,
  same version comparison — proven by differential tests against libAPG
  itself (see `docs/libapg-compat.md`).
- **Repository index.** Generates a canonical, byte-reproducible
  `repodata.json` over a `pool/` directory laid out as
  `pool/<channel>/<name>/<arch>/<name>-<version>-<arch>.apg`.
- **Clean REST v2 API** for browsing, searching, and downloading packages,
  with HEAD, Range (resume) and conditional-GET support.
- **Signature-safe.** Package bytes are stored and served verbatim — never
  repackaged — so detached `.apg.sig` signatures stay valid. Publish-time
  verification against a libAPG-compatible Ed25519ph keyring.
- **Authenticated publishing.** Bearer-token-gated streaming upload
  (multipart) with libAPG-aligned validation, plus delete (yank). Uploads are
  streamed to disk under hard size caps; the payload never accumulates in RAM.
- **Protection.** Per-IP request rate limiting with automatic bans, per-IP
  concurrent-download caps + optional throughput throttling, strict identifier
  allowlists, path-traversal containment, log-injection sanitization.
- **Operations.** Interactive admin console (ban/unban/log/metrics/reindex/…),
  periodic metrics, a bounded request log, startup config validation, and
  TLS/HTTPS.

## Usage

```bash
# Start the server (interactive admin console attached)
java -jar tulpar-server-2.0.0-all.jar start

# Custom config / port / detached
java -jar tulpar-server-2.0.0-all.jar start --config /path/to/application.conf
java -jar tulpar-server-2.0.0-all.jar start --port 8080 --daemon

# Validate a package the way libAPG would read it
java -jar tulpar-server-2.0.0-all.jar check ./mypkg-1.0.0-x86_64.apg

# Version / help
java -jar tulpar-server-2.0.0-all.jar version
java -jar tulpar-server-2.0.0-all.jar --help
```

Exit codes: `0` success, `1` runtime failure (`check`: package not acceptable
to libAPG), `2` configuration error.

### Admin console commands

When started in the foreground, an interactive console is available:

`help`, `packages`, `reindex`, `metrics`, `log [n]`, `ban <ip>`, `unban <ip>`,
`banlist`, `version`, `stop`.

## REST API (v2)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v2/packages?arch=&channel=&type=&q=` | List / filter / search packages |
| GET | `/api/v2/packages/{name}` | All builds of a package name (latest first) |
| GET | `/api/v2/download/{channel}/{name}/{version}/{arch}` | Download the `.apg` (HEAD, Range supported) |
| GET | `/api/v2/download/{channel}/{name}/{version}/{arch}.sig` | Download the detached signature |
| GET | `/repodata.json`, `/api/v2/repodata` | Full repository index (ETag / If-None-Match supported) |
| GET | `/api/v2/health` | Readiness + package count (503 `starting` until the first index completes) |
| GET | `/api/v2/version` | Server version, API version, repodata format, targeted libAPG commit |
| GET | `/metrics` | Runtime metrics snapshot (JSON) |
| POST | `/api/v2/packages` | Publish (multipart, Bearer auth) |
| DELETE | `/api/v2/packages/{channel}/{name}/{version}/{arch}` | Yank (Bearer auth) |

All API error responses use one JSON shape:

```json
{ "error": "not_found", "detail": "package not found" }
```

| Status | Meaning |
|--------|---------|
| 400 | structural problem (bad identifier, payload not an archive, bad JSON, chunked/multipart body) |
| 401 | missing or invalid bearer token |
| 403 | publishing disabled |
| 404 | unknown package / missing file or signature |
| 409 | package already exists (publish without `allowOverwrite`) |
| 411 | publish without a `Content-Length` header (chunked upload) |
| 413 | upload exceeds `publish.maxUploadBytes` |
| 422 | semantic rejection (libAPG-incompatible package, failed validation, bad signature) |
| 429 | rate limit / download concurrency limit / active ban |
| 500 | internal error (`detail` is always null; the cause is logged server-side only) |
| 503 | `/api/v2/health` before the first index completes (`ready:false`) |

The `errors/404.html` file, if present in the working directory, is served
instead of JSON for unmatched routes; the favicon 404 has an empty body. All
other API errors use the JSON shape above.

`q` matches case-insensitively against name, description and tags (substring).

### Publishing a package

```bash
curl -X POST http://localhost:8080/api/v2/packages \
  -H "Authorization: Bearer <token>" \
  -F "apg=@mypkg-1.0.0-x86_64.apg" \
  -F "sig=@mypkg-1.0.0-x86_64.apg.sig" \
  -F "channel=main"
```

If a `.sig` is present it is **always** verified against the configured keyring
(`publish.keyringDir`, libAPG format: `*.key` files holding 32 raw Ed25519
public key bytes) — an invalid signature is rejected even when signatures are
optional. Note that libAPG signatures are **Ed25519ph** (prehashed Ed25519,
what libsodium's streaming API produces); plain-Ed25519 signatures do not
verify. See `docs/libapg-compat.md` §6.

## Package format (APG v2)

An `.apg` is a tar archive under one of the filters libAPG accepts
(`.xz` canonical; `.zst`, `.gz` and raw tar also supported):

```
metadata.json   package metadata (name, version, type, architecture, …)
data/           files extracted to the system root (required)
md5sums         checksums (optional; libAPG itself does not verify them)
crc32sums       checksums (optional)
sha256sums      checksums (optional)
scripts/        optional pre/post install/remove scripts (never executed by the server)
home/           optional files extracted to $HOME
```

Metadata is parsed with libAPG's exact JSON semantics (strict RFC 8259; only
string values populate fields; first duplicate key wins). See
[apg-docs](https://github.com/NurOS-Linux/apg-docs) for the format document
and [`docs/libapg-compat.md`](docs/libapg-compat.md) for the authoritative
behavior matrix.

## Configuration

Tulpar Server uses HOCON. Create `application.conf` in the working directory
(any omitted value falls back to the built-in default). Unknown keys are
warned about at startup. Secrets support HOCON environment substitution, e.g.
`tokens = [${TULPAR_PUBLISH_TOKEN}]`.

```hocon
server {
    address = "0.0.0.0"
    port = 8080            # ports below 1024 need root
    runInBackground = false
    httpsRedirect = false  # requires tls.enabled
    behindProxy = false    # trust X-Forwarded-For (rightmost hop) only behind a known reverse proxy
    tls {
        enabled = false
        port = 8443
        keyStorePath = "keystore.p12"   # JKS or PKCS12
        keyStorePassword = ${?TLS_KS_PASSWORD}
        keyAlias = "tulpar"
        privateKeyPassword = ${?TLS_KEY_PASSWORD}
    }
}

repo {
    root = "./repo-data"   # holds pool/ and the generated repodata.json
    defaultChannel = "main"
    reindexOnStart = true
}

limits {
    maxRequestsPerWindow = 120
    windowMillis = 60000
    banDurationMillis = 60000
    maxDownloadsPerIP = 4
    maxDownloadSpeed = 0   # bytes/sec per stream, 0 = unlimited; advisory:
                           # applies to full-body GETs only (Range/resume
                           # requests are served unthrottled), per concurrent
                           # download stream of an IP
    bufferSize = 65536
    exemptLoopback = true  # never applies to X-Forwarded-For addresses
}

publish {
    enabled = false
    tokens = []            # Bearer tokens; >= 32 chars each; env substitution supported
    validate = true        # also enforce checksum integrity + indexability
    requireSignature = false
    keyringDir = ""        # directory of *.key files (32 raw Ed25519 pubkey bytes)
    allowOverwrite = false
    maxUploadBytes = 4294967296    # 4 GiB
    maxSignatureBytes = 4096
}

metrics { enabled = true, intervalMillis = 300000 }

cli {
    color = "#cccccc"
    hello = []
}
```

Startup refuses unsafe combinations: publishing enabled with no tokens,
tokens shorter than 32 characters, `requireSignature` without a keyring,
`httpsRedirect` without TLS, out-of-range ports/limits. Default keystore
passwords (`changeit`) produce a loud warning.

### `/metrics` fields

`cpuLoadPercent`, `heapUsedBytes`, `heapMaxBytes`, `nonHeapUsedBytes`,
`bannedIps`, `blockedRequests`, `activeDownloads`, `totalDownloads`,
`packages`, `totalRequests` (JSON object; names are stable).

## Deployment

### Readiness and startup

With `repo.reindexOnStart = true` (default) the server binds its port only
**after** the first index completes — during warmup clients see
connection-refused, not the 503 `starting` state. The 503/`ready:false`
response is observable when the module is embedded programmatically (and is
covered by tests). With `reindexOnStart = false` the server binds immediately
with an intentionally empty index and reports `ready:true`; use that mode only
behind a proxy that tolerates an empty repository.

### Reverse proxy

Run behind nginx/Caddy/Cloudflare with `server.behindProxy = true`. The server
then treats the **rightmost** `X-Forwarded-For` entry (the hop your proxy
vouches for) as the client IP. Do not enable `behindProxy` when the server is
directly reachable — clients could spoof the header. The `exemptLoopback`
setting never exempts proxy-supplied addresses.

### systemd

```ini
[Unit]
Description=Tulpar Server (APG repository)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=tulpar
Group=tulpar
WorkingDirectory=/opt/tulpar
EnvironmentFile=/etc/tulpar/token.env
ExecStart=/usr/bin/java -Xms64m -Xmx512m -jar /opt/tulpar/tulpar-server.jar start \
    --config /opt/tulpar/application.conf --port 8080 -d
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/tulpar
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
```

`/etc/tulpar/token.env` (mode 0400): `TULPAR_PUBLISH_TOKEN=...`

### Limits

- Upload size: `publish.maxUploadBytes` (default 4 GiB), enforced while
  streaming — memory use stays flat regardless of upload size.
- Archive reading: ≤ 200 000 entries, ≤ 8 GiB decompressed, ≤ 4 GiB per
  entry, path < 4096 bytes, component ≤ 255 bytes, bounded xz/zstd decoder
  memory. Packages violating libAPG's safety rules (traversal, device nodes,
  unsafe hardlinks) are rejected.
- Request log: last 1000 requests in memory.
- Single instance per repository: publish/yank serialize on an in-process
  lock and the index is in-memory. Do not run two server processes against
  the same `repo.root` — scale reads with a reverse proxy/cache instead.

## Security

See [SECURITY.md](SECURITY.md) for the vulnerability reporting policy.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building, testing and the
conformance workflow.

## Roadmap

- [x] 🛠 Core architecture rewrite (v2.0)
- [x] 🔍 libAPG differential conformance (v2.0.0)
- [ ] 📦 Official plugin registry (post-2.0)
