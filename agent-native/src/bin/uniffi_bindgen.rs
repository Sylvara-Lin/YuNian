// uniffi-bindgen CLI wrapper（宿主工具，生成 Kotlin 绑定用）
//
// 用法（在 agent-native 目录）：
//   cargo run --features cli-bin --bin uniffi-bindgen -- generate \
//     --library target/<triple>/release/liblianyu_agent.so \
//     --language kotlin --out-dir <输出目录>
fn main() {
    uniffi::uniffi_bindgen_main();
}
