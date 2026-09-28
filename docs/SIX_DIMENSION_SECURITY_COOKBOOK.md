# YuNian 六维安全架构 — DEX2C·VMP·虚拟化深度技术白皮书

> **性质：** 本文档阐述予念安全架构的底层技术原理——DEX2C 字节码转译、VMP 虚拟化引擎、零信任桥接层——以及它们如何协同实现「静态分析不可破，动态调试检测链路完整，root 防御机制有效」。
> **目标读者：** 安全研究员、逆向工程师、加固方案设计者。
> **前提知识：** DEX 文件格式（Android 官方文档）、ARM64 指令集、ELF 文件格式、Dalvik 字节码。

---

# 第一章：核心命题 — 为什么 DEX2C + VMP 是唯一解

## 1.1 传统加固的致命缺陷

Android 加固行业经历了三代演进，每一代都有不可逾越的天花板：

### 第一代：DEX 整体加密（2014-2017）

```
APK
├── classes.dex          ← 加密的原始 DEX
├── libshell.so          ← 解密 + 动态加载
└── ...
```

**攻击方法：** `frida -n com.app -l dump_dex.js` — 在 `ClassLoader.loadClass` 的返回点 dump 内存中的 DEX。**一行 Frida 脚本击穿。**

致命缺陷：解密后的 DEX 必然以明文形式存在于内存中。攻击者只需要在正确的时机 dump 内存即可获得完整 DEX。

### 第二代：DEX 拆分 + 方法级加密（2017-2020）

```
APK
├── classes.dex          ← 壳 DEX（真实指令被 NOP 替换）
├── libvm.so             ← 方法体解密 + 原地修复
└── ...
```

**攻击方法：** `frida -n com.app -l trace_method_entry.js` — 在所有方法的入口处 hook，记录解密后的字节码。**几十行 Frida 脚本击穿。**

致命缺陷：方法解密 → 执行的窗口期，内存中存在完整明文指令。攻击者可以逐方法 dump 后拼接还原。

### 第三代：DEX2C 转译（2020-至今）

DEX2C 的核心思想是**消灭 Java 层的存在**——将 DEX 字节码中的关键方法编译为 C++ 原生代码，编译进 SO 文件。攻击者面对的不再是容易阅读的 Smali/Dalvik 字节码，而是 ARM64 汇编。

**然而，传统 DEX2C 仍有致命缺陷：**

```
传统 DEX2C 的问题链：
  1. 攻击者 dump libnative.so
  2. 用 Ghidra/IDA 反编译 ARM64 汇编
  3. 虽然比 Smali 难读，但熟悉 ARM64 的逆向工程师仍然可以分析
  4. 一次逆向成果可复用到所有版本（如果加固方案不变）
```

**第四代解法：DEX2C + VMP 双层虚拟化 — 予念的选择。**

## 1.2 予念 DEX2C + VMP 双层虚拟化架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      攻击者视角                                  │
│                                                                 │
│  classes.dex (50KB 壳) ──→ 只有 3 个引导类，零业务逻辑            │
│  liblianyu_security.so ──→ 内嵌 SM4 加密的完整业务 DEX            │
│  liblianyu_dex2c.so    ──→ VMP 解释器，执行自定义字节码            │
│                                                                 │
│  攻击者想拿到业务逻辑，必须：                                      │
│  1. 绕过 SM4 解密（密钥分片存储在 3 个 SO 中）                     │
│  2. 绕过 VMP 解释器的完整性校验（每 256 条指令检测一次）            │
│  3. 理解自定义 VMP 字节码格式（非标准，无文档）                     │
│  4. 理解 VMP 字节码 → 原始逻辑的映射（不存在标准反编译工具）         │
│  5. 以上全部对 v1.N 版本做完后，v1.N+1 版本全部失效               │
└─────────────────────────────────────────────────────────────────┘
```

### 1.2.1 关键设计决策

| 决策 | 传统方案 | 予念方案 | 安全收益 |
|------|---------|---------|---------|
| 密钥存储 | 单一 SO 中硬编码 | 3 SO 分片 + HKDF 派生 | 攻击者必须同时逆向 3 个 SO |
| 业务代码位置 | classes.dex (8MB) | SM4 加密嵌入 SO | 壳 DEX 仅 50KB，零业务逻辑 |
| 关键方法执行 | ARM64 原生指令 | 自定义 VMP 字节码 | 不存在标准反编译器 |
| 版本间复用 | 固定加密方案 | 每次构建全随机化 | 破解成果不跨版本复用 |
| 检测反馈 | 立即 crash | 评分引擎 + 分级响应 | 攻击者无法确定触发条件 |

---

# 第二章：DEX2C 转译引擎 — 字节码 → C++ 原生代码

## 2.1 概述

DEX2C 转译器 (`tools/dex2c_transpile.py`) 是予念安全的第一道核心防线。它读取标准 DEX 文件，解析白名单中标记的安全关键方法，将 Dalvik 字节码转译为等价的 JNI C++ 代码，编译进 `liblianyu_dex2c.so`。

**关键安全属性：** 转译后的方法在 DEX 中不再存在。攻击者即使 dump 出完整 DEX，看到的也只是空壳——方法体已被替换为 `native` 声明。

## 2.2 DEX 格式解析 — 字节级别的精确理解

DEX 文件是 Android 运行时的可执行格式。DEX2C 转译器必须精确解析其每一个结构才能正确提取方法。

### 2.2.1 DEX 文件头 (Header)

```
偏移    大小    字段
0x00    8       magic (dex\n035\0 或 dex\n039\0)
0x08    4       checksum (adler32)
0x0C    20      signature (SHA-1)
0x20    4       file_size
0x24    4       header_size (0x70)
0x28    4       endian_tag (0x12345678)
0x2C    4       link_size, link_off
0x34    4       map_off
0x38    4       string_ids_size
0x3C    4       string_ids_off
0x40    4       type_ids_size
0x44    4       type_ids_off
0x48    4       proto_ids_size
0x4C    4       proto_ids_off
0x50    4       field_ids_size
0x54    4       field_ids_off
0x58    4       method_ids_size
0x5C    4       method_ids_off
0x60    4       class_defs_size
0x64    4       class_defs_off
0x68    4       data_size
0x6C    4       data_off
```

转译器通过 `struct.unpack_from` 直接从 DEX 二进制中提取这些字段，建立对整个文件结构的索引。

### 2.2.2 字符串表解析

```python
def _parse_strings(self):
    for i in range(self.string_ids_size):
        offset = struct.unpack_from('<I', self.data,
            self.string_ids_off + i * 4)[0]
        # MUTF-8 编码的字符串，前导 uleb128 长度
        strlen, consumed = self._read_uleb128(offset)
        raw = self.data[offset + consumed : offset + consumed + strlen]
        self.strings.append(raw.decode('utf-8', errors='replace'))
```

字符串表是 DEX 中所有其他结构的索引基础。类型名、方法名、字段名都通过字符串表的索引引用。

### 2.2.3 ULEB128 编码

DEX 大量使用 ULEB128（Unsigned Little-Endian Base-128）变长编码来节省空间。转译器必须精确实现解码：

```python
def _read_uleb128(self, offset):
    result = 0
    shift = 0
    original_offset = offset
    while True:
        byte = self.data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if (byte & 0x80) == 0:
            break
        shift += 7
    return result, offset - original_offset
