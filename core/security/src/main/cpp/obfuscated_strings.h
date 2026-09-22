/**
 * obfuscated_strings.h — XOR-encoded detection strings for all native modules.
 *
 * Every detection string (Frida, Xposed, Magisk, debugger paths, etc.) is
 * stored XOR-obfuscated. The OB_KEY(idx) pattern uses golden-ratio hash
 * so each string has a unique rotating key.
 *
 * Usage:
 *   #include "obfuscated_strings.h"
 *   if (XS_FRIDA(buf)) { ... }
 *   char decoded[64]; DE_XOR_IDX(decoded, OBS_FRIDA, 0);
 */
#ifndef YUNIAN_OBFUSCATED_STRINGS_H
#define YUNIAN_OBFUSCATED_STRINGS_H

#include <cstring>
#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

/* Golden-ratio rotating key per obfuscation index */
#define OB_KEY(idx) ((uint8_t)((idx) * 0x9E3779B9ULL & 0xFF))

/* ── Obfuscated string definitions ── */
/* idx=0  key=0x00 */ #define OBS_FRIDA           0x2E,0x38,0x2D,0x39,0x3D
/* idx=1  key=0xB9 */ #define OBS_FRIDA_AGENT     0xB8,0xAA,0xB1,0xBC,0xA7,0x79,0xA6,0xB1,0xA9,0xAF,0xB2
/* idx=2  key=0x72 */ #define OBS_FRIDA_SERVER    0x3A,0x24,0x3F,0x32,0x37,0x21,0x2E,0x3B,0x24,0x3C,0x2C
/* idx=3  key=0x2B */ #define OBS_GUMJS           0x14,0x01,0x18,0x32,0x05,0x3A
/* idx=4  key=0xE4 */ #define OBS_FRIDAGENT       0xEE,0xEF,0xE0,0xE5,0xEC,0xE4,0xE1,0xE2,0xED
/* idx=5  key=0x9D */ #define OBS_LINJECTOR       0xF3,0xF0,0xE8,0xF7,0xF8,0xE9,0xEB,0xFE,0xEE
/* idx=6  key=0x56 */ #define OBS_XPOSED          0x14,0x15,0x08,0x13,0x11,0x13
/* idx=7  key=0x0F */ #define OBS_XPOSED_CAP      0x4D,0x4E,0x43,0x4C,0x4A,0x4C
/* idx=8  key=0xC8 */ #define OBS_SUBSTRATE       0xBB,0xBD,0xA4,0xBB,0xBC,0xBA,0xA9,0xBC,0xAD
/* idx=9  key=0x81 */ #define OBS_SUBSTRATE_CAP   0xD3,0xD2,0xCD,0xD3,0xD4,0xC2,0xC1,0xD4,0xC5
/* idx=10 key=0x3A */ #define OBS_LSPOSED         0x5D,0x48,0x4B,0x56,0x48,0x5E,0x5F
/* idx=11 key=0xF3 */ #define OBS_LSPOSED_CAP     0x94,0x81,0x82,0x9F,0x81,0x97,0x96
/* idx=12 key=0xAC */ #define OBS_EDXPOSED        0xC7,0xC0,0xCA,0xC1,0xCE,0xC3,0xC0,0xC3
/* idx=13 key=0x65 */ #define OBS_RIRU            0x2F,0x1C,0x2F,0x32
/* idx=14 key=0x1E */ #define OBS_MAGISK          0x7D,0x78,0x79,0x76,0x75,0x72
/* idx=15 key=0xD7 */ #define OBS_MAGISK_CAP      0xB4,0xB1,0xB0,0xBF,0xBC,0xBB
/* idx=16 key=0x90 */ #define OBS_FRIDA_ABS_0     0xFE,0x94,0xF7,0xEE,0xF5,0xF8,0xE3
/* idx=17 key=0x49 */ #define OBS_FRIDA_ABS_1     0x60,0x64,0x31,0x24,0x28,0x23,0x36,0x24,0x60,0x23,0x2C,0x2B,0x24,0x60
/* idx=18 key=0x02 */ #define OBS_FRIDA_ABS_2     0x40,0x72,0x64,0x6B,0x62,0x6B,0x64,0x6F,0x6C
/* idx=19 key=0xBB */ #define OBS_FRIDA_ABS_3     0xD9,0xC9,0xCE,0xD2,0xC8,0xD7,0xC5,0x8D,0xC8,0xC3,0xD2,0xCB
/* idx=20 key=0x74 */ #define OBS_GUMJS_ABS       0x40,0x27,0x25,0x30,0x0D,0x20,0x16
/* idx=21 key=0x2D */ #define OBS_TRACERPID       0x7B,0x76,0x78,0x73,0x76,0x6F,0x78,0x7A,0x6C,0x40
/* idx=22 key=0xE6 */ #define OBS_HTTPCANARY      0x9B,0x9D,0x9D,0x90,0x92,0x8D,0x8A,0x8A,0x8F,0x99
/* idx=23 key=0x9F */ #define OBS_GMAIN           0x33,0x38,0x3C,0x36,0x3D
/* idx=24 key=0x58 */ #define OBS_GDBUS           0x13,0x14,0x1A,0x0F,0x04
/* idx=25 key=0x11 */ #define OBS_AGENT           0x72,0x7E,0x73,0x70,0x60
/* idx=26 key=0xCA */ #define OBS_VAEXPOSED       0xB8,0xBF,0xBE,0x8C,0xB1,0xAE,0xAF,0xA8,0xB2
/* idx=27 key=0x83 */ #define OBS_EXPOSED         0xCE,0xC9,0xCC,0xCF,0xC6,0xC7,0xD0
/* idx=28 key=0x3C */ #define OBS_VMOS            0x77,0x7A,0x76,0x6B
/* idx=29 key=0xF5 */ #define OBS_PARALLEL        0xA1,0xA4,0xA7,0x9A,0x9E,0x9B,0x9B,0x85
/* idx=30 key=0xAE */ #define OBS_VIRTUAL         0xC8,0xC5,0xCD,0xC2,0xC3,0xDA,0xCA
/* idx=31 key=0x67 */ #define OBS_SANDBOX         0x22,0x2D,0x28,0x2B,0x24,0x2D,0x2E
/* idx=32 key=0x20 */ #define OBS_CONTAINER       0x63,0x74,0x72,0x64,0x6B,0x6C,0x72,0x60,0x6C
/* idx=33 key=0xD9 */ #define OBS_ZYGISK          0xBC,0xA0,0xA4,0xB7,0xB0,0xA7
/* idx=34 key=0x92 */ #define OBS_SHAMIKO         0xE6,0xE1,0xE8,0xFD,0xF8,0xFE,0xE4
/* idx=35 key=0x4B */ #define OBS_SUI             0x26,0x23,0x2C
/* idx=36 key=0x04 */ #define OBS_MOMO            0x75,0x76,0x72,0x76
/* idx=37 key=0xBD */ #define OBS_MAGISKPOLICY    0xC6,0xD2,0xD7,0xC8,0xDD,0xDA,0x8C,0xC6,0xD5,0xCB,0xC8,0xD9,0xCA
/* idx=38 key=0x76 */ #define OBS_MAGISKINIT      0x65,0x27,0x38,0x29,0x38,0x3E,0x32,0x2C,0x31,0x26
/* idx=39 key=0x2F */ #define OBS_KSUD            0x62,0x40,0x5A,0x6F
/* idx=40 key=0xE8 */ #define OBS_KERNELSU        0xAA,0xA0,0xAD,0xA2,0xAE,0xA6,0x8D,0xAB
/* idx=41 key=0xA1 */ #define OBS_APD             0xC2,0xEE,0xCA
/* idx=42 key=0x5A */ #define OBS_APATCH          0x12,0x1C,0x1E,0x17,0x13,0x1F
/* idx=43 key=0x13 */ #define OBS_PATH_FRIDA_SERVER    0x69,0x50,0x50,0x46,0x42,0x4C,0x5B,0x51,0x59,0x49,0x45,0x58,0x4A,0x4B,0x58,0x53,0x4D,0x48,0x53,0x55,0x53,0x4E,0x54,0x4A,0x5F,0x59,0x4D,0x55
/* idx=44 key=0xCC */ #define OBS_PATH_FRIDA_SVR16     0x69,0x50,0x50,0x46,0x42,0x4C,0x5B,0x51,0x59,0x49,0x45,0x58,0x4A,0x4B,0x58,0x53,0x4D,0x48,0x53,0x55,0x53,0x4E,0x54,0x4A,0x5F,0x59,0x4D,0x55,0x8E,0x81,0x80
/* idx=45 key=0x85 */ #define OBS_PATH_FRIDA_TMP       0xF1,0xE0,0xE4,0xE9,0xEE,0xE0,0xF2,0xE1,0xF2,0xEB,0xE6,0xEA,0xE0,0xF3,0xFC,0xFD,0xE8,0xF1
/* idx=46 key=0x3E */ #define OBS_PATH_FRIDA_AGENT      0x49,0x58,0x48,0x56,0x52,0x5C,0x4B,0x41,0x49,0x59,0x55,0x4B,0x52,0x41,0x44,0x38,0x56,0x5D,0x43,0x46,0x56,0x5C,0x44
/* idx=47 key=0xF7 */ #define OBS_PATH_RE_FRIDA_SERVER  0xC9,0x98,0x88,0x84,0x81,0x8C,0x82,0x86,0x9E,0x85,0x8B,0x8F,0x86,0x99,0x96,0x97,0x8C,0x8E,0x97
/* idx=48 key=0xB0 */ #define OBS_PATH_GADGET_TMP       0xC4,0xD5,0xD1,0xDC,0xDB,0xD5,0xC7,0xDC,0xDF,0xD2,0xDA,0xDF,0xDD,0xCE,0xBD,0xBE,0xD5,0xCC
/* idx=49 key=0x69 */ #define OBS_PATH_FRIDA_SDCARD     0x14,0x29,0x2D,0x20,0x2D,0x29,0x3B,0x2C,0x22,0x22,0x35,0x2C,0x27,0x28,0x35,0x3E,0x00,0x2D,0x22,0x38,0x3C
/* idx=50 key=0x22 */ #define OBS_PATH_FRIDA_SD         0x4A,0x57,0x53,0x5E,0x59,0x57,0x4F,0x50,0x5E,0x54,0x41,0x50,0x5B,0x5C,0x41,0x4E
/* idx=51 key=0xDB */ #define OBS_PATH_HLUDA_TMP        0xBB,0xCA,0xCE,0xC3,0xC4,0xCA,0xD2,0xC1,0xCF,0xC5,0xD0,0xC7,0xCA,0xD9,0xDE,0xDF,0xCA,0xD5

