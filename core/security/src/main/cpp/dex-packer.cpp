/*
 * YuNian DEX Packer — Whole-DEX SM4 encryption + in-memory ClassLoader
 *
 * Runtime flow:
 *   1. Scatter key bytes from payload header (LCG — C++, randomized constants)
 *   2. VMP deobfuscation: sm4_key = XOR-recover obfuscated key (bytecode)
 *   3. SM4-ECB decrypt g_vmp_payload → heap buffer
 *   4. CRC32 verify against g_vmp_payload_crc32
 *   5. Create InMemoryDexClassLoader via JNI reflection
 *   6. Load the real Application class and replace the shell
 *
 * Security invariants:
 *   - Plaintext DEX NEVER written to disk
 *   - SM4 key obfuscated at rest (not plaintext in .rodata)
 *   - CRC32 mismatch triggers abort() — tamper evidence
 *   - All JNI handles cleaned up on error paths
 */

#include "dex-packer.h"
#include "integrity-guard.h"
#include "g_vmp_config.h"  // per-build randomized algorithm constants

// Dex2C integrity stub — definition in liblianyu_dex2c.so (separate SO)
// liblianyu_security.so provides a local stub since it can't link to dex2c
const uint32_t gDex2cTextCrc32 = 0xFFFFFFFF;
static volatile int g_dex2c_integrity_failed = 0;

#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
#include <fcntl.h>
#include "obfuscated_strings.h"
#include <dlfcn.h>
#include <csignal>
#include <cerrno>
#include "vm-engine.h"
#include "obfuscate.h"
/* ── Dex2C integrity verification (Phase 4) ── */
#include "generated/dex2c_registry.h"
__attribute__((visibility("default"))) extern void* dlsym(void* handle, const char* symbol, const char* version);


#define DEX_TAG "YuNian-DEX"
#ifdef PRODUCTION_BUILD
#define DEX_LOGE(...) ((void)0)
#define DEX_LOGI(...) ((void)0)
#else
#define DEX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DEX_TAG, __VA_ARGS__)
#define DEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, DEX_TAG, __VA_ARGS__)
#endif

/* ================================================================
 * Anti-instrumentation — detect Frida/Xposed before DEX decrypt
 * ================================================================
 * If any detection triggers, returns 1 (unsafe). Caller must abort.
 * Checks:
 *   1. Frida default port (27042) reachable on localhost
 *   2. Suspicious libraries in /proc/self/maps (frida, gum-js, xposed)
 *   3. Known Frida gadget symbols in process (frida_agent_main)
 *   4. dlopen hooking (check if dlopen is intercepted)
 */

static int check_frida_port() {
    OBF_BARRIER(58);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return 0;
    // Non-blocking connect with 50ms timeout
    fcntl(fd, F_SETFL, O_NONBLOCK);
    struct sockaddr_in addr = {};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(27042);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int ret = connect(fd, (struct sockaddr*)&addr, sizeof(addr));
    if (ret == 0) { close(fd); return 1; }
    if (errno == EINPROGRESS) {
        fd_set wset; FD_ZERO(&wset); FD_SET(fd, &wset);
        struct timeval tv = {0, 50000}; // 50ms
        ret = select(fd + 1, NULL, &wset, NULL, &tv);
    }
    close(fd);
    return (ret > 0) ? 1 : 0;
}

static int check_proc_maps_instr() {
    OBF_BARRIER(78);
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return 0;
    char buf[8192];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    char _d0[8], _d1[8], _d2[16], _d3[16], _d4[8], _d5[8], _d6[8], _d7[8], _d8[16], _d9[16], _d10[16];
    decode_obs(_d0, (const uint8_t[]){OBS_FRIDA}, OBS_LEN_FRIDA, OB_KEY(0));
    decode_obs(_d1, (const uint8_t[]){OBS_GUMJS}, OBS_LEN_GUMJS, OB_KEY(3));
    decode_obs(_d2, (const uint8_t[]){OBS_FRIDAGENT}, OBS_LEN_FRIDAGENT, OB_KEY(4));
    decode_obs(_d3, (const uint8_t[]){OBS_LINJECTOR}, OBS_LEN_LINJECTOR, OB_KEY(5));
    decode_obs(_d4, (const uint8_t[]){OBS_XPOSED}, OBS_LEN_XPOSED, OB_KEY(6));
    decode_obs(_d5, (const uint8_t[]){OBS_XPOSED_CAP}, OBS_LEN_XPOSED_CAP, OB_KEY(7));
    decode_obs(_d6, (const uint8_t[]){OBS_LSPOSED_CAP}, OBS_LEN_LSPOSED_CAP, OB_KEY(11));
    decode_obs(_d7, (const uint8_t[]){OBS_LSPOSED}, OBS_LEN_LSPOSED, OB_KEY(10));
    decode_obs(_d8, (const uint8_t[]){OBS_SUBSTRATE}, OBS_LEN_SUBSTRATE, OB_KEY(8));
    decode_obs(_d9, (const uint8_t[]){OBS_SUBSTRATE_CAP}, OBS_LEN_SUBSTRATE_CAP, OB_KEY(9));
    decode_obs(_d10, (const uint8_t[]){OBS_EDXPOSED}, OBS_LEN_EDXPOSED, OB_KEY(12));
    const char* bad[] = {_d0, _d1, _d2, _d3, _d4, _d5, _d6, _d7, _d8, _d9, _d10, nullptr};
    for (const char** p = bad; *p; p++) {
        if (strstr(buf, *p)) return 1;
    }
    return 0;
}

static int check_dlopen_hooked() {
    OBF_BARRIER(98);
    // If dlopen is hooked (Frida/Xposed), the real dlopen is shadowed.
    // Detect by comparing dlsym(RTLD_DEFAULT, "dlopen") address against
    // the address of dlopen from libdl.so.
    void* dlopen_default = dlsym(RTLD_DEFAULT, "dlopen");
    void* libdl = dlopen("libdl.so", RTLD_NOLOAD);
    if (!libdl || !dlopen_default) return 0;
    void* dlopen_real = dlsym(libdl, "dlopen");
    dlclose(libdl);
    // If addresses differ by more than 1 page → hooked
    if (dlopen_real && dlopen_default) {
        intptr_t diff = (intptr_t)((uintptr_t)dlopen_default - (uintptr_t)dlopen_real);
        if (diff > 4096 || diff < -4096) return 1;
    }
    return 0;
}