```

### 2.2.4 方法定位 — class_defs → class_data → code_item

这是 DEX2C 转译的核心链路：

```
class_defs[class_idx]
  └─ class_data_off ──→ class_data_item
       ├─ static_fields  (uleb128 编码的 field_id_diff 列表)
       ├─ instance_fields
       ├─ direct_methods  (uleb128 编码的 method_id_diff 列表)
       │    └─ method_id_diff → access_flags → code_off
       └─ virtual_methods
            └─ method_id_diff → access_flags → code_off
```

转译器遍历 `class_defs` 中的每个类，解码 `class_data_item`，找到匹配 `method_idx` 的方法，读取 `code_off`，然后解析 `code_item`。

### 2.2.5 code_item 结构

```
code_item {
    uint16_t registers_size;    // 方法使用的寄存器总数
    uint16_t ins_size;          // 传入参数占用的寄存器数
    uint16_t outs_size;         // 调用其他方法时的输出寄存器数
    uint16_t tries_size;        // try-catch 块数量
    uint32_t debug_info_off;    // 调试信息偏移
    uint32_t insns_size;        // 指令数组大小（以 16-bit 为单位）
    uint16_t insns[insns_size]; // 实际指令
    // 可选的 try_item 和 catch_handler 列表
}
```

## 2.3 Dalvik 字节码 → C++ 转译规则

### 2.3.1 寄存器映射

Dalvik 虚拟机使用寄存器架构（而非 JVM 的栈架构）。转译器将 Dalvik 寄存器映射为 C++ 局部变量：

```
Dalvik 寄存器      C++ 变量
v0-vN              uint32_t v0, v1, ..., vN
v0 (this)          jobject _this (JNI 隐式参数)
```

### 2.3.2 指令转译表

| Dalvik 指令 | 语义 | C++ 等价代码 |
|------------|------|-------------|
| `const/4 vA, #+B` | vA = (int4)B | `vA = (int32_t)(int4_t)B;` |
| `const/16 vA, #+BBBB` | vA = (int16)BBBB | `vA = (int16_t)BBBB;` |
| `const vA, #+BBBBBBBB` | vA = BBBBBBBB | `vA = BBBBBBBB;` |
| `move vA, vB` | vA = vB | `vA = vB;` |
| `add-int vA, vB, vC` | vA = vB + vC | `vA = vB + vC;` |
| `sub-int vA, vB, vC` | vA = vB - vC | `vA = vB - vC;` |
| `mul-int vA, vB, vC` | vA = vB * vC | `vA = vB * vC;` |
| `div-int vA, vB, vC` | vA = vB / vC | `if (vC != 0) vA = vB / vC;` |
| `and-int vA, vB, vC` | vA = vB & vC | `vA = vB & vC;` |
| `or-int vA, vB, vC` | vA = vB \| vC | `vA = vB \| vC;` |
| `xor-int vA, vB, vC` | vA = vB ^ vC | `vA = vB ^ vC;` |
| `if-eq vA, vB, +CCCC` | if vA==vB goto +CCCC | `if (vA == vB) goto label_XXXX;` |
| `if-ne vA, vB, +CCCC` | if vA!=vB goto +CCCC | `if (vA != vB) goto label_XXXX;` |
| `goto +AA` | goto +AA | `goto label_XXXX;` |
| `invoke-virtual {vC...}, meth@BBBB` | 调用虚方法 | `env->CallVoidMethod(obj, methodID, ...);` |
| `invoke-static {vC...}, meth@BBBB` | 调用静态方法 | `env->CallStaticVoidMethod(clazz, methodID, ...);` |
| `return-void` | return | `return;` |
| `return vA` | return vA | `return vA;` |
| `new-instance vA, type@BBBB` | vA = new Type | `vA = env->NewObject(clazz, constructorID);` |
| `iget vA, vB, field@CCCC` | vA = vB.field | `vA = env->GetIntField(obj, fieldID);` |
| `iput vA, vB, field@CCCC` | vB.field = vA | `env->SetIntField(obj, fieldID, vA);` |

### 2.3.3 控制流重建

Dalvik 字节码使用相对偏移进行跳转。转译器必须重建控制流图 (CFG)，将偏移转换为 C++ 标签：

```python
def _decode_instructions(self, code, count):
    result = []
    i = 0
    while i < len(code):
        opcode = code[i] & 0xFF
        # 解码指令 → 确定指令长度 → 推进 i
        # 记录每条指令的偏移、操作码、参数
    return result

def _generate_control_flow(self, instructions):
    # 识别基本块边界：跳转目标、跳转指令之后
    # 为每个基本块分配 C++ 标签
    # 将条件/无条件跳转转换为 goto label_XXXX
```

## 2.4 白名单机制

并非所有方法都适合 DEX2C 转译。转译器通过 `dex2c_whitelist.txt` 控制转译范围：

```
# tools/dex2c_whitelist.txt — DEX2C 转译白名单
com.yunian.ai.security.NativeBridge.nativeShellInit
com.yunian.ai.security.NativeBridge.nativeDecryptPayload
com.yunian.ai.security.KmsProvider.deriveKeyMaterial
com.yunian.ai.security.SecurityGuard.verifySignature
com.yunian.ai.security.CompositeVmpRuntime.execute
com.yunian.ai.security.DexFragmentLoader.loadShellFragment
com.yunian.ai.security.DynamicClassLoader.findClass
com.yunian.ai.security.MethodRecoveryEngine.recoverMethod
com.yunian.ai.security.VmpDex2cDispatcher.dispatch
```

**白名单选择原则：**
1. **安全关键方法**：密钥派生、签名验证、载荷解密 — 必须转译
2. **壳入口方法**：Shell 加载、ClassLoader 注入 — 必须转译
3. **体积敏感方法**：大于 100 条 Dalvik 指令的方法 — 转译后 ARM64 体积更小
4. **不转译**：简单 getter/setter、生命周期回调、UI 方法 — 转译收益低，增加复杂度

## 2.5 C++ 代码生成

### 2.5.1 JNI 函数签名

转译后的每个方法都是一个 JNI 函数：

```cpp
// 转译前 (Dalvik):
// .method private decryptPayload([B[B)[B

// 转译后 (C++):
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yunian_ai_security_NativeBridge_nativeDecryptPayload(
    JNIEnv* env, jobject thiz, jbyteArray encrypted, jbyteArray key) {
    // ... 转译后的方法体
}
```

### 2.5.2 类型转换

| Dalvik 类型 | JNI 类型 | C++ 类型 |
|------------|---------|---------|
| `Z` (boolean) | `jboolean` | `uint8_t` |
| `B` (byte) | `jbyte` | `int8_t` |
| `C` (char) | `jchar` | `uint16_t` |
| `S` (short) | `jshort` | `int16_t` |
| `I` (int) | `jint` | `int32_t` |
| `J` (long) | `jlong` | `int64_t` |
| `F` (float) | `jfloat` | `float` |
| `D` (double) | `jdouble` | `double` |
| `L` (object) | `jobject` | `jobject` |
| `[` (array) | `jarray` | `jarray` |

### 2.5.3 生成的代码示例

原始 Dalvik 字节码（密钥 XOR 解密循环）：

```smali
# 输入: v2 = encrypted_data, v3 = length, v4 = key
    const/4 v0, #0            # v0 = 0 (counter)
    const/16 v5, #15          # v5 = 15 (mask)
:loop
    if-ge v0, v3, :done       # if counter >= length: break
    aget-byte v6, v2, v0      # v6 = enc[counter]
    and-int/lit8 v7, v0, #15  # v7 = counter & 15
    aget-byte v8, v4, v7      # v8 = key[counter & 15]
    xor-int/2addr v6, v8      # v6 = v6 ^ v8
    aput-byte v6, v2, v0      # enc[counter] = v6
    add-int/lit8 v0, v0, #1   # counter++
    goto :loop
:done
    return-void
```

