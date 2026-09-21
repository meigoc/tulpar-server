#!/usr/bin/env python3
"""Regenerate the version-compare and dependency-parse matrices from the oracle.

Reads the committed input matrices (goldens-vercmp.json / goldens-depparse.json
provide both the inputs and the last recorded outputs) and re-queries the live
oracle, rewriting the files with fresh outputs. Inputs are deterministic:

  python3 conformance/matrix-generator.py <oracle> <resources-dir>

The input pairs/constraints are defined below; changing them changes the
matrices, so review goldens diffs after any edit here.
"""

import json
import os
import subprocess
import sys

VERSIONS = [
    "0", "1", "2", "10", "1.0", "1.0.0", "1.0.1", "1.1", "1.9", "1.10", "2.0", "1.2.3",
    "0:1.0", "1:1.0", "0:9.9", "2:0.1", "1:2", "1:", ":1.0", "x:1.0", "-1:1.0", "999999999999:1",
    "1.0-1", "1.0-2", "1.9.17p2", "10.5p1", "20260904-1", "3.8.2-1", "1.2304.0-1", "2026.09.07-2",
    "1.0rc1", "1.0rc2", "1.0a", "1.0b", "1.0-alpha", "1.0.beta", "1.0beta", "01.02", "1.02", "1.2",
    "9223372036854775807.1", "9223372036854775808.1", "99999999999999999999.1", "0.0.0.0.1",
    " 1.0", "1.0 ", "+1.0", "-1.0", "1. 0", "1..0", "...", "1...2", "a", "b", "ab", "ba", "A",
    "~1.0", "1.0~", "_1.0", "1.0.0.0", "0.1", "1.0+meta", "1.0_2", "v1.0", "1!2", "2026c-1",
    "1.0.0-1", "1.0.0.1",
]

EXTRA_PAIRS = [
    ("1:1.0", "0:9.9"), ("2", "2.0"), ("2.0", "2"), ("1.0", "1.0.0"), ("", "1.0"), ("1.0", ""),
    ("", ""), ("a", ""), ("0", "a"), ("1.0rc1", "1.0"), ("1.0", "1.0rc1"),
    ("9223372036854775807", "9223372036854775808"), ("1:2", "1:10"), ("01", "1"),
    ("1.0 ", "1.0"), (" 1.0", "1.0"), ("+1", "1"), ("-1", "1"), ("~", "1"),
]

CONSTRAINTS = [
    "libfoo", "libfoo >= 2.0", "libfoo>=2.0", "libfoo >=2.0", "libfoo>= 2.0", " x <= 1.0 ",
    "a==1", "a!=1", "a<1", "a>1", "a<=1", "a>=1", "weird<1.0>2.0", "a>=>=1", "==1", "a==",
    "a = = 1", "name\t>=\t2.0", "a>=1<2", "<>=!", "a!>1", "", " ", "pkg-1.0 >= 2.0",
    "libfoo  >=  2.0  ",
]


def vercmp_pairs():
    pairs = []
    for i, a in enumerate(VERSIONS):
        for j in range(0, len(VERSIONS), 5):
            pairs.append((a, VERSIONS[j]))
    pairs += EXTRA_PAIRS
    seen = set()
    uniq = []
    for a, b in pairs:
        if (a, b) in seen:
            continue
        seen.add((a, b))
        uniq.append((a, b))
    return uniq


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    oracle, resdir = sys.argv[1], sys.argv[2]
    os.makedirs(resdir, exist_ok=True)

    vercmp = []
    for a, b in vercmp_pairs():
        r = subprocess.run([oracle, "vercmp", a, b], capture_output=True, text=True, timeout=30)
        vercmp.append({"a": a, "b": b, "cmp": json.loads(r.stdout)["cmp"]})
    with open(os.path.join(resdir, "goldens-vercmp.json"), "w") as f:
        json.dump(vercmp, f, indent=0)

    depparse = []
    for c in CONSTRAINTS:
        r = subprocess.run([oracle, "depparse", c], capture_output=True, text=True, timeout=30)
        depparse.append({"input": c, "oracle": json.loads(r.stdout)})
    with open(os.path.join(resdir, "goldens-depparse.json"), "w") as f:
        json.dump(depparse, f, indent=0)

    print(f"vercmp pairs: {len(vercmp)}, depparse cases: {len(depparse)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
