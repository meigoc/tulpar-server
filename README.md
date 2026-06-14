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

[![](https://img.shields.io/badge/License-GPL%203.0-blue.svg?style=flat-square)](https://www.gnu.org/licenses/gpl-3.0)
[![](https://img.shields.io/github/issues/nuros-linux/tulpar-server?style=flat-square&logo=github)](https://github.com/nuros-linux/tulpar-server/issues)

</div>

<br>

## 🔧 Build info

**Requirements:** JDK 21+

```bash
./gradlew shadowJar   # fat-jar with all dependencies (recommended)
./gradlew build       # all jars
./gradlew test        # run the test suite
```

#### Libraries
- [Ktor](https://ktor.io/) — asynchronous web framework (Netty engine)
- [Clikt](https://ajalt.github.io/clikt/) — command-line parsing
- [Mordant](https://github.com/ajalt/mordant) — terminal styling
- [Hoplite](https://github.com/sksamuel/hoplite) — HOCON configuration
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) — JSON
- [Commons Compress](https://commons.apache.org/proper/commons-compress/) + [XZ](https://tukaani.org/xz/java.html) — read `.apg` (tar.xz) natively
- [Logback](https://logback.qos.ch/) — logging

## Features

- **APG-native.** Reads package metadata from inside each `.apg` (tar.xz) exactly
  as libAPG does, so the index always matches what clients install.
- **Repository index.** Generates a canonical `repodata.json` over a `pool/`
  directory laid out as `pool/<channel>/<name>/<arch>/<name>-<version>-<arch>.apg`.
- **Clean REST v2 API** for browsing, searching, and downloading packages.
- **Signature-safe.** Package bytes are stored and served verbatim — never
  repackaged — so detached `.apg.sig` signatures stay valid.
- **Authenticated publishing.** Bearer-token-gated upload (multipart) with
  `apgcheck`-style validation, plus delete (yank).
- **Protection.** Per-IP request rate limiting with automatic bans, and per-IP
  concurrent-download caps + optional throughput throttling.
- **Operations.** Interactive admin console (ban/unban/log/metrics/reindex/…),
  periodic metrics, a bounded request log, and TLS/HTTPS.
- **Tolerant reader.** Accepts the ecosystem's known inconsistencies
  (`metadata.json`/`meta.json`, either checksum line order, hyphenated or
  non-hyphenated script names) and emits canonical output.

## Usage

```bash
# Start the server (interactive admin console attached)
java -jar tulpar-server-2.0.0-all.jar start

# Custom config / port / detached
java -jar tulpar-server-2.0.0-all.jar start --config /path/to/application.conf
java -jar tulpar-server-2.0.0-all.jar start --port 8080 --daemon

# Validate a package against the APG spec
java -jar tulpar-server-2.0.0-all.jar check ./mypkg-1.0.0-x86_64.apg

# Version / help
java -jar tulpar-server-2.0.0-all.jar version
java -jar tulpar-server-2.0.0-all.jar --help
```

### Admin console commands

When started in the foreground, an interactive console is available:

`help`, `packages`, `reindex`, `metrics`, `log [n]`, `ban <ip>`, `unban <ip>`,
`banlist`, `version`, `restart`, `stop`.

## REST API (v2)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v2/packages?arch=&channel=&type=&q=` | List / filter / search packages |
| GET | `/api/v2/packages/{name}` | All builds of a package name |
| GET | `/api/v2/download/{channel}/{name}/{version}/{arch}` | Download the `.apg` |
| GET | `/api/v2/download/{channel}/{name}/{version}/{arch}.sig` | Download the detached signature |
| GET | `/repodata.json`, `/api/v2/repodata` | Full repository index |
| GET | `/api/v2/health`, `/api/v2/version`, `/metrics` | Status & metrics |
| POST | `/api/v2/packages` | Publish (multipart, Bearer auth) |
| DELETE | `/api/v2/packages/{channel}/{name}/{version}/{arch}` | Yank (Bearer auth) |

### Publishing a package

```bash
curl -X POST http://localhost:8080/api/v2/packages \
  -H "Authorization: Bearer <token>" \
  -F "apg=@mypkg-1.0.0-x86_64.apg" \
  -F "sig=@mypkg-1.0.0-x86_64.apg.sig" \
  -F "channel=main"
```

## Package format (APG v2)

An `.apg` is a `tar.xz` archive containing:

```
metadata.json   package metadata (name, version, type, architecture, …)
data/           files extracted to the system root
md5sums         checksums (v1)
crc32sums       checksums (v2 adds this)
scripts/        optional pre/post install/remove scripts
home/           optional files extracted to $HOME
```

See [apg-docs](https://github.com/NurOS-Linux/apg-docs) for the full spec.

## Configuration

Tulpar Server uses HOCON. Create `application.conf` in the working directory
(any omitted value falls back to the built-in default):

```hocon
server {
    address = "0.0.0.0"
    port = 8080            # ports below 1024 need root
    runInBackground = false
    httpsRedirect = false
    behindProxy = false    # trust X-Forwarded-For only behind a known proxy
    tls {
        enabled = false
        port = 8443
        keyStorePath = "keystore.p12"   # JKS or PKCS12
        keyStorePassword = "changeit"
        keyAlias = "tulpar"
        privateKeyPassword = "changeit"
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
    maxDownloadSpeed = 0   # bytes/sec, 0 = unlimited
    bufferSize = 65536
    exemptLoopback = true
}

publish {
    enabled = false
    tokens = []            # Bearer tokens for publish/delete
    validate = true
    requireSignature = false
    allowOverwrite = false
}

metrics { enabled = true, intervalMillis = 300000 }

cli {
    color = "#cccccc"
    hello = [
        "▀█▀ █░█ █░░ █▀█ ▄▀█ █▀█ ▄▄ █▀ █▀▀ █▀█ █░█ █▀▀ █▀█   ▀█ ░ █▀█",
        "░█░ █▄█ █▄▄ █▀▀ █▀█ █▀▄ ░░ ▄█ ██▄ █▀▄ ▀▄▀ ██▄ █▀▄   █▄ ▄ █▄█"
    ]
}
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Open a Pull Request

## Roadmap

- [x] 🛠 Core architecture rewrite (v2.0)
- [ ] 📦 Official plugin registry