/* idx=53 key=0x23 */ #define OBS_PATH_MAGISK_SU      0x4E,0x40,0x58,0x52,0x5E,0x5A,0x11,0x42,0x54,0x5A,0x5F,0x48,0x4F,0x5F,0x54,0x41,0x4C,0x5F
/* idx=54 key=0xDC */ #define OBS_PATH_MAGISK_ADB     0x9C,0x81,0x85,0x88,0xEF,0x8F,0x8B,0xE5,0x8B,0x81,0xF3,0x8C,0x80,0x8B,0x85,0x8E

/* idx=56 key=0x7E */ #define OBS_IO_VA_EXPOSED      0x16,0x10,0x03,0x17,0x2D,0x2E,0x1A,0x04,0x08,0x10,0x5E,0x1C,0x0E
/* idx=57 key=0x0D */ #define OBS_VIRTUALXPOSED      0x69,0x6F,0x7E,0x79,0x79,0x68,0x60,0x6E,0x78,0x72,0x7A,0x63,0x66,0x66
/* idx=58 key=0xC2 */ #define OBS_VIRTUALAPP         0x97,0x8F,0x9E,0x91,0x91,0x88,0x80,0xF6,0x9A,0x92,0x8D
/* idx=55 key=0x95 */ #define OBS_PATH_MAGISK_IMG     0x9C,0x81,0x85,0x88,0xEF,0x8F,0x8B,0xE5,0x8B,0x81,0xF3,0x8C,0x80,0x8B,0x85,0x8E,0xEE,0x82,0x8C,0x8D
/* idx=52 key=0x94 */ #define OBS_PATH_GADGET_CFG       0xF0,0x81,0x85,0x88,0x8F,0x81,0x99,0x8E,0x88,0x8C,0x96,0x9B,0x9C,0x85,0x9A,0x84,0x8F,0x8F,0x8B,0x82,0x8A,0x82


