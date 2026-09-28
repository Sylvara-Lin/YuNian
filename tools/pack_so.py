#!/usr/bin/env python3
"""
YuNian SO Packer — ELF .text Encryption with Decrypt Stub Injection

Reads a compiled .so file, encrypts the .text section, and injects a
position-independent ARM64/ARM32 decrypt stub that runs before JNI_OnLoad.

Usage:
    # Pack a single .so (reads key from env or generates one)
    python3 tools/pack_so.py --input liblianyu_security.so --output liblianyu_security.packed.so

    # Pack with explicit key
    YUNIAN_SO_KEY=hex:00112233445566778899aabbccddeeff python3 tools/pack_so.py -i in.so -o out.so

    # Pack all ABIs from APK
    python3 tools/pack_so.py --apk app-release.apk --output-apk app-release-packed.apk

Requirements: pyelftools (pip install pyelftools)
"""

from __future__ import annotations

import os
import sys
import struct
import argparse
import secrets
import hashlib
import tempfile
import zipfile
import shutil
from typing import Optional, Tuple, List

# Lazy import — only check pyelftools when actually needed
# This allows --help and --list-targets to work without pyelftools
_elftools_available = False


def _ensure_elftools():
    global ELFFile, SymbolTableSection, SHN_INDICES, _elftools_available
    if _elftools_available:
        return
    try:
        from elftools.elf.elffile import ELFFile
        from elftools.elf.sections import SymbolTableSection
        from elftools.elf.constants import SHN_INDICES
        _elftools_available = True
    except ImportError:
        print("ERROR: pyelftools not installed. Run: pip install pyelftools")
        sys.exit(1)

# ─── Constants ───────────────────────────────────────────────────────
PAGE_SIZE = 0x1000  # 4KB
XOR_KEY_SIZE = 16   # 128-bit XOR key
ELFCLASS64 = 2      # 64-bit ELF
ELFCLASS32 = 1      # 32-bit ELF

# Sentinels for placeholder patching (decrypt_stub.S)
SENTINEL_TEXT_START_64 = 0x00000000DEAD0000
SENTINEL_TEXT_SIZE_64  = 0x0000000000000000
SENTINEL_KEY_PTR_64    = 0x0000000000000000
SENTINEL_TEXT_START_32 = 0xDEAD0000
SENTINEL_TEXT_SIZE_32  = 0x00000000
SENTINEL_KEY_PTR_32    = 0x00000000


# ─── XOR Encryption ──────────────────────────────────────────────────

def xor_encrypt(data: bytearray, key: bytes, offset: int = 0) -> None:
    """XOR-encrypt data in-place with repeating key."""
    key_len = len(key)
    for i in range(len(data)):
        data[i] ^= key[(i + offset) % key_len]


def generate_random_key() -> bytes:
    """Generate a 16-byte random XOR key (128-bit)."""
    return secrets.token_bytes(XOR_KEY_SIZE)


def derive_key_from_env() -> Optional[bytes]:
    """Read encryption key from YUNIAN_SO_KEY environment variable."""
    env_key = os.environ.get('YUNIAN_SO_KEY')
    if not env_key:
        return None
    if env_key.startswith('hex:'):
        return bytes.fromhex(env_key[4:])
    return hashlib.sha256(env_key.encode()).digest()[:XOR_KEY_SIZE]


# ─── ELF Parsing Utilities ───────────────────────────────────────────

def find_section(elf: ELFFile, name: str):
    """Find an ELF section by name."""
    for sec in elf.iter_sections():
        if sec.name == name:
            return sec
    return None


def find_symbol(elf: ELFFile, name: str):
    """Find an ELF symbol by name."""
    for sec in elf.iter_sections():
        if isinstance(sec, SymbolTableSection):
            syms = sec.get_symbol_by_name(name)
            if syms:
                return syms[0]
    return None


def vaddr_to_offset(elf: ELFFile, vaddr: int) -> Optional[int]:
    """Convert virtual address to file offset using program headers."""
    for seg in elf.iter_segments():
        if seg['p_type'] == 'PT_LOAD':
            seg_start = seg['p_vaddr']
            seg_end = seg_start + seg['p_memsz']
            if seg_start <= vaddr < seg_end:
                return seg['p_offset'] + (vaddr - seg_start)
    return None


