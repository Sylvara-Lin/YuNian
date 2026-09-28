#ifndef YUNIAN_VM_ENGINE_H
#define YUNIAN_VM_ENGINE_H

#include <cstdint>
#include <cstddef>
#include "g_vmp_config.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ========== VM Instruction Set ==========
 * All instructions are 16-bit aligned.
 * Format: [opcode:8] [operands:...]
 * Registers: R0-R15 (32-bit each)
 * Stack: 256 x 32-bit
 */

/* Opcodes */
/* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  /* Opcodes — per-build randomized via VMP_OP_* defines in g_vmp_config.h */
#ifdef VMP_OP_NOP
  // Randomized opcodes from g_vmp_config.h
  #define OP_NOP       VMP_OP_NOP
  #define OP_LOAD_IMM  VMP_OP_LOAD_IMM
  #define OP_LOAD_REG  VMP_OP_LOAD_REG
  #define OP_STORE_REG VMP_OP_STORE_REG
  #define OP_LOAD_MEM  VMP_OP_LOAD_MEM
  #define OP_STORE_MEM VMP_OP_STORE_MEM
  #define OP_ADD       VMP_OP_ADD
  #define OP_SUB       VMP_OP_SUB
  #define OP_XOR       VMP_OP_XOR
  #define OP_AND       VMP_OP_AND
  #define OP_OR        VMP_OP_OR
  #define OP_SHL       VMP_OP_SHL
  #define OP_SHR       VMP_OP_SHR
  #define OP_ADD_IMM   VMP_OP_ADD_IMM
  #define OP_SBOX      VMP_OP_SBOX
  #define OP_GFMUL     VMP_OP_GFMUL
  #define OP_XTIME     VMP_OP_XTIME
  #define OP_MUL       VMP_OP_MUL
  #define OP_MUL_IMM   VMP_OP_MUL_IMM
  #define OP_CMP       VMP_OP_CMP
  #define OP_JMP       VMP_OP_JMP
  #define OP_JE        VMP_OP_JE
  #define OP_JNE       VMP_OP_JNE
  #define OP_JG        VMP_OP_JG
  #define OP_JL        VMP_OP_JL
  #define OP_CMP_IMM   VMP_OP_CMP_IMM
  #define OP_JGE       VMP_OP_JGE
  #define OP_CALL      VMP_OP_CALL
  #define OP_RET       VMP_OP_RET
  #define OP_HYPERCALL VMP_OP_HYPERCALL
  #define OP_HALT      VMP_OP_HALT
#else
  // Fallback: default opcodes
  enum VMOpcode : uint8_t {    OP_ADD = 0x10,
    OP_ADD_IMM = 0x17,
    OP_AND = 0x13,
    OP_CALL = 0x30,
    OP_CMP = 0x20,
    OP_CMP_IMM = 0x26,
    OP_GFMUL = 0x19,
    OP_HALT = 0xFF,
    OP_HYPERCALL = 0x32,
    OP_JE = 0x22,
    OP_JG = 0x24,
    OP_JGE = 0x27,
    OP_JL = 0x25,
    OP_JMP = 0x21,
    OP_JNE = 0x23,
    OP_LOAD_IMM = 0x01,
    OP_LOAD_MEM = 0x04,
    OP_LOAD_REG = 0x02,
    OP_MUL = 0x1B,
    OP_MUL_IMM = 0x1C,
    OP_NOP = 0x00,
    OP_OR = 0x14,
    OP_RET = 0x31,
    OP_SBOX = 0x18,
    OP_SHL = 0x15,
    OP_SHR = 0x16,
    OP_STORE_MEM = 0x05,
    OP_STORE_REG = 0x03,
    OP_SUB = 0x11,
    OP_XOR = 0x12,
    OP_XTIME = 0x1A,
  };
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif
#endif

/* VM State */
typedef struct {
    uint32_t regs[16];      // R0-R15
    uint32_t stack[256];    // call/scratch stack
    uint32_t sp;            // stack pointer
    uint32_t pc;            // program counter (byte offset)
    uint32_t flags;         // CMP results: bit0=Z, bit1=C
    const uint8_t* code;    // bytecode
    uint32_t code_size;     // bytecode size in bytes
    uint32_t steps;         // instruction counter
    int halted;             // 1 = halted
    int error;              // error code
    /* === VMP Hardening (Task 2.3) === */
    uint32_t bytecode_crc;     /**< Expected CRC32 of bytecode (set at init) */
    uint64_t last_tick_ts;     /**< Monotonic timestamp of last instruction (anti-singlestep) */
    uint32_t tick_count;       /**< Instruction counter for periodic integrity check */
    uint32_t integrity_seed;   /**< Random seed for per-run code hash */
    uint8_t  tampered;         /**< Set to 1 if integrity violation detected */
} VMState;

/* Initialize VM with bytecode */
void vm_init(VMState* vm, const uint8_t* bytecode, uint32_t size);

/* Execute until HALT or max_steps */
int vm_run(VMState* vm, uint32_t max_steps);

/* Set register value */
void vm_set_reg(VMState* vm, int reg, uint32_t value);

/* Get register value */
uint32_t vm_get_reg(VMState* vm, int reg);

/* ========== Bytecode Builders ========== */