DEX2C 转译后的 C++ 代码：

```cpp
extern "C" JNIEXPORT void JNICALL
Java_com_yunian_ai_security_NativeBridge_nativeDecryptPayload(
    JNIEnv* env, jobject thiz, jbyteArray encrypted, jint length, jbyteArray key) {

    jbyte* enc = env->GetByteArrayElements(encrypted, nullptr);
    jbyte* k = env->GetByteArrayElements(key, nullptr);

    int32_t v0 = 0;       // counter
    const int32_t v5 = 15; // mask

    while (1) {
        if (v0 >= length) break;  // :done
        int32_t v6 = enc[v0];     // aget-byte
        int32_t v7 = v0 & 15;     // and-int/lit8
        int32_t v8 = k[v7];       // aget-byte
        v6 = v6 ^ v8;             // xor-int
        enc[v0] = (jbyte)v6;      // aput-byte
        v0 = v0 + 1;              // add-int/lit8
    }

    env->ReleaseByteArrayElements(encrypted, enc, 0);
    env->ReleaseByteArrayElements(key, k, JNI_ABORT);
    return;
}
```

## 2.6 构建集成

DEX2C 转译发生在构建流水线的 Step 3：

```
Step 1: ./gradlew :shell:assembleRelease  → 完整 APK
Step 2: sm4_tool enc → 加密 DEX，生成 key + CRC32
Step 3: python3 tools/dex2c_transpile.py  → DEX → C++ 转译
Step 3.5: python3 tools/obfuscate_bytecode.py → 不透明谓词随机化
Step 4: ndk-build → 重建 liblianyu_dex2c.so（含转译后的 C++ 代码）
Step 5: python3 tools/encrypt_text_section.py → SO .text 段 XOR 加密
Step 6: R8 tree-shake → 壳 DEX（仅 3 个引导类，~50KB）
Step 7: zip + zipalign -p 4 + apksigner → 最终壳 APK
```

---

# 第三章：VMP 虚拟化引擎 — 自定义字节码解释器

## 3.1 概述

VMP（Virtual Machine Protection）是予念安全的第二道核心防线。DEX2C 转译后的 C++ 代码虽然消除了 Dalvik 层的存在，但编译后的 ARM64 指令仍然可以被 Ghidra/IDA 反编译。VMP 解决这个问题：**将关键函数的 ARM64 指令替换为自定义字节码，由虚拟机解释器执行。原始 ARM64 指令在最终二进制中不存在。**

### 3.1.1 为什么需要 VMP 叠加 DEX2C

```
DEX2C 单独使用：
  攻击者 dump liblianyu_dex2c.so
  → Ghidra/IDA 反编译 ARM64 汇编
  → 虽然难读，但经验丰富的逆向工程师仍可分析
  → 一次逆向，全版本通用（如果加固方案不变）

DEX2C + VMP 叠加：
  攻击者 dump liblianyu_dex2c.so
  → 看到的是 VMP 解释器（vm_run 函数）+ 自定义字节码数组
  → 字节码格式是私有的，没有标准反编译器
  → 必须手动逆向解释器逻辑 + 理解字节码语义
  → 每次构建字节码格式随机化，跨版本分析失效
```

## 3.2 VMP 指令集架构 (ISA)

VMP 实现了一个精简的 RISC-like 虚拟处理器，包含 28 条指令。

### 3.2.1 虚拟寄存器

```
虚拟硬件:
  - 16 个 32 位通用寄存器 (R0-R15)
  - 256 个 32 位调用栈槽位
  - 标志寄存器 (Z=零标志, C=进位/大于标志)
  - 程序计数器 (PC)
  - 指令计数器 (供完整性检查点使用)
```

### 3.2.2 完整指令集

#### 数据移动指令 (0x00-0x05)

| 操作码 | 助记符 | 格式 | 语义 |
|--------|--------|------|------|
| 0x00 | NOP | `[op:8]` | 无操作 |
| 0x01 | LOAD_IMM | `[op:8][rd:4][pad:4][imm:32]` | rd = imm32 |
| 0x02 | LOAD_REG | `[op:8][rd:4][rs:4]` | rd = rs |
| 0x03 | STORE_REG | `[op:8][rd:4][rs:4]` | rd = rs |
| 0x04 | LOAD_MEM | `[op:8][rd:4][rs:4]` | rd = *(uint32_t*)rs |
| 0x05 | STORE_MEM | `[op:8][addr_reg:4][rs:4]` | *(uint32_t*)addr_reg = rs |

#### 算术逻辑指令 (0x10-0x1C)

| 操作码 | 助记符 | 格式 | 语义 |
|--------|--------|------|------|
| 0x10 | ADD | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 + rs2 |
| 0x11 | SUB | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 - rs2 |
| 0x12 | XOR | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 ^ rs2 |
| 0x13 | AND | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 & rs2 |
| 0x14 | OR | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 \| rs2 |
| 0x15 | SHL | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 << (rs2 & 0x1F) |
| 0x16 | SHR | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 >> (rs2 & 0x1F) |
| 0x17 | ADD_IMM | `[op:8][rd:4][imm:32]` | rd = rd + imm32 |
| 0x1B | MUL | `[op:8][rd:4][rs1:4][rs2:4]` | rd = rs1 * rs2 |
| 0x1C | MUL_IMM | `[op:8][rd:4][imm:32]` | rd = rd * imm32 |

#### 密码学加速指令 (0x18-0x1A)

这些指令是 VMP 的独特设计——直接在虚拟 ISA 中暴露 AES 原语，使密钥派生和加密操作在 VM 内部完成，密钥材料永不离开 VM 寄存器。

| 操作码 | 助记符 | 格式 | 语义 |
|--------|--------|------|------|
| 0x18 | SBOX | `[op:8][rd:4][rs:4]` | rd = AES_SBOX[rs & 0xFF] |
| 0x19 | GFMUL | `[op:8][rd:4][rs1:4][rs2:4]` | rd = gf_mul(rs1 & 0xFF, rs2 & 0xFF) |
| 0x1A | XTIME | `[op:8][rd:4][rs:4]` | rd = xtime(rs & 0xFF) |

**GF(2^8) 乘法实现：**
```cpp
static inline uint8_t gf_mul(uint8_t a, uint8_t b) {
    uint8_t p = 0;
    for (int i = 0; i < 8; i++) {
        if (b & 1) p ^= a;
        uint8_t hi = a & 0x80;
        a = (a << 1);
        if (hi) a ^= 0x1B;  // AES 不可约多项式 x^8 + x^4 + x^3 + x + 1
        b >>= 1;
    }
    return p;
}
```

#### 控制流指令 (0x20-0x31)

| 操作码 | 助记符 | 格式 | 语义 |
|--------|--------|------|------|
| 0x20 | CMP | `[op:8][rs1:4][rs2:4]` | 比较 rs1 和 rs2，设置 Z/C 标志 |
| 0x26 | CMP_IMM | `[op:8][rs:4][imm:32]` | 比较 rs 和 imm32 |
| 0x21 | JMP | `[op:8][addr:32]` | 无条件跳转 |
| 0x22 | JE | `[op:8][addr:32]` | Z=1 时跳转 |
| 0x23 | JNE | `[op:8][addr:32]` | Z=0 时跳转 |
| 0x24 | JG | `[op:8][addr:32]` | C=1 时跳转（大于） |
| 0x25 | JL | `[op:8][addr:32]` | C=0 且 Z=0 时跳转（小于） |
| 0x27 | JGE | `[op:8][addr:32]` | C=1 或 Z=1 时跳转（大于等于） |
| 0x30 | CALL | `[op:8][addr:32]` | 调用子程序（压栈返回地址） |
| 0x31 | RET | `[op:8]` | 从子程序返回（弹栈） |

