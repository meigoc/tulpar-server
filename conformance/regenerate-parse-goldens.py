#!/usr/bin/env python3
"""Regenerate goldens-parse.json from the live oracle over the committed corpus.

Usage:
    python3 conformance/regenerate-parse-goldens.py <oracle> <resources-dir>

Only the stable fields are recorded; host-specific archive_error strings
(containing temp paths and pids) are dropped.
"""

import json
import os
import subprocess
import sys
import tempfile


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    oracle, resdir = sys.argv[1], sys.argv[2]
    corpus = os.path.join(resdir, "corpus")
    out = []
    for name in sorted(os.listdir(corpus)):
        path = os.path.join(corpus, name)
        if not os.path.isfile(path):
            continue
        with tempfile.TemporaryDirectory() as root:
            r = subprocess.run([oracle, "parse", path, root], capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            print(f"FAIL: oracle on {name}: {r.stderr.strip()}")
            return 1
        obj = json.loads(r.stdout)
        obj.pop("archive_error", None)
        out.append({"case": name, "oracle": obj})
    dest = os.path.join(resdir, "goldens-parse.json")
    with open(dest, "w") as f:
        json.dump(out, f, indent=2, sort_keys=True)
    print(f"wrote {dest}: {len(out)} cases, {sum(1 for x in out if x['oracle'].get('accepted'))} accepted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