/* ── Length macros ── */
#define OBS_LEN_FRIDA         5
#define OBS_LEN_FRIDA_AGENT   11
#define OBS_LEN_FRIDA_SERVER  11
#define OBS_LEN_GUMJS         6
#define OBS_LEN_FRIDAGENT     9
#define OBS_LEN_LINJECTOR     9
#define OBS_LEN_XPOSED        6
#define OBS_LEN_XPOSED_CAP    6
#define OBS_LEN_SUBSTRATE     9
#define OBS_LEN_SUBSTRATE_CAP 9
#define OBS_LEN_LSPOSED       7
#define OBS_LEN_LSPOSED_CAP   7
#define OBS_LEN_EDXPOSED      8
#define OBS_LEN_RIRU          4
#define OBS_LEN_MAGISK        6
#define OBS_LEN_MAGISK_CAP    6
#define OBS_LEN_FRIDA_ABS_0   7
#define OBS_LEN_FRIDA_ABS_1   14
#define OBS_LEN_FRIDA_ABS_2   9
#define OBS_LEN_FRIDA_ABS_3   12
#define OBS_LEN_GUMJS_ABS     7
#define OBS_LEN_TRACERPID     10
#define OBS_LEN_HTTPCANARY    10
#define OBS_LEN_GMAIN         5
#define OBS_LEN_GDBUS         5
#define OBS_LEN_AGENT         5
#define OBS_LEN_VAEXPOSED     9
#define OBS_LEN_EXPOSED       7
#define OBS_LEN_VMOS          4
#define OBS_LEN_PARALLEL      8
#define OBS_LEN_VIRTUAL       7
#define OBS_LEN_SANDBOX       7
#define OBS_LEN_CONTAINER     9
#define OBS_LEN_ZYGISK        6
#define OBS_LEN_SHAMIKO       7
#define OBS_LEN_SUI           3
#define OBS_LEN_MOMO          4
#define OBS_LEN_MAGISKPOLICY  13
#define OBS_LEN_MAGISKINIT    10
#define OBS_LEN_KSUD          4
#define OBS_LEN_KERNELSU      8
#define OBS_LEN_APD           3
#define OBS_LEN_APATCH        6