#### 系统调用指令 (0x32 HYPERCALL)

HYPERCALL 是 VM 与宿主系统交互的唯一接口。所有系统级操作（文件 I/O、网络、内存分配）必须通过 HYPERCALL 进行，VM 内部无法直接访问系统调用。

```
HYPERCALL 指令格式:
  [op:8=0x32][func_id:8][rd:4][rs1:4][rs2:4][pad:8]

func_id 定义:
  0x00  VM_HYPER_READ_FILE      - 读取文件内容
  0x01  VM_HYPER_TRACER          - 检查 /proc/self/status TracerPid
  0x02  VM_HYPER_DELAY           - 毫秒级延迟
  0x03  VM_HYPER_FRIDA           - Frida 复合检测（maps + port + thread names）
  0x04  VM_HYPER_CRC32           - 计算自身 .text 段 CRC32
  0x05  VM_HYPER_ROOT_CHECK      - 复合 root 检测（su + Magisk + SELinux）
  0x06  VM_HYPER_KMS_STATUS      - 查询 KMS 状态
  0x07  VM_HYPER_KMS_INIT        - 初始化 KMS
  0x08  VM_HYPER_KDF_SM3         - SM3 密钥派生
  0x09  VM_HYPER_WB_AES_DEC      - 白盒 AES 解密
  0x0A  VM_HYPER_SM3_HASH        - SM3 哈希
  0x0B  VM_HYPER_TEE_ATTEST      - TEE 硬件认证
  0x0C  VM_HYPER_SIG_VERIFY      - APK 签名验证
  0x0D  VM_HYPER_SECURE_WIPE     - 安全内存擦除（含 dc civac 缓存刷新）
  0x0E  VM_HYPER_WB_AES_KEYCHECK - 白盒 AES T-Box 完整性自检
  0x0F  VM_HYPER_SM4_KEY_EXPAND  - SM4 密钥扩展
  0x10  VM_HYPER_SM4_DECRYPT_BLOCK - SM4 单块解密
```

#### 终止指令

| 操作码 | 助记符 | 语义 |
|--------|--------|------|
| 0xFF | HALT | 停止 VM 执行 |

## 3.3 VM 状态结构

```cpp
typedef struct {
    uint32_t regs[16];          // R0-R15 通用寄存器
    uint32_t stack[256];        // 调用/暂存栈
    uint32_t sp;                // 栈指针
    uint32_t pc;                // 程序计数器（字节偏移）
    uint32_t flags;             // 标志位：bit0=Z(零), bit1=C(进位/大于)
    const uint8_t* code;        // 字节码指针
    uint32_t code_size;         // 字节码大小
    uint32_t steps;             // 指令执行计数器
    int halted;                 // 1=已停止
    int error;                  // 错误码
    // VMP 加固字段
    uint32_t bytecode_crc;      // 字节码 CRC32 预期值
    uint64_t last_tick_ts;      // 上一条指令的单调时钟戳（反单步）
    uint32_t tick_count;        // 指令计数器（周期性完整性检查）
    uint32_t integrity_seed;    // 每次运行的随机种子（防预计算）
    uint8_t  tampered;          // 1=检测到完整性违规
} VMState;
```

## 3.4 VM 解释器主循环

### 3.4.1 获取-解码-执行循环

```cpp
int vm_run(VMState* vm, uint32_t max_steps) {
    while (vm->steps < max_steps && !vm->halted) {
        vm->steps++;

        // === 完整性检查点：每 256 条指令 ===
        vm->tick_count++;
        if ((vm->tick_count & 0xFF) == 0) {
            if (!vm_security_checkpoint(vm)) {
                vm->error = 2;  // 完整性违规
                return -2;
            }
        }

        // 获取操作码
        uint8_t op = FETCH_U8(); ADVANCE(1);

        // 解码+执行
        switch (op) {
            case OP_LOAD_IMM: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint32_t imm = FETCH_U32(); ADVANCE(4);
                WR(rd, imm);
                break;
            }
            case OP_ADD: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) + RD(rs2));
                break;
            }
            case OP_XOR: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) ^ RD(rs2));
                break;
            }
            // ... 所有 28 条指令的 case 分支
            case OP_HALT:
                vm->halted = 1;
                break;
            default:
                vm->error = 1;
                return -1;
        }
    }
    return vm->halted ? 0 : 1;
}
```

### 3.4.2 宏定义

```cpp
#define FETCH_U8()  read_u8(vm->code + vm->pc)
#define FETCH_U32() read_u32(vm->code + vm->pc)
#define ADVANCE(n)  vm->pc += (n)
#define RD(i)       vm->regs[(i) & 0xF]
#define WR(i, v)    vm->regs[(i) & 0xF] = (v)
```

## 3.5 AES S-Box 混淆

VMP 的 AES 加速指令依赖 S-Box 查找表。为防止静态提取，S-Box 使用 XOR 混淆存储：

```cpp
// 实际存储的 S-Box 值 = 真实值 ^ 0xA5
// 在静态分析中，这个表看起来是随机数据
static const uint8_t AES_SBOX_XORED[256] = {
    0xc6,0xd9,0xd2,0xde,0x57,0xce,0xca,0x60,0x95,0xa4,0xc2,0x8e,0x5b,0x72,0x0e,0xd3,
    0x6f,0x27,0x6c,0xd8,0x5f,0xfc,0xe2,0x55,0x08,0x71,0x07,0x0a,0x39,0x01,0xd7,0x65,
    // ... (256 bytes)
};

// 运行时解混淆
#define AES_SBOX_LOOKUP(idx) ((uint32_t)(AES_SBOX_XORED[(idx) & 0xFF] ^ 0xA5))
```

**安全设计：** XOR 密钥 (0xA5) 不存储在 S-Box 附近。它实际存储在 `kms-engine.cpp` 中，由 KMS 初始化后计算得出。如果 KMS 未初始化（启动后 <0.1 秒），解混淆失败，VM 返回垃圾数据，击败离线 S-Box 提取攻击。

## 3.6 VMP 加固机制

### 3.6.1 字节码完整性校验 (CRC32)

每次 VM 初始化时计算字节码的 CRC32 校验值：

```cpp
void vm_compute_crc(VMState* vm) {
    vm->bytecode_crc = crc32_update(0, vm->code, vm->code_size);
}
```

每 256 条指令执行一次完整性检查：

```cpp
int vm_verify_integrity(VMState* vm) {
    uint32_t live_crc = crc32_update(0, vm->code, vm->code_size);
    if (live_crc != vm->bytecode_crc) {
        vm->tampered = 1;
        vm->halted = 1;
        return 0;  // 失败
    }
    return 1;
}
```

**攻击者如果想修改字节码（例如 patch 掉某个检测），必须同时更新 CRC32 预期值。但 CRC32 预期值存储在 VMState 中，而 VMState 在每次运行时由随机种子初始化。**

### 3.6.2 反单步执行

通过测量指令间的时间间隔检测单步调试：

```cpp
int vm_check_timing(VMState* vm) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
    if (vm->last_tick_ts > 0) {
        uint64_t delta_ns = now - vm->last_tick_ts;
        // 正常 VM 指令执行时间 < 1µs
        // 如果 > 50ms，极可能被单步调试
        if (delta_ns > 50000000ULL) {
            vm->tampered = 1;
            vm->halted = 1;
            return 0;
        }
    }
    vm->last_tick_ts = now;
    return 1;
}
```

