#!/usr/bin/env python3
"""
package_shell.py — Simple, reliable APK shell packing.

Extracts classes.dex, encrypts with XOR, stores as asset.
At runtime, StaticApkShell decrypts and loads via InMemoryDexClassLoader.
"""
import zipfile, struct, sys, os

SHELL_KEY = bytes([0x4C,0x69,0x61,0x6E,0x59,0x75,0x53,0x68,0x65,0x6C,0x6C,0x4B,0x65,0x79,0x32,0x35])

def encrypt(data, key):
    result = bytearray(data)
    for i, b in enumerate(result):
        result[i] = b ^ key[i % len(key)] ^ (i * 0x9D & 0xFF)
    return bytes(result)

def main():
    apk_path = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/release/app-release.apk"
    out_dir = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/yunian_shell"
    os.makedirs(out_dir, exist_ok=True)

    with zipfile.ZipFile(apk_path, 'r') as zf:
        dex_files = sorted([n for n in zf.namelist() if n.endswith('.dex')])
        for dex_name in dex_files:
            data = zf.read(dex_name)
            encrypted = encrypt(data, SHELL_KEY)
            # Write header: [original_size:4 LE] [encrypted_data...]
            blob = struct.pack('<I', len(data)) + encrypted
            out_name = dex_name.replace('/', '_').replace('.dex', '.bin')
            with open(os.path.join(out_dir, out_name), 'wb') as f:
                f.write(blob)
            print(f"[{dex_name}] {len(data):,}B → {out_name} ({len(blob):,}B encrypted)")

    print(f"Done → {out_dir}/")

if __name__ == '__main__':
    main()
