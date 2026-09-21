#!/usr/bin/env python3
"""Regenerate goldens-signatures.json from the live oracle.

Usage:
    python3 conformance/regenerate-signature-goldens.py <oracle> <resources-dir>

Re-runs `apg_oracle keyring` over the recorded scenarios (fixture files under
<resources-dir>/signatures/, signed with apg_sigtool at vector-creation time)
and rewrites the golden verdicts.
"""

import json
import os
import subprocess
import sys

SCENARIOS = [
    ("valid-keyA",         "keyring-a",     "payload.bin",          "payload.sig"),
    ("unknown-key",        "keyring-a",     "payload.bin",          "payload.sig.byB"),
    ("any-key-signs",      "keyring-ab",    "payload.bin",          "payload.sig.byB"),
    ("malleable-S",        "keyring-a",     "payload.bin",          "payload.sig.malleable"),
    ("tampered-payload",   "keyring-a",     "payload-tampered.bin", "payload.sig"),
    ("short-sig",          "keyring-a",     "payload.bin",          "payload.sig.short"),
    ("empty-payload",      "keyring-a",     "empty.bin",            "empty.sig"),
    ("empty-keyring",      "keyring-empty", "payload.bin",          "payload.sig"),
    ("short-key-skipped",  "keyring-short", "payload.bin",          "payload.sig"),
    ("long-key-truncated", "keyring-long",  "payload.bin",          "payload.sig.byB"),
    ("missing-keyring",    "keyring-nope",  "payload.bin",          "payload.sig"),
]


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    oracle, resdir = sys.argv[1], sys.argv[2]
    sigdir = os.path.join(resdir, "signatures")
    out = []
    for name, ring, pkg, sig in SCENARIOS:
        r = subprocess.run(
            [oracle, "keyring", os.path.join(sigdir, ring), os.path.join(sigdir, pkg), os.path.join(sigdir, sig)],
            capture_output=True, text=True, timeout=30,
        )
        if r.returncode != 0:
            print(f"FAIL: oracle on {name}: {r.stderr.strip()}")
            return 1
        out.append({"case": name, "keyring": ring, "pkg": pkg, "sig": sig, "oracle": json.loads(r.stdout)})
    dest = os.path.join(resdir, "goldens-signatures.json")
    with open(dest, "w") as f:
        json.dump(out, f, indent=2)
    print(f"wrote {dest}: {len(out)} scenarios, {sum(1 for x in out if x['oracle']['verified'])} verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