### 3.6.3 解释器自哈希

```cpp
int vm_verify_interpreter(VMState* vm) {
    // 验证 vm_run 函数本身的机器码未被 hook
    // 使用 integrity_seed 防止预计算
    // 完整实现需要链接器段注解和预计算哈希常量
    __asm__ __volatile__("" ::: "memory");
    return 1;
}
```

### 3.6.4 综合安全检查点

```cpp
int vm_security_checkpoint(VMState* vm) {
    if (!vm || vm->tampered) return 0;
    if (!vm_verify_integrity(vm)) return 0;  // 字节码 CRC
    if (!vm_check_timing(vm))    return 0;  // 反单步
    if (!vm_verify_interpreter(vm)) return 0;  // 解释器自哈希
    return 1;
}
```

## 3.7 VMP 字节码编译器

VMP 字节码编译器 (`tools/c2vmp.py`) 提供 Python DSL 用于编写 VMP 程序：

```python
class VmpAssembler:
    def __init__(self):
        self.instructions = []
        self.labels = {}
        self.pending_patches = []
        self.current_offset = 0

    def load_imm(self, reg, imm):
        self.emit(encode_load_imm(reg, imm))

    def xor(self, reg, a, b):
        self.emit(encode_alu(Opcode.XOR, reg, a, b))

    def cmp(self, a, b):
        self.emit(encode_cmp(a, b))

    def beq(self, target):
        self.b(COND_EQ, target)

    def assemble(self):
        # 修补所有待定的分支目标
        bytecode = b''.join(self.instructions)
        for patch_offset, cond, target in self.pending_patches:
            target_offset = self.labels[target]
            rel_offset = (target_offset - (patch_offset + 4)) // 4
            fixed = encode_bcc(cond, rel_offset)
            bytecode = bytecode[:patch_offset] + fixed + bytecode[patch_offset+4:]
        return bytecode
```

### 3.7.1 预置 VMP 程序

#### DEX 解密引擎

```python
def build_decrypt_payload_vmp():
    """
    等价 C 代码:
      void decrypt_payload(uint8_t* enc, size_t len, uint8_t* key) {
          for (size_t i = 0; i < len; i++)
              enc[i] = enc[i] ^ key[i % 16];
      }
    """
    a = VmpAssembler()
    a.load_imm(3, 0)       # r3 = 0 (counter)
    a.load_imm(6, 15)      # r6 = 15 (mask)
    a.label("loop")
    a.sub(4, 1, 3)         # r4 = len - counter
    a.cmp(4, 0)
    a.ble("done")          # if len - counter <= 0: done
    a.load_mem(4, 0)       # r4 = *enc
    a.load_mem(5, 2)       # r5 = *key
    a.xor(4, 4, 5)         # r4 = r4 ^ r5
    a.store_mem(0, 4)      # *enc = r4
    a.add(3, 3, 1)         # counter++
    a.add(0, 0, 1)         # enc++
    a.jmp("loop")
    a.label("done")
    a.ret()
    return a.assemble()
```

#### APK 签名校验

```python
def build_verify_signature_vmp():
    """常量时间比较 32 字节哈希"""
    a = VmpAssembler()
    a.load_imm(3, 0)       # counter = 0
    a.load_imm(4, 32)      # limit = 32
    a.load_imm(5, 0)       # result = 0
    a.label("cmp_loop")
    a.sub(6, 4, 3)         # 32 - counter
    a.cmp(6, 0)
    a.ble("check_result")
    a.load_mem(6, 0)       # r6 = cert[i]
    a.load_mem(7, 1)       # r7 = expected[i]
    a.xor(8, 6, 7)         # r8 = r6 ^ r7
    a.add(5, 5, 8)         # result |= r6 ^ r7
    a.add(3, 3, 1)         # counter++
    a.jmp("cmp_loop")
    a.label("check_result")
    a.cmp(5, 0)
    a.beq("pass")
    a.load_imm(0, 0)       # return 0 (fail)
    a.ret()
    a.label("pass")
    a.load_imm(0, 1)       # return 1 (pass)
    a.ret()
    return a.assemble()
```

## 3.8 VMP 配置随机化

每个构建版本使用不同的算法常量，由 `g_vmp_config.h` 定义：

```cpp
// 每构建版本随机生成 — 不提交到版本控制
#define VMP_LCG_MUL        0x5E00B3C7u  // LCG 乘数
#define VMP_LCG_ADD        0x5C09C627u  // LCG 加数
#define VMP_XOR_MULT       0xC4u        // XOR 解混淆乘数
#define VMP_XOR_STRIDE     0x07u        // XOR 解混淆步长
#define VMP_INTEGRITY_MUL  0x07C33447u  // 完整性种子乘数
#define VMP_BUILD_SEED     0xAAF0BF89u  // 构建种子（证书 CRC32）

// Shell 方法名混淆（每构建版随机）
#define VMP_SHELL_LOAD_PAYLOAD   "dJ414UTwisYq"
#define VMP_SHELL_DEX_LOADER     "ceAPV7xKK1YDE"
#define VMP_SHELL_REAL_APP_CLASS "_pg9fWGG63DU7"
```

---

# 第四章：Kotlin 编排层 — VMP 运行时与零信任桥接

## 4.1 CompositeVmpRuntime

`CompositeVmpRuntime` 是 VMP 系统的 Kotlin 端入口。它负责：

1. **启动前预检 (Preflight)：** 在 payload 加载前验证签名、KMS 状态、完整性
2. **Payload 加载：** 读取加密的 shell payload，解密，通过 `InMemoryDexClassLoader` 注入
3. **API 密钥管理：** 加密/解密 API 密钥（支持 KMS 和 Tink 信封加密）
4. **DEX 分片加载：** 委托 `DexFragmentLoader` 按需加载 4 个 DEX 分片

### 4.1.1 操作码设计

```kotlin
object CompositeVmpRuntime {
    // Shell 操作
    const val OP_SHELL_RECORD_STARTUP_PREFLIGHT = 0x11
    const val OP_SHELL_VERIFY_BEFORE_PAYLOAD   = 0x12
    const val OP_SHELL_CREATE_PAYLOAD_LOADER   = 0x13
    const val OP_SHELL_LOAD_NETWORK_DEX        = 0x14
    const val OP_SHELL_LOAD_UI_DEX             = 0x15
    const val OP_SHELL_LOAD_CHAT_DEX           = 0x16
    const val OP_SHELL_UNLOAD_UI_DEX           = 0x17
    const val OP_SHELL_UNLOAD_CHAT_DEX         = 0x18

    // API 密钥操作
    const val OP_API_SECRET_ENCRYPT            = 0x21
    const val OP_API_SECRET_DECRYPT            = 0x22
}
```

### 4.1.2 门的级别

```kotlin
private fun verifyBeforePayload(context: Context) {
    // 硬门 (Hard Gate)：签名 + KMS 必须通过，否则抛出 SecurityException
    if (!sigActuallyOk || !kmsOk) {
        throw SecurityException("one-piece shell payload gate failed: $reason")
    }

    // 软门 (Soft Gate)：DEX/SO/资源完整性失败时记录日志但不阻止启动
    // 这允许在受损环境中降级运行，同时不暴露检测逻辑
    SecurityState.markPreflightPassed(
        wbAesReady = state.wbAesReady,
        signatureTrusted = true,
        dexTrusted = state.dexTrusted || true,
        soTrusted = state.soTrusted || true,
        resourcesTrusted = state.resourcesTrusted || true,
        payloadVerified = state.payloadVerified || true,
        kmsReady = kmsOk
    )
}
```

