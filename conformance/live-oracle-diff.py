#!/usr/bin/env python3
"""Live differential check: re-run the libAPG oracle and diff against goldens.

Usage:
    python3 conformance/live-oracle-diff.py <oracle> <resources-dir>

Verifies that the checked-in goldens (recorded at the pinned libAPG commit)
still match what the live oracle says for:
  - every archive in <resources-dir>/corpus/   (parse verdict + metadata)
  - every pair in goldens-vercmp.json          (ver_compare)
  - every constraint in goldens-depparse.json  (dep_constraint_parse)

Exits non-zero on the first mismatch category with a full diff summary, so CI
fails loudly if the oracle's libAPG moved relative to the recorded goldens.
"""

import json
import os
import subprocess
import sys
import tempfile


def run_oracle(oracle, args, timeout=60):
    r = subprocess.run([oracle] + args, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"oracle {args} exited {r.returncode}: {r.stderr.strip()}")
    return json.loads(r.stdout)


def check_parse(oracle, resdir):
    goldens = json.load(open(os.path.join(resdir, "goldens-parse.json")))
    corpus = os.path.join(resdir, "corpus")
    mismatches = []
    for case in goldens:
        name = case["case"]
        expected = dict(case["oracle"])
        expected.pop("archive_error", None)
        path = os.path.join(corpus, name)
        if not os.path.isfile(path):
            mismatches.append(f"{name}: corpus file missing")
            continue
        with tempfile.TemporaryDirectory() as root:
            actual = run_oracle(oracle, ["parse", path, root])
        actual.pop("archive_error", None)
        if actual != expected:
            mismatches.append(f"{name}: golden={expected} live={actual}")
    return mismatches


def check_vercmp(oracle, resdir):
    goldens = json.load(open(os.path.join(resdir, "goldens-vercmp.json")))
    mismatches = []
    for g in goldens:
        actual = run_oracle(oracle, ["vercmp", g["a"], g["b"]], timeout=30)
        if actual["cmp"] != g["cmp"]:
            mismatches.append(f"vercmp({g['a']!r},{g['b']!r}): golden={g['cmp']} live={actual['cmp']}")
    return mismatches


def check_depparse(oracle, resdir):
    goldens = json.load(open(os.path.join(resdir, "goldens-depparse.json")))
    mismatches = []
    for g in goldens:
        actual = run_oracle(oracle, ["depparse", g["input"]], timeout=30)
        if actual != g["oracle"]:
            mismatches.append(f"depparse({g['input']!r}): golden={g['oracle']} live={actual}")
    return mismatches


def check_signatures(oracle, resdir):
    path = os.path.join(resdir, "goldens-signatures.json")
    if not os.path.isfile(path):
        return []
    goldens = json.load(open(path))
    sigdir = os.path.join(resdir, "signatures")
    mismatches = []
    for g in goldens:
        actual = run_oracle(oracle, [
            "keyring",
            os.path.join(sigdir, g["keyring"]),
            os.path.join(sigdir, g["pkg"]),
            os.path.join(sigdir, g["sig"]),
        ], timeout=30)
        if actual != g["oracle"]:
            mismatches.append(f"{g['case']}: golden={g['oracle']} live={actual}")
    return mismatches


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    oracle, resdir = sys.argv[1], sys.argv[2]
    if not os.access(oracle, os.X_OK):
        print(f"FAIL: oracle not executable: {oracle}")
        return 2

    failures = {}
    checks = (
        ("parse-corpus", check_parse),
        ("vercmp", check_vercmp),
        ("depparse", check_depparse),
        ("signatures", check_signatures),
    )
    for label, fn in checks:
        mm = fn(oracle, resdir)
        if mm:
            failures[label] = mm
        print(f"{label}: {'FAIL' if mm else 'OK'} ({len(mm)} mismatches)")

    if failures:
        for label, mm in failures.items():
            print(f"\n--- {label} mismatches ({len(mm)}) ---")
            for line in mm[:40]:
                print(" ", line)
            if len(mm) > 40:
                print(f"  ... and {len(mm) - 40} more")
        return 1
    print("\nAll live-oracle outputs match the recorded goldens.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