/* ── Decode obfuscated string into caller buffer ── */
static inline void decode_obs(char* dst, const uint8_t* src, size_t len, uint8_t key) {
    for (size_t i = 0; i < len; i++) dst[i] = (char)(src[i] ^ key);
    dst[len] = '\0';
}

/* ── Check if haystack contains the obfuscated needle ── */
static inline int xstrstr_obs(const char* haystack, const uint8_t* obs, size_t len, uint8_t key) {
    if (!haystack || !obs || len == 0 || len >= 64) return 0;
    char n[64];
    decode_obs(n, obs, len, key);
    int r = (strstr(haystack, n) != NULL);
    for (size_t i = 0; i < len; i++) n[i] = 0;
    return r;
}

#ifdef __cplusplus
}
#endif


/* ── Convenience macros ── */
#define XS_FRIDA(h)       xstrstr_obs(h, g_obs_frida,       sizeof(g_obs_frida),       OB_KEY(0))
#define XS_GUMJS(h)       xstrstr_obs(h, g_obs_gumjs,       sizeof(g_obs_gumjs),       OB_KEY(3))
#define XS_FRIDAGENT(h)   xstrstr_obs(h, g_obs_fridagent,   sizeof(g_obs_fridagent),   OB_KEY(4))
#define XS_LINJECTOR(h)   xstrstr_obs(h, g_obs_linjector,   sizeof(g_obs_linjector),   OB_KEY(5))
#define XS_XPOSED(h)      xstrstr_obs(h, g_obs_xposed,      sizeof(g_obs_xposed),      OB_KEY(6))
#define XS_SUBSTRATE(h)   xstrstr_obs(h, g_obs_substrate,   sizeof(g_obs_substrate),   OB_KEY(8))
#define XS_LSPOSED(h)     xstrstr_obs(h, g_obs_lsposed,     sizeof(g_obs_lsposed),     OB_KEY(10))
#define XS_RIRU(h)        xstrstr_obs(h, g_obs_riru,        sizeof(g_obs_riru),        OB_KEY(13))
#define XS_MAGISK(h)      xstrstr_obs(h, g_obs_magisk,      sizeof(g_obs_magisk),      OB_KEY(14))
#define XS_TRACERPID(h)   xstrstr_obs(h, g_obs_tracerpid,   sizeof(g_obs_tracerpid),   OB_KEY(21))
#define XS_GMAIN(h)       xstrstr_obs(h, g_obs_gmain,       sizeof(g_obs_gmain),       OB_KEY(23))
#define XS_GDBUS(h)       xstrstr_obs(h, g_obs_gdbus,       sizeof(g_obs_gdbus),       OB_KEY(24))
#define XS_AGENT(h)       xstrstr_obs(h, g_obs_agent,       sizeof(g_obs_agent),       OB_KEY(25))
#define XS_VAEXPOSED(h)   xstrstr_obs(h, g_obs_vaexposed,   sizeof(g_obs_vaexposed),   OB_KEY(26))
#define XS_EXPOSED(h)     xstrstr_obs(h, g_obs_exposed,     sizeof(g_obs_exposed),     OB_KEY(27))
#define XS_VMOS(h)        xstrstr_obs(h, g_obs_vmos,        sizeof(g_obs_vmos),        OB_KEY(28))
#define XS_PARALLEL(h)    xstrstr_obs(h, g_obs_parallel,    sizeof(g_obs_parallel),    OB_KEY(29))
#define XS_VIRTUAL(h)     xstrstr_obs(h, g_obs_virtual,     sizeof(g_obs_virtual),     OB_KEY(30))
#define XS_SANDBOX(h)     xstrstr_obs(h, g_obs_sandbox,     sizeof(g_obs_sandbox),     OB_KEY(31))
#define XS_CONTAINER(h)   xstrstr_obs(h, g_obs_container,   sizeof(g_obs_container),   OB_KEY(32))
#define XS_ZYGISK(h)      xstrstr_obs(h, g_obs_zygisk,      sizeof(g_obs_zygisk),      OB_KEY(33))
#define XS_SHAMIKO(h)     xstrstr_obs(h, g_obs_shamiko,     sizeof(g_obs_shamiko),     OB_KEY(34))
#define XS_SUI(h)         xstrstr_obs(h, g_obs_sui,         sizeof(g_obs_sui),         OB_KEY(35))
#define XS_MOMO(h)        xstrstr_obs(h, g_obs_momo,        sizeof(g_obs_momo),        OB_KEY(36))