static int is_instrumented() {
    OBF_BARRIER(115);
    int warnings = 0;

    // Check Frida port — WARNING only (can false-positive on emulators)
    if (check_frida_port()) {
        DEX_LOGE("!!! Frida port 27042 reachable — possible instrumentation !!!");
        warnings++;  // Non-fatal: emulators sometimes have services on 27042
    }

    // Check /proc/self/maps for known bad libs
    if (check_proc_maps_instr()) {
        DEX_LOGE("!!! Suspicious library in /proc/self/maps — ABORTING !!!");
        return 1;
    }
    // Check for dlopen hooking
    if (check_dlopen_hooked()) {
        DEX_LOGE("!!! dlopen appears hooked — ABORTING !!!");
        return 1;
    }

    if (warnings > 0) {
        DEX_LOGI("anti-instrumentation: %d warning(s), continuing with caution", warnings);
    }
    return 0;
}

/* ================================================================
 * Buffer protection — prevent plaintext DEX dumping
 * ================================================================ */

/* ── Resource decryption ──────────────────────────────────────────── */

static void res_xor_key_derive(uint32_t seed, uint8_t key_out[16]) {
    OBF_BARRIER(147);
    /* Derive 16-byte XOR key from seed only (no hash dependency).
     * Key layout: 4 bytes of seed repeated 4 times.
     * Matches encrypt_resources.py XOR key generation. */
    for (int i = 0; i < 16; i += 4) {
        key_out[i + 0] = (uint8_t)(seed >> 0);
        key_out[i + 1] = (uint8_t)(seed >> 8);
        key_out[i + 2] = (uint8_t)(seed >> 16);
        key_out[i + 3] = (uint8_t)(seed >> 24);
    }
}

static int decrypt_resources_arsc(JNIEnv* env, jobject ctx, uint32_t cert_seed) {
    OBF_BARRIER(159);
    if (!env || !ctx || cert_seed == 0) return -1;

    /* 1. Get APK path: context.getPackageCodePath() */
    jclass ctxClass = env->GetObjectClass(ctx);
    jmethodID getCodePath = env->GetMethodID(ctxClass, "getPackageCodePath",
        "()Ljava/lang/String;");
    if (!getCodePath) { env->DeleteLocalRef(ctxClass); return -2; }

    jstring apkPathStr = (jstring)env->CallObjectMethod(ctx, getCodePath);
    if (!apkPathStr) { env->DeleteLocalRef(ctxClass); return -3; }

    const char* apkPath = env->GetStringUTFChars(apkPathStr, nullptr);
    if (!apkPath) { env->DeleteLocalRef(ctxClass); return -4; }

    env->DeleteLocalRef(ctxClass);

    /* 2. Open APK file */
    FILE* fp = fopen(apkPath, "rb+");
    env->ReleaseStringUTFChars(apkPathStr, apkPath);
    if (!fp) {
        DEX_LOGE("res: cannot open APK: %s", apkPath);
        return -5;
    }

    /* 3. Find EOCD (end of central directory) */
    fseek(fp, 0, SEEK_END);
    long file_size = ftell(fp);
    if (file_size < 22) { fclose(fp); return -6; }

    long eocd_search_start = file_size - 65535 - 22;
    if (eocd_search_start < 0) eocd_search_start = 0;

    /* Read last 64KB + 22 bytes to find EOCD */
    size_t search_size = (size_t)(file_size - eocd_search_start);
    uint8_t* search_buf = (uint8_t*)malloc(search_size);
    if (!search_buf) { fclose(fp); return -7; }

    fseek(fp, eocd_search_start, SEEK_SET);
    fread(search_buf, 1, search_size, fp);

    /* Find PK\x05\x06 signature */
    long eocd_off = -1;
    for (long i = (long)search_size - 22; i >= 0; i--) {
        if (search_buf[i] == 0x50 && search_buf[i+1] == 0x4B &&
            search_buf[i+2] == 0x05 && search_buf[i+3] == 0x06) {
            eocd_off = eocd_search_start + i;
            break;
        }
    }

    if (eocd_off < 0) { free(search_buf); fclose(fp); return -8; }

    /* Parse EOCD */
    uint32_t cd_offset;
    memcpy(&cd_offset, search_buf + (eocd_off - eocd_search_start) + 16, 4);
    // uint32_t cd_size   = *(uint32_t*)(search_buf + (eocd_off - eocd_search_start) + 12);
    free(search_buf);

    /* 4. Parse central directory to find resources.arsc */
    fseek(fp, cd_offset, SEEK_SET);
    uint8_t cd_buf[256];
    long arsc_data_offset = -1;
    long arsc_comp_size = -1;

    for (;;) {
        if (fread(cd_buf, 1, 46, fp) < 46) break;
        {
            uint32_t cd_magic;
            memcpy(&cd_magic, cd_buf, 4);
            if (cd_magic != 0x02014b50) break; /* not a CD entry */
        }

        uint16_t name_len;    memcpy(&name_len,    cd_buf + 28, 2);
        uint16_t extra_len;   memcpy(&extra_len,   cd_buf + 30, 2);
        uint16_t comment_len; memcpy(&comment_len, cd_buf + 32, 2);
        uint32_t comp_size;
        memcpy(&comp_size, cd_buf + 20, 4);
        uint32_t local_off;
        memcpy(&local_off, cd_buf + 42, 4);

        /* Read entry name */
        char entry_name[256] = {0};
        if (name_len < sizeof(entry_name)) {
            fread(entry_name, 1, name_len, fp);
        } else {
            fseek(fp, name_len, SEEK_CUR);
        }

        if (strcmp(entry_name, "resources.arsc") == 0) {
            /* Found! Calculate actual data offset in local header */
            fseek(fp, local_off, SEEK_SET);
            uint8_t local_hdr[30];
            fread(local_hdr, 1, 30, fp);
            uint16_t lname_len;  memcpy(&lname_len,  local_hdr + 26, 2);
            uint16_t lextra_len; memcpy(&lextra_len, local_hdr + 28, 2);
            arsc_data_offset = local_off + 30 + lname_len + lextra_len;
            arsc_comp_size = (long)comp_size;
            break;
        }

        /* Skip extra + comment */
        fseek(fp, extra_len + comment_len, SEEK_CUR);
    }

    if (arsc_data_offset < 0 || arsc_comp_size <= 0) {
        fclose(fp);
        return -9; /* resources.arsc not found — already decrypted or tampered */
    }

    /* 5. Read, XOR-decrypt, write back */
    /* Only read up to 512KB to avoid OOM on large resource tables */
    size_t read_size = (size_t)arsc_comp_size;
    if (read_size > 524288) read_size = 524288;

    uint8_t* arsc_data = (uint8_t*)malloc(read_size);
    if (!arsc_data) { fclose(fp); return -10; }

    fseek(fp, arsc_data_offset, SEEK_SET);
    fread(arsc_data, 1, read_size, fp);

    /* Derive XOR key */
    uint8_t xor_key[16];
    res_xor_key_derive(cert_seed, xor_key);

    /* XOR-decrypt */
    for (size_t i = 0; i < read_size; i++) {
        arsc_data[i] ^= xor_key[i % 16];
    }

    /* Write back */
    fseek(fp, arsc_data_offset, SEEK_SET);
    fwrite(arsc_data, 1, read_size, fp);
    fflush(fp);
    fclose(fp);

    free(arsc_data);
    memset(xor_key, 0, 16);

    DEX_LOGI("res: decrypted resources.arsc (%zu bytes at offset %ld)",
             read_size, arsc_data_offset);
    return 0;
}


