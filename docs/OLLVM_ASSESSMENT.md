# OLLVM 集成评估

> 状态: 暂不集成。采用增强版 obfuscate.h 替代方案。
> 更新: 2026-06

## 版本兼容性

| NDK | LLVM | OLLVM fork | 状态 |
|-----|------|------------|------|
| r26 | 17 | GreenDamTan/llvm-project_ollvm | ✅ 官方支持 |
| r30 | 21 | 无 | ❌ 领先4个版本 |

## 可行方案对比

| 方案 | 控制流平坦化 | 虚假控制流 | 指令替换 | 成本 |
|---|---|---|---|---|
| OLLVM (NDK r26) | -fla | -bcf | -sub | 降级NDK→丢失LLVM 21优化+R30工具链 |
| obfuscate.h (当前) | OBF_BARRIER 123处 | OBF_BOGUS_BRANCH | OBF_JUNK_ASM | 零成本，已部署 |
| Hikari (LLVM 15) | ✅ | ✅ | ✅ | 同样过时，无Windows预编译 |
| Pluto (LLVM 18) | ✅ | ✅ | ✅ | 仍然落后NDK r30 |

## 当前混淆覆盖度

- 123 个 `OBF_BARRIER` 调用（10 个 .cpp 文件）
- `OBF_INDIRECT_CALL` — 隐藏调用目标
- `OBF_JUNK_ASM` — ARM64 内联垃圾指令
- `OBF_DEAD_STORE` — 混淆数据流分析
- `__attribute__((noinline))` — 防止关键函数内联
- `-fvisibility=hidden` — 隐藏所有内部符号
- `-fomit-frame-pointer` — 移除栈帧指针

## 结论

**不降级 NDK。** r30 带来的编译优化和安全补丁比 OLLVM 的混淆收益更高。当 OLLVM fork 更新到 LLVM 21 时再集成。

## 增强建议

1. 将 OBF_BARRIER 数量从 123 提升到 ~300（每检测函数入口/出口各一个）
2. 在 vm-engine.cpp 的 HYPERCALL 分发中加入不透明谓词
3. 在 Android.mk 中启用 `-mllvm -bcf` 等效项（如果 NDK clang 支持）