/* ── DE_XOR_IDX macro for filling local char arrays ── */
#define DE_XOR_IDX(dst, obs_arr, idx) do { \
    const uint8_t _s[] = { obs_arr }; size_t _n = sizeof(_s); \
    decode_obs(dst, _s, _n, OB_KEY(idx)); } while(0)

/* ── Static obfuscated string arrays ── */
static const uint8_t g_obs_frida[]       = { OBS_FRIDA };
static const uint8_t g_obs_gumjs[]       = { OBS_GUMJS };
static const uint8_t g_obs_fridagent[]   = { OBS_FRIDAGENT };
static const uint8_t g_obs_linjector[]   = { OBS_LINJECTOR };
static const uint8_t g_obs_xposed[]      = { OBS_XPOSED };
static const uint8_t g_obs_substrate[]   = { OBS_SUBSTRATE };
static const uint8_t g_obs_lsposed[]     = { OBS_LSPOSED };
static const uint8_t g_obs_riru[]        = { OBS_RIRU };
static const uint8_t g_obs_magisk[]      = { OBS_MAGISK };
static const uint8_t g_obs_magisk_cap[]  = { OBS_MAGISK_CAP };
static const uint8_t g_obs_tracerpid[]   = { OBS_TRACERPID };
static const uint8_t g_obs_httpcanary[]  = { OBS_HTTPCANARY };
static const uint8_t g_obs_gmain[]       = { OBS_GMAIN };
static const uint8_t g_obs_gdbus[]       = { OBS_GDBUS };
static const uint8_t g_obs_agent[]       = { OBS_AGENT };
static const uint8_t g_obs_vaexposed[]   = { OBS_VAEXPOSED };
static const uint8_t g_obs_exposed[]     = { OBS_EXPOSED };
static const uint8_t g_obs_vmos[]        = { OBS_VMOS };
static const uint8_t g_obs_parallel[]    = { OBS_PARALLEL };
static const uint8_t g_obs_virtual[]     = { OBS_VIRTUAL };
static const uint8_t g_obs_sandbox[]     = { OBS_SANDBOX };
static const uint8_t g_obs_container[]   = { OBS_CONTAINER };
static const uint8_t g_obs_zygisk[]      = { OBS_ZYGISK };
static const uint8_t g_obs_shamiko[]     = { OBS_SHAMIKO };
static const uint8_t g_obs_sui[]         = { OBS_SUI };
static const uint8_t g_obs_momo[]        = { OBS_MOMO };
static const uint8_t g_obs_magiskpolicy[] = { OBS_MAGISKPOLICY };
static const uint8_t g_obs_magiskinit[]  = { OBS_MAGISKINIT };
static const uint8_t g_obs_ksud[]        = { OBS_KSUD };
static const uint8_t g_obs_kernelsu[]    = { OBS_KERNELSU };
static const uint8_t g_obs_apd[]         = { OBS_APD };
static const uint8_t g_obs_apatch[]      = { OBS_APATCH };
static const uint8_t g_obs_path_frida_server[]  = { OBS_PATH_FRIDA_SERVER };
static const uint8_t g_obs_path_frida_svr16[]   = { OBS_PATH_FRIDA_SVR16 };
static const uint8_t g_obs_path_frida_tmp[]     = { OBS_PATH_FRIDA_TMP };
static const uint8_t g_obs_path_frida_agent[]   = { OBS_PATH_FRIDA_AGENT };
static const uint8_t g_obs_path_re_frida[]      = { OBS_PATH_RE_FRIDA_SERVER };
static const uint8_t g_obs_path_gadget_tmp[]    = { OBS_PATH_GADGET_TMP };
static const uint8_t g_obs_path_frida_sdcard[]  = { OBS_PATH_FRIDA_SDCARD };
static const uint8_t g_obs_path_frida_sd[]      = { OBS_PATH_FRIDA_SD };
static const uint8_t g_obs_path_hluda_tmp[]     = { OBS_PATH_HLUDA_TMP };
static const uint8_t g_obs_path_gadget_cfg[]    = { OBS_PATH_GADGET_CFG };
static const uint8_t g_obs_path_magisk_su[]     = { OBS_PATH_MAGISK_SU };
static const uint8_t g_obs_path_magisk_adb[]    = { OBS_PATH_MAGISK_ADB };
static const uint8_t g_obs_path_magisk_img[]    = { OBS_PATH_MAGISK_IMG };
static const uint8_t g_obs_io_va_exposed[]     = { OBS_IO_VA_EXPOSED };
static const uint8_t g_obs_virtualxposed[]     = { OBS_VIRTUALXPOSED };
static const uint8_t g_obs_virtualapp[]        = { OBS_VIRTUALAPP };

#endif /* YUNIAN_OBFUSCATED_STRINGS_H */
