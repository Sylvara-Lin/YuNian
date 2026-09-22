// Runtime decrypt stub — constructor-based self-decryption.
// pack_so.py encrypts .text in-place and patches the globals below.
#pragma once

#include <cstdint>

#ifdef __cplusplus
extern "C" {
#endif

// 128-bit XOR key — patched by pack_so.py at build time
extern __attribute__((visibility("default"))) uint8_t lianyu_xor_key[16];

// .text section info — patched by pack_so.py at build time
extern __attribute__((visibility("default"))) uint64_t lianyu_text_start;
extern __attribute__((visibility("default"))) uint64_t lianyu_text_size;

// Backward compat pointer (unused in new scheme, kept for version scripts)
extern __attribute__((visibility("default"))) void* lianyu_text_decrypt_ptr;

// D2 .text self-decryption — called via constructor(101) before JNI_OnLoad
__attribute__((visibility("default"))) void lianyu_d2_decrypt();

#ifdef __cplusplus
}
#endif