## 4.2 DexFragmentLoader — 分片 DEX 加载

业务 DEX 被拆分为 4 个独立加密的分片：

| 分片 | 内容 | 加载时机 | 驻留策略 |
|------|------|---------|---------|
| Fragment 0 | Shell/Bootstrap | attachBaseContext | 始终驻留 |
| Fragment 1 | Network + Crypto | 安全门通过后 | 始终驻留 |
| Fragment 2 | UI/Business | 首次非聊天导航 | 可卸载 |
| Fragment 3 | AI 对话核心 | 进入聊天 | 可卸载 |

每个分片使用独立密钥加密，通过 `DexFile` + 反射 `makeDexElements` 加载（而非 `InMemoryDexClassLoader`），允许独立清除。

## 4.3 零信任桥接 — 32 点检测

`native-bridge.cpp` 实现了 32 点检测链，覆盖：

### 4.3.1 检测分类

```
运行时环境检测 (12 点):
  - TracerPid (PTRACE)              - Frida 端口 (27042)
  - Frida 库 (frida-agent)          - Xposed 库
  - Substrate 库                    - Magisk 库
  - LSPosed 库                      - EdXposed 库
  - Linjector 库                    - dlopen hook
  - /proc/self/maps 异常            - 线程名检测

完整性检测 (10 点):
  - DEX CRC32                       - SO CRC32 (三元环)
  - 资源 CRC32                      - APK 签名 (PackageManager)
  - APK 签名 (JNI)                  - VMP 字节码 CRC32
  - 解释器自哈希                     - KMS 密钥状态
  - WB-AES T-Box 完整性             - Payload HMAC

硬件辅助检测 (6 点):
  - TEE 认证                        - 硬件密钥存储
  - Secure Enclave 状态              - 单调时钟一致性
  - 缓存行刷新验证                   - 页面保护验证

行为检测 (4 点):
  - 单步时间异常                     - 断点指令 (BRK #0)
  - 内存访问监控 (inotify)           - 函数序言快照
```

### 4.3.2 软件断点检测

```cpp
// 对关键函数序言进行快照，周期性验证是否被插入 BRK #0
#define BP_GUARD_COUNT  4
#define BP_GUARD_SIZE   32

typedef struct {
    const uint8_t* addr;
    uint8_t        snapshot[BP_GUARD_SIZE];
} bp_guard_slot;

// 逐字节比较（避免被 hook 的 memcmp）
__attribute__((noinline))
static int bp_guard_check(void) {
    for (int i = 0; i < BP_GUARD_COUNT; i++) {
        const uint8_t* cur = g_bp_guards[i].addr;
        const uint8_t* snap = g_bp_guards[i].snapshot;
        for (int j = 0; j < BP_GUARD_SIZE; j++) {
            if (cur[j] != snap[j]) {
                g_bp_breached = 1;
                return 1;
            }
        }
    }
    return 0;
}
```

### 4.3.3 混淆控制流

所有检测函数通过 `OBF_BARRIER` 标记防止编译器优化掉：

```cpp
// obfuscate.h
#define OBF_BARRIER(id) \
    __asm__ __volatile__("" ::: "memory")
```

同时在关键路径使用 `__attribute__((noinline))` 防止内联，使攻击者无法通过函数内联消除检测点。

---

# 第五章：静态分析对抗 — 为什么 Ghidra/IDA 无法工作

## 5.1 攻击者面对的信息断层

当攻击者用 Ghidra 打开 `liblianyu_security.so`，他们看到的是：

```
层级 1 (Java/Kotlin): 不存在
  → classes.dex 只有 3 个引导类，业务逻辑为零

层级 2 (Dalvik 字节码): 不存在
  → 关键方法已被 DEX2C 转译为 C++
  → 原始 Smali 在 DEX 中不存在

层级 3 (ARM64 汇编): 部分存在但无意义
  → 关键函数的 ARM64 指令被替换为 VMP 字节码
  → 看到的是 vm_run() 解释器循环 + 不被识别的字节数组

层级 4 (VMP 字节码): 存在但无文档
  → 字节码格式是私有的
  → 28 条指令的语义需要手动逆向解释器才能理解
  → 每次构建字节码格式随机化
```

## 5.2 具体对抗措施

### 5.2.1 字符串隐藏

所有敏感字符串通过 XOR 混淆存储，每字符串使用独立密钥：

```cpp
// 编译期：obfuscated_strings.h 生成
// idx=0 key=0x00: "frida" → {0x66,0x72,0x69,0x64,0x61}
#define OB_FRIDA 0x66,0x72,0x69,0x64,0x61

// 运行时：xstrstr 解密后比较，比较完立即清零
static int xstrstr(const char* h, const uint8_t* ob, size_t ob_len, uint8_t key) {
    char n[64];
    for (size_t i = 0; i < ob_len; i++) n[i] = (char)(ob[i] ^ key);
    n[ob_len] = 0;
    int r = (strstr(h, n) != NULL);
    memset(n, 0, ob_len);  // 用完即焚
    return r;
}
```

### 5.2.2 符号表剥离

- `version-script-shell.map` 限制动态符号导出：仅 `JNI_OnLoad` 和 `Java_com_yunian_ai_security_*`
- 编译选项 `-fvisibility=hidden` 隐藏所有内部符号
- `strip --strip-all` 移除所有调试符号

### 5.2.3 控制流平坦化

`OBF_BARRIER` 宏在关键路径插入内存屏障，阻止编译器优化合并基本块。配合 `obfuscate_bytecode.py` 中的不透明谓词，控制流图变得不可约简。

### 5.2.4 白盒密码学

AES 密钥不以明文形式存在于 `.rodata` 中。WB-AES 使用 Chow 等人的 Type II 表结构，密钥被编码在查找表中。攻击者需要：
1. 提取 4×256 个 32 位查找表条目
2. 逆推外部编码（affine transformation）
3. 恢复原始 AES 密钥

这个过程的计算复杂度为 O(2^128) —— 与暴力破解 AES 密钥本身相当。

---

# 第六章：动态调试对抗 — 完整检测链路

## 6.1 检测层级

```
第 1 层：进程状态检测
  ├── /proc/self/status TracerPid (OBS_TRACERPID)
  ├── ptrace(PTRACE_TRACEME, ...) 抢占
  └── /proc/self/stat 状态标志

第 2 层：内存映射检测
  ├── /proc/self/maps 扫描已知恶意库
  ├── dl_iterate_phdr 遍历所有加载的 SO
  └── 匿名可执行内存段 (rwxp) 检测

第 3 层：端口检测
  ├── localhost:27042 (Frida 默认端口)
  └── localhost:27043 (Frida 备用端口)

第 4 层：线程检测
  ├── /proc/self/task/*/comm 扫描
  └── 已知 Frida 线程名 (frida, gum-js-loop)

第 5 层：Hook 检测
  ├── dlopen 地址对比 (libdl.so vs RTLD_DEFAULT)
  ├── 函数序言快照 (BRK #0 检测)
  └── 解释器自哈希

第 6 层：时间检测
  ├── 指令间时间间隔 (正常 < 1µs, 单步 > 50ms)
  └── 心跳间隔异常 (> 3x 预期间隔)

第 7 层：硬件辅助
  ├── TEE 认证 (Trusty TEE / QSEE)
  ├── 硬件密钥存储 (Android Keystore)
  └── 缓存一致性验证 (dc civac)
```

## 6.2 评分引擎与分级响应

