"""Build ARM64 PIE benchmark executables with a pinned local Android NDK.

Vendor files remain byte-for-byte intact. Historical C declaration/timer adaptations
are generated into build/native/ported, with precise transformations below.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[3]
WORKSPACE = PROJECT.parent
NDK = Path(os.environ.get("PULSE_NDK_HOME", WORKSPACE / "work/android-tools/android-ndk-r29"))
HOST = "windows-x86_64" if os.name == "nt" else "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
CLANG = NDK / "toolchains/llvm/prebuilt" / HOST / "bin" / ("clang.exe" if os.name == "nt" else "clang")
READELF = CLANG.parent / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
OUT = HERE.parent / "jniLibs/arm64-v8a"
PORT = PROJECT / "app/build/native/ported"


def generated(name: str, source: Path, changes: list[tuple[str, str]]) -> Path:
    text = source.read_text(encoding="utf-8")
    for old, new in changes:
        if old not in text:
            raise RuntimeError(f"Port adaptation no longer matches {source.name}: {old!r}")
        text = text.replace(old, new)
    destination = PORT / name
    destination.write_text(text, encoding="utf-8", newline="\n")
    return destination


def compile_exe(name: str, sources: list[Path], extra: list[str] = []) -> None:
    args = [str(CLANG), "--target=aarch64-linux-android26", "-O2", "-fno-lto", "-fPIE", "-pie",
            "-Wl,-z,max-page-size=16384", "-Wl,-z,relro,-z,now", "-Wl,--build-id=sha1"]
    args += extra + [str(p) for p in sources] + ["-lm", "-o", str(OUT / name)]
    subprocess.run(args, check=True)
    header = subprocess.check_output([str(READELF), "-h", str(OUT / name)], text=True)
    if "AArch64" not in header or "DYN" not in header:
        raise RuntimeError(f"{name} is not an ARM64 PIE")
    print(f"Built {name} ({(OUT/name).stat().st_size:,} bytes)", flush=True)


def main() -> None:
    if not CLANG.is_file():
        raise SystemExit(f"NDK r29 is required. Set PULSE_NDK_HOME to the NDK directory. Missing: {CLANG}")
    lock = json.loads((HERE / "vendor-lock.json").read_text(encoding="utf-8"))
    for path, expected in lock["sha256"].items():
        actual = hashlib.sha256((HERE / path).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f"Vendor integrity mismatch: {path}")
    OUT.mkdir(parents=True, exist_ok=True)
    PORT.mkdir(parents=True, exist_ok=True)

    core = HERE / "vendor/coremark"
    core_port = generated("core_portme.c", core / "posix/core_portme.c",
                          [("clock_gettime(CLOCK_REALTIME, _t)", "clock_gettime(CLOCK_MONOTONIC, _t)")])
    compile_exe("libcoremark.so", [core / n for n in ["core_list_join.c", "core_main.c", "core_matrix.c", "core_state.c", "core_util.c"]] + [core_port],
                ["-std=gnu11", "-I" + str(core), "-I" + str(core / "posix"), "-I" + str(HERE / "android_port")])

    dhry = HERE / "vendor/dhrystone"
    dhry_one = generated("dhry_1.c", dhry / "dhry_1.c", [
        ('#include "dhry.h"', '#include "dhry.h"\n#include <stdlib.h>\n#include <string.h>\n#include <unistd.h>'),
        ('extern char     *malloc ();', '/* malloc prototype is supplied by stdlib.h for LP64. */'),
        ('extern  int     times ();', '/* times prototype is supplied by sys/times.h for LP64. */'),
        ('main ()', 'int main (void)'),
        ('  \n}\n\n\nProc_1', '  return 0;\n}\n\n\nProc_1'),
    ])
    compile_exe("libdhrystone.so", [dhry_one, dhry / "dhry_2.c"],
                ["-std=gnu89", "-fno-builtin", "-fno-inline-functions", "-Wno-implicit-int", "-Wno-implicit-function-declaration", "-Wno-deprecated-non-prototype", "-Wno-int-conversion", "-Wno-pointer-to-int-cast", "-DHZ=sysconf(_SC_CLK_TCK)", "-include", "unistd.h", "-include", "string.h", "-I" + str(dhry)])

    whet = generated("whetstone.c", HERE / "vendor/whetstone/whetstone.c", [
        ('#include <time.h>', '#include <time.h>\nstatic double pulse_monotonic_seconds(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec/1e9; }'),
        ('long startsec, finisec;', 'double startsec, finisec;'),
        ('float KIPS;', 'double KIPS;'),
        ('time(0)', 'pulse_monotonic_seconds()'),
        ('Duration: %ld sec.', 'Duration: %.6f sec.'),
    ])
    compile_exe("libwhetstone.so", [whet], ["-std=gnu11", "-fno-builtin", "-fno-inline-functions"])
    compile_exe("libpulsebench.so", [HERE / "pulsebench.c"], ["-std=gnu11", "-Wall", "-Wextra"])
    outputs = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.glob("*.so")}
    (PROJECT / "app/build/native/build-manifest.json").write_text(json.dumps({"ndk": "29.0.14206865", "target": "aarch64-linux-android26", "sha256": outputs}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