/* Hypercall function IDs */
#define VM_HYPER_READ_FILE  0   // rs1=filename_ptr, rs2=buf_ptr → rd=bytes_read
#define VM_HYPER_TRACER     1   // → rd=1 if traced, 0 if clean
#define VM_HYPER_DELAY      2   // rd=milliseconds to sleep
#define VM_HYPER_FRIDA      3   // → rd=1 if frida detected
#define VM_HYPER_CRC32      4   // → rd=CRC32 of .text section
#define VM_HYPER_ROOT_CHECK 5   // → rd=1 if rooted
#define VM_HYPER_KMS_STATUS 6   // → rd=KMS state (0=uninit,1=ready,-1=destroyed)
#define VM_HYPER_KMS_INIT   7   // → rd=1 if KMS init OK
#define VM_HYPER_KDF_SM3    8   // rs1=ctx_ptr, rs2=ctx_len → rd=buf_ptr(32B in scratch)
#define VM_HYPER_WB_AES_DEC 9   // rs1=in_ptr, rs2=out_ptr → rd=1 ok
#define VM_HYPER_SM3_HASH   10  // rs1=data_ptr, rs2=data_len → rd=hash_ptr
#define VM_HYPER_TEE_ATTEST 11  // → rd=1 if TEE attestation passes
#define VM_HYPER_SIG_VERIFY 12  // → rd=1 if APK signature valid
#define VM_HYPER_SECURE_WIPE 13 // rs1=ptr, rs2=len → secure wipe
#define VM_HYPER_WB_AES_KEYCHECK 14 // → rd=1 if WB-AES T-Box integrity OK
#define VM_HYPER_SM4_KEY_EXPAND   15 // rs1=key_ptr(16B), rs2=rk_ptr(128B out) → rd=1 ok
#define VM_HYPER_SM4_DECRYPT_BLOCK 16 // rs1=rk_ptr, rs2=block_ptr(16B in/out) → rd=1 ok
#define VM_HYPER_DERIVE_SHELL_KEY 17  // → rd=buf_ptr(32B key in scratch)
#define VM_HYPER_EXECUTION_HASH 18    // → rd=64-bit VMP execution hash

/* Encode instructions into bytecode buffer.
 * Returns bytes written. */
void vm_encode_nop(uint8_t* buf, uint32_t* off);
void vm_encode_load_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_add(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_xor(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_sbox(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs);
void vm_encode_cmp(uint8_t* buf, uint32_t* off, uint8_t rs1, uint8_t rs2);
void vm_encode_jmp(uint8_t* buf, uint32_t* off, uint32_t addr);
void vm_encode_je(uint8_t* buf, uint32_t* off, uint32_t addr);
void vm_encode_halt(uint8_t* buf, uint32_t* off);
void vm_encode_hypercall(uint8_t* buf, uint32_t* off, uint8_t func_id, uint8_t rd, uint8_t rs1, uint8_t rs2);
/* v2.0 extended encoders */
void vm_encode_add_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_mul(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2);
void vm_encode_mul_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm);
void vm_encode_cmp_imm(uint8_t* buf, uint32_t* off, uint8_t rs, uint32_t imm);

/* ========== Pre-compiled VM Bytecode Entry Points ========== */

/* VM program that decrypts an AES block using bytecode.
 * Runs entirely inside the VM — no key material exposed. */
extern const uint8_t g_vm_aes_decrypt[];
extern const uint32_t g_vm_aes_decrypt_size;

/* VM program that checks TracerPid (anti-debug) */
extern const uint8_t g_vm_check_tracer[];
extern const uint32_t g_vm_check_tracer_size;

/* VMP v2.0 — Core Security Bytecode Programs */
extern const uint8_t g_vmp_wb_aes_keycheck[];
extern const uint32_t g_vmp_wb_aes_keycheck_size;
extern const uint8_t g_vmp_kms_derive_sk[];
extern const uint32_t g_vmp_kms_derive_sk_size;
extern const uint8_t g_vmp_tee_attest[];
extern const uint32_t g_vmp_tee_attest_size;
extern const uint8_t g_vmp_apk_sig_verify[];
extern const uint32_t g_vmp_apk_sig_verify_size;

/* VMP v3.0 — Extended Security Bytecode Programs */
extern const uint8_t g_vmp_root_detect[];
extern const uint32_t g_vmp_root_detect_size;
extern const uint8_t g_vmp_code_integrity[];
extern const uint32_t g_vmp_code_integrity_size;
extern const uint8_t g_vmp_sm3_hash[];
extern const uint32_t g_vmp_sm3_hash_size;
extern const uint8_t g_vmp_frida_heartbeat[];
extern const uint32_t g_vmp_frida_heartbeat_size;

/* Wipe all cached key material and VM state from memory.
 * Called by zero-trust incident response on BREACH (ZT_ACTION_WIPE).
 * Zeroes internal VM key cache, register file, and any buffered secrets.
 */
void vm_engine_wipe_cache(void);

/* === VMP Hardening (Task 2.3) === */

/** Compute CRC32 of bytecode and store in VMState */
void vm_compute_crc(VMState* vm);

/** Verify bytecode integrity (compares live CRC with stored) */
int vm_verify_integrity(VMState* vm);

/** Anti-singlestep: measure instruction timing */
int vm_check_timing(VMState* vm);

/** Interpreter self-hash: verify the native interpreter code */
int vm_verify_interpreter(VMState* vm);

/** Save interpreter prologue at init time for later verification */
void vm_save_interpreter_prologue(void);

/** Full security checkpoint: runs all VMP hardening checks */
int vm_security_checkpoint(VMState* vm);

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_VM_ENGINE_H */