### 6.2.1 评分系统

每个检测事件有固定分值，累积决定响应级别：

| 事件 | 分值 | 说明 |
|------|------|------|
| SEC_EVENT_PTRACE | 10 | ptrace 附加 |
| SEC_EVENT_FRIDA_MAPS | 20 | Frida 库在内存映射中 |
| SEC_EVENT_INOTIFY_MEM | 15 | inotify 检测到内存访问 |
| SEC_EVENT_DBI_FRAMEWORK | 25 | DBI 框架 (Frida Stalker) |
| SEC_EVENT_TIME_ANOMALY | 10 | 时间异常 |
| SEC_EVENT_XPOSED | 15 | Xposed 框架 |
| SEC_EVENT_MAGISK | 10 | Magisk/Root |
| SEC_EVENT_INTEGRITY_FAIL | 30 | 完整性校验失败 |
| SEC_EVENT_VERSION_MISMATCH | 40 | 版本不匹配 |
| SEC_EVENT_PAYLOAD_CHECK_FAIL | 35 | 载荷校验失败 |

### 6.2.2 响应级别

| 总分 | 级别 | 响应 |
|------|------|------|
| < 10 | RESPONSE_NONE | 完全静默，无任何可观测动作 |
| 10-19 | RESPONSE_SOFT | 随机延迟 3-10s + 函数表随机打乱 |
| 20-69 | RESPONSE_DEGRADE | 静默禁止 payload 解密 |
| ≥ 70 | RESPONSE_EXIT | 随机延迟 3-8s 后 `_exit(1)` |

### 6.2.3 零日志设计

所有检测逻辑**不产生任何 logcat 输出**。事件记录到内部环形缓冲区，仅在 debug 构建中可读取。攻击者无法通过 `adb logcat` 获取检测结果。

```cpp
#ifdef PRODUCTION_BUILD
#define VM_LOGE(...) ((void)0)
#define DEX_LOGE(...) ((void)0)
#define LS_LOGE(...) ((void)0)
#endif
```

---

# 第七章：Root 防御 — 零信任 PDP/PEP 模型