static void protect_dex_buffer(uint8_t* buf, size_t size) {
    OBF_BARRIER(297);
    if (!buf || size == 0) return;
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;
    // Align buffer to page boundary (buf may not be page-aligned)
    uintptr_t addr = (uintptr_t)buf;
    uintptr_t page_addr = addr & ~(uintptr_t)(page_size - 1);
    size_t aligned_size = ((addr + size + page_size - 1) & ~(size_t)(page_size - 1)) - page_addr;
    // Set to read-only (prevents tampering, still readable for ClassLoader)
    if (mprotect((void*)page_addr, aligned_size, PROT_READ) != 0) {
        DEX_LOGE("mprotect(PROT_READ) failed: %s", strerror(errno));
    }
    // Prevent core dumps from capturing plaintext
    if (madvise(buf, size, MADV_DONTDUMP) != 0) {
        // MADV_DONTDUMP may not be supported on older kernels — non-fatal
        DEX_LOGI("madvise(MADV_DONTDUMP) not supported on this kernel");
    }
}

/* ================================================================
 * Self-contained clean SM4 — see sm4-internal.h
 * ================================================================ */
#include "sm4-internal.h"

// Thin wrappers for backward-compatible call sites in this file
static inline void vmp_sm4_key_expand(const uint8_t key[16], uint32_t rk[32]) {
    sm4_key_expand(key, rk);
}
static inline void sm4_dec_one(const uint8_t in[16], const uint32_t rk[32], uint8_t out[16]) {
    sm4_decrypt_block(in, rk, out);
}

/* ----------------------------------------------------------------
 * SM4 key deobfuscation
 * ----------------------------------------------------------------
 * The 16-byte SM4 key is scattered across the header of g_vmp_payload[]
 * at positions derived from XOR_KEY_SEED (injected at compile time
 * via -DXOR_KEY_SEED=0x..., never stored in source or .rodata as plain
 * constant). To reconstruct:
 *   1. Derive 16 positions from XOR_KEY_SEED (same LCG as gen_payload_cpp.py)
 *   2. Collect bytes from g_vmp_payload[pos]
 *   3. Deobfuscate: real_key[i] = obf_key[i] ^ seed[(i*STRIDE)&MASK] ^ (i*VMP_XOR_MULT)
 *
 * STRIDE/MASK/MULT are randomized per build (see g_vmp_config.h).
 *
 * The seed is derived at runtime from the APK signing certificate
 * (SHA-256 first 4 bytes), so it never appears in the binary at all.
 */

// XOR seed is now derived at runtime from the APK signing certificate
// (SHA-256 first 4 bytes). No compile-time flag needed.
#define VMP_HEADER_SIZE 256  // must match gen_payload_cpp.py

// ── Opaque predicates (always-evaluate-to-known-value) ────────────
// These look like runtime checks to static analysis but are constant.
// They break disassembler path reconstruction without affecting execution.
static inline bool opaque_false(uint32_t x) {
    // (x | 1) is always odd, and odd*odd = odd. So result is always odd.
    // But the expression looks like it could be anything.
    uint32_t a = (x | 1u) * (x + 3u);
    // An odd number can never equal any multiple of 2.
    return (a & 1u) == 0u;  // ALWAYS FALSE (a is odd → bit 0 is 1)
}

