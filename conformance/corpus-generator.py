#!/usr/bin/env python3
"""Deterministic generator for the differential-conformance archive corpus.

Reproduces every archive under src/test/resources/conformance/corpus/. Entries
use mtime=0, uid/gid=0 and a fixed ordering so the byte output is stable and
the goldens stay reproducible across runs.

Usage:
    python3 conformance/corpus-generator.py <output-dir>

The corpus is organized by the behavior each case probes. See
conformance/README.md and docs/libapg-compat.md for how libAPG rules on each.
"""

import io
import os
import sys
import tarfile

REG = tarfile.REGTYPE
DIR = tarfile.DIRTYPE
SYM = tarfile.SYMTYPE
LNK = tarfile.LNKTYPE
CHR = tarfile.CHRTYPE
BLK = tarfile.BLKTYPE
FIFO = tarfile.FIFOTYPE

META = b'{"name":"adv","version":"1.0"}'


def _add_file(t, name, data=b"x"):
    ti = tarfile.TarInfo(name)
    ti.size = len(data)
    ti.mtime = 0
    ti.uid = 0
    ti.gid = 0
    t.addfile(ti, io.BytesIO(data))


def _add_special(t, name, typ, link=None, major=0, minor=0, size=0, data=None):
    ti = tarfile.TarInfo(name)
    ti.type = typ
    ti.mtime = 0
    ti.uid = 0
    ti.gid = 0
    if link is not None:
        ti.linkname = link
    if typ in (CHR, BLK):
        ti.devmajor = major
        ti.devminor = minor
    if typ == REG:
        ti.size = len(data) if data is not None else 0
        t.addfile(ti, io.BytesIO(data if data is not None else b""))
    else:
        t.addfile(ti)


def _build(path, filt, body):
    """Write `path` as a tar filtered by filt; body(tarfile) fills entries."""
    raw = path
    mode = "w"
    if filt == "gz":
        mode, raw = "w:gz", path
    elif filt == "bz2":
        mode, raw = "w:bz2", path
    elif filt == "xz":
        mode, raw = "w:xz", path
    with tarfile.open(raw, mode) as t:
        body(t)
    if filt == "zst":
        tmp = path + ".tmp"
        with tarfile.open(tmp, "w") as t:
            body(t)
        os.system(f"zstd -qf {tmp} -o {path} && rm {tmp}")