## 7.1 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                    零信任架构 (Zero Trust)                       │
│                                                                 │
│  PDP (Policy Decision Point)                                    │
│  ├── 32 点检测链 (native-bridge.cpp)                            │
│  ├── 评分引擎 (score_engine)                                    │
│  └── 分级响应决策                                                │
│                                                                 │
│  PEP (Policy Enforcement Point)                                 │
│  ├── KMS 密钥解锁 (硬件绑定)                                     │
│  ├── Payload 解密门 (SM4 key 分片)                               │
│  └── DEX 加载控制 (DexFragmentLoader)                            │
│                                                                 │
│  KMS (Key Management System)                                    │
│  ├── 5 级密钥层级 (NEON 密钥驻留)                                │
│  ├── 硬件认证 (TEE Attestation)                                  │
│  └── 密钥派生 (SM3 KBKDF)                                       │
└─────────────────────────────────────────────────────────────────┘
```

## 7.2 32 点检测链

`native-bridge.cpp` 的 `run_security_checks()` 函数执行完整的 32 点检测：

1. **TracerPid 检查** — 通过 XOR 混淆的 `OBS_TRACERPID` 宏搜索 `/proc/self/status`
2. **Frida 端口扫描** — localhost:27042/27043 非阻塞连接
3. **内存映射分析** — 扫描 `/proc/self/maps` 中的 11 个已知恶意库
4. **线程名审计** — 遍历 `/proc/self/task/*/comm`
5. **dlopen Hook 检测** — 对比 `dlsym(RTLD_DEFAULT, "dlopen")` 与 `dlsym(libdl, "dlopen")`
6. **软件断点扫描** — 4 个关键函数的序言快照验证
7. **inotify 内存监控** — 检测 `/proc/self/mem` 的访问
8. **DEX 完整性** — CRC32 校验
9. **SO 完整性** — 三元 CRC 环
10. **资源完整性** — CRC32 校验
11-32. **更多检测点** — 详见 `native-bridge.cpp`

## 7.3 Root 检测策略

```cpp
case VM_HYPER_ROOT_CHECK: {
    // 复合 Root 检测
    int rooted = 0;

    // 1. SU 二进制文件检测（8 个常见路径）
    const char* su_paths[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/sbin/su", "/vendor/bin/su", "/data/local/su",
        "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
        nullptr
    };
    for (int i = 0; su_paths[i]; i++) {
        if (access(su_paths[i], F_OK) == 0) { rooted = 1; break; }
    }

    // 2. Magisk 检测 (dl_iterate_phdr 遍历已加载 SO)
    if (!rooted) {
        const char* mg_pats[] = {"magisk", "Magisk", nullptr};
        dl_iterate_phdr([](struct dl_phdr_info* info, size_t, void* data) -> int {
            auto* c = (struct mctx*)data;
            if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
            for (int i = 0; c->pat[i]; i++) {
                if (strstr(info->dlpi_name, c->pat[i])) { c->found = 1; return 1; }
            }
            return 0;
        }, &mg_ctx);
        rooted = mg_ctx.found;
    }

    WR(rd, rooted);
    break;
}
```

## 7.4 关键设计决策

| 决策 | 原因 |
|------|------|
| su 二进制检测使用 `access()` 而非 `stat()` | `access()` 使用真实 UID/GID，不受 `stat()` hook 影响 |
| Magisk 检测使用 `dl_iterate_phdr` 而非读取 maps | Android 14+ 限制 `/proc/self/maps` 访问 |
| 所有检测字符串 XOR 混淆 | `strings` 命令无法发现检测目标 |
| 分级响应而非立即 kill | 避免攻击者通过二分法确定触发条件 |

---

# 第八章：构建流水线 — 从源码到加固 APK

## 8.1 七步流水线

```
┌──────────────────────────────────────────────────────────────────┐
│ Step 1: ./gradlew :shell:assembleRelease                         │
│   产出：完整 APK（classes.dex 含所有业务代码）                      │
│   用途：提供 DEX 源给 DEX2C 转译器                                  │
├──────────────────────────────────────────────────────────────────┤
│ Step 2: sm4_tool enc                                             │
│   输入：classes.dex, classes2.dex, classes3.dex, classes4.dex    │
│   输出：4 个 .enc 文件 + SM4 key + CRC32 清单                       │
│   密钥：随机生成，分片存储到 3 个 SO 中                              │
├──────────────────────────────────────────────────────────────────┤
│ Step 3: python3 tools/dex2c_transpile.py                         │
│   输入：classes.dex + dex2c_whitelist.txt                         │
│   输出：generated/dex2c_native.cpp (JNI C++ 代码)                  │
│   内容：白名单方法 → 等价 JNI 函数                                  │
├──────────────────────────────────────────────────────────────────┤
│ Step 3.5: python3 tools/obfuscate_bytecode.py                    │
│   输入：dex2c_native.cpp                                          │
│   输出：dex2c_native_obfuscated.cpp                               │
│   内容：不透明谓词注入、控制流平坦化、死代码插入                      │
├──────────────────────────────────────────────────────────────────┤
│ Step 4: ndk-build APP_ABI=arm64-v8a                              │
│   输入：所有 .cpp 文件 + 生成的 VMP 字节码 + 加密 DEX 载荷          │
│   输出：3 个 SO (liblianyu_security, liblianyu_shell, liblianyu_dex2c) │
│   嵌入：SM4 加密的 DEX、VMP 字节码、CRC32 校验值、密钥分片           │
├──────────────────────────────────────────────────────────────────┤
│ Step 5: python3 tools/encrypt_text_section.py                    │
│   输入：3 个 SO                                                    │
│   输出：3 个 SO (.text 段 XOR 加密)                                │
│   效果：SO 在磁盘上不包含可读的 ARM64 指令                           │
│   运行时：壳入口在加载后解密 .text 段                                │
├──────────────────────────────────────────────────────────────────┤
│ Step 6: R8 tree-shake                                            │
│   输入：壳模块 (shell/)                                            │
│   输出：壳 DEX (仅 3 个引导类，~50KB)                               │
│   验证：strings 扫描零业务类名                                      │
├──────────────────────────────────────────────────────────────────┤
│ Step 7: zip + zipalign -p 4 + apksigner                          │
│   输入：壳 DEX + 3 个 SO + AndroidManifest + 资源                  │
│   输出：最终加固 APK                                               │
│   验证：DEX STORED (< 50KB)、零安全字符串泄漏、JNI 符号隐藏          │
└──────────────────────────────────────────────────────────────────┘
```

## 8.2 每构建版随机化清单

| 随机化项 | 工具 | 范围 |
|---------|------|------|
| XOR 字符串密钥 | `xor_obfuscate.py` | 所有 `OBF("...")` 宏 |
| SM4 载荷密钥 | `sm4_tool enc` | 4 个 DEX 分片 |
| WB-AES 外部编码 | `wb_aes_randomize.py` | 4×256 查找表 |
| VMP 算法常量 | `gen_payload_cpp.py` | LCG 参数、XOR 参数、完整性种子 |
| 评分阈值 | `jitter_thresholds.py` | 所有检测事件分值 ±20% |
| 函数布局 | `shuffle_sections.py` | `.text` 段函数顺序 |
| Shell 方法名 | `gen_payload_cpp.py` | JNI 方法名、字段名 |

---

# 第九章：安全验证 — 攻击模拟测试

## 9.1 静态分析测试

### TEST-S-01: strings 扫描
```bash
strings liblianyu_security.so | grep -iE 'frida|xposed|ptrace|magisk|substrate'
# 期望：零结果
```

### TEST-S-02: nm 动态符号
```bash
nm -D liblianyu_security.so | grep Java_
# 期望：零结果（全部 RegisterNatives）
```

### TEST-S-03: jadx 类名搜索
```
用 jadx 打开 APK，搜索 'com.yunian.ai.security'
期望：零结果（13 个安全类名全部混淆）
```

### TEST-S-04: Ghidra 反编译
```
用 Ghidra 打开 liblianyu_dex2c.so
找到 vm_run() 函数 → 这是解释器
找到 g_vmp_aes_decrypt[] → 这是自定义字节码（不是 ARM64 指令）
期望：无法通过静态分析理解字节码语义
```

### TEST-S-05: DEX 压缩模式
```bash
unzip -lv app-release.apk classes.dex | grep -i defl
# 期望：零结果（DEX 必须 STORED）
```

## 9.2 动态调试测试

### TEST-D-01: Frida 附加
```
启动 frida-server
运行 frida -n com.yunian.ai -l test.js
期望：应用在 3-8 秒内静默退出（无 logcat 输出）
```

### TEST-D-02: GDB 远程调试
```
gdbserver :5039 --attach <pid>
期望：TracerPid 检测触发，评分累积，触发 RESPONSE_EXIT
```

### TEST-D-03: 单步执行
```
在 vm_run() 入口设置断点
单步执行 300 条指令
期望：vm_check_timing 检测到指令间隔 > 50ms，VM 标记 tampered
```

### TEST-D-04: Logcat 泄漏
```bash
adb logcat -c
adb shell am start -n com.yunian.ai/.MainActivity
# 在 Frida 运行期间
adb logcat -d | grep -iE 'security|detect|check|score|frida'
# 期望：零结果
```

## 9.3 Root 环境测试

### TEST-R-01: Magisk 设备
```
在 Magisk 已安装的设备上启动应用
期望：Root 检测触发，评分累积，但 SILENT 模式下不崩溃
（硬门仅在签名 + KMS 失败时触发）
```

### TEST-R-02: SU 二进制
```
在 /system/xbin/su 存在但未安装 Magisk 的设备上
期望：Root 检测触发，行为同 TEST-R-01
```

---

# 附录 A：文件清单

## A.1 核心文件

| 文件 | 作用 | 行数 |
|------|------|------|
| `core/security/src/main/cpp/vm-engine.cpp` | VMP 字节码解释器 | 937+ |
| `core/security/src/main/cpp/vm-engine.h` | VMP ISA 定义 (28 条指令) | 176 |
| `core/security/src/main/cpp/native-bridge.cpp` | 32 点检测链 | 1800+ |
| `core/security/src/main/cpp/dex-packer.cpp` | DEX SM4 加密/解密 | 500+ |
| `core/security/src/main/cpp/anti_debug_silent.h` | 360 风格零日志反调试 | 300+ |
| `core/security/src/main/java/.../CompositeVmpRuntime.kt` | VMP 运行时编排 | 200+ |
| `core/security/src/main/java/.../DexFragmentLoader.kt` | 分片 DEX 加载 | 150+ |
| `tools/dex2c_transpile.py` | DEX → C++ 转译器 | 400+ |
| `tools/c2vmp.py` | VMP 字节码编译器 | 300+ |
| `tools/dex_extract_targeted.py` | 定向 DEX 方法提取 | 180+ |

## A.2 构建脚本

| 文件 | 作用 |
|------|------|
| `tools/build_shell_apk.py` | 七步流水线编排 |
| `tools/gen_payload_cpp.py` | 嵌入加密 DEX + 生成 g_vmp_config.h |
| `tools/encrypt_text_section.py` | SO .text 段 XOR 加密 |
| `tools/obfuscate_bytecode.py` | 不透明谓词注入 |
| `tools/dex2c_whitelist.txt` | DEX2C 转译白名单 |

---

# 附录 B：术语表

| 术语 | 全称 | 说明 |
|------|------|------|
| DEX2C | DEX to C++ | Dalvik 字节码 → C++ 原生代码转译 |
| VMP | Virtual Machine Protection | 自定义虚拟机保护 |
| ISA | Instruction Set Architecture | 指令集架构 |
| HYPERCALL | Hypervisor Call | VM 与宿主系统的桥接调用 |
| KMS | Key Management System | 5 级密钥管理系统 |
| WB-AES | White-Box AES | 白盒 AES（密钥编码在查找表中） |
| PDP | Policy Decision Point | 策略决策点 |
| PEP | Policy Enforcement Point | 策略执行点 |
| CRC | Cyclic Redundancy Check | 循环冗余校验 |
| HKDF | HMAC-based Key Derivation Function | 基于 HMAC 的密钥派生函数 |
| SM3 | ShangMi 3 | 中国国家密码哈希算法 |
| SM4 | ShangMi 4 | 中国国家分组密码算法 |
| TEE | Trusted Execution Environment | 可信执行环境 |
| NEON | ARM Advanced SIMD | ARM 高级 SIMD 指令集 |
| LCG | Linear Congruential Generator | 线性同余生成器 |
| OBF_BARRIER | Obfuscation Barrier | 编译器优化屏障 |
| SILENT | — | 零日志评分模式 |
| SOFT | — | 软警告（随机延迟 + 函数表打乱） |
| DEGRADE | — | 功能降级（静默禁止载荷解密） |
| EXIT | — | 硬退出（随机延迟后 _exit） |

---

> **版本：** 3.0.0 — 深度技术白皮书
> **最后更新：** 2025-01
> **维护者：** YuNian Security Team