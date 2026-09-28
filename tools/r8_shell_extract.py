#!/usr/bin/env python3
"""
r8_shell_extract.py — Extract minimal shell DEX from full business DEX.

Uses Android SDK's R8 in library mode to tree-shake a 9.5MB DEX
down to ~500KB containing only shell bootstrapping classes + Kotlin runtime.

Usage:
  python tools/r8_shell_extract.py [--input classes.dex] [--output shell.dex]

Prerequisites:
  - ANDROID_HOME or LOCALAPPDATA/Android/Sdk
  - R8 jar in build-tools
  - kotlin-stdlib jar in Gradle cache
"""

import os, sys, subprocess, zipfile, shutil, glob, zlib
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
SDK = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"
BT = sorted((SDK / "build-tools").glob("*"))[-1] if (SDK / "build-tools").exists() else None
R8_JAR = SDK / "cmdline-tools" / "latest" / "lib" / "r8.jar"
R8_CP_JAR = SDK / "cmdline-tools" / "latest" / "lib" / "r8-classpath.jar"
if not R8_JAR.exists():
    # Fallback: downloaded jar
    R8_JAR = PROJECT / "tools" / "r8-full.jar"
    R8_CP_JAR = None
GRADLE_CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
PROGUARD_RULES = PROJECT / "tools" / "proguard-shell.pro"


def find_kotlin_stdlib() -> str:
    """Find kotlin-stdlib jar in Gradle cache."""
    candidates = sorted(
        GRADLE_CACHE.glob("org.jetbrains.kotlin/kotlin-stdlib/**/kotlin-stdlib-*.jar"),
        key=lambda p: p.stat().st_size, reverse=True
    )
    for c in candidates:
        if 'sources' not in str(c) and 'javadoc' not in str(c):
            return str(c)
    raise SystemExit("kotlin-stdlib not found in Gradle cache")


def find_androidx_core() -> str:
    """Find androidx core jar."""
    candidates = sorted(
        GRADLE_CACHE.glob("androidx.core/core/**/core-*.aar"),
        key=lambda p: p.stat().st_size, reverse=True
    )
    for aar in candidates:
        try:
            with zipfile.ZipFile(str(aar), 'r') as zf:
                classes = zf.read('classes.jar')
            tmp = PROJECT / "app" / "build" / "tmp" / "androidx_core_classes.jar"
            tmp.parent.mkdir(parents=True, exist_ok=True)
            tmp.write_bytes(classes)
            return str(tmp)
        except Exception:
            continue
    print("⚠ androidx.core not found in Gradle cache — skipping")
    return None


