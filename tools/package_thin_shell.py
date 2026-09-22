#!/usr/bin/env python3
"""package_thin_shell.py — Replace root business DEX with pure-Java thin shell.

Pipeline:
  1. Compile pure Java shell sources → shell classes.dex (~KB)
  2. Encrypt all root classes*.dex from input APK → assets/shell/*.dat
  3. Rewrite APK:
       - root classes.dex = shell DEX only
       - strip other root classes*.dex
       - inject assets/shell/*
       - strip META-INF signatures
  4. Optionally re-sign with apksigner

This is the production packaging step that turns a normal Gradle APK
(with ~10MB+ plaintext root DEX) into a thin-shell APK.

Usage:
  python tools/package_thin_shell.py \\
      --apk app/build/outputs/apk/release/app-release.apk \\
      --out YuNian-thin-shell.apk \\
      --sign

Environment (for --sign release keystore):
  YUNIAN_STORE_PASSWORD / YUNIAN_KEY_PASSWORD / YUNIAN_KEY_ALIAS
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import hmac
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parent.parent
STABLE_SHELL_SRC = PROJECT / "app" / "src" / "shell" / "java" / "com" / "yunian" / "ai" / "security"
SHELL_WORK = PROJECT / "app" / "build" / "tmp" / "thin_shell"
LOCALAPPDATA = Path(os.environ.get("LOCALAPPDATA", ""))
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or (LOCALAPPDATA / "Android" / "Sdk"))

# cert obs from nativeDeriveDexKey (obfuscation mask)
CERT_OBS = bytes([
    0x4e, 0x0d, 0xa5, 0x67, 0x7e, 0xc7, 0x29, 0x22, 0x5f, 0xbc, 0x9e, 0xf0, 0x7a, 0x73, 0xf0, 0x88,
    0x41, 0x64, 0x2b, 0x4f, 0x39, 0xef, 0x22, 0xca, 0xe1, 0x5f, 0x78, 0x49, 0x9a, 0x41, 0x13, 0xec,
])
# CRC64("r-xp /liblianyu_shell.so") matching native maps_crc64_stable()
EXPECTED_MAPS = 0x8e7beee5d9b3c6e4

_FALLBACK_KEY = bytes([
    0x50, 0x59, 0x65, 0x1c, 0xb6, 0xac, 0x74, 0xf7, 0xf5, 0x4c, 0x5d, 0x32, 0x75, 0x80, 0x1f, 0x51,
    0x0b, 0x3c, 0x1f, 0x48, 0x3e, 0x20, 0xc7, 0xd7, 0x32, 0x60, 0x17, 0xcc, 0x23, 0xf1, 0xe9, 0x53,
])

SHELL_SOURCES = [
    "StaticApkShell.java",
    "SActivity.java",
    "MethodRecoveryEngine.java",
]


def run(cmd: list[str], timeout: int = 120,
        env: dict | None = None) -> subprocess.CompletedProcess:
    # NOTE: never place credentials in `cmd` -- this line echoes argv verbatim
    # into build logs. Secrets must travel via `env` (see sign_apk).
    print(f"  $ {' '.join(cmd)}")
    result = subprocess.run(cmd, capture_output=True, timeout=timeout, env=env)
    if result.returncode != 0:
        if result.stdout:
            print(result.stdout.decode("utf-8", errors="replace"))
        if result.stderr:
            print(result.stderr.decode("utf-8", errors="replace"))
        result.check_returncode()
    return result


def find_build_tools() -> Path:
    preferred = SDK / "build-tools" / "36.0.0"
    if preferred.exists():
        return preferred
    versions = sorted((SDK / "build-tools").glob("*"), reverse=True)
    if not versions:
        sys.exit(f"Android build-tools not found under {SDK}")
    return versions[0]


def find_android_jar() -> Path:
    for api in ("android-35", "android-34", "android-36"):
        jar = SDK / "platforms" / api / "android.jar"
        if jar.exists():
            return jar
    jars = sorted((SDK / "platforms").glob("android-*/android.jar"), reverse=True)
    if not jars:
        sys.exit(f"android.jar not found under {SDK / 'platforms'}")
    return jars[0]


def crc64(data: bytes) -> int:
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ (0xC96C5795D7870F42 if (c & 1) else 0)
        table.append(c)
    c = 0xFFFFFFFFFFFFFFFF
    for b in data:
        c = (table[((c >> 56) ^ b) & 0xFF] ^ (c << 8)) & 0xFFFFFFFFFFFFFFFF
    return c ^ 0xFFFFFFFFFFFFFFFF


def derive_dex_key(cert_sha256: bytes | None, use_actual_cert: bool) -> bytes:
    cert_hash = bytes(CERT_OBS[i] ^ ((0xC3 ^ (i * 0x9D)) & 0xFF) for i in range(32))
    if use_actual_cert and cert_sha256 and len(cert_sha256) >= 32:
        cert_for_salt = cert_sha256[:32]
    else:
        cert_for_salt = cert_hash
    salt = struct.pack("<Q", EXPECTED_MAPS) + cert_for_salt + b"\x00" * 32 + b"lianyu_dex_v3___"
    return hmac.new(cert_hash, salt, hashlib.sha256).digest()


def extract_cert_sha256_from_keystore(keystore: Path, store_pass: str, alias: str) -> bytes | None:
    try:
        kt = subprocess.run(
            [
                "keytool", "-list", "-v",
                "-keystore", str(keystore),
                "-storepass", store_pass,
                "-alias", alias,
            ],
            capture_output=True,
            timeout=30,
        )
        out = kt.stdout.decode("utf-8", errors="replace")
        for line in out.splitlines():
            if "SHA256:" in line:
                cert_hex = line.split("SHA256:")[-1].strip().replace(":", "").replace(" ", "")
                if len(cert_hex) == 64:
                    return bytes.fromhex(cert_hex)
    except Exception as exc:
        print(f"  WARNING: keystore cert extract failed: {exc}")
    return None


def extract_cert_sha256_from_apk(apk_path: Path) -> bytes | None:
    """Extract signer cert SHA-256 via apksigner (works for v2/v3 signed APKs)."""
    try:
        bt = find_build_tools()
        apksigner = bt / ("apksigner.bat" if os.name == "nt" else "apksigner")
        cmd = [str(apksigner), "verify", "--print-certs", str(apk_path)]
        if os.name == "nt":
            result = subprocess.run(["cmd", "/c"] + cmd, capture_output=True, timeout=60)
        else:
            result = subprocess.run(cmd, capture_output=True, timeout=60)
        out = (result.stdout or b"").decode("utf-8", errors="replace")
        err = (result.stderr or b"").decode("utf-8", errors="replace")
        text = out + "\n" + err
        for line in text.splitlines():
            # Signer #1 certificate SHA-256 digest: abcd...
            if "SHA-256 digest:" in line or "SHA256 digest:" in line:
                cert_hex = line.split("digest:")[-1].strip().replace(":", "").replace(" ", "")
                if len(cert_hex) == 64:
                    return bytes.fromhex(cert_hex)
    except Exception as exc:
        print(f"  WARNING: APK cert extract failed: {exc}")
    return None


def encrypt_ctr(data: bytes, key: bytes) -> bytes:
    """Fast DEX stream cipher v2 (must match nativeDecryptDex).

    Layout: IV(16) || ciphertext
    For each 4KiB block:
      seed = HMAC-SHA256(key, IV || be64(block_idx) || 8x00)
      keystream chunks = SHA256(seed || be32(sub))  # 32B each
    """
    block_size = 4096
    iv = os.urandom(16)
    enc = bytearray(iv)
    total_blocks = (len(data) + block_size - 1) // block_size
    for block_idx in range(total_blocks):
        offset = block_idx * block_size
        chunk = data[offset:offset + block_size]
        seed = hmac.new(key, struct.pack(">16sQ8x", iv, block_idx), hashlib.sha256).digest()
        produced = 0
        sub = 0
        while produced < len(chunk):
            ks = hashlib.sha256(seed + struct.pack(">I", sub)).digest()
            n = min(32, len(chunk) - produced)
            for j in range(n):
                enc.append(chunk[produced + j] ^ ks[j])
            produced += n
            sub += 1
    return bytes(enc)


def build_shell_dex() -> Path:
    print("\n═══ Build pure-Java shell DEX ═══")
    android_jar = find_android_jar()
    bt = find_build_tools()
    work = SHELL_WORK
    if work.exists():
        shutil.rmtree(work)
    cls = work / "classes"
    dex = work / "dex"
    cls.mkdir(parents=True)
    dex.mkdir(parents=True)

    missing = [n for n in SHELL_SOURCES if not (STABLE_SHELL_SRC / n).exists()]
    if missing:
        sys.exit(f"Shell sources missing in {STABLE_SHELL_SRC}: {', '.join(missing)}")

    java_files = [str(STABLE_SHELL_SRC / n) for n in SHELL_SOURCES]
    run(["javac", "-cp", str(android_jar), "-d", str(cls)] + java_files, timeout=60)

    jar = work / "shell.jar"
    run(["jar", "cf", str(jar), "-C", str(cls), "."], timeout=30)

    d8 = bt / ("d8.bat" if os.name == "nt" else "d8")
    run([str(d8), "--lib", str(android_jar), "--release", "--output", str(dex), str(jar)], timeout=60)

    out = dex / "classes.dex"
    if not out.exists():
        sys.exit("shell classes.dex not produced by d8")
    size = out.stat().st_size
    print(f"  shell DEX: {size:,} bytes ({size / 1024:.1f} KB)")
    if size > 64 * 1024:
        print("  WARNING: shell DEX unexpectedly large (>64KB); check for accidental deps")
    return out


def encrypt_business_dex(apk_path: Path, dex_key: bytes) -> dict[str, bytes]:
    print("\n═══ Encrypt business DEX ═══")
    assets: dict[str, bytes] = {}
    with zipfile.ZipFile(apk_path, "r") as zf:
        dex_names = sorted(
            n for n in zf.namelist()
            if n.startswith("classes") and n.endswith(".dex") and "/" not in n
        )
        if not dex_names:
            sys.exit(f"No root classes*.dex in {apk_path}")
        for name in dex_names:
            data = zf.read(name)
            enc = encrypt_ctr(data, dex_key)
            out_name = name.replace(".dex", ".dat")
            assets[f"assets/shell/{out_name}"] = enc
            print(f"  {name} ({len(data):,} B) → assets/shell/{out_name} ({len(enc):,} B)")

    real_app = b"com.yunian.ai.YuNianApplication"
    assets["assets/shell/app_meta.bin"] = encrypt_ctr(real_app, dex_key)
    print(f"  app_meta.bin → com.yunian.ai.YuNianApplication")
    return assets


def assemble_thin_shell(
    apk_path: Path,
    shell_dex: Path,
    encrypted_assets: dict[str, bytes],
    out_apk: Path,
) -> None:
    print("\n═══ Assemble thin-shell APK ═══")
    shell_bytes = shell_dex.read_bytes()
    tmp = out_apk.with_suffix(out_apk.suffix + ".tmp")
    if tmp.exists():
        tmp.unlink()

    kept = 0
    stripped_dex = 0
    stripped_profile = 0
    with zipfile.ZipFile(apk_path, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            name = item.filename
            if name.startswith("META-INF/"):
                continue
            # 见 STRIPPED_PROFILE_ENTRIES 注释：内嵌 baseline profile 绑定的是**原始业务
            # DEX** 的 checksum，root classes.dex 换壳后必然失配，会导致整包安装失败。
            if name in STRIPPED_PROFILE_ENTRIES:
                stripped_profile += 1
                continue
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                if name == "classes.dex":
                    info = zipfile.ZipInfo(filename="classes.dex")
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zout.writestr(info, shell_bytes)
                    kept += 1
                else:
                    stripped_dex += 1
                continue
            # Drop any previous shell assets; we rewrite them below.
            if name.startswith("assets/shell/"):
                continue
            zout.writestr(item, zin.read(name))
            kept += 1

        for name, data in encrypted_assets.items():
            info = zipfile.ZipInfo(filename=name)
            info.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(info, data)

    if out_apk.exists():
        out_apk.unlink()
    shutil.move(str(tmp), str(out_apk))
    print(f"  replaced root classes.dex with shell ({len(shell_bytes):,} B)")
    print(f"  stripped secondary root DEX: {stripped_dex}")
    print(f"  stripped stale baseline profile entries: {stripped_profile}")
    print(f"  injected encrypted assets: {len(encrypted_assets)}")
    print(f"  output: {out_apk} ({out_apk.stat().st_size / (1024 * 1024):.2f} MB)")


def sign_apk(apk_path: Path, keystore: Path, store_pass: str, key_pass: str, alias: str) -> None:
    print("\n═══ Sign APK ═══")
    bt = find_build_tools()
    apksigner = bt / ("apksigner.bat" if os.name == "nt" else "apksigner")
    signed = apk_path.with_suffix(".signed.apk")
    if signed.exists():
        signed.unlink()
    # Secrets travel via the child environment, never via argv: argv is visible
    # to other processes and `run()` echoes it into build logs.
    child_env = dict(os.environ)
    child_env["YUNIAN_THIN_SHELL_KS_PASS"] = store_pass
    child_env["YUNIAN_THIN_SHELL_KEY_PASS"] = key_pass
    cmd = [
        str(apksigner), "sign",
        "--ks", str(keystore),
        "--ks-pass", "env:YUNIAN_THIN_SHELL_KS_PASS",
        "--ks-key-alias", alias,
        "--key-pass", "env:YUNIAN_THIN_SHELL_KEY_PASS",
        "--out", str(signed),
        str(apk_path),
    ]
    if os.name == "nt":
        run(["cmd", "/c"] + cmd, timeout=120, env=child_env)
    else:
        run(cmd, timeout=120, env=child_env)
    shutil.move(str(signed), str(apk_path))
    print(f"  signed: {apk_path}")


ALLOWED_ROOT_CLASSES = {
    "Lcom/yunian/ai/security/StaticApkShell;",
    "Lcom/yunian/ai/security/SActivity;",
    "Lcom/yunian/ai/security/MethodRecoveryEngine;",
}

# String markers that must NOT appear in root shell DEX.
# Note: shell may legitimately contain the real Application class name string
# ("com.yunian.ai.YuNianApplication") for reflection bootstrap — do not ban that.
FORBIDDEN_ROOT_MARKERS = (
    "Lcom/yunian/ai/YuNianApplication;",
    "Lcom/yunian/ai/MainActivity;",
    "Lkotlin/",
    "Lkotlinx/",
    "Landroidx/compose",
    "Lcom/yunian/ai/feature/",
    "Lcom/yunian/ai/network/AiService;",
    "Lcom/yunian/ai/database/AppDatabase;",
    "Lcom/yunian/ai/security/YuNianShellApplication;",
)

# AGP 内嵌的 baseline profile（供 ART 在安装时 dexopt 使用）。profile 内部按 dex 文件
# 记录 checksum（ProfileCompilationInfo，每 dex 一个 dex_checksum）。
#
# ⚠️ 瘦壳会把 root classes.dex 从业务 DEX（约 16 MB）替换为壳 DEX（约 13 KB），
# 二者 checksum 必然不同 ⇒ 安装时 dexopt 加载 profile 失败：
#     Warning: Error occurred during dexopt when processing external profiles:
#       Failed to load profile '.../base.apk.prof': The profile does not match the APK
#       (The checksums in the profile do not match the checksums of the .dex files in the APK)
# vivo/OPPO 等 ROM 会把该 dexopt 警告当作**安装失败**（`pm` 返回非 0，`adb install` 报
# `Completed with warning(s)` 后退出码 1）——即整包无法安装。
#
# 业务 DEX 此时已加密为 assets/shell/*.dat，不可能被 AOT 编译，profile 已完全无意义，
# 因此直接在瘦壳打包时丢弃，并在黑盒门禁中断言其不存在（防回归）。
STRIPPED_PROFILE_ENTRIES = (
    "assets/dexopt/baseline.prof",
    "assets/dexopt/baseline.profm",
)


def find_dexdump() -> Path | None:
    bt = find_build_tools()
    candidate = bt / ("dexdump.exe" if os.name == "nt" else "dexdump")
    if candidate.exists():
        return candidate
    matches = sorted((SDK / "build-tools").glob("*/dexdump*"), reverse=True)
    return matches[0] if matches else None


def list_root_dex_classes(apk_path: Path) -> list[str]:
    dexdump = find_dexdump()
    if dexdump is None:
        print("  WARNING: dexdump not found; skipping class-level black-box scan")
        return []
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        with zipfile.ZipFile(apk_path, "r") as zf:
            zf.extract("classes.dex", tmp_path)
        result = subprocess.run(
            [str(dexdump), "-f", str(tmp_path / "classes.dex")],
            capture_output=True,
            timeout=60,
        )
        text = (result.stdout or b"").decode("utf-8", errors="replace")
        classes: list[str] = []
        for line in text.splitlines():
            line = line.strip()
            # Class descriptor  : 'Lcom/yunian/ai/security/StaticApkShell;'
            if "Class descriptor" in line and "'" in line:
                desc = line.split("'", 1)[1].rsplit("'", 1)[0]
                classes.append(desc)
        return classes


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_offline_envelope(
    apk_path: Path,
    shell_dex: Path,
    encrypted_assets: dict[str, bytes],
    cert_sha: bytes | None,
    out_json: Path,
) -> None:
    print("\n═══ Offline signed envelope ═══")
    with zipfile.ZipFile(apk_path, "r") as zf:
        native_libs = sorted(n for n in zf.namelist() if n.startswith("lib/") and n.endswith(".so"))
        native_digests = {n: sha256_bytes(zf.read(n)) for n in native_libs}
        shell_asset_digests = {
            n: sha256_bytes(zf.read(n))
            for n in sorted(zf.namelist())
            if n.startswith("assets/shell/")
        }
        root_dex_digest = sha256_bytes(zf.read("classes.dex"))

    envelope = {
        "format": "lianyu.offline_envelope.v1",
        "apk_sha256": sha256_file(apk_path),
        "root_classes_dex_sha256": root_dex_digest,
        "shell_dex_build_sha256": sha256_file(shell_dex),
        "signer_cert_sha256": cert_sha.hex() if cert_sha else None,
        "assets_shell": shell_asset_digests,
        "native_libs": native_digests,
        "encrypted_asset_count": len(encrypted_assets),
        "policy": {
            "max_root_dex_bytes": 64 * 1024,
            "allowed_root_classes": sorted(ALLOWED_ROOT_CLASSES),
            "forbid_business_symbols_in_root_dex": True,
        },
    }
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(
        __import__("json").dumps(envelope, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"  wrote {out_json}")


def verify_thin_shell(apk_path: Path, max_shell_bytes: int = 64 * 1024) -> None:
    print("\n═══ Verify thin-shell layout ═══")
    with zipfile.ZipFile(apk_path, "r") as zf:
        root_dex = [n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex") and "/" not in n]
        shell_assets = [n for n in zf.namelist() if n.startswith("assets/shell/")]
        if root_dex != ["classes.dex"]:
            sys.exit(f"FAIL: expected only root classes.dex, found {root_dex}")
        classes_size = zf.getinfo("classes.dex").file_size
        print(f"  root classes.dex: {classes_size:,} bytes")
        if classes_size > max_shell_bytes:
            sys.exit(f"FAIL: root classes.dex too large ({classes_size} > {max_shell_bytes})")
        # 门禁：内嵌 baseline profile 必须已被剥离，否则安装时 dexopt checksum 校验失败
        # ⇒ vivo/OPPO 等 ROM 直接判安装失败（不是可忽略的警告）。详见 STRIPPED_PROFILE_ENTRIES。
        leaked_profiles = [n for n in zf.namelist() if n in STRIPPED_PROFILE_ENTRIES]
        if leaked_profiles:
            sys.exit(
                "FAIL: stale baseline profile still embedded "
                f"{leaked_profiles} — its dex checksums no longer match the shell DEX; "
                "installation will fail on ROMs that treat the dexopt warning as fatal."
            )
        print("  [OK] no stale baseline profile embedded (install-safe)")
        if "assets/shell/classes.dat" not in shell_assets:
            sys.exit("FAIL: missing assets/shell/classes.dat")
        if "assets/shell/app_meta.bin" not in shell_assets:
            sys.exit("FAIL: missing assets/shell/app_meta.bin")
        print(f"  shell assets: {len(shell_assets)}")
        for name in sorted(shell_assets):
            print(f"    {name}: {zf.getinfo(name).file_size:,} B")

        # Black-box: root DEX must not contain business symbols as plain strings.
        root_bytes = zf.read("classes.dex")
        for marker in FORBIDDEN_ROOT_MARKERS:
            if marker.encode("utf-8") in root_bytes:
                sys.exit(f"FAIL: forbidden business marker in root DEX: {marker}")

    classes = list_root_dex_classes(apk_path)
    if classes:
        print(f"  root class count: {len(classes)}")
        for desc in classes:
            print(f"    {desc}")
        unexpected = [c for c in classes if c not in ALLOWED_ROOT_CLASSES]
        if unexpected:
            sys.exit(f"FAIL: unexpected root DEX classes: {unexpected}")
        missing = sorted(ALLOWED_ROOT_CLASSES - set(classes))
        if missing:
            sys.exit(f"FAIL: missing required shell classes: {missing}")
    print("  [OK] thin-shell layout OK (black-box gate passed)")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Package YuNian thin-shell APK")
    p.add_argument("--apk", required=True, help="Input Gradle APK path")
    p.add_argument("--out", required=True, help="Output thin-shell APK path")
    p.add_argument("--sign", action="store_true", help="Re-sign output APK")
    p.add_argument("--release-key", action="store_true",
                   help="Use release keystore + YUNIAN_* env for key derivation/signing")
    p.add_argument("--keystore", default="", help="Keystore path override")
    p.add_argument("--store-pass", default="", help="Keystore password override")
    p.add_argument("--key-pass", default="", help="Key password override")
    p.add_argument("--alias", default="", help="Key alias override")
    p.add_argument("--max-shell-bytes", type=int, default=64 * 1024,
                   help="Fail if shell DEX exceeds this size")
    p.add_argument("--envelope", default="",
                   help="Write offline envelope JSON path (default: <out>.envelope.json)")
    p.add_argument("--promote", action="store_true",
                   help="Backup input APK as *.plain.apk and replace it with thin-shell output")
    return p.parse_args()


def main() -> None:
    args = parse_args()
    apk_path = Path(args.apk).resolve()
    out_apk = Path(args.out).resolve()
    if not apk_path.exists():
        sys.exit(f"Input APK not found: {apk_path}")

    use_release = bool(args.release_key)
    if use_release:
        keystore = Path(args.keystore or (PROJECT / "release.keystore"))
        store_pass = args.store_pass or os.environ.get("YUNIAN_STORE_PASSWORD", "")
        key_pass = args.key_pass or os.environ.get("YUNIAN_KEY_PASSWORD", "")
        alias = args.alias or os.environ.get("YUNIAN_KEY_ALIAS", "your_alias")
        if args.sign and (not store_pass or not key_pass):
            sys.exit("Release signing requires YUNIAN_STORE_PASSWORD and YUNIAN_KEY_PASSWORD")
    else:
        keystore = Path(args.keystore or (Path.home() / ".android" / "debug.keystore"))
        store_pass = args.store_pass or "android"
        key_pass = args.key_pass or "android"
        alias = args.alias or "androiddebugkey"

    # Prefer APK signer cert (matches runtime PackageManager cert), then keystore.
    cert_sha = extract_cert_sha256_from_apk(apk_path)
    if cert_sha is None and keystore.exists():
        cert_sha = extract_cert_sha256_from_keystore(keystore, store_pass, alias)
    if cert_sha:
        print(f"  cert SHA256: {cert_sha.hex()[:16]}...")
        dex_key = derive_dex_key(cert_sha, use_actual_cert=use_release)
    else:
        print("  WARNING: using fallback DEX key (cert unavailable)")
        dex_key = _FALLBACK_KEY

    shell_dex = build_shell_dex()
    if shell_dex.stat().st_size > args.max_shell_bytes:
        sys.exit(
            f"Shell DEX too large: {shell_dex.stat().st_size} > {args.max_shell_bytes}. "
            "Pure Java shell must stay KB-level."
        )

    encrypted = encrypt_business_dex(apk_path, dex_key)
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    assemble_thin_shell(apk_path, shell_dex, encrypted, out_apk)

    if args.sign:
        if not keystore.exists():
            sys.exit(f"Keystore not found: {keystore}")
        sign_apk(out_apk, keystore, store_pass, key_pass, alias)

    verify_thin_shell(out_apk, max_shell_bytes=args.max_shell_bytes)

    envelope_path = Path(args.envelope).resolve() if args.envelope else Path(str(out_apk) + ".envelope.json")
    write_offline_envelope(out_apk, shell_dex, encrypted, cert_sha, envelope_path)

    if args.promote:
        plain_backup = apk_path.with_name(apk_path.stem + "-plain" + apk_path.suffix)
        if plain_backup.exists():
            plain_backup.unlink()
        shutil.copy2(apk_path, plain_backup)
        shutil.copy2(out_apk, apk_path)
        print(f"  promoted thin-shell → {apk_path}")
        print(f"  plain backup → {plain_backup}")

    print("\n═══ DONE ═══")
    print(f"  {out_apk}")
    print(f"  envelope: {envelope_path}")


if __name__ == "__main__":
    main()