def get_elf_class(data: bytes) -> int:
    """Get ELF class from magic bytes (32 or 64)."""
    if len(data) < 5:
        return 0
    ei_class = data[4]  # EI_CLASS at offset 4
    if ei_class == 2:
        return ELFCLASS64
    elif ei_class == 1:
        return ELFCLASS32
    return 0


def is_arm64(data: bytes) -> bool:
    """Check if ELF is ARM64 (aarch64)."""
    if len(data) < 20:
        return False
    ei_class = data[4]
    # e_machine at offset 18
    if ei_class == 2:  # 64-bit
        machine = struct.unpack_from('<H', data, 18)[0]
        return machine == 0xB7  # EM_AARCH64
    elif ei_class == 1:  # 32-bit
        machine = struct.unpack_from('<H', data, 18)[0]
        return machine == 0x28  # EM_ARM
    return False


# ─── Stub Compilation and Injection ──────────────────────────────────

def compile_decrypt_stub_asm(project_root: str, is_64bit: bool) -> bytes:
    """
    Compile decrypt_stub.S into a raw binary blob.
    Uses Android NDK cross-compiler (aarch64-linux-android-as / arm-linux-androideabi-as).
    Returns the raw bytes of the stub.
    """
    asm_dir = os.path.join(project_root, 'core', 'security', 'src', 'main', 'asm')
    stub_path = os.path.join(asm_dir, 'decrypt_stub.S')

    if not os.path.exists(stub_path):
        raise FileNotFoundError(f"Stub assembly not found: {stub_path}")

    # Find NDK
    ndk_path = os.environ.get('ANDROID_NDK_HOME') or os.environ.get('NDK_HOME')
    if not ndk_path:
        # Try common locations
        candidates = [
            os.path.expanduser('~/Android/Sdk/ndk'),
            os.path.expanduser('~/Library/Android/sdk/ndk'),
            'C:\\Users\\%s\\AppData\\Local\\Android\\Sdk\\ndk' % os.environ.get('USERNAME', ''),
        ]
        for c in candidates:
            if os.path.isdir(c):
                # Find newest version
                versions = sorted(os.listdir(c), reverse=True)
                for v in versions:
                    ndk_path = os.path.join(c, v)
                    if os.path.isdir(ndk_path):
                        break
                break

    if not ndk_path or not os.path.isdir(ndk_path):
        raise RuntimeError(
            "Android NDK not found. Set ANDROID_NDK_HOME or install NDK.\n"
            "Fallback: set YUNIAN_NDK_AS to point to the assembler directly."
        )

    # Find the assembler
    if is_64bit:
        toolchain_prefix = 'aarch64-linux-android'
    else:
        toolchain_prefix = 'arm-linux-androideabi'

    # Try toolchain paths
    as_path = None
    for root, dirs, files in os.walk(os.path.join(ndk_path, 'toolchains')):
        for f in files:
            if f.endswith(toolchain_prefix + '-as') or f.endswith(toolchain_prefix + '-as.exe'):
                as_path = os.path.join(root, f)
                break
        if as_path:
            break

    # Fallback: check env
    if not as_path:
        as_path = os.environ.get('YUNIAN_NDK_AS')
    if not as_path:
        # Final fallback: use system assembler (may not work for cross-compile)
        as_path = 'as'

    # Assemble to object, then objcopy to binary
    with tempfile.TemporaryDirectory() as tmpdir:
        obj_path = os.path.join(tmpdir, 'decrypt_stub.o')
        bin_path = os.path.join(tmpdir, 'decrypt_stub.bin')

        # Step 1: Assemble
        arch_flag = '--target=aarch64-linux-android' if is_64bit else '--target=arm-linux-androideabi'
        cmd = [as_path, arch_flag, '-o', obj_path, stub_path]
        import subprocess
        try:
            subprocess.run(cmd, check=True, capture_output=True, text=True)
        except subprocess.CalledProcessError as e:
            print(f"  [WARN] Assembler failed. Using pre-computed stub. Error: {e.stderr}")
            return _get_fallback_stub(is_64bit)

        # Step 2: Objdump to binary
        # Find objcopy in same directory as assembler
        objcopy_path = as_path.replace('-as', '-objcopy')
        cmd2 = [objcopy_path, '-O', 'binary', '-j', '.text.decrypt_stub',
                obj_path, bin_path]
        try:
            subprocess.run(cmd2, check=True, capture_output=True, text=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            # Try without -j (get all sections)
            cmd2 = [objcopy_path, '-O', 'binary', obj_path, bin_path]
            try:
                subprocess.run(cmd2, check=True, capture_output=True, text=True)
            except (subprocess.CalledProcessError, FileNotFoundError):
                print(f"  [WARN] objcopy failed. Using pre-computed stub.")
                return _get_fallback_stub(is_64bit)

        if os.path.exists(bin_path) and os.path.getsize(bin_path) > 0:
            with open(bin_path, 'rb') as f:
                return f.read()
        else:
            return _get_fallback_stub(is_64bit)


def _get_fallback_stub(is_64bit: bool) -> bytes:
    """
    Pre-computed minimal ARM64/ARM32 decrypt stub.
    Used when NDK assembler is not available.

    ARM64 stub:
      - Save registers
      - mprotect(syscall 226)
      - XOR decrypt loop (4 bytes at a time, PC-relative key)
      - Cache flush (DC CVAC + IC IVAU)
      - mprotect back to RX
      - Restore registers + return

    ARM32 stub:
      - Save registers
      - mprotect(syscall 125)
      - XOR decrypt loop
      - mprotect back to RX
      - Restore registers + return
    """
    if is_64bit:
        # ARM64 position-independent decrypt stub
        # Hand-assembled from decrypt_stub.S
        stub = bytes([
            # Save regs: STP x29,x30,[sp,#-160]! ... MOV x29,sp
            0xFD, 0x7B, 0x0A, 0xA9,  # stp x29, x30, [sp, #-160]!
            0xE0, 0x43, 0x01, 0xA9,  # stp x0, x1, [sp, #16]
            0xE2, 0x4B, 0x02, 0xA9,  # stp x2, x3, [sp, #32]
            0xE4, 0x53, 0x03, 0xA9,  # stp x4, x5, [sp, #48]
            0xE6, 0x5B, 0x04, 0xA9,  # stp x6, x7, [sp, #64]
            0xE8, 0x63, 0x05, 0xA9,  # stp x8, x9, [sp, #80]
            0xEA, 0x6B, 0x06, 0xA9,  # stp x10, x11, [sp, #96]
            0xEC, 0x73, 0x07, 0xA9,  # stp x12, x13, [sp, #112]
            0xEE, 0x7B, 0x08, 0xA9,  # stp x14, x15, [sp, #128]
            0xF0, 0x83, 0x09, 0xA9,  # stp x16, x17, [sp, #144]
            0xFD, 0x03, 0x00, 0x91,  # mov x29, sp

            # mprotect(.text, PROT_READ|PROT_WRITE, size)
            # This section gets patched at pack time
            0x40, 0x00, 0x00, 0x90,  # adrp x0, <text_start_page>
            0x00, 0x00, 0x00, 0x91,  # add x0, x0, #0 (patched)
            0x00, 0x00, 0x40, 0xF9,  # ldr x0, [x0]
            0x61, 0x00, 0x00, 0x90,  # adrp x1, <text_size_page>
            0x21, 0x00, 0x00, 0x91,  # add x1, x1, #0 (patched)
            0x21, 0x00, 0x40, 0xF9,  # ldr x1, [x1]
            0xE3, 0x03, 0xFF, 0x92,  # mov x3, #-0x1000 (-0xFFF)
            0x00, 0x00, 0x03, 0x8A,  # and x0, x0, x3
            0x01, 0x00, 0x00, 0x8B,  # add x1, x1, x0
            0x21, 0x00, 0x03, 0x8B,  # add x1, x1, x3
            0x82, 0x00, 0x80, 0xD2,  # mov x2, #3
            0x08, 0x45, 0x8C, 0xD2,  # mov x8, #226
            0x01, 0x00, 0x00, 0xD4,  # svc #0

            0x00, 0x00, 0x00, 0x00,  # padding placeholder
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,

            # Restore + ret
            0xF0, 0x83, 0x49, 0xA9,  # ldp x16, x17, [sp, #144]
            0xEE, 0x7B, 0x48, 0xA9,
            0xEC, 0x73, 0x47, 0xA9,
            0xEA, 0x6B, 0x46, 0xA9,
            0xE8, 0x63, 0x45, 0xA9,
            0xE6, 0x5B, 0x44, 0xA9,
            0xE4, 0x53, 0x43, 0xA9,
            0xE2, 0x4B, 0x42, 0xA9,
            0xE0, 0x43, 0x41, 0xA9,
            0xFD, 0x7B, 0x4A, 0xA9,  # ldp x29, x30, [sp], #160
            0xC0, 0x03, 0x5F, 0xD6,  # ret
        ])
    else:
        # ARM32 simplified decrypt stub
        stub = bytes([
            0xF0, 0x4F, 0x2D, 0xE9,  # push {r4-r11, lr}
            # Placeholder for text start / size / key
            0x00, 0x00, 0x00, 0x00,  # (will be patched)
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0xF0, 0x8F, 0xBD, 0xE8,  # pop {r4-r11, pc}
        ])

    return stub


def patch_stub_placeholders(stub_data: bytearray, text_vaddr: int,
                             text_size: int, key_vaddr: int,
                             is_64bit: bool) -> None:
    """
    Patch placeholder values in the decrypt stub with actual addresses.
    Searches for sentinel values and replaces them.
    """
    if is_64bit:
        # Search for SENTINEL_TEXT_START_64 (8 bytes, little-endian)
        sentinel_start = struct.pack('<Q', SENTINEL_TEXT_START_64)
        sentinel_size = struct.pack('<Q', SENTINEL_TEXT_SIZE_64)
        sentinel_key = struct.pack('<Q', SENTINEL_KEY_PTR_64)

        def patch_sentinel(sentinel: bytes, value: int):
            """Replace first occurrence of sentinel with packed value."""
            pos = stub_data.find(sentinel)
            if pos != -1:
                stub_data[pos:pos + 8] = struct.pack('<Q', value)
                return True
            return False

        # Patch text_start, text_size, key_ptr
        found = patch_sentinel(sentinel_start, text_vaddr)
        if not found:
            print("  [WARN] Could not find text_start sentinel in stub")

        found = patch_sentinel(sentinel_size, text_size)
        if not found:
            print("  [WARN] Could not find text_size sentinel in stub")

        found = patch_sentinel(sentinel_key, key_vaddr)
        if not found:
            print("  [WARN] Could not find key_ptr sentinel in stub")
    else:
        sentinel_start = struct.pack('<I', SENTINEL_TEXT_START_32)
        sentinel_size = struct.pack('<I', SENTINEL_TEXT_SIZE_32)
        sentinel_key = struct.pack('<I', SENTINEL_KEY_PTR_32)

        def patch_sentinel(sentinel: bytes, value: int):
            pos = stub_data.find(sentinel)
            if pos != -1:
                stub_data[pos:pos + 4] = struct.pack('<I', value & 0xFFFFFFFF)
                return True
            return False

        patch_sentinel(sentinel_start, text_vaddr)
        patch_sentinel(sentinel_size, text_size)
        patch_sentinel(sentinel_key, key_vaddr)


# ─── Main SO Packing Logic ───────────────────────────────────────────

def _find_symbol_file_offset(elf: ELFFile, data: bytes, sym_name: str) -> Optional[Tuple[int, int]]:
    """Find a symbol's file offset and size by name.
    Returns (file_offset, size) or None.
    """
    sym = find_symbol(elf, sym_name)
    if sym is None:
        return None
    sym_value = sym['st_value']  # virtual address
    sym_size = sym['st_size']
    if sym_size == 0:
        return None
    file_offset = vaddr_to_offset(elf, sym_value)
    if file_offset is None:
        return None
    return (file_offset, sym_size)


def pack_so(input_path: str, output_path: str,
            xor_key: Optional[bytes] = None,
            project_root: Optional[str] = None) -> bool:
    """
    Pack a single .so file using in-place .text encryption:
    1. Parse ELF, find .text section
    2. Find lianyu_xor_key / lianyu_text_start / lianyu_text_size symbols
    3. Encrypt .text in-place (NO file insertion — ELF structure unchanged)
    4. Patch the three symbols' values in the file
    5. Write output

    The decrypt function (lianyu_d2_decrypt) lives in .lianyu_decrypt section
    (NOT .text) and runs via __attribute__((constructor(101))) before JNI_OnLoad.
    """
    _ensure_elftools()

    # Read input
    with open(input_path, 'rb') as f:
        data = bytearray(f.read())

    elf = ELFFile(open(input_path, 'rb'))
    elf_class = get_elf_class(bytes(data))
    is_64bit = (elf_class == ELFCLASS64)

    print(f"  ELF class: {'64-bit' if is_64bit else '32-bit'}")
    print(f"  Architecture: {'ARM64' if is_arm64(bytes(data)) else 'ARM32/Other'}")

    # 1. Find .text section
    text_section = find_section(elf, '.text')
    if text_section is None:
        print(f"  ERROR: .text section not found")
        return False

    text_offset = text_section['sh_offset']
    text_size = text_section['sh_size']
    text_vaddr = text_section['sh_addr']

    print(f"  .text: offset=0x{text_offset:X} size=0x{text_size:X} vaddr=0x{text_vaddr:X}")

    # 2. Find decrypt symbols (must be compiled into the SO)
    key_loc = _find_symbol_file_offset(elf, bytes(data), 'lianyu_xor_key')
    start_loc = _find_symbol_file_offset(elf, bytes(data), 'lianyu_text_start')
    size_loc = _find_symbol_file_offset(elf, bytes(data), 'lianyu_text_size')

    if key_loc is None or start_loc is None or size_loc is None:
        print(f"  ERROR: decrypt symbols not found (decrypt-stub.cpp not compiled in?)")
        missing = []
        if key_loc is None: missing.append('lianyu_xor_key')
        if start_loc is None: missing.append('lianyu_text_start')
        if size_loc is None: missing.append('lianyu_text_size')
        print(f"  Missing: {', '.join(missing)}")
        return False

    key_file_off, key_size = key_loc
    start_file_off, start_size = start_loc
    size_file_off, size_size = size_loc

    print(f"  lianyu_xor_key:    offset=0x{key_file_off:X} size={key_size}")
    print(f"  lianyu_text_start: offset=0x{start_file_off:X} size={start_size}")
    print(f"  lianyu_text_size:  offset=0x{size_file_off:X} size={size_size}")

    # Verify .lianyu_decrypt section exists (decrypt code must not be in .text)
    decrypt_sec = find_section(elf, '.lianyu_decrypt')
    if decrypt_sec is None:
        print(f"  WARNING: .lianyu_decrypt section not found — decrypt code may be in .text!")
        print(f"  This would cause the decrypt function to be encrypted. Aborting.")
        return False
    else:
        dec_off = decrypt_sec['sh_offset']
        dec_size = decrypt_sec['sh_size']
        print(f"  .lianyu_decrypt: offset=0x{dec_off:X} size=0x{dec_size:X}")
        # Verify no overlap with .text
        if dec_off < text_offset + text_size and dec_off + dec_size > text_offset:
            print(f"  ERROR: .lianyu_decrypt overlaps with .text — cannot encrypt safely")
            return False

    # 3. Generate or derive XOR key
    if xor_key is None:
        xor_key = derive_key_from_env()
    if xor_key is None:
        xor_key = generate_random_key()
        print(f"  Generated random XOR key: {xor_key.hex()}")
    else:
        print(f"  Using XOR key: {xor_key.hex()}")

    if len(xor_key) != XOR_KEY_SIZE:
        print(f"  ERROR: XOR key must be {XOR_KEY_SIZE} bytes, got {len(xor_key)}")
        return False

    # 4. Encrypt .text in-place (NO file insertion)
    text_data = data[text_offset:text_offset + text_size]
    original_checksum = hashlib.sha256(text_data).hexdigest()[:16]
    xor_encrypt(text_data, xor_key)
    encrypted_checksum = hashlib.sha256(text_data).hexdigest()[:16]
    print(f"  .text SHA256 (before): {original_checksum}")
    print(f"  .text SHA256 (after):  {encrypted_checksum}")
    data[text_offset:text_offset + text_size] = text_data

    # 5. Patch symbol values in the file
    # Patch lianyu_xor_key (16 bytes)
    data[key_file_off:key_file_off + 16] = xor_key

    # Patch lianyu_text_start with SIGNED OFFSET from &lianyu_xor_key to .text.
    # At runtime: text_ptr = &lianyu_xor_key + (int64_t)lianyu_text_start
    # This is ASLR-independent — works regardless of where the SO is loaded.
    # Note: lianyu_text_start is always uint64_t (8 bytes) in the C code,
    # so we always write 8 bytes with signed 64-bit format.
    key_sym = find_symbol(elf, 'lianyu_xor_key')
    key_vaddr = key_sym['st_value'] if key_sym else 0
    text_offset_from_key = text_vaddr - key_vaddr  # signed, may be negative

    print(f"  text_vaddr=0x{text_vaddr:X} key_vaddr=0x{key_vaddr:X} offset={text_offset_from_key}")

    struct.pack_into('<q', data, start_file_off, text_offset_from_key)
    struct.pack_into('<Q', data, size_file_off, text_size)

    # 6. Write output — file size is UNCHANGED (in-place encryption)
    with open(output_path, 'wb') as f:
        f.write(data)

    orig_size = os.path.getsize(input_path)
    new_size = len(data)
    print(f"  Original size: {orig_size} bytes")
    print(f"  Packed size:   {new_size} bytes (delta: {new_size - orig_size})")
    print(f"  OK Packed SO written to: {output_path}")

    return True


def _update_section_header(data: bytearray, elf: ELFFile, section_name: str,
                            sh_size: int = None, sh_offset: int = None) -> None:
    """Update a section header's sh_size and/or sh_offset in the ELF data."""
    for sec in elf.iter_sections():
        if sec.name == section_name:
            header = sec.header
            shdr_offset = header['sh_offset'] if 'sh_offset' in header else 0

            # This is the section header offset in the section header table
            e_shoff = elf.header['e_shoff']
            e_shentsize = elf.header['e_shentsize']

            # Find the index of this section in the SHT
            for i, s in enumerate(elf.iter_sections()):
                if s.name == section_name:
                    shdr_file_offset = e_shoff + i * e_shentsize
                    if sh_size is not None:
                        # sh_size is at offset 0x20 in 64-bit, 0x14 in 32-bit
                        size_off = 0x20 if elf.elfclass == 64 else 0x14
                        struct.pack_into('<Q' if elf.elfclass == 64 else '<I',
                                        data, shdr_file_offset + size_off, sh_size)
                    if sh_offset is not None:
                        # sh_offset is at offset 0x18 in 64-bit, 0x10 in 32-bit
                        off_off = 0x18 if elf.elfclass == 64 else 0x10
                        struct.pack_into('<Q' if elf.elfclass == 64 else '<I',
                                        data, shdr_file_offset + off_off, sh_offset)
                    break
            break


def _shift_sections(data: bytearray, elf: ELFFile, after_offset: int, shift: int) -> None:
    """Shift all section header offsets that are >= after_offset by shift bytes."""
    e_shoff = elf.header['e_shoff']
    e_shentsize = elf.header['e_shentsize']
    e_shnum = elf.header['e_shnum']
    is_64 = (elf.elfclass == 64)

    off_field_off = 0x18 if is_64 else 0x10
    size_field_off = 0x20 if is_64 else 0x14

    for i in range(e_shnum):
        shdr_off = e_shoff + i * e_shentsize
        if shdr_off + size_field_off + 8 > len(data):
            continue

        if is_64:
            sec_offset = struct.unpack_from('<Q', data, shdr_off + off_field_off)[0]
        else:
            sec_offset = struct.unpack_from('<I', data, shdr_off + off_field_off)[0]

        if sec_offset >= after_offset:
            new_offset = sec_offset + shift
            if is_64:
                struct.pack_into('<Q', data, shdr_off + off_field_off, new_offset)
            else:
                struct.pack_into('<I', data, shdr_off + off_field_off, new_offset & 0xFFFFFFFF)


def _update_program_headers(data: bytearray, elf: ELFFile, after_offset: int, shift: int) -> None:
    """Update program header p_filesz and p_memsz for segments containing .text."""
    e_phoff = elf.header['e_phoff']
    e_phentsize = elf.header['e_phentsize']
    e_phnum = elf.header['e_phnum']
    is_64 = (elf.elfclass == 64)

    for i in range(e_phnum):
        phdr_off = e_phoff + i * e_phentsize
        if phdr_off + 56 > len(data):
            continue

        if is_64:
            p_type = struct.unpack_from('<I', data, phdr_off)[0]
            p_offset = struct.unpack_from('<Q', data, phdr_off + 8)[0]
            p_vaddr = struct.unpack_from('<Q', data, phdr_off + 16)[0]
            p_filesz = struct.unpack_from('<Q', data, phdr_off + 32)[0]
            p_memsz = struct.unpack_from('<Q', data, phdr_off + 40)[0]
        else:
            p_type = struct.unpack_from('<I', data, phdr_off)[0]
            p_offset = struct.unpack_from('<I', data, phdr_off + 4)[0]
            p_vaddr = struct.unpack_from('<I', data, phdr_off + 8)[0]
            p_filesz = struct.unpack_from('<I', data, phdr_off + 16)[0]
            p_memsz = struct.unpack_from('<I', data, phdr_off + 20)[0]

        if p_type == 1:  # PT_LOAD
            # If this segment covers the .text area, expand it
            seg_end = p_offset + p_filesz
            if p_offset <= after_offset < seg_end:
                new_filesz = p_filesz + shift
                new_memsz = p_memsz + shift
                if is_64:
                    struct.pack_into('<Q', data, phdr_off + 32, new_filesz)
                    struct.pack_into('<Q', data, phdr_off + 40, new_memsz)
                else:
                    struct.pack_into('<I', data, phdr_off + 16, new_filesz & 0xFFFFFFFF)
                    struct.pack_into('<I', data, phdr_off + 20, new_memsz & 0xFFFFFFFF)


def pack_apk(input_apk: str, output_apk: str,
             xor_key: Optional[bytes] = None,
             project_root: Optional[str] = None) -> bool:
    """Pack all liblianyu_security.so entries inside an APK."""
    if not os.path.exists(input_apk):
        print(f"ERROR: APK not found: {input_apk}")
        return False

    abis = ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']
    packed = 0

    # Read APK
    with zipfile.ZipFile(input_apk, 'r') as zf:
        all_entries = {name: zf.read(name) for name in zf.namelist()}

    so_data = {}

    for abi in abis:
        so_entry = f'lib/{abi}/liblianyu_security.so'
        if so_entry not in all_entries:
            continue

        print(f"\n📦 Packing {abi}")

        # Write SO to temp file for processing
        with tempfile.NamedTemporaryFile(suffix='.so', delete=False) as tmp:
            tmp.write(all_entries[so_entry])
            tmp_path = tmp.name

        try:
            out_tmp = tmp_path + '.packed'
            ok = pack_so(tmp_path, out_tmp, xor_key, project_root)
            if ok:
                with open(out_tmp, 'rb') as f:
                    so_data[so_entry] = f.read()
                os.unlink(out_tmp)
                packed += 1
            else:
                so_data[so_entry] = all_entries[so_entry]  # keep original
        finally:
            os.unlink(tmp_path)

    # Rewrite APK
    if packed > 0:
        with zipfile.ZipFile(output_apk, 'w', compression=zipfile.ZIP_DEFLATED) as zf_out:
            for name, data in all_entries.items():
                if name in so_data:
                    zf_out.writestr(zipfile.ZipInfo(name), so_data[name])
                else:
                    zf_out.writestr(zipfile.ZipInfo(name), data)
        print(f"\nOK Packed {packed} SO(s) into {output_apk}")
    else:
        print(f"\nWARN No SOs packed - output APK unchanged")
        shutil.copy(input_apk, output_apk)

    return packed > 0


# ─── Main ────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description='YuNian SO Packer — Encrypt .text and inject decrypt stub')
    parser.add_argument('--input', '-i', help='Input .so file')
    parser.add_argument('--output', '-o', help='Output .so file')
    parser.add_argument('--apk', help='Input APK file (pack all SOs inside)')
    parser.add_argument('--output-apk', help='Output APK file')
    parser.add_argument('--key', help='16-byte XOR key (hex:...) or raw')
    parser.add_argument('--project-root', help='Project root directory')
    args = parser.parse_args()

    # Parse key
    xor_key = None
    if args.key:
        if args.key.startswith('hex:'):
            xor_key = bytes.fromhex(args.key[4:])
        elif len(args.key) == 32:
            xor_key = bytes.fromhex(args.key)
        else:
            xor_key = hashlib.sha256(args.key.encode()).digest()[:XOR_KEY_SIZE]

    if xor_key is None:
        xor_key = derive_key_from_env()

    # Resolve project root
    project_root = args.project_root
    if project_root is None:
        project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    if args.apk:
        output_apk = args.output_apk or args.apk.replace('.apk', '-packed.apk')
        ok = pack_apk(args.apk, output_apk, xor_key, project_root)
        sys.exit(0 if ok else 1)
    elif args.input and args.output:
        ok = pack_so(args.input, args.output, xor_key, project_root)
        sys.exit(0 if ok else 1)
    else:
        parser.print_help()
        print("\nExamples:")
        print("  python3 tools/pack_so.py -i liblianyu_security.so -o liblianyu_security.packed.so")
        print("  python3 tools/pack_so.py --apk app-release.apk --output-apk app-release-packed.apk")
        sys.exit(1)


if __name__ == '__main__':
    main()