def run_r8(input_dex: str, output_dir: str, lib_jars: list = None):
    """Run R8 to tree-shake DEX."""
    if lib_jars is None:
        lib_jars = []

    # Always add Android platform jar
    android_jar = SDK / "platforms" / "android-34" / "android.jar"
    if not android_jar.exists():
        android_jar = max((SDK / "platforms").glob("android-*/android.jar"))
    lib_args = ["--lib", str(android_jar)]

    # Add Kotlin stdlib
    kotlin_jar = find_kotlin_stdlib()
    print(f"  Kotlin stdlib: {kotlin_jar}")
    lib_args += ["--lib", kotlin_jar]

    # Add AndroidX core
    android_x = find_androidx_core()
    if android_x:
        lib_args += ["--lib", android_x]

    # Build classpath
    cp = str(R8_JAR)
    if R8_CP_JAR and R8_CP_JAR.exists():
        cp = cp + os.pathsep + str(R8_CP_JAR)

    # Run R8
    cmd = [
        "java", "-Dfile.encoding=UTF-8", "-Xmx2g",
        "-cp", cp, "com.android.tools.r8.R8",
        "release",
        "--classfile",
        "--no-desugaring",
        "--pg-conf", str(PROGUARD_RULES),
        "--output", output_dir,
        *lib_args,
        input_dex
    ]

    print(f"  R8 command: {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=300, cwd=str(PROJECT))

    if result.returncode != 0:
        print(f"  R8 stderr:\n{result.stderr[-2000:]}")
        raise SystemExit(f"R8 failed with exit code {result.returncode}")

    # Find output DEX
    dex_files = list(Path(output_dir).glob("*.dex"))
    if not dex_files:
        raise SystemExit("R8 produced no DEX files")

    return dex_files


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Extract minimal shell DEX")
    parser.add_argument("--input", default=None, help="Input full DEX path")
    parser.add_argument("--output", default=None, help="Output shell DEX path")
    args = parser.parse_args()

    input_dex = args.input or str(PROJECT / "app/build/intermediates/dex/release/minifyReleaseWithR8/classes.dex")
    output_dir = str(PROJECT / "app/build/tmp/r8_shell_out")
    output_dex = args.output or str(PROJECT / "app/build/tmp/shell_dex_final")

    if not Path(input_dex).exists():
        raise SystemExit(f"Input DEX not found: {input_dex}")

    if not R8_JAR or not R8_JAR.exists():
        raise SystemExit(f"R8 jar not found at {R8_JAR}")

    # Clean output
    shutil.rmtree(output_dir, ignore_errors=True)
    os.makedirs(output_dir, exist_ok=True)
    os.makedirs(output_dex, exist_ok=True)

    print(f"R8 Shell Extraction")
    print(f"  Input:  {input_dex} ({Path(input_dex).stat().st_size/1024:.1f}KB)")
    print(f"  Rules:  {PROGUARD_RULES}")

    # Phase 1: R8 tree-shake
    dex_files = run_r8(input_dex, output_dir)

    # Phase 2: Pick smallest dex that has StaticApkShell
    best = None
    best_size = float('inf')
    for df in dex_files:
        with open(df, 'rb') as f:
            data = f.read()
        if b'StaticApkShell' in data:
            if len(data) < best_size:
                best = df
                best_size = len(data)

    if not best:
        print("❌ No output DEX contains StaticApkShell — check ProGuard rules")
        for df in dex_files:
            print(f"  {df.name}: {df.stat().st_size}B")
        sys.exit(1)

    # Copy to output
    out_path = Path(output_dex) / "classes.dex"
    shutil.copy(best, out_path)

    compressed = len(zlib.compress(out_path.read_bytes(), 9))
    print(f"\n✅ Shell DEX extracted")
    print(f"  Uncompressed: {best_size}B ({best_size/1024:.1f}KB)")
    print(f"  Compressed:   ~{compressed}B ({compressed/1024:.1f}KB)")

    # Verify key classes present
    with open(out_path, 'rb') as f:
        data = f.read()
    checks = [
        b'StaticApkShell', b'NativeBridge', b'G0', b'MethodRecoveryEngine',
        b'SActivity', b'kotlin/jvm/internal/Intrinsics',
    ]
    for c in checks:
        print(f"  {'✅' if c in data else '❌'} {c.decode()}")

    # Replace in APK and sign
    apk = PROJECT / "app/build/outputs/apk/release/app-release.apk"
    if apk.exists():
        tmp = str(apk) + '.tmp'
        with zipfile.ZipFile(str(apk), 'r') as zin:
            with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
                for item in zin.infolist():
                    zout.writestr(item, data if item.filename == 'classes.dex' else zin.read(item.filename))
        shutil.move(tmp, str(apk))
        print(f"\n  ✅ APK DEX replaced ({len(data)}B)")

        # Sign
        apksigner = BT / "apksigner.bat"
        ks = PROJECT / "release.keystore"
        store_pass = os.environ.get("YUNIAN_STORE_PASSWORD", "")
        key_pass = os.environ.get("YUNIAN_KEY_PASSWORD", "")
        key_alias = os.environ.get("YUNIAN_KEY_ALIAS", "your_alias")
        missing = [name for name, value in [
            ("YUNIAN_STORE_PASSWORD", store_pass),
            ("YUNIAN_KEY_PASSWORD", key_pass),
        ] if not value]
        if missing:
            sys.exit(f"Release signing requires: {', '.join(missing)}")
        if not ks.exists():
            sys.exit(f"Release keystore not found: {ks}")
        signed = str(apk).replace('.apk', '-signed.apk')
        subprocess.run([
            'cmd', '/c', str(apksigner), 'sign',
            '--ks', str(ks), '--ks-pass', f'pass:{store_pass}',
            '--key-pass', f'pass:{key_pass}', '--ks-key-alias', key_alias,
            '--out', signed, str(apk)
        ], check=True)
        shutil.move(signed, str(apk))
        print(f"  ✅ APK signed ({Path(apk).stat().st_size/1024/1024:.1f}MB)")


if __name__ == "__main__":
    main()
