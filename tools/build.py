#!/usr/bin/env python3
"""YuNian One-Click APK Builder — strips ContentProviders, injects shell DEX."""

import zipfile, shutil, os, sys, subprocess, glob, re, argparse, tempfile, struct, hashlib, hmac, zlib

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHELL_SRC = os.path.join(PROJECT, "app/build/tmp/ultimate_shell/src/com/yunian/ai/security")
STABLE_SHELL_SRC = os.path.join(PROJECT, "app/src/shell/java/com/yunian/ai/security")
SHELL_WORK = os.path.join(PROJECT, "app/build/tmp/ultimate_shell")
LOCALAPPDATA = os.environ.get("LOCALAPPDATA", "")

def _resolve_sdk():
    """按优先级解析 Android SDK 根目录。

    1) ANDROID_HOME / ANDROID_SDK_ROOT 环境变量（CI 与本地通用）
    2) 项目 local.properties 的 sdk.dir（Android Studio 写入的权威路径）
    3) 兜底 %LOCALAPPDATA%/Android/Sdk

    与 tools/package_thin_shell.py 的解析口径保持一致。
    原先只认 LOCALAPPDATA，在 SDK 安装于其它盘（如 D:\\Android\\Sdk）的机器上
    会解析到不存在的 android.jar，导致壳 DEX 编译时所有 Android 类"找不到符号"。
    """
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        candidate = os.environ.get(key)
        if candidate and os.path.isdir(candidate):
            return candidate
    try:
        with open(os.path.join(PROJECT, "local.properties"), "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("sdk.dir="):
                    # Java properties 转义：\: -> : ，\\ -> \
                    value = line[len("sdk.dir="):].strip().replace("\\:", ":").replace("\\\\", "\\")
                    if os.path.isdir(value):
                        return value
    except OSError:
        pass
    return os.path.join(LOCALAPPDATA, "Android", "Sdk")

SDK = _resolve_sdk()
BT = os.path.join(SDK, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
APKTOOL = os.path.join(PROJECT, "tools", "apktool.jar")
CPP_DIR = os.path.join(PROJECT, "core", "security", "src", "main", "cpp")
WB_TABLES_INC = os.path.join(CPP_DIR, "wb_tables.inc")
G_VMP_PAYLOAD_CPP = os.path.join(CPP_DIR, "g_vmp_payload_data.cpp")
G_VMP_CONFIG_H = os.path.join(CPP_DIR, "g_vmp_config.h")
PYTHON = sys.executable

# cert obs from nativeDeriveDexKey (obfuscation mask)
CERT_OBS = bytes([
    0x4e,0x0d,0xa5,0x67,0x7e,0xc7,0x29,0x22,0x5f,0xbc,0x9e,0xf0,0x7a,0x73,0xf0,0x88,
    0x41,0x64,0x2b,0x4f,0x39,0xef,0x22,0xca,0xe1,0x5f,0x78,0x49,0x9a,0x41,0x13,0xec,
])
# Expected maps CRC64 from nativeDeriveDexKey — maps_crc64_stable() formula:
#   CRC64(permissions + ' ' + '/' + basename)
# = CRC64(\"r-xp /liblianyu_shell.so\") = 0x8e7beee5d9b3c6e4
# Stable across SO recompiles (extracts only metadata, not .text content).
EXPECTED_MAPS = 0x8e7beee5d9b3c6e4

def crc64(data):
    """CRC64 matching native crc64_buf() — poly 0xC96C5795D7870F42, init=~0, xorout=~0.
       LEFT-shift table-driven (matching native non-standard implementation).
       EXPECTED_MAPS = CRC64('r-xp /liblianyu_shell.so') = 0x8e7beee5d9b3c6e4
       This value is STABLE across SO recompilations — maps_crc64_stable()
       extracts only permissions + '/' + basename, NOT .text content."""
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

def derive_dex_key(cert_sha256, use_actual_cert):
    """Derive DEX encryption key matching nativeDeriveDexKey().
    Debug: cert_for_salt = cert_hash (hardcoded, matches native is_debug path)
    Release: cert_for_salt = actual cert SHA-256 (set via nativeSetApkCert)
    """
    # cert_hash = cert_obs[i] ^ (0xC3 ^ (i * 0x9D)) — fixed value matching native
    cert_hash = bytes(CERT_OBS[i] ^ ((0xC3 ^ (i * 0x9D)) & 0xFF) for i in range(32))
    # Salt: maps_crc(8) || cert_for_salt(32) || hw_sig(32) || "lianyu_dex_v3___"(16) = 88 bytes
    # Debug: cert_for_salt = cert_hash; Release: cert_for_salt = cert_sha256[:32]
    cert_for_salt = cert_sha256[:32] if use_actual_cert and len(cert_sha256) >= 32 else cert_hash
    salt = struct.pack('<Q', EXPECTED_MAPS) + cert_for_salt + b'\x00' * 32 + b'lianyu_dex_v3___'
    return hmac.new(cert_hash, salt, hashlib.sha256).digest()

# Fallback XOR_KEY for when cert is unavailable
_FALLBACK_KEY = bytes([
    0x50,0x59,0x65,0x1c,0xb6,0xac,0x74,0xf7,0xf5,0x4c,0x5d,0x32,0x75,0x80,0x1f,0x51,
    0x0b,0x3c,0x1f,0x48,0x3e,0x20,0xc7,0xd7,0x32,0x60,0x17,0xcc,0x23,0xf1,0xe9,0x53,
])

# Vivo multi-DEX: put unencrypted business DEX as classes2.dex (system auto-loads)
VIVO_MULTIDEX = os.environ.get("YUNIAN_VIVO_MULTIDEX", "").lower() in ("1", "true", "yes")

# Dynamically computed encryption key — populated in main()
DEX_KEY = _FALLBACK_KEY

SHELL_SO_LIST = ["lib/arm64-v8a/liblianyu_shell.so",
                 "lib/arm64-v8a/liblianyu_security.so"]

# SOs to pack (encrypt .text section)
# Both SOs now have decrypt-stub.cpp compiled in, enabling self-decryption
# of the .text section at runtime before JNI_OnLoad executes.
PACK_SO_LIST = ["liblianyu_shell.so", "liblianyu_security.so"]
PACKED_SO_DIR = os.path.join(PROJECT, "app/build/tmp/ultimate_shell/packed_so")

def run(cmd, timeout=120):
    printable = ' '.join(cmd) if isinstance(cmd, list) else cmd
    # 不回显签名口令：apksigner 等调用把 --ks-pass/--key-pass 的明文口令拼在命令行里，
    # 原样打印会让口令落进构建日志。统一把 pass:XXX 脱敏为 pass:****。
    printable = re.sub(r"pass:\S+", "pass:****", printable)
    print(f"  $ {printable}")
    result = subprocess.run(cmd, capture_output=True, timeout=timeout)
    if result.returncode != 0:
        if result.stdout:
            print(result.stdout.decode("utf-8", errors="replace"))
        if result.stderr:
            print(result.stderr.decode("utf-8", errors="replace"))
        result.check_returncode()
    return result

def find_gradle_apk(variant):
    """Find the APK emitted by Gradle for the requested variant."""
    apk_dir = os.path.join(PROJECT, "app", "build", "outputs", "apk", variant)
    candidates = [
        os.path.join(apk_dir, f"app-{variant}.apk"),
        os.path.join(apk_dir, f"app-{variant}-unsigned.apk"),
    ]
    candidates.extend(sorted(glob.glob(os.path.join(apk_dir, "*.apk")), key=os.path.getmtime, reverse=True))
    for apk in candidates:
        if os.path.exists(apk):
            return apk
    sys.exit(f"Gradle APK not found in {apk_dir}")

def get_cert_crc64(keystore_path, store_pass, alias):
    """Compute CRC64 of signing certificate (matching native crc64_buf),
    truncated to 32 bits for VMP_BUILD_SEED (uint32_t)."""
    try:
        r = subprocess.run(
            ["keytool", "-exportcert", "-keystore", keystore_path,
             "-storepass", store_pass, "-alias", alias],
            capture_output=True, timeout=30)
        if r.returncode == 0 and r.stdout:
            return crc64(r.stdout) & 0xFFFFFFFF
    except Exception as e:
        print(f"  WARNING: cert CRC64 failed ({e})")
    return int.from_bytes(os.urandom(4), 'little')


def phase0_wb_aes():
    """Generate White-Box AES lookup tables (wb_tables.inc) before Gradle build.

    Without this step, whitebox-aes.cpp #include "wb_tables.inc" fails to compile,
    or uses stale/placeholder tables — the AES key is effectively known.
    """
    print("\n═══ Phase 0: WB-AES Table Generation ═══")
    setup_wb = os.path.join(PROJECT, "tools", "setup_wb_key.py")
    harden_wb = os.path.join(PROJECT, "tools", "wb_tables_harden.py")

    # Skip if wb_tables.inc already exists and is newer than gen_wb_tables.py
    gen_wb = os.path.join(PROJECT, "tools", "gen_wb_tables.py")
    if os.path.exists(WB_TABLES_INC) and os.path.exists(gen_wb):
        if os.path.getmtime(WB_TABLES_INC) > os.path.getmtime(gen_wb):
            print("  wb_tables.inc up-to-date — skipping")
            return

    if not os.path.exists(setup_wb):
        print("  WARNING: setup_wb_key.py not found — skipping WB-AES generation")
        return

    # Try to use existing key file, otherwise generate a new one
    keyfile = os.path.join(PROJECT, ".lianyu_wb_key")
    env_key = os.environ.get("YUNIAN_WB_KEY")

    if env_key:
        print("  Using YUNIAN_WB_KEY from environment")
        run([PYTHON, setup_wb, "--output", WB_TABLES_INC], timeout=60)
    elif os.path.exists(keyfile):
        print(f"  Using key from {keyfile}")
        run([PYTHON, setup_wb, "--keyfile", keyfile, "--output", WB_TABLES_INC], timeout=60)
    else:
        print("  Generating new WB-AES key (saved to .lianyu_wb_key)")
        run([PYTHON, setup_wb, "--generate", "--keyfile", keyfile, "--output", WB_TABLES_INC], timeout=60)

    # Post-process: move tables to .text section + recompute CRC32 checksums
    if os.path.exists(harden_wb) and os.path.exists(WB_TABLES_INC):
        print("  Hardening wb_tables.inc (.text section + CRC32)...")
        run([PYTHON, harden_wb, WB_TABLES_INC], timeout=30)

    if os.path.exists(WB_TABLES_INC):
        size_kb = os.path.getsize(WB_TABLES_INC) // 1024
        print(f"  ✅ wb_tables.inc: {size_kb}KB")
    else:
        print("  ❌ wb_tables.inc not generated — native build will fail!")
        sys.exit(1)


def phase0b_vmp_payload(seed_hex):
    """Generate g_vmp_payload_data.cpp + g_vmp_config.h + g_ss_config.h.

    Reads encrypted DEX from assets (if present) and embeds it as a C array.
    Also randomizes VMP algorithm constants and shell method names per-build.
    """
    print("\n═══ Phase 0b: VMP Payload Generation ═══")
    gen_py = os.path.join(PROJECT, "tools", "gen_payload_cpp.py")
    enc_dex = os.path.join(PROJECT, "app/src/main/assets/yunian_shell/classes.bin")

    if not os.path.exists(gen_py):
        print("  WARNING: gen_payload_cpp.py not found — skipping")
        return

    if not os.path.exists(enc_dex):
        print("  Skipped — no encrypted DEX (classes.bin) found")
        # Still generate config headers (g_vmp_config.h + g_ss_config.h)
        # gen_payload_cpp.py requires a file arg, so create a dummy
        enc_dex = os.path.join(PROJECT, "app/build/tmp/.empty_payload")
        os.makedirs(os.path.dirname(enc_dex), exist_ok=True)
        if not os.path.exists(enc_dex):
            open(enc_dex, 'wb').write(b'\x00' * 16)

    crc = hex(zlib.crc32(open(enc_dex, 'rb').read()) & 0xFFFFFFFF)
    # SM4 key — in production this should come from KMS; use a per-build random key
    sm4_key = os.urandom(16).hex()

    run([PYTHON, gen_py, enc_dex, G_VMP_PAYLOAD_CPP, crc, sm4_key, seed_hex], timeout=120)
    print(f"  ✅ g_vmp_payload_data.cpp + g_vmp_config.h + g_ss_config.h generated")


def phase3b_vmp_randomize(seed_hex):
    """Randomize VMP opcodes — must run BEFORE ndk-build."""
    print("\n═══ Phase 3b: VMP Opcode Randomization ═══")
    rand_ops = os.path.join(PROJECT, "tools", "randomize_vmp_ops.py")
    if not os.path.exists(rand_ops):
        print("  WARNING: randomize_vmp_ops.py not found — skipping")
        return
    run([PYTHON, rand_ops, "--seed", seed_hex], timeout=30)
    print("  ✅ VMP opcodes randomized")


def phase6_pack_so():
    """Encrypt SO .text sections with pack_so.py.

    Without this, SO code is in plaintext inside the APK — trivially disassemblable.
    """
    print("\n═══ Phase 6a: SO .text Encryption ═══")
    pack_so = os.path.join(PROJECT, "tools", "pack_so.py")
    if not os.path.exists(pack_so):
        sys.exit("pack_so.py not found — SO encryption cannot continue")

    # Find SO build output directory
    so_base = None
    for variant_name in ["Release", "Debug"]:
        dirs = glob.glob(os.path.join(PROJECT, "core/security/build/intermediates/cxx",
                                      variant_name, "*", "obj", "local"))
        if dirs:
            so_base = max(dirs, key=os.path.getmtime)
            break

    if not so_base:
        sys.exit("No compiled SOs found — SO encryption cannot continue")

    shutil.rmtree(PACKED_SO_DIR, ignore_errors=True)
    packed_count = 0
    for abi in os.listdir(so_base):
        for so_name in PACK_SO_LIST:
            so_path = os.path.join(so_base, abi, so_name)
            if not os.path.exists(so_path):
                continue
            print(f"  [{abi}] encrypting {so_name}...", end=" ", flush=True)
            out_dir = os.path.join(PACKED_SO_DIR, abi)
            os.makedirs(out_dir, exist_ok=True)
            tmp = os.path.join(out_dir, so_name)
            try:
                run([PYTHON, pack_so, "--input", so_path, "--output", tmp,
                     "--project-root", PROJECT], timeout=60)
                packed_count += 1
                print(f"OK ({os.path.getsize(tmp)//1024}KB)")
            except subprocess.CalledProcessError as e:
                detail = e.stderr.decode('utf-8', errors='replace')[-200:] if e.stderr else ''
                sys.exit(f"SO encryption failed for {abi}/{so_name}: {detail}")

    if packed_count == 0:
        sys.exit("No SOs were encrypted — production build cannot continue")

    print(f"  ✅ {packed_count} SO(s) encrypted")
    return PACKED_SO_DIR


def phase6b_patch_crc32(apk_path, config_dir):
    """Inject SO .text CRC32 when the runtime still uses the legacy sentinel."""
    print("\n═══ Phase 6b: SO CRC32 Integrity Injection ═══")
    integrity_cpp = os.path.join(config_dir, "integrity-guard.cpp")
    if os.path.exists(integrity_cpp):
        with open(integrity_cpp, encoding="utf-8", errors="replace") as f:
            integrity_source = f.read()
        if "deobfuscate_ig_value(IG_EXPECTED_TEXT_CRC32_OBF)" not in integrity_source:
            print("  Runtime SO integrity uses HMAC self-check; legacy CRC32 injection skipped")
            return False

    patch_py = os.path.join(PROJECT, "tools", "patch_so_crc32.py")
    if not os.path.exists(patch_py):
        print("  WARNING: patch_so_crc32.py not found — skipping CRC32 injection")
        return False
    if not os.path.exists(apk_path):
        print(f"  WARNING: APK not found ({apk_path}) — skipping")
        return False
    run([PYTHON, patch_py, "--apk", apk_path, "--config-dir", config_dir], timeout=60)
    print("  ✅ CRC32 integrity values patched")
    return True

def shell_dex():
    """Compile shell DEX from source."""
    print("\n═══ Shell DEX ═══")
    shell_source_names = ["StaticApkShell.java", "SActivity.java", "MethodRecoveryEngine.java"]
    os.makedirs(SHELL_SRC, exist_ok=True)
    for name in shell_source_names:
        target = os.path.join(SHELL_SRC, name)
        source = os.path.join(STABLE_SHELL_SRC, name)
        # Always copy to ensure latest source is used
        if os.path.exists(source):
            shutil.copy(source, target)

    cls = os.path.join(SHELL_WORK, "classes"); dex = os.path.join(SHELL_WORK, "dex")
    if os.path.exists(cls): shutil.rmtree(cls, ignore_errors=True)
    if os.path.exists(dex): shutil.rmtree(dex, ignore_errors=True)
    os.makedirs(cls); os.makedirs(dex)
    java_files = [os.path.join(SHELL_SRC, f) for f in shell_source_names
                  if os.path.exists(os.path.join(SHELL_SRC, f))]
    missing = [f for f in shell_source_names
               if not os.path.exists(os.path.join(SHELL_SRC, f))]
    if missing:
        sys.exit("Shell source missing: " + ", ".join(missing) + f" in {SHELL_SRC}")
    run(["javac","-cp",ANDROID_JAR,"-d",cls] + java_files, timeout=30)
    jar = os.path.join(SHELL_WORK, "shell.jar")
    run(["jar","cf",jar,"-C",cls,"."], timeout=10)
    d8 = os.path.join(BT, "d8.bat") if os.name=="nt" else os.path.join(BT,"d8")
    run([d8,"--lib",ANDROID_JAR,"--release","--output",dex,jar], timeout=30)
    out = os.path.join(dex, "classes.dex")
    print(f"  {os.path.getsize(out)}B")
    return out

def dex_ctr_encrypt(data, key):
    """Fast DEX stream cipher v2 — MUST match nativeDecryptDex (dex-extractor.cpp).

    Layout: IV(16) || ciphertext
    For each 4KiB block:
      seed = HMAC-SHA256(key, IV || be64(block_idx) || 8x00)   # 32B
      keystream chunks = SHA256(seed || be32(sub))              # 32B each
    """
    import hashlib as _hl, hmac as _hm
    block_size = 4096
    iv = os.urandom(16)
    enc = bytearray(iv)
    total_blocks = (len(data) + block_size - 1) // block_size
    for block_idx in range(total_blocks):
        offset = block_idx * block_size
        chunk = data[offset:offset + block_size]
        seed = _hm.new(key, struct.pack(">16sQ8x", iv, block_idx), _hl.sha256).digest()
        produced = 0
        sub = 0
        while produced < len(chunk):
            ks = _hl.sha256(seed + struct.pack(">I", sub)).digest()
            n = min(32, len(chunk) - produced)
            for j in range(n):
                enc.append(chunk[produced + j] ^ ks[j])
            produced += n
            sub += 1
    return bytes(enc)


def encrypt_dex(src_apk):
    """HMAC-SHA256 CTR encrypt all .dex files + app_meta.bin (real Application class name)."""
    import hashlib as _hl, hmac as _hm
    print("\n═══ DEX Encryption ═══")
    work = tempfile.mkdtemp(prefix="lianyu_dex_")
    extra_dex = tempfile.mkdtemp(prefix="lianyu_extra_dex_")
    count = 0
    with zipfile.ZipFile(src_apk, "r") as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".dex"): continue
            data = z.read(name)
            if VIVO_MULTIDEX:
                extra_name = name
                if count == 0 and extra_name == "classes.dex":
                    extra_name = "classes2.dex"
                open(os.path.join(extra_dex, extra_name), "wb").write(data)
            enc = dex_ctr_encrypt(data, DEX_KEY)
            out_name = name.replace("/","_").replace(".dex",".dat")
            open(os.path.join(work, out_name), "wb").write(enc)
            count += 1
            print(f"  {name} → {out_name} {len(enc)//1024}KB")

    # Encrypt real Application class name as app_meta.bin
    real_app_class = "com.yunian.ai.YuNianApplication"
    data = real_app_class.encode("utf-8")
    enc = dex_ctr_encrypt(data, DEX_KEY)
    open(os.path.join(work, "app_meta.bin"), "wb").write(enc)
    print(f"  app_meta.bin → {real_app_class} ({len(data)}B + 16B IV)")

    print(f"  {count} DEX + 1 meta")
    return work, count, extra_dex

def assemble(shell_dex, dex_dir, extra_dex_dir, repacked, variant, keystore, ks_pass, key_alias, key_pass, packed_so_dir=None):
    """Replace DEX + SOs + encrypted DEX → sign. Optionally add extra DEX for Vivo."""
    print("\n═══ Assembly ═══")
    out = os.path.join(PROJECT, f"YuNian-v2.apk")
    tmp = out + ".tmp"
    shell = open(shell_dex, "rb").read()

    so_base = None
    for v in [variant.capitalize(), "Release", "Debug"]:
        dirs = glob.glob(os.path.join(PROJECT,"core/security/build/intermediates/cxx",v,"*","obj","local"))
        if dirs: so_base = max(dirs, key=os.path.getmtime); break
    if not so_base: print("  WARNING: No compiled SOs")

    dex_files = {}
    for f in os.listdir(dex_dir):
        p = os.path.join(dex_dir, f)
        dex_files[f"assets/shell/{f}"] = open(p, "rb").read()

    # Extra unencrypted DEX for Vivo (classes2.dex, classes3.dex, ...)
    extra_dex_entries = {}
    if os.path.isdir(extra_dex_dir):
        for f in sorted(os.listdir(extra_dex_dir)):
            if f == "classes.dex": continue
            extra_dex_entries[f] = open(os.path.join(extra_dex_dir, f), "rb").read()
    if extra_dex_entries:
        print(f"  Adding {len(extra_dex_entries)} unencrypted DEX: {list(extra_dex_entries.keys())}")

    with zipfile.ZipFile(repacked, "r") as zin:
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == "classes.dex": data = shell
                elif item.filename.startswith("META-INF/"): continue
                elif item.filename.startswith("assets/shell/"): continue  # stale entries (e.g. Gradle thin-shell) — we rewrite these below
                elif item.filename.startswith("classes") and item.filename.endswith(".dex"): continue
                elif item.filename.endswith(".packed.so"): continue
                else:
                    for so in SHELL_SO_LIST:
                        if item.filename == so and so_base:
                            abi = os.path.dirname(so).replace("lib/", "")
                            sp = os.path.join(packed_so_dir or so_base, abi, os.path.basename(so))
                            if os.path.exists(sp): data = open(sp,"rb").read(); break
                zout.writestr(item, data)
            for name, data in dex_files.items(): zout.writestr(name, data)
            for name, data in extra_dex_entries.items(): zout.writestr(name, data)

    shutil.move(tmp, out)
    apksigner = os.path.join(BT, "apksigner.bat") if os.name=="nt" else os.path.join(BT,"apksigner")
    signed = out.replace(".apk", "-signed.apk")
    if os.path.exists(signed):
        os.remove(signed)
    run(["cmd","/c",apksigner,"sign","--ks",keystore,"--ks-pass",f"pass:{ks_pass}",
         "--ks-key-alias",key_alias,"--key-pass",f"pass:{key_pass}","--out",signed,out])
    shutil.move(signed, out)
    shutil.rmtree(dex_dir, ignore_errors=True)
    if os.path.isdir(extra_dex_dir): shutil.rmtree(extra_dex_dir, ignore_errors=True)
    print(f"  {os.path.getsize(out)//1048576}MB → {out}")
    return out

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--release", action="store_true")
    p.add_argument("--no-build", action="store_true")
    p.add_argument("--skip-wb-aes", action="store_true",
                   help="Skip WB-AES table generation (use existing wb_tables.inc)")
    p.add_argument("--skip-vmp", action="store_true",
                   help="Skip VMP payload generation and opcode randomization")
    p.add_argument("--skip-so-encrypt", action="store_true",
                   help="Skip SO .text encryption")
    p.add_argument("--skip-crc32", action="store_true",
                   help="Skip SO CRC32 integrity injection")
    args = p.parse_args()
    variant = "release" if args.release else "debug"
    print(f"═══ YuNian {variant.upper()} Build ═══")
    print(f"  SDK: {SDK}")
    if VIVO_MULTIDEX:
        print(f"  VIVO mode: unencrypted multi-DEX (system auto-loads)")

    # ── Resolve signing config (needed for VMP seed) ──
    if args.release:
        ks = os.path.join(PROJECT, "release.keystore")
        store_pass = os.environ.get("YUNIAN_STORE_PASSWORD", "")
        key_pass = os.environ.get("YUNIAN_KEY_PASSWORD", "")
        alias = os.environ.get("YUNIAN_KEY_ALIAS", "your_alias")
        missing = [name for name, value in [
            ("YUNIAN_STORE_PASSWORD", store_pass),
            ("YUNIAN_KEY_PASSWORD", key_pass),
        ] if not value]
        if missing:
            sys.exit(f"Release signing requires: {', '.join(missing)}")
        if not os.path.exists(ks):
            sys.exit(f"Release keystore not found: {ks}")
    else:
        ks = os.path.join(os.environ["USERPROFILE"], ".android", "debug.keystore")
        store_pass = "android"; key_pass = "android"; alias = "androiddebugkey"

    # ── Compute VMP seed from signing cert CRC64 (truncated to uint32) ──
    cert_crc64_low32 = get_cert_crc64(ks, store_pass, alias)
    seed_hex = f"0x{cert_crc64_low32:08X}"
    print(f"  VMP seed (cert CRC64→u32): {seed_hex}")

    # ── Phase 0: WB-AES table generation (before Gradle) ──
    if not args.skip_wb_aes:
        phase0_wb_aes()

    # ── Phase 0b: VMP payload + config generation (before Gradle) ──
    if not args.skip_vmp:
        phase0b_vmp_payload(seed_hex)

    # ── Phase 3b: VMP opcode randomization (before Gradle ndk-build) ──
    if not args.skip_vmp:
        phase3b_vmp_randomize(seed_hex)

    sdex = shell_dex()
    if args.no_build:
        gradle_apk = find_gradle_apk(variant)
    else:
        gradlew = os.path.join(PROJECT, "gradlew.bat")
        gradle_cmd = [gradlew, f"assemble{variant.capitalize()}", "--no-daemon", "-q"]
        # 可选的 JDK 覆盖（机器无关）：gradle.properties 里的 org.gradle.java.home /
        # org.gradle.java.installations.paths 可能是某台机器专有路径，其它机器上 Gradle 会
        # 直接报 "Value ... is invalid (Java home supplied is invalid)" 而无法构建/打包。
        # 通过环境变量注入 -D 覆盖，既不改动受跟踪的配置，也让各机器用自己的 JDK。
        #   YUNIAN_GRADLE_JAVA_HOME   运行 Gradle 的 JDK（Windows 示例：D:/Android Studio/jbr）
        #   YUNIAN_JDK_INSTALLATIONS  供 toolchain 解析的 JDK 列表（逗号分隔，可给多个）
        _java_home = os.environ.get("YUNIAN_GRADLE_JAVA_HOME", "").strip()
        if _java_home:
            gradle_cmd.append(f"-Dorg.gradle.java.home={_java_home}")
        _jdk_paths = os.environ.get("YUNIAN_JDK_INSTALLATIONS", "").strip()
        if _jdk_paths:
            gradle_cmd.append(f"-Dorg.gradle.java.installations.paths={_jdk_paths}")
        if args.release:
            # Skip Gradle's built-in thin-shell pipeline: build.py implements the
            # full Ultimate Shell hardening itself, and it must consume the PLAIN
            # APK (multi-MB business DEX) — otherwise Gradle already stripped the
            # DEX into assets/shell/*.dat and we'd re-encrypt a stub + duplicate
            # ZIP entries (ApkFormatException in apksigner).
            gradle_cmd.extend(["-x", "lintVitalAnalyzeRelease", "-x", "lintVitalReportRelease", "-x", "lintVitalRelease", "-PyunianSkipThinShell=true"])
        run(gradle_cmd, timeout=600)
        gradle_apk = find_gradle_apk(variant)

    # Derive DEX encryption key from actual signing cert (matches nativeDeriveDexKey)
    global DEX_KEY
    try:
        derived = False
        kt = subprocess.run(
            ["keytool", "-list", "-v", "-keystore", ks, "-storepass", store_pass, "-alias", alias],
            capture_output=True, timeout=30
        )
        out = kt.stdout.decode('utf-8', errors='replace')
        # Extract SHA-256 from keytool output (format: "SHA256: AB:CD:...")
        for line in out.split('\n'):
            if "SHA256:" in line:
                cert_hex = line.split("SHA256:")[-1].strip().replace(':', '').replace(' ', '')
                if len(cert_hex) == 64:
                    cert_sha = bytes.fromhex(cert_hex)
                    DEX_KEY = derive_dex_key(cert_sha, use_actual_cert=args.release)
                    derived = True
                    print(f"  Derived encryption key from cert SHA256: {cert_hex[:16]}...")
                    break
        if not derived:
            print("  WARNING: Could not extract cert — using fallback key")
    except Exception as e:
        print(f"  WARNING: Key derivation failed ({e}) — using fallback key")

    # ── Phase 6a: SO .text encryption (after Gradle, before assembly) ──
    packed_so_dir = None
    if not args.skip_so_encrypt:
        packed_so_dir = phase6_pack_so()

    dex_dir, count, extra_dex = encrypt_dex(gradle_apk)
    repacked = gradle_apk

    final = assemble(sdex, dex_dir, extra_dex, repacked, variant, ks, store_pass, alias, key_pass, packed_so_dir)

    # ── Phase 6b: CRC32 integrity injection (after signing, then re-sign) ──
    if not args.skip_crc32:
        if phase6b_patch_crc32(final, CPP_DIR):
            # Re-sign after CRC32 patching
            apksigner = os.path.join(BT, "apksigner.bat") if os.name == "nt" else os.path.join(BT, "apksigner")
            signed = final.replace(".apk", "-signed.apk")
            if os.path.exists(signed):
                os.remove(signed)
            run(["cmd", "/c", apksigner, "sign", "--ks", ks, "--ks-pass", f"pass:{store_pass}",
                 "--ks-key-alias", alias, "--key-pass", f"pass:{key_pass}", "--out", signed, final])
            shutil.move(signed, final)
            print(f"  CRC32 patched + re-signed: {os.path.getsize(final)//1048576}MB")

    if args.release:
        desk = os.path.join(os.environ.get("USERPROFILE",""), "Desktop", "YuNian-release.apk")
        shutil.copy(final, desk)
        print(f"\n  Desktop: {desk}")

    print(f"\n═══ DONE ═══")

if __name__ == "__main__":
    main()