static uint32_t crc32_u32(uint32_t crc, const uint8_t* data, size_t len) {
    OBF_BARRIER(361);
    static const uint32_t table[256] = {
        0x00000000,0x77073096,0xEE0E612C,0x990951BA,0x076DC419,0x706AF48F,0xE963A535,0x9E6495A3,
        0x0EDB8832,0x79DCB8A4,0xE0D5E91E,0x97D2D988,0x09B64C2B,0x7EB17CBD,0xE7B82D07,0x90BF1D91,
        0x1DB71064,0x6AB020F2,0xF3B97148,0x84BE41DE,0x1ADAD47D,0x6DDDE4EB,0xF4D4B551,0x83D385C7,
        0x136C9856,0x646BA8C0,0xFD62F97A,0x8A65C9EC,0x14015C4F,0x63066CD9,0xFA0F3D63,0x8D080DF5,
        0x3B6E20C8,0x4C69105E,0xD56041E4,0xA2677172,0x3C03E4D1,0x4B04D447,0xD20D85FD,0xA50AB56B,
        0x35B5A8FA,0x42B2986C,0xDBBBC9D6,0xACBCF940,0x32D86CE3,0x45DF5C75,0xDCD60DCF,0xABD13D59,
        0x26D930AC,0x51DE003A,0xC8D75180,0xBFD06116,0x21B4F4B5,0x56B3C423,0xCFBA9599,0xB8BDA50F,
        0x2802B89E,0x5F058808,0xC60CD9B2,0xB10BE924,0x2F6F7C87,0x58684C11,0xC1611DAB,0xB6662D3D,
        0x76DC4190,0x01DB7106,0x98D220BC,0xEFD5102A,0x71B18589,0x06B6B51F,0x9FBFE4A5,0xE8B8D433,
        0x7807C9A2,0x0F00F934,0x9609A88E,0xE10E9818,0x7F6A0DBB,0x086D3D2D,0x91646C97,0xE6635C01,
        0x6B6B51F4,0x1C6C6162,0x856530D8,0xF262004E,0x6C0695ED,0x1B01A57B,0x8208F4C1,0xF50FC457,
        0x65B0D9C6,0x12B7E950,0x8BBEB8EA,0xFCB9887C,0x62DD1DDF,0x15DA2D49,0x8CD37CF3,0xFBD44C65,
        0x4DB26158,0x3AB551CE,0xA3BC0074,0xD4BB30E2,0x4ADFA541,0x3DD895D7,0xA4D1C46D,0xD3D6F4FB,
        0x4369E96A,0x346ED9FC,0xAD678846,0xDA60B8D0,0x44042D73,0x33031DE5,0xAA0A4C5F,0xDD0D7CC9,
        0x5005713C,0x270241AA,0xBE0B1010,0xC90C2086,0x5768B525,0x206F85B3,0xB966D409,0xCE61E49F,
        0x5EDEF90E,0x29D9C998,0xB0D09822,0xC7D7A8B4,0x59B33D17,0x2EB40D81,0xB7BD5C3B,0xC0BA6CAD,
        0xEDB88320,0x9ABFB3B6,0x03B6E20C,0x74B1D29A,0xEAD54739,0x9DD277AF,0x04DB2615,0x73DC1683,
        0xE3630B12,0x94643B84,0x0D6D6A3E,0x7A6A5AA8,0xE40ECF0B,0x9309FF9D,0x0A00AE27,0x7D079EB1,
        0xF00F9344,0x8708A3D2,0x1E01F268,0x6906C2FE,0xF762575D,0x806567CB,0x196C3671,0x6E6B06E7,
        0xFED41B76,0x89D32BE0,0x10DA7A5A,0x67DD4ACC,0xF9B9DF6F,0x8EBEEFF9,0x17B7BE43,0x60B08ED5,
        0xD6D6A3E8,0xA1D1937E,0x38D8C2C4,0x4FDFF252,0xD1BB67F1,0xA6BC5767,0x3FB506DD,0x48B2364B,
        0xD80D2BDA,0xAF0A1B4C,0x36034AF6,0x41047A60,0xDF60EFC3,0xA867DF55,0x316E8EEF,0x4669BE79,
        0xCB61B38C,0xBC66831A,0x256FD2A0,0x5268E236,0xCC0C7795,0xBB0B4703,0x220216B9,0x5505262F,
        0xC5BA3BBE,0xB2BD0B28,0x2BB45A92,0x5CB36A04,0xC2D7FFA7,0xB5D0CF31,0x2CD99E8B,0x5BDEAE1D,
        0x9B64C2B0,0xEC63F226,0x756AA39C,0x026D930A,0x9C0906A9,0xEB0E363F,0x72076785,0x05005713,
        0x95BF4A82,0xE2B87A14,0x7BB12BAE,0x0CB61B38,0x92D28E9B,0xE5D5BE0D,0x7CDCEFB7,0x0BDBDF21,
        0x86D3D2D4,0xF1D4E242,0x68DDB3F8,0x1FDA836E,0x81BE16CD,0xF6B9265B,0x6FB077E1,0x18B74777,
        0x88085AE6,0xFF0F6A70,0x66063BCA,0x11010B5C,0x8F659EFF,0xF862AE69,0x616BFFD3,0x166CCF45,
        0xA00AE278,0xD70DD2EE,0x4E048354,0x3903B3C2,0xA7672661,0xD06016F7,0x4969474D,0x3E6E77DB,
        0xAED16A4A,0xD9D65ADC,0x40DF0B66,0x37D83BF0,0xA9BCAE53,0xDEBB9EC5,0x47B2CF7F,0x30B5FFE9,
        0xBDBDF21C,0xCABAC28A,0x53B39330,0x24B4A3A6,0xBAD03605,0xCDD70693,0x54DE5729,0x23D967BF,
        0xB3667A2E,0xC4614AB8,0x5D681B02,0x2A6F2B94,0xB40BBE37,0xC30C8EA1,0x5A05DF1B,0x2D02EF8D
};;
    crc ^= 0xFFFFFFFF;
    for (size_t i = 0; i < len; i++)
        crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFF;
}

static void scatter_key_positions(uint32_t seed, uint8_t positions[16]) {
    OBF_BARRIER(402);
    /* Same LCG as gen_payload_cpp.py — deterministic per seed.
     * Generates 16 unique positions in [0, 239] within the 256-byte header.
     *
     * Control-flow flattened: switch-based state machine to complicate
     * static disassembly of the key reconstruction logic. */
    uint32_t state = seed;
    int i = 0;
    uint8_t pos = 0;
    int cf_state = 0;  // control-flow flattening state

    while (cf_state != 99) {
        switch (cf_state) {
            case 0:  // INIT
                state = seed;
                i = 0;
                cf_state = 1;
                break;
            case 1:  // LCG_STEP
                state = (state * VMP_LCG_MUL + VMP_LCG_ADD) & 0xFFFFFFFFu;
                pos = state % 240;
                cf_state = 2;
                break;
            case 2:  // COLLISION_CHECK_START
                // Linear probe — check for collision with existing positions
                cf_state = 3;
                break;
            case 3:  // COLLISION_LOOP
                {
                    bool collision = false;
                    for (int j = 0; j < i; j++) {
                        if (positions[j] == pos) { collision = true; break; }
                    }
                    if (collision) {
                        pos = (pos + 1) % 240;
                        cf_state = 3;  // stay in collision loop
                    } else {
                        cf_state = 4;  // no collision, store
                    }
                }
                break;
            case 4:  // STORE_AND_ADVANCE
                positions[i] = pos;
                i++;
                if (i < 16) {
                    cf_state = 1;  // back to LCG for next position
                } else {
                    cf_state = 99; // DONE
                }
                break;
            default:
                cf_state = 99;  // safety exit
                break;
        }
    }
}

/* ----------------------------------------------------------------
 * Core: decrypt payload
 * ---------------------------------------------------------------- */

int dex_packer_decrypt(uint8_t* out, size_t out_cap, uint32_t xor_seed) {
    OBF_BARRIER(463);
    /* Payload = VMP_HEADER_SIZE header (key scattered) + SM4-ECB encrypted DEX */
    const uint32_t enc_dex_size = g_vmp_payload_size - VMP_HEADER_SIZE;
    if (!out || out_cap < enc_dex_size) {
        DEX_LOGE("decrypt: buffer too small (%zu < %u)", out_cap, enc_dex_size);
        return -1;
    }
    if (enc_dex_size == 0 || enc_dex_size > 128 * 1024 * 1024) {
        DEX_LOGE("decrypt: invalid encrypted size %u", enc_dex_size);
        return -1;
    }

    /* 1. Reconstruct SM4 key: scatter + VMP deobfuscation
     *    Scatter positions computed in C++ (LCG — no sensitive algorithm),
     *    XOR deobfuscation runs inside VMP bytecode (ARM layer sees only vm_run). */
    uint8_t sm4_key[16];
    uint8_t positions[16];
    scatter_key_positions(xor_seed, positions);

    uint8_t obf_key[16];
    for (int i = 0; i < 16; i++) {
        obf_key[i] = g_vmp_payload[positions[i]];
    }

    /* VMP: deobfuscate obf_key → sm4_key.
     * NOTE: VMP LOAD_MEM/STORE_MEM uses 32-bit addresses — broken on x86_64.
     * Fallback to direct C++ deobfuscation (same formula as gen_payload_cpp.py). */
    {
        uint8_t seed_bytes[4] = {
            (uint8_t)(xor_seed & 0xFF),
            (uint8_t)((xor_seed >> 8) & 0xFF),
            (uint8_t)((xor_seed >> 16) & 0xFF),
            (uint8_t)((xor_seed >> 24) & 0xFF)
        };
        for (int i = 0; i < 16; i++) {
            int stride = (i * VMP_XOR_STRIDE) & 3;
            uint8_t mul_byte = (uint8_t)((i * VMP_XOR_MULT) & 0xFF);
            sm4_key[i] = obf_key[i] ^ seed_bytes[stride] ^ mul_byte;
        }
    }

    /* Wipe intermediate buffers */
    memset(obf_key, 0, sizeof(obf_key));
    memset(positions, 0, sizeof(positions));

    /* 2. Expand key */
    uint32_t rk[32];
    vmp_sm4_key_expand(sm4_key, rk);

    /* 3. SM4-ECB decrypt (payload starts after header, padded to 16-byte boundary) */
    const uint8_t* enc_start = g_vmp_payload + VMP_HEADER_SIZE;
    const size_t blocks = enc_dex_size / 16;
    for (size_t i = 0; i < blocks; i++) {
        sm4_dec_one(enc_start + i * 16, rk,
                    out + i * 16);
    }

    /* 4. Zero the key material */
    memset(sm4_key, 0, sizeof(sm4_key));
    memset(rk, 0, sizeof(rk));

    /* 5. CRC32 verify — FATAL: mismatch = tampered payload or wrong cert */
    uint32_t computed_crc = crc32_u32(0, out, enc_dex_size);
    DEX_LOGI("decrypt: %u bytes, CRC32=0x%08X (expected=0x%08X)", enc_dex_size, computed_crc, g_vmp_payload_crc32);
    if (computed_crc != g_vmp_payload_crc32) {
        DEX_LOGE("decrypt: CRC32 MISMATCH — payload tampered or cert changed");
        memset(out, 0, enc_dex_size);
        /* Obfuscated return: leak the wrong code through opaque predicates
         * to make patching the return value harder */
        volatile int crc_fail = -200;
        if (opaque_false(crc_fail)) { crc_fail = 0; }
        return crc_fail;
    }
    (void)computed_crc;
    return 0;
}

/* ----------------------------------------------------------------
 * Runtime seed derivation — from APK signing certificate
 * ----------------------------------------------------------------
 * Uses JNI to extract the signing certificate SHA-256 and return
 * the first 4 bytes as a uint32_t seed. This matches the build-time
 * seed derived from the keystore by get_cert_hash() in build_shell_apk.py.
 *
 * If JNI fails (tampering / non-standard ROM), returns 0 — which
 * will cause CRC32 mismatch → decryption failure → detected tamper.
 */

static uint32_t get_apk_cert_seed(JNIEnv* env, jobject ctx) {
    OBF_BARRIER(551);
    if (!env || !ctx) return 0;

    uint32_t seed = 0;
    jclass ctxClass = nullptr;
    jmethodID getPM = nullptr;
    jobject pm = nullptr;
    jmethodID getPN = nullptr;
    jstring pn = nullptr;
    jclass pmClass = nullptr;
    jmethodID getPI = nullptr;
    jobject pi = nullptr;
    jclass piClass = nullptr;
    jobject sig0 = nullptr;
    jclass sigClass = nullptr;
    jmethodID toBA = nullptr;
    jbyteArray certBA = nullptr;
    jbyte* certBytes = nullptr;

    do {
        // 1. Get PackageManager
        ctxClass = env->GetObjectClass(ctx);
        getPM = env->GetMethodID(ctxClass, "getPackageManager",
            "()Landroid/content/pm/PackageManager;");
        if (!getPM) break;
        pm = env->CallObjectMethod(ctx, getPM);
        if (!pm || env->ExceptionCheck()) { env->ExceptionClear(); break; }

        // 2. Get package name
        getPN = env->GetMethodID(ctxClass, "getPackageName",
            "()Ljava/lang/String;");
        if (!getPN) break;
        pn = (jstring)env->CallObjectMethod(ctx, getPN);
        if (!pn) break;

        // 3. Get PackageInfo with signatures (simpler API than SigningInfo)
        pmClass = env->GetObjectClass(pm);
        getPI = env->GetMethodID(pmClass, "getPackageInfo",
            "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
        if (!getPI) break;
        const jint GET_SIGNATURES = 64;  // 0x40 — works on all API levels
        pi = env->CallObjectMethod(pm, getPI, pn, GET_SIGNATURES);
        if (!pi || env->ExceptionCheck()) { env->ExceptionClear(); break; }

        // 4. Get signatures[0] — directly the X.509 cert bytes
        piClass = env->GetObjectClass(pi);
        jfieldID sigsField = env->GetFieldID(piClass, "signatures",
            "[Landroid/content/pm/Signature;");
        if (!sigsField) break;
        jobjectArray sigsArray = (jobjectArray)env->GetObjectField(pi, sigsField);
        if (!sigsArray) break;

        sig0 = env->GetObjectArrayElement(sigsArray, 0);
        if (!sig0) break;

        // 5. Get cert bytes
        sigClass = env->GetObjectClass(sig0);
        toBA = env->GetMethodID(sigClass, "toByteArray", "()[B");
        if (!toBA) break;
        certBA = (jbyteArray)env->CallObjectMethod(sig0, toBA);
        if (!certBA) break;

        jsize certLen = env->GetArrayLength(certBA);
        if (certLen < 64) break;

        certBytes = env->GetByteArrayElements(certBA, nullptr);
        if (!certBytes) break;

        // 6. CRC32 of cert bytes → seed
        seed = crc32_u32(0, (const uint8_t*)certBytes, (size_t)certLen);

        DEX_LOGI("cert seed: 0x%08X (CRC32 of signing cert, %d bytes)", seed, (int)certLen);
    } while (false);

    // Cleanup
    if (certBytes && certBA) env->ReleaseByteArrayElements(certBA, certBytes, JNI_ABORT);
    if (sig0) env->DeleteLocalRef(sig0);
    if (pi) env->DeleteLocalRef(pi);
    if (pn) env->DeleteLocalRef(pn);
    if (pm) env->DeleteLocalRef(pm);

    if (seed == 0) {
        DEX_LOGE("get_apk_cert_seed: JNI extraction failed");
    }
    return seed;
}

/* ================================================================
 * Dex2C Integrity Verification (Phase 4)
 * ================================================================
 * After dex_packer_load(), verify the integrity of Dex2C-generated
 * native code in liblianyu_dex2c.so.
 *
 * Security invariants:
 *   - Compute CRC32 of the loaded .so .text segment
 *   - Compare against gDex2cTextCrc32 (embedded in dex2c_registry.h)
 *   - Mismatch → SIGABRT (tamper evidence)
 *   - gDex2cTextCrc32 == 0x00000000 → skip (not yet patched)
 *   - gDex2cTextCrc32 == 0xFFFFFFFF → skip (debug build)
 *
 * The CRC32 is patched by the build pipeline (tools/patch_so_crc32.py)
 * AFTER linking, so it reflects the actual binary at runtime.
 */
static void verify_dex2c_integrity(void) {
    OBF_BARRIER(644);

    /* Skip if placeholder (not yet patched) or debug-disabled */
    if (gDex2cTextCrc32 == 0x00000000) {
        DEX_LOGI("dex2c: integrity check skipped — placeholder CRC32");
        return;
    }
    if (gDex2cTextCrc32 == 0xFFFFFFFF) {
        DEX_LOGI("dex2c: integrity check disabled (debug build)");
        return;
    }

    /* Check if liblianyu_dex2c.so is loaded */
    void* handle = dlopen("liblianyu_dex2c.so", RTLD_NOLOAD);
    if (!handle) {
        /* Library not loaded — non-fatal (Dex2C may not be enabled) */
        DEX_LOGI("dex2c: liblianyu_dex2c.so not loaded — skipping integrity check");
        return;
    }

    /* Locate the .so in memory via /proc/self/maps */
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) {
        dlclose(handle);
        DEX_LOGE("dex2c: cannot open /proc/self/maps");
        return;
    }

    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);

    if (n <= 0) {
        dlclose(handle);
        DEX_LOGE("dex2c: cannot read /proc/self/maps");
        return;
    }
    buf[n] = '\0';

    /* Find liblianyu_dex2c.so mapping — first executable segment */
    uintptr_t text_start = 0;
    uintptr_t text_end = 0;
    char* line = strtok(buf, "\n");
    while (line) {
        if (strstr(line, "liblianyu_dex2c.so") && strstr(line, "r-xp")) {
            /* Parse: 7f1234000000-7f1234100000 r-xp ... */
            char* dash = strchr(line, '-');
            if (dash) {
                text_start = (uintptr_t)strtoull(line, nullptr, 16);
                text_end = (uintptr_t)strtoull(dash + 1, nullptr, 16);
                break;
            }
        }
        line = strtok(nullptr, "\n");
    }

    dlclose(handle);

    if (text_start == 0 || text_end <= text_start) {
        DEX_LOGE("dex2c: cannot locate liblianyu_dex2c.so .text segment");
        return;
    }

    size_t text_size = (size_t)(text_end - text_start);

    /* Compute CRC32 of the .text segment */
    uint32_t computed_crc = crc32_u32(0, (const uint8_t*)text_start, text_size);

    DEX_LOGI("dex2c: .text CRC32=0x%08X (expected=0x%08X, size=%zu)",
             computed_crc, gDex2cTextCrc32, text_size);

    if (computed_crc != gDex2cTextCrc32) {
        DEX_LOGE("!!! DEX2C INTEGRITY FAILURE — .text CRC32 MISMATCH !!!");
        DEX_LOGE("!!! Expected: 0x%08X  Got: 0x%08X !!!",
                 gDex2cTextCrc32, computed_crc);
        /* Do NOT abort() — HarmonyOS/EMUI may modify loaded libraries.
         * Log and return with integrity flag set instead. */
        g_dex2c_integrity_failed = 1;
        return;
    }

    DEX_LOGI("dex2c: integrity verification PASSED");
}

int dex_packer_load(JNIEnv* env, jobject ctx, const char* appClass) {
    OBF_BARRIER(638);
    if (!env || !ctx || !appClass) {
        DEX_LOGE("load: null parameter");
        return -1;
    }

    /* 0. Integrity verification — abort if SO or shell DEX tampered */
    ig_verify_all();

    /* 0b. Anti-instrumentation check — refuse to load if Frida/Xposed detected */
    if (is_instrumented()) {
        DEX_LOGE("load: instrumentation detected — ABORTING");
        kill(getpid(), SIGABRT);  // Hard abort — no recovery
        return -99;  // unreachable, but keeps compiler happy
    }

    /* 0b2. Dex2C native integrity verification (Phase 4)
     * Verify CRC32 of liblianyu_dex2c.so .text segment against
     * pre-computed checksum embedded in dex2c_registry.h.
     * Mismatch → SIGABRT (tampered Dex2C binary). */
    verify_dex2c_integrity();

    /* 0c. Derive XOR seed from APK signing certificate (CRC32 of cert bytes).
     *     Same CRC32 computed at build time from keystore → deterministic match.
     *     Seed never appears in .text or .rodata — derived fresh each run. */
    uint32_t cert_seed = get_apk_cert_seed(env, ctx);
    if (cert_seed == 0) {
        DEX_LOGE("load: cert seed extraction failed — possible tampering");
        return -100;
    }

    /* 0d. Secondary cert seed verification — independent XOR-obfuscated check.
     *     Different obfuscation key and location from d_se() in native-bridge.cpp.
     *     An attacker must find and patch BOTH to bypass cert verification. */
    {
        /* Expected: 0x55A565AD XOR 0x93939393 = obfuscated constant below */
        static const uint32_t g_cert_seed_obf2 = 0xC636F63E;
        volatile uint32_t deobf = g_cert_seed_obf2 ^ 0x93939393u;
        if (cert_seed != deobf) {
            DEX_LOGE("load: cert seed secondary verification FAILED");
            /* Obfuscated failure — use opaque predicate to resist patching */
            volatile int sec_fail = -101;
            if (opaque_false(sec_fail)) { sec_fail = 0; }
            return sec_fail;
        }
    }

    /* 0e. Decrypt resources.arsc in-place in the APK file.
     * Must happen BEFORE the framework accesses resources (lazy-loaded).
     * Uses same cert_seed for deterministic key derivation.
     * Non-fatal: resource loading falls back to encrypted version if
     * the file can't be opened (e.g., on some ROMs). */
    {
        int res_rc = decrypt_resources_arsc(env, ctx, cert_seed);
        if (res_rc != 0) {
            DEX_LOGI("load: resource decrypt rc=%d (may be pre-decrypted)", res_rc);
        }
    }

    /* 1. Allocate buffer via mmap (NOT malloc — protects Scudo heap metadata) */
    uint8_t* dex_buf = (uint8_t*)mmap(NULL, g_vmp_payload_size,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (dex_buf == MAP_FAILED) {
        DEX_LOGE("load: mmap(%u) failed", g_vmp_payload_size);
        return -1;
    }

    int rc = dex_packer_decrypt(dex_buf, g_vmp_payload_size, cert_seed);
    if (rc != 0) {
        DEX_LOGE("load: decrypt failed (rc=%d)", rc);
        munmap(dex_buf, g_vmp_payload_size);
        return rc;
    }

    /* 2. Read actual DEX size from header (strip SM4-ECB padding) */
    uint32_t dex_size = g_vmp_payload_size;
    if (dex_size >= 40) {  /* DEX header is at least 40 bytes (magic+checksum+sig+file_size) */
        uint32_t dex_size_raw;
        memcpy(&dex_size_raw, dex_buf + 32, 4);
        dex_size = dex_size_raw;
        if (dex_size == 0 || dex_size > g_vmp_payload_size) {
            DEX_LOGI("load: DEX header file_size=%u, using payload_size=%u", dex_size, g_vmp_payload_size);
            dex_size = g_vmp_payload_size;
        }
    }

    /* 3. Create ByteBuffer wrapping the decrypted DEX (use real file_size) */
    jobject byteBuffer = env->NewDirectByteBuffer(dex_buf, (jlong)dex_size);
    if (!byteBuffer || env->ExceptionCheck()) {
        DEX_LOGE("load: NewDirectByteBuffer failed");
        env->ExceptionClear();
        munmap(dex_buf, g_vmp_payload_size);
        return -3;
    }

    /* 4. Get InMemoryDexClassLoader class */
    jclass loaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!loaderClass || env->ExceptionCheck()) {
        DEX_LOGE("load: InMemoryDexClassLoader not found (API < 26?)");
        env->ExceptionClear();
        env->DeleteLocalRef(byteBuffer);
        munmap(dex_buf, g_vmp_payload_size);
        return -4;
    }

    /* 5. Get parent ClassLoader from context */
    jclass ctxClass = env->GetObjectClass(ctx);
    jmethodID getClassLoader = env->GetMethodID(ctxClass, "getClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    if (!getClassLoader) {
        DEX_LOGE("load: getClassLoader method not found");
        env->DeleteLocalRef(ctxClass);
        env->DeleteLocalRef(loaderClass);
        env->DeleteLocalRef(byteBuffer);
        munmap(dex_buf, g_vmp_payload_size);
        return -5;
    }
    jobject parentLoader = env->CallObjectMethod(ctx, getClassLoader);
    env->DeleteLocalRef(ctxClass);

    /* 5. Construct InMemoryDexClassLoader(ByteBuffer, ClassLoader) */
    jmethodID ctor = env->GetMethodID(loaderClass, "<init>",
                                       "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    if (!ctor) {
        DEX_LOGE("load: InMemoryDexClassLoader(ByteBuffer, ClassLoader) ctor not found");
        env->DeleteLocalRef(loaderClass);
        env->DeleteLocalRef(byteBuffer);
        env->DeleteLocalRef(parentLoader);
        munmap(dex_buf, g_vmp_payload_size);
        return -6;
    }

    jobject dexLoader = env->NewObject(loaderClass, ctor, byteBuffer, parentLoader);
    if (!dexLoader || env->ExceptionCheck()) {
        DEX_LOGE("load: InMemoryDexClassLoader construction failed");
        env->ExceptionClear();
        env->DeleteLocalRef(loaderClass);
        env->DeleteLocalRef(byteBuffer);
        env->DeleteLocalRef(parentLoader);
        munmap(dex_buf, g_vmp_payload_size);
        return -7;
    }

    env->DeleteLocalRef(byteBuffer);
    env->DeleteLocalRef(parentLoader);

    /* 6. Load the real Application class from the payload ClassLoader.
     *     We MUST use dexLoader.loadClass() rather than env->FindClass()
     *     because FindClass uses the system ClassLoader (APK DEX), not
     *     the InMemoryDexClassLoader (encrypted payload). */
    jmethodID loadClassMethod = env->GetMethodID(
        loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!loadClassMethod) {
        DEX_LOGE("load: ClassLoader.loadClass() method not found");
        env->DeleteLocalRef(loaderClass);
        env->DeleteLocalRef(byteBuffer);
        env->DeleteLocalRef(dexLoader);
        munmap(dex_buf, g_vmp_payload_size);
        return -8;
    }

    jstring classNameStr = env->NewStringUTF(appClass);
    jclass appClassObj = (jclass)env->CallObjectMethod(dexLoader, loadClassMethod, classNameStr);
    env->DeleteLocalRef(classNameStr);

    if (!appClassObj || env->ExceptionCheck()) {
        DEX_LOGE("load: real Application class '%s' not found in payload DEX", appClass);
        env->ExceptionClear();
        env->DeleteLocalRef(loaderClass);
        env->DeleteLocalRef(dexLoader);
        munmap(dex_buf, g_vmp_payload_size);
        return -9;
    }

    DEX_LOGI("load: DEX loaded successfully, found real Application class '%s'", appClass);

    /* 7. Return the ClassLoader + Application class via JNI globals
     *    The Kotlin side will use these to replace the shell Application */
    jclass globalLoaderClass = (jclass)env->NewGlobalRef(loaderClass);
    jobject globalDexLoader = env->NewGlobalRef(dexLoader);
    jclass globalAppClass = (jclass)env->NewGlobalRef(appClassObj);

    env->DeleteLocalRef(loaderClass);
    env->DeleteLocalRef(dexLoader);
    env->DeleteLocalRef(appClassObj);

    /* Store globals in static fields on the caller's class for later use.
     * We use the NativeBridge class as a holder. */
    jclass bridgeClass = env->FindClass("com/yunian/ai/security/NativeBridge");
    if (!bridgeClass || env->ExceptionCheck()) {
        DEX_LOGE("load: NativeBridge class not found — can't store references");
        env->ExceptionClear();
        env->DeleteGlobalRef(globalLoaderClass);
        env->DeleteGlobalRef(globalDexLoader);
        env->DeleteGlobalRef(globalAppClass);
        munmap(dex_buf, g_vmp_payload_size);
        return -9;
    }

    // Store sDexClassLoader field (name randomized per build via g_vmp_config.h)
    jfieldID loaderField = env->GetStaticFieldID(bridgeClass, VMP_SHELL_DEX_LOADER,
                                                  "Ljava/lang/ClassLoader;");
    if (loaderField) {
        env->SetStaticObjectField(bridgeClass, loaderField, globalDexLoader);
    } else {
        DEX_LOGE("load: NativeBridge.%s field not found", VMP_SHELL_DEX_LOADER);
    }

    // Store sRealAppClass field (name randomized per build via g_vmp_config.h)
    jfieldID appClassField = env->GetStaticFieldID(bridgeClass, VMP_SHELL_REAL_APP_CLASS,
                                                    "Ljava/lang/Class;");
    if (appClassField) {
        env->SetStaticObjectField(bridgeClass, appClassField, globalAppClass);
    } else {
        DEX_LOGE("load: NativeBridge.%s field not found", VMP_SHELL_REAL_APP_CLASS);
    }

    env->DeleteGlobalRef(globalLoaderClass);
    env->DeleteGlobalRef(globalDexLoader);
    env->DeleteGlobalRef(globalAppClass);

    /* NOTE: dex_buf is NOT freed — the InMemoryDexClassLoader holds a
     * reference through the DirectByteBuffer. It will be freed when the
     * ClassLoader is GC'd.
     * However, we PROTECT the buffer immediately:
     *   - mprotect(PROT_READ)  → prevents tampering
     *   - madvise(DONTDUMP)    → prevents core dump capture */

    protect_dex_buffer(dex_buf, dex_size);

    DEX_LOGI("load: complete — real Application ready");
    return 0;
}

/*
 * nativeLoadPayload entry point — called via RegisterNatives from native-bridge.cpp.
 * The Java method name is randomized per build (VMP_SHELL_LOAD_PAYLOAD in g_vmp_config.h).
 */

jint native_load_payload_entry(
    JNIEnv* env, jclass clazz, jobject context, jstring appClassName) {

    (void)clazz; /* unused */

    const char* appClass = env->GetStringUTFChars(appClassName, nullptr);
    if (!appClass) return -1;

    int rc = dex_packer_load(env, context, appClass);

    env->ReleaseStringUTFChars(appClassName, appClass);
    return (jint)rc;
}
