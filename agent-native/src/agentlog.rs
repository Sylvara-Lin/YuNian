//! Agent 运行日志（真机可见性方案 A：Rust 写文件 → Kotlin 增量 dump 到 logcat）。
//!
//! **为什么不用 `eprintln!` 就够了**：Android 应用的 native stdout/stderr 默认被
//! 重定向到 `/dev/null`，`eprintln!` 不会出现在 logcat（真机实测复现：重试链路
//! 打了日志但 logcat 里什么都看不到）。本模块把所有关键日志同时写到一个与数据库
//! 同目录的文件，Kotlin 侧 [`RustAgentLogBridge`] 增量读取并转发到 logcat
//! （tag = `LianYuNative`），从而真机可见。
//!
//! **与 panic 自证文件同机制**：路径推导与 [`crate::agent::AgentRuntime::new`] 的
//! panic hook 完全一致（`<db_path 同目录>/rust_agent_log.txt`），风险面相同。
//!
//! 设计取舍：
//! - 每次写入 `open(append)+flush` 后关闭，进程被杀也不丢已写内容（崩溃安全）；
//! - 单文件超过 [`MAX_BYTES`] 时**清空重写**（简单轮转：丢弃历史，优先保留最近日志）；
//! - 无论是否已 [`init`]，`eprintln!` 镜像始终保留（宿主 `cargo test` / 调试可见）。
//!
//! [`RustAgentLogBridge`]: ../../../core/agent/src/main/kotlin/com/yunian/ai/agent/RustAgentLogBridge.kt

use std::io::Write;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

/// 日志文件名（与数据库同目录；Kotlin 侧按同一约定读取）。
pub const LOG_FILE: &str = "rust_agent_log.txt";

/// 单文件上限（字节）。超过则清空重写，避免无限增长。
const MAX_BYTES: u64 = 512 * 1024;

/// 日志文件路径（进程级，仅首次 [`init`] 生效）。
static LOG_PATH: OnceLock<PathBuf> = OnceLock::new();

/// 写文件串行化（避免多线程交叉写入半行）。
static LOCK: Mutex<()> = Mutex::new(());

/// 初始化日志文件路径（在 [`crate::agent::AgentRuntime::new`] 调用一次）。
///
/// 路径 = `db_path` 同目录 + [`LOG_FILE`]；与 Kotlin
/// `context.getDatabasePath("yunian_database").parentFile` 保持一致。
/// 重复调用仅首次生效（幂等）。
pub fn init(db_path: &str) {
    let path = std::path::Path::new(db_path).with_file_name(LOG_FILE);
    let _ = LOG_PATH.set(path);
}

fn current_path() -> Option<&'static PathBuf> {
    LOG_PATH.get()
}

/// 写一行日志（`level` / `tag` / `message`）。
///
/// 始终镜像到 stderr；在已 [`init`] 时追加到日志文件（真机经 Kotlin 转发到 logcat）。
/// 任何 IO 失败都静默忽略——日志绝不允许影响主流程。
pub fn log(level: &str, tag: &str, message: &str) {
    // stderr 镜像：宿主测试 / 本地调试可见（真机不可见，故仍需文件通道）。
    eprintln!("[lianyu_agent][{level}][{tag}] {message}");

    let Some(path) = current_path() else {
        return;
    };
    // 串行写入；锁中毒也继续（日志不可因 panic 中断）。
    let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());

    // 超限清空重写（简单轮转）。
    if let Ok(meta) = std::fs::metadata(path) {
        if meta.len() > MAX_BYTES {
            let _ = std::fs::write(path, b"");
        }
    }

    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let _ = writeln!(file, "{} [{level}][{tag}] {message}", timestamp());
        let _ = file.flush();
    }
}

/// INFO 级日志。
pub fn info(tag: &str, message: &str) {
    log("INFO", tag, message);
}

/// WARN 级日志。
pub fn warn(tag: &str, message: &str) {
    log("WARN", tag, message);
}

/// ERROR 级日志。
pub fn error(tag: &str, message: &str) {
    log("ERROR", tag, message);
}

/// 本地时间戳（毫秒精度），供日志行前缀。
fn timestamp() -> String {
    chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f").to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_without_init_only_mirrors_stderr() {
        // 未 init 时不应 panic（路径缺失直接返回）。
        info("test", "no-init line should be a no-op for the file channel");
    }

    #[test]
    fn timestamp_is_non_empty() {
        assert!(!timestamp().is_empty());
    }
}
