#!/usr/bin/env python3
"""
build_minimal_dex.py — Build a minimal classes.dex for shell APK.

Single-pass DEX builder: all offsets are final and absolute from the start.
No two-pass patching — class_data items are written once with correct code_off.
"""

import struct, hashlib, zlib, sys, os


# ═══════════════════════════════════════
# Shell class definitions
# ═══════════════════════════════════════

SHELL_CLASSES = [
    {"name": "Lcom/yunian/ai/security/StaticApkShell;", "super": "Landroid/app/Application;", "access": 0x0001,
     "methods": [
         ("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00]),
         ("attachBaseContext", "V", "LLandroid/content/Context;", 0x0004, [0x70,0x10,0x02,0x00,0x00,0x00, 0x0E,0x00]),
         ("onCreate", "V", "L", 0x0001, [0x0E,0x00]),
     ]},
    {"name": "Lcom/yunian/ai/security/YuNianShellApplication;", "super": "Landroid/app/Application;", "access": 0x0001,
     "methods": [
         ("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00]),
         ("attachBaseContext", "V", "LLandroid/content/Context;", 0x0004, [0x70,0x10,0x02,0x00,0x00,0x00, 0x0E,0x00]),
         ("onCreate", "V", "L", 0x0001, [0x0E,0x00]),
     ]},
    {"name": "Lcom/yunian/ai/security/NativeBridge;", "super": "Ljava/lang/Object;", "access": 0x0001,
     "methods": [
         ("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00]),
         ("nativeShellInitWithBlob", "V", "[BL", 0x0109, None),  # native
         ("nativeWipeDexHeader", "V", "L", 0x0109, None),
         ("nativeEnableMemoryGuard", "V", "L", 0x0109, None),
         ("nativeAntiHookInit", "V", "L", 0x0109, None),
         ("nativeRecoverClassMethods", "V", "[BL", 0x0109, None),
     ]},
    {"name": "Lcom/yunian/ai/security/MethodRecoveryEngine;", "super": "Ljava/lang/Object;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Lcom/yunian/ai/security/VmpDex2cDispatcher;", "super": "Ljava/lang/Object;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Lcom/yunian/ai/security/G0;", "super": "Landroid/app/Activity;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Lcom/yunian/ai/security/SActivity;", "super": "Landroid/app/Activity;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Lcom/yunian/ai/security/SReceiver;", "super": "Landroid/content/BroadcastReceiver;", "access": 0x0001,
     "methods": [
         ("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00]),
         ("onReceive", "V", "LLandroid/content/Context;Landroid/content/Intent;", 0x0001, [0x0E,0x00]),
     ]},
    {"name": "Lcom/yunian/ai/security/SService;", "super": "Landroid/app/Service;", "access": 0x0001,
     "methods": [
         ("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00]),
         ("onBind", "Landroid/os/IBinder;", "LLandroid/content/Intent;", 0x0001, [0x11,0x00]),
     ]},
    {"name": "Lcom/yunian/ai/security/OnePieceShellGate;", "super": "Ljava/lang/Object;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Lcom/yunian/ai/MainActivity;", "super": "Landroid/app/Activity;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
    {"name": "Landroidx/core/content/FileProvider;", "super": "Landroid/content/ContentProvider;", "access": 0x0001,
     "methods": [("<init>", "V", "L", 0x10001, [0x70,0x10,0x01,0x00,0x00,0x00, 0x0E,0x00])]},
]


def uleb128(v):
    r = bytearray()
    while True:
        b = v & 0x7F; v >>= 7
        if v: b |= 0x80
        r.append(b)
        if not v: break
    return bytes(r)

def wuleb(buf, v):
    buf.extend(uleb128(v))

def build_dex():
    # --- Collect all strings, types, protos, methods, fields ---
    strings = []
    types = []
    protos = []
    methods = []
    fields = []
    
    def add_str(s):
        b = s.encode('latin-1') if isinstance(s, str) else s
        if b not in strings: strings.append(b)
        return strings.index(b)
    
    def add_type(t):
        idx = add_str(t.encode('latin-1') if isinstance(t, str) else t)
        if idx not in types: types.append(idx)
        return types.index(idx)
    
    def add_proto(shorty, rtype, ptypes):
        """shorty: 'VL' = void(Object), rtype: 'V', ptypes: ['Ljava/lang/Object;']"""
        si = add_str(shorty)
        ri = add_type(rtype)
        for i, (s, r, _) in enumerate(protos):
            if s == si and r == ri: return i
        protos.append((si, ri, 0))
        return len(protos) - 1
    
    def add_method(class_name, method_name, proto_shorty, return_type, param_types):
        ci = add_type(class_name)
        pi = add_proto(proto_shorty, return_type, param_types)
        ni = add_str(method_name)
        methods.append((ci, pi, ni))
        return len(methods) - 1
    
    # Build all methods first to populate string/type/proto tables
    class_entries = []
    for cls in SHELL_CLASSES:
        ci = add_type(cls["name"])
        si = add_type(cls["super"])
        method_list = []
        for mname, rtype, pshorty, access, code in cls.get("methods", []):
            # Parse proto shorty: first char = return, rest = params
            rt_map = {'V': 'V', 'Z': 'Z', 'I': 'I', 'J': 'J', 'F': 'F', 'D': 'D',
                      'L': 'Ljava/lang/Object;', '[': '[Ljava/lang/Object;'}
            rt = rt_map.get(rtype[0], 'Ljava/lang/Object;')
            pt_list = []
            for c in pshorty[1:]:
                pt_list.append(rt_map.get(c, 'Ljava/lang/Object;'))
            midx = add_method(cls["name"], mname, pshorty, rt, pt_list)
            method_list.append((midx, access, code))
        class_entries.append((ci, si, cls["access"], method_list, []))
    
    # --- Compute absolute offsets (single pass) ---
    HEADER_SIZE = 0x70
    
    string_ids_off = HEADER_SIZE
    string_ids_size = len(strings)
    
    type_ids_off = string_ids_off + string_ids_size * 4
    type_ids_size = len(types)
    
    proto_ids_off = type_ids_off + type_ids_size * 4
    proto_ids_size = len(protos)
    
    field_ids_off = proto_ids_off + proto_ids_size * 12
    field_ids_size = len(fields)
    
    method_ids_off = field_ids_off + field_ids_size * 8
    method_ids_size = len(methods)
    
    class_defs_off = method_ids_off + method_ids_size * 8
    class_defs_size = len(class_entries)
    
    # Data section: string_data, then class_data, then code_items
    data_start = class_defs_off + class_defs_size * 32
    
    # Build string data
    string_offs = []
    string_data = bytearray()
    for s in strings:
        string_offs.append(data_start + len(string_data))
        wuleb(string_data, len(s))
        string_data.extend(s)
        string_data.append(0)
    
    # Build class_data and code_items (interleaved, single pass)
    class_data_offs = []  # absolute offset of each class_data_item
    class_data_buf = bytearray()
    code_items_buf = bytearray()
    
    for ci, si, access, method_list, field_list in class_entries:
        cdi_start = data_start + len(string_data) + len(class_data_buf)
        class_data_offs.append(cdi_start)
        
        # Separate static/instance fields and direct/virtual methods
        static_fields = []
        instance_fields = []
        direct_methods = []
        virtual_methods = []
        
        for midx, macc, mcode in method_list:
            if macc & 0x0100:  # native
                direct_methods.append((midx, macc, 0))
            else:
                # Non-native: allocate code_item
                code_bytes = mcode or [0x0E, 0x00]
                code_off = data_start + len(string_data) + len(class_data_buf) + len(code_items_buf)
                # code_items_buf starts after class_data_buf, so offset = data_start + len(string_data) + len(class_data_buf) + offset_in_ci
                # But class_data_buf is still growing... we need to handle this differently
                # Actually, all code_items come AFTER all class_data. So:
                code_off = 0  # placeholder, will compute after class_data is done
                direct_methods.append((midx, macc, code_off, code_bytes))
        
        # Write class_data_item (with placeholder code_offs)
        wuleb(class_data_buf, len(static_fields))
        wuleb(class_data_buf, len(instance_fields))
        wuleb(class_data_buf, len(direct_methods))
        wuleb(class_data_buf, len(virtual_methods))
        
        last_fidx = 0
        for fidx in static_fields + instance_fields:
            wuleb(class_data_buf, fidx - last_fidx)
            wuleb(class_data_buf, 0)
            last_fidx = fidx
        
        last_midx = 0
        for entry in direct_methods + virtual_methods:
            midx = entry[0]
            macc = entry[1]
            co = entry[2]  # 0 for native, or placeholder
            wuleb(class_data_buf, midx - last_midx)
            wuleb(class_data_buf, macc)
            wuleb(class_data_buf, co)
            last_midx = midx
    
    # Now class_data_buf is complete. code_items start after string_data + class_data
    code_items_start = data_start + len(string_data) + len(class_data_buf)
    code_items_start = (code_items_start + 3) & ~3  # 4-byte align
    
    # Rebuild class_data with CORRECT code_off values
    class_data_buf_final = bytearray()
    class_data_offs_final = []
    ci_offset = 0  # offset within code_items_buf
    
    for ci, si, access, method_list, field_list in class_entries:
        cdi_start = data_start + len(string_data) + len(class_data_buf_final)
        class_data_offs_final.append(cdi_start)
        
        static_fields = []
        instance_fields = []
        direct_methods = []
        virtual_methods = []
        
        for midx, macc, mcode in method_list:
            if macc & 0x0100:
                direct_methods.append((midx, macc, 0))
            else:
                code_bytes = mcode or [0x0E, 0x00]
                abs_co = code_items_start + ci_offset
                # Build code_item
                code_items_buf.extend(struct.pack('<H', 1))   # regs
                code_items_buf.extend(struct.pack('<H', 0))   # ins
                code_items_buf.extend(struct.pack('<H', 0))   # outs
                code_items_buf.extend(struct.pack('<H', 0))   # tries
                code_items_buf.extend(struct.pack('<I', 0))   # debug
                code_items_buf.extend(struct.pack('<I', len(code_bytes) // 2))
                for b in code_bytes:
                    code_items_buf.append(b)
                ci_offset = len(code_items_buf)
                direct_methods.append((midx, macc, abs_co))
        
        wuleb(class_data_buf_final, len(static_fields))
        wuleb(class_data_buf_final, len(instance_fields))
        wuleb(class_data_buf_final, len(direct_methods))
        wuleb(class_data_buf_final, len(virtual_methods))
        
        last_fidx = 0
        for fidx in static_fields + instance_fields:
            wuleb(class_data_buf_final, fidx - last_fidx)
            wuleb(class_data_buf_final, 0)
            last_fidx = fidx
        
        last_midx = 0
        for midx, macc, co in direct_methods + virtual_methods:
            wuleb(class_data_buf_final, midx - last_midx)
            wuleb(class_data_buf_final, macc)
            wuleb(class_data_buf_final, co)
            last_midx = midx
    
    # Map list
    map_off = code_items_start + len(code_items_buf)
    map_data = bytearray()
    map_data.extend(struct.pack('<I', 1))  # 1 entry in map
    map_data.extend(struct.pack('<H', 0x1000))  # TYPE_MAP_LIST
    map_data.extend(struct.pack('<H', 0))
    map_data.extend(struct.pack('<I', 1))  # size=1
    map_data.extend(struct.pack('<H', 0x0000))  # TYPE_HEADER_ITEM
    map_data.extend(struct.pack('<H', 0))
    map_data.extend(struct.pack('<I', 1))
    map_data.extend(struct.pack('<I', 0))
    
    file_size = map_off + len(map_data)
    
    # --- Write DEX ---
    buf = bytearray()
    
    # Header
    buf.extend(b'dex\n038\x00')
    buf.extend(struct.pack('<I', 0))  # checksum placeholder
    buf.extend(b'\x00' * 20)  # signature placeholder
    buf.extend(struct.pack('<I', file_size))
    buf.extend(struct.pack('<I', HEADER_SIZE))
    buf.extend(struct.pack('<I', 0x12345678))
    buf.extend(struct.pack('<I', 0))      # link_size
    buf.extend(struct.pack('<I', 0))      # link_off
    buf.extend(struct.pack('<I', map_off))
    buf.extend(struct.pack('<I', string_ids_size))
    buf.extend(struct.pack('<I', string_ids_off))
    buf.extend(struct.pack('<I', type_ids_size))
    buf.extend(struct.pack('<I', type_ids_off))
    buf.extend(struct.pack('<I', proto_ids_size))
    buf.extend(struct.pack('<I', proto_ids_off))
    buf.extend(struct.pack('<I', field_ids_size))
    buf.extend(struct.pack('<I', field_ids_off))
    buf.extend(struct.pack('<I', method_ids_size))
    buf.extend(struct.pack('<I', method_ids_off))
    buf.extend(struct.pack('<I', class_defs_size))
    buf.extend(struct.pack('<I', class_defs_off))
    buf.extend(struct.pack('<I', 0))      # data_size
    buf.extend(struct.pack('<I', 0))      # data_off
    
    # String IDs
    for off in string_offs:
        buf.extend(struct.pack('<I', off))
    
    # Type IDs
    for si in types:
        buf.extend(struct.pack('<I', si))
    
    # Proto IDs
    for si, ri, _ in protos:
        buf.extend(struct.pack('<I', si))
        buf.extend(struct.pack('<I', ri))
        buf.extend(struct.pack('<I', 0))
    
    # Field IDs
    for ci, ti, ni in fields:
        buf.extend(struct.pack('<H', ci))
        buf.extend(struct.pack('<H', ti))
        buf.extend(struct.pack('<I', ni))
    
    # Method IDs
    for ci, pi, ni in methods:
        buf.extend(struct.pack('<H', ci))
        buf.extend(struct.pack('<H', pi))
        buf.extend(struct.pack('<I', ni))
    
    # Class defs
    for idx, (ci, si, access, _, _) in enumerate(class_entries):
        buf.extend(struct.pack('<I', ci))
        buf.extend(struct.pack('<I', access))
        buf.extend(struct.pack('<I', si))
        buf.extend(struct.pack('<I', 0))  # interfaces_off
        buf.extend(struct.pack('<I', 0xFFFFFFFF))  # source_file
        buf.extend(struct.pack('<I', 0))  # annotations
        buf.extend(struct.pack('<I', class_data_offs_final[idx]))
        buf.extend(struct.pack('<I', 0))  # static_values
    
    # String data
    buf.extend(string_data)
    
    # Class data
    buf.extend(class_data_buf_final)
    
    # Pad to code_items_start
    while len(buf) < code_items_start:
        buf.append(0)
    
    # Code items
    buf.extend(code_items_buf)
    
    # Map
    while len(buf) < map_off:
        buf.append(0)
    buf.extend(map_data)
    
    # Pad to file_size
    while len(buf) < file_size:
        buf.append(0)
    
    actual = len(buf)
    struct.pack_into('<I', buf, 32, actual)
    
    # Checksum + signature
    for i in range(8, 32):
        buf[i] = 0
    struct.pack_into('<I', buf, 8, zlib.adler32(bytes(buf)) & 0xFFFFFFFF)
    sha = hashlib.sha1(bytes(buf)).digest()
    for i in range(20):
        buf[12 + i] = sha[i]
    
    return bytes(buf)


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <output.dex>"); sys.exit(1)
    out = sys.argv[1]
    os.makedirs(os.path.dirname(out) or '.', exist_ok=True)
    dex = build_dex()
    with open(out, 'wb') as f: f.write(dex)
    print(f"Minimal shell DEX: {out}")
    print(f"  Size: {len(dex):,} bytes uncompressed")
    print(f"  Classes: {len(SHELL_CLASSES)}")

if __name__ == '__main__':
    main()