def cases():
    """Map case filename -> (filter, builder)."""
    c = {}

    def normal(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/usr/bin/x", b"hello")
    c["01-normal.tar.xz"] = ("xz", normal)

    def dotdot_name(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "../evil")
    c["02-dotdot-name.tar.xz"] = ("xz", dotdot_name)

    def dotdot_mid(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/../../evil")
    c["03-dotdot-mid.tar.xz"] = ("xz", dotdot_mid)

    def absolute(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "/etc/absfile")
    c["04-absolute-name.tar.xz"] = ("xz", absolute)

    def dot_prefix(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "./data/x")
    c["05-dot-prefix.tar.xz"] = ("xz", dot_prefix)

    def sym_rel(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/link", SYM, link="x")
        _add_file(t, "data/x", b"t")
    c["06-symlink-rel.tar.xz"] = ("xz", sym_rel)

    def sym_abs(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/link", SYM, link="/etc/passwd")
    c["07-symlink-abs.tar.xz"] = ("xz", sym_abs)

    def sym_dotdot(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/link", SYM, link="../../etc/passwd")
    c["08-symlink-dotdot.tar.xz"] = ("xz", sym_dotdot)

    def hard_existing(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/f", b"content")
        _add_special(t, "data/hl", LNK, link="data/f")
    c["09-hardlink-existing.tar.xz"] = ("xz", hard_existing)

    def hard_dotdot(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/f", b"c")
        _add_special(t, "data/hl", LNK, link="../f")
    c["10-hardlink-dotdot.tar.xz"] = ("xz", hard_dotdot)

    def hard_abs(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/f", b"c")
        _add_special(t, "data/hl", LNK, link="/etc/passwd")
    c["11-hardlink-abs.tar.xz"] = ("xz", hard_abs)

    def hard_missing(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/hl", LNK, link="data/nope")
    c["12-hardlink-missing.tar.xz"] = ("xz", hard_missing)

    def hard_dotdotmid(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/f", b"c")
        _add_special(t, "data/hl", LNK, link="foo/../data/f")
    c["13-hardlink-dotdotmid.tar.xz"] = ("xz", hard_dotdotmid)

    def chrdev(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/dev/null", CHR, major=1, minor=3)
    c["14-chrdev.tar.xz"] = ("xz", chrdev)

    def blkdev(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/dev/sda", BLK, major=8, minor=0)
    c["15-blkdev.tar.xz"] = ("xz", blkdev)

    def fifo(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/fifo", FIFO)
    c["16-fifo.tar.xz"] = ("xz", fifo)

    def meta_json_only(t):
        _add_file(t, "meta.json", META)
        _add_file(t, "data/x", b"1")
    c["17-meta-json-only.tar.xz"] = ("xz", meta_json_only)

    def empty_meta(t):
        _add_file(t, "metadata.json", b"{}")
        _add_file(t, "data/x", b"1")
    c["18-empty-meta.tar.xz"] = ("xz", empty_meta)

    def no_data_dir(t):
        _add_file(t, "metadata.json", META)
    c["19-no-data-dir.tar.xz"] = ("xz", no_data_dir)

    def bom_meta(t):
        _add_file(t, "metadata.json", b'\xef\xbb\xbf{"name":"adv","version":"1.0"}')
        _add_file(t, "data/x", b"1")
    c["20-bom-meta.tar.xz"] = ("xz", bom_meta)

    def comments_meta(t):
        _add_file(t, "metadata.json", b'{/*c*/"name":"adv","version":"1.0"}')
        _add_file(t, "data/x", b"1")
    c["21-comments-meta.tar.xz"] = ("xz", comments_meta)

    def trailing_comma(t):
        _add_file(t, "metadata.json", b'{"name":"adv","version":"1.0",}')
        _add_file(t, "data/x", b"1")
    c["22-trailing-comma.tar.xz"] = ("xz", trailing_comma)

    def numeric_version(t):
        _add_file(t, "metadata.json", b'{"name":"adv","version":1.0}')
        _add_file(t, "data/x", b"1")
    c["23-numeric-version.tar.xz"] = ("xz", numeric_version)

    def dup_keys(t):
        _add_file(t, "metadata.json", b'{"name":"first","name":"second","version":"1.0"}')
        _add_file(t, "data/x", b"1")
    c["24-dup-keys.tar.xz"] = ("xz", dup_keys)

    def nonstr_arrays(t):
        _add_file(t, "metadata.json",
                  b'{"name":"adv","version":"1.0","tags":["a",5,null,"b"],"dependencies":["ok",7]}')
        _add_file(t, "data/x", b"1")
    c["25-nonstr-in-arrays.tar.xz"] = ("xz", nonstr_arrays)

    def empty_strings(t):
        _add_file(t, "metadata.json",
                  b'{"name":"adv","version":"1.0","description":"","architecture":""}')
        _add_file(t, "data/x", b"1")
    c["26-empty-strings.tar.xz"] = ("xz", empty_strings)

    def long_name(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/" + "d" * 300 + "/" + "f" * 300)
    c["27-long-name.tar.xz"] = ("xz", long_name)

    def raw_normal(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/x", b"1")
    c["28-raw-tar.tar"] = ("none", raw_normal)
    c["29-gz.tar.gz"] = ("gz", raw_normal)
    c["30-zstd.tar.zst"] = ("zst", raw_normal)
    c["31-bz2.tar.bz2"] = ("bz2", raw_normal)

    def unknown_field(t):
        _add_file(t, "metadata.json",
                  b'{"name":"adv","version":"1.0","future_field":{"x":1},"zeta":"kept"}')
        _add_file(t, "data/x", b"1")
    c["32-unknown-field.tar.xz"] = ("xz", unknown_field)

    def lone_surrogate(t):
        _add_file(t, "metadata.json",
                  b'{"name":"adv","version":"1.0","description":"\\ud800 alone"}')
        _add_file(t, "data/x", b"1")
    c["33-lone-surrogate.tar.xz"] = ("xz", lone_surrogate)

    def unescaped_ctl(t):
        _add_file(t, "metadata.json",
                  b'{"name":"adv","version":"1.0","description":"tab\there"}')
        _add_file(t, "data/x", b"1")
    c["34-unescaped-ctl.tar.xz"] = ("xz", unescaped_ctl)

    def nan_number(t):
        _add_file(t, "metadata.json", b'{"name":"adv","version":"1.0","weird":NaN}')
        _add_file(t, "data/x", b"1")
    c["35-nan-number.tar.xz"] = ("xz", nan_number)

    def sym_ok_target(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/usr", SYM, link="../usr")
    c["36-symlink-ok-target.tar.xz"] = ("xz", sym_ok_target)

    def comp(n):
        def b(t):
            _add_file(t, "metadata.json", META)
            _add_file(t, "data/" + "d" * n)
        return b
    c["37-component-254.tar.xz"] = ("xz", comp(254))
    c["38-component-255.tar.xz"] = ("xz", comp(255))
    c["39-component-256.tar.xz"] = ("xz", comp(256))

    def hard_after(t):
        _add_file(t, "metadata.json", META)
        _add_file(t, "data/f", b"content")
        _add_special(t, "data/hl", LNK, link="data/f")
    c["40-hardlink-after-target.tar.xz"] = ("xz", hard_after)

    def hard_before(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/hl", LNK, link="data/f")
        _add_file(t, "data/f", b"content")
    c["41-hardlink-before-target.tar.xz"] = ("xz", hard_before)

    def sym_dangling(t):
        _add_file(t, "metadata.json", META)
        _add_special(t, "data/sl", SYM, link="nowhere")
    c["42-symlink-dangling.tar.xz"] = ("xz", sym_dangling)

    return c


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    for name, (filt, body) in cases().items():
        _build(os.path.join(outdir, name), filt, body)
        print("wrote", name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
