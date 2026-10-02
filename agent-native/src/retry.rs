// retry.rs — LLM 调用自动重试策略（指数退避 + 抖动 + 可取消）
//
// 动机：Agent 主路径（native_gateway::send / send_stream）此前**无任何重试**，
// 一次瞬时网络抖动（连接重置 / 超时）或服务端限流（429）就会中止整个回合，
// 多轮工具链被从中间打断 → 用户看到「暂停任务报错」。本模块提供可复用、
// 可测试、可取消的重试驱动：只对「可重试」错误退避重试，客户端错误（4xx）立即失败。
//
// 设计边界：
// - 纯逻辑（无 HTTP / 无全局状态），退避睡眠由调用方注入 → 便于单测零等待。
// - 只做「一次操作」粒度的重试；多 API Key 故障转移由上层 NativeGateway 负责。

use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// 取消时返回的固定错误文案（供上层识别）。
pub(crate) const CANCELLED_MESSAGE: &str = "请求已取消";

/// 重试策略参数。
#[derive(Clone, Copy, Debug)]
pub(crate) struct RetryPolicy {
    /// 总尝试次数（含首次；3 = 首次 + 最多 2 次重试）。
    pub max_attempts: u32,
    /// 首次退避（后续 ×2）。
    pub initial_backoff: Duration,
    /// 单次退避上限（同时约束 Retry-After，防长时间挂起）。
    pub max_backoff: Duration,
    /// 整个重试序列的总时间预算（超过即放弃，保证等待有界）。
    pub total_budget: Duration,
    /// 抖动比例（0..=1；退避在 [b*(1-r), b*(1+r)] 内随机，避免多端同步重试）。
    pub jitter_ratio: f64,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        // 参数取值理由（真机场景权衡）：
        // - max_attempts = 3：覆盖绝大多数瞬时故障（一次抖动或一次限流），又不会在
        //   上游长时间不可用时把回合拖到用户无法容忍；用户可感知等待仍是秒级。
        // - initial_backoff = 800ms、×2：首次重试很快（体感接近「一次重发」），
        //   第二次显著拉长，给服务端恢复时间。
        // - max_backoff = 8s：单次等待硬上限；同时作为 Retry-After 的封顶，
        //   避免服务端返回超大 Retry-After 造成长时间挂起。
        // - total_budget = 30s：整个「失败 → 重试」序列的绝对上限（含睡+请求），
        //   保证回合不会无限拖延（WorkManager / 用户体感的有界性）。
        // - jitter_ratio = 0.3：±30% 抖动，打散多客户端同步重试。
        Self {
            max_attempts: 3,
            initial_backoff: Duration::from_millis(800),
            max_backoff: Duration::from_secs(8),
            total_budget: Duration::from_secs(30),
            jitter_ratio: 0.3,
        }
    }
}

/// 单次尝试失败的信息（供重试驱动决策）。
pub(crate) struct AttemptFailure {
    /// 面向用户的最终错误文本（保留原始 status / body 供诊断）。
    pub message: String,
    /// 是否可重试。
    pub retryable: bool,
    /// 服务端建议的等待（Retry-After 解析结果；None = 未提供）。
    pub retry_after: Option<Duration>,
}

impl AttemptFailure {
    /// 不可重试失败（立即以原文案结束）。
    pub(crate) fn fatal(message: String) -> Self {
        Self { message, retryable: false, retry_after: None }
    }
}

/// 传输层瞬时（网络类）错误关键字（小写匹配）。
/// 仅在没有结构化 HTTP 状态码时用于判定；命中即视为可重试。
const TRANSIENT_KEYWORDS: &[&str] = &[
    // 本模块 ureq 传输层的网络错误前缀（非 2xx 会走 "HTTP {code}:" 分支，不走这里）
    "http 请求失败",
    "http 流式请求失败",
    "读取响应失败",
    "读取 sse 行失败",
    // ureq / std::io 常见瞬时错误文案
    "timeout",
    "timed out",
    "connection",
    "reset",
    "refused",
    "unreachable",
    "broken pipe",
    "unexpected eof",
    "would block",
    "temporarily",
    "try again",
    "dns",
];

/// 从网络层错误文本判定是否可重试，并解析 Retry-After。
///
/// 可重试（瞬时故障）：
/// - HTTP 408（Request Timeout）/ 429（Too Many Requests）/ 5xx（服务端错误）；
/// - 传输层错误：连接失败 / 超时 / 读取中断 / 连接重置 / DNS 解析失败。
///
/// 不可重试（重试无意义，立即失败并保留原文案）：
/// - HTTP 400 / 401 / 403 / 404 / 405 / 409 / 413 / 422 等其余 4xx 客户端错误；
/// - 配置类错误（返回网页而非 API 响应、脚本化 mock 耗尽等）。
///
/// 返回 `(是否可重试, Retry-After)`。
pub(crate) fn classify_error(err: &str) -> (bool, Option<Duration>) {
    let retry_after = parse_retry_after(err);
    // 结构化状态码优先：错误文本统一以 "HTTP {code}: " 开头。
    if let Some(code) = parse_http_status(err) {
        let retryable = code == 408 || code == 429 || (500..=599).contains(&code);
        return (retryable, retry_after);
    }
    // 无状态码 → 传输层 / 配置类。
    let lower = err.to_lowercase();
    let transient = TRANSIENT_KEYWORDS.iter().any(|k| lower.contains(k));
    (transient, retry_after)
}

/// 解析错误文本中的 `HTTP {code}:` 前缀状态码。
///
/// 仅当 "HTTP " 之后紧跟数字且数字后紧跟 ':' 时才认定为状态错误；
/// `"HTTP 请求失败: ..."`（传输错误前缀）因数字为空而返回 None，落入关键字判定。
fn parse_http_status(err: &str) -> Option<u16> {
    let rest = err.strip_prefix("HTTP ")?;
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        return None;
    }
    if !rest[digits.len()..].starts_with(':') {
        return None;
    }
    digits.parse::<u16>().ok()
}

/// 解析错误文本中的 Retry-After（秒）。约定格式：`"... (retry-after 2s)"`。
fn parse_retry_after(err: &str) -> Option<Duration> {
    let lower = err.to_lowercase();
    let idx = lower.find("retry-after")?;
    let rest = &lower[idx + "retry-after".len()..];
    let digits: String = rest
        .chars()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse::<u64>().ok().map(Duration::from_secs)
}

/// 计算本次退避时长：Retry-After 优先（受单次上限约束），否则指数退避 + 抖动。
fn compute_backoff(policy: &RetryPolicy, tries: u32, retry_after: Option<Duration>) -> Duration {
    if let Some(ra) = retry_after {
        return ra.min(policy.max_backoff);
    }
    let exponent = tries.saturating_sub(1);
    let base_ms = policy.initial_backoff.as_millis() as f64 * 2f64.powi(exponent as i32);
    let capped_ms = base_ms.min(policy.max_backoff.as_millis() as f64);
    let jitter = capped_ms * policy.jitter_ratio.clamp(0.0, 1.0);
    let lo = (capped_ms - jitter).max(1.0);
    let hi = (capped_ms + jitter).max(lo);
    let ratio = next_random_ratio();
    Duration::from_millis((lo + (hi - lo) * ratio).round() as u64)
}

/// 时间熵驱动的伪随机比例 [0,1)。仅用于抖动，无安全要求，无需引入随机数依赖。
fn next_random_ratio() -> f64 {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0);
    // xorshift 混合，避免相邻调用相关
    let mut x = nanos ^ 0x9E37_79B9_7F4A_7C15;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    (x % 1_000_000) as f64 / 1_000_000.0
}

/// 是否已被要求取消。
fn is_cancelled(cancel: Option<&AtomicBool>) -> bool {
    cancel.map(|c| c.load(Ordering::SeqCst)).unwrap_or(false)
}

/// 可取消的重试驱动。
///
/// - `policy`：退避参数；
/// - `cancel`：取消标志（`Some` 时，在**每次尝试前**与**退避等待后**检查；
///   一旦置位即停止重试并返回 [`CANCELLED_MESSAGE`]）；
/// - `label`：日志标签（便于真机排查是哪条链路在重试）；
/// - `attempt`：一次尝试，返回 `Ok(结果)` 或 `Err(AttemptFailure)`；
/// - `on_backoff`：退避等待实现（生产为 `thread::sleep`，测试注入零等待）。
///
/// 返回：首次/重试成功的结果、不可重试错误、重试耗尽后的最后一次错误，或取消错误。
pub(crate) fn run_with_retry<F, S>(
    policy: RetryPolicy,
    cancel: Option<&AtomicBool>,
    label: &str,
    mut attempt: F,
    mut on_backoff: S,
) -> Result<String, String>
where
    F: FnMut() -> Result<String, AttemptFailure>,
    S: FnMut(Duration),
{
    let started = Instant::now();
    let mut tries: u32 = 0;
    loop {
        if is_cancelled(cancel) {
            eprintln!("[lianyu_agent][retry] {label}: 已取消，停止重试");
            return Err(CANCELLED_MESSAGE.to_string());
        }
        tries += 1;
        match attempt() {
            Ok(value) => {
                if tries > 1 {
                    eprintln!("[lianyu_agent][retry] {label}: 第 {tries} 次尝试成功");
                }
                return Ok(value);
            }
            Err(failure) => {
                if !failure.retryable {
                    return Err(failure.message);
                }
                if tries >= policy.max_attempts {
                    eprintln!(
                        "[lianyu_agent][retry] {label}: 已达最大尝试次数 {tries}/{}，最终失败：{}",
                        policy.max_attempts, failure.message
                    );
                    return Err(failure.message);
                }
                let backoff = compute_backoff(&policy, tries, failure.retry_after);
                if started.elapsed() + backoff > policy.total_budget {
                    eprintln!(
                        "[lianyu_agent][retry] {label}: 退避将超出总预算 {:?}，放弃重试：{}",
                        policy.total_budget, failure.message
                    );
                    return Err(failure.message);
                }
                eprintln!(
                    "[lianyu_agent][retry] {label}: 第 {tries}/{} 次失败（{}），{:?} 后重试",
                    policy.max_attempts, failure.message, backoff
                );
                on_backoff(backoff);
                if is_cancelled(cancel) {
                    eprintln!("[lianyu_agent][retry] {label}: 退避期间被取消，停止重试");
                    return Err(CANCELLED_MESSAGE.to_string());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[test]
    fn classify_retries_server_and_throttle_errors() {
        assert!(classify_error("HTTP 500: internal error").0);
        assert!(classify_error("HTTP 503: unavailable").0);
        assert!(classify_error("HTTP 429: rate limited").0);
        assert!(classify_error("HTTP 408: timeout").0);
    }

    #[test]
    fn classify_rejects_client_and_config_errors() {
        assert!(!classify_error("HTTP 400: bad request").0);
        assert!(!classify_error("HTTP 401: unauthorized").0);
        assert!(!classify_error("HTTP 403: forbidden").0);
        assert!(!classify_error("HTTP 404: not found").0);
        assert!(!classify_error("HTTP 422: unprocessable").0);
        assert!(!classify_error("服务器返回了网页而非API响应，请检查API密钥/地址是否正确").0);
        assert!(!classify_error("MockTransport: 预置响应耗尽").0);
    }

    #[test]
    fn classify_retries_transport_errors() {
        assert!(classify_error("HTTP 请求失败: timed out reading response").0);
        assert!(classify_error("HTTP 流式请求失败: Connection reset by peer").0);
        assert!(classify_error("读取响应失败: broken pipe").0);
        // "HTTP 请求失败" 前缀本身即传输类（无结构化状态码）
        assert!(classify_error("HTTP 请求失败: some transport error").0);
    }

    #[test]
    fn classify_parses_retry_after() {
        let (retryable, ra) = classify_error("HTTP 429: slow down (retry-after 2s)");
        assert!(retryable);
        assert_eq!(ra, Some(Duration::from_secs(2)));
    }

    #[test]
    fn run_with_retry_succeeds_after_transient_failures() {
        let attempts = Mutex::new(0u32);
        let sleeps = Mutex::new(Vec::<Duration>::new());
        let result = run_with_retry(
            RetryPolicy::default(),
            None,
            "test",
            || {
                let mut n = attempts.lock().unwrap();
                *n += 1;
                match *n {
                    1 => Err(AttemptFailure { message: "HTTP 500: x".into(), retryable: true, retry_after: None }),
                    2 => Err(AttemptFailure { message: "HTTP 429: y".into(), retryable: true, retry_after: None }),
                    _ => Ok("ok".to_string()),
                }
            },
            |d| sleeps.lock().unwrap().push(d),
        )
        .unwrap();
        assert_eq!(result, "ok");
        assert_eq!(*attempts.lock().unwrap(), 3);
        assert_eq!(sleeps.lock().unwrap().len(), 2);
    }

    #[test]
    fn run_with_retry_stops_on_fatal_without_retry() {
        let attempts = Mutex::new(0u32);
        let err = run_with_retry(
            RetryPolicy::default(),
            None,
            "test",
            || {
                *attempts.lock().unwrap() += 1;
                Err(AttemptFailure::fatal("HTTP 401: unauthorized".into()))
            },
            |_| panic!("fatal 不应触发退避"),
        )
        .unwrap_err();
        assert_eq!(err, "HTTP 401: unauthorized");
        assert_eq!(*attempts.lock().unwrap(), 1);
    }

    #[test]
    fn run_with_retry_gives_up_after_max_attempts() {
        let attempts = Mutex::new(0u32);
        let err = run_with_retry(
            RetryPolicy::default(),
            None,
            "test",
            || {
                *attempts.lock().unwrap() += 1;
                Err(AttemptFailure { message: "HTTP 500: boom".into(), retryable: true, retry_after: None })
            },
            |_| {},
        )
        .unwrap_err();
        assert!(err.contains("boom"));
        assert_eq!(*attempts.lock().unwrap(), 3);
    }

    #[test]
    fn run_with_retry_aborts_when_cancelled_during_backoff() {
        let cancel = AtomicBool::new(false);
        let attempts = Mutex::new(0u32);
        let err = run_with_retry(
            RetryPolicy::default(),
            Some(&cancel),
            "test",
            || {
                *attempts.lock().unwrap() += 1;
                Err(AttemptFailure { message: "HTTP 500: boom".into(), retryable: true, retry_after: None })
            },
            // 模拟「退避等待期间」用户取消
            |_| cancel.store(true, Ordering::SeqCst),
        )
        .unwrap_err();
        assert_eq!(err, CANCELLED_MESSAGE);
        assert_eq!(*attempts.lock().unwrap(), 1, "取消后不得再次尝试");
    }

    #[test]
    fn run_with_retry_checks_cancel_before_first_attempt() {
        let cancel = AtomicBool::new(true);
        let attempts = Mutex::new(0u32);
        let err = run_with_retry(
            RetryPolicy::default(),
            Some(&cancel),
            "test",
            || {
                *attempts.lock().unwrap() += 1;
                Ok("should not happen".to_string())
            },
            |_| {},
        )
        .unwrap_err();
        assert_eq!(err, CANCELLED_MESSAGE);
        assert_eq!(*attempts.lock().unwrap(), 0);
    }

    #[test]
    fn compute_backoff_honours_retry_after_capped() {
        let policy = RetryPolicy {
            max_backoff: Duration::from_secs(5),
            ..RetryPolicy::default()
        };
        // Retry-After 超过单次上限 → 封顶
        assert_eq!(compute_backoff(&policy, 1, Some(Duration::from_secs(60))), Duration::from_secs(5));
        // Retry-After 在上限内 → 原样遵循
        assert_eq!(compute_backoff(&policy, 1, Some(Duration::from_secs(2))), Duration::from_secs(2));
    }

    #[test]
    fn compute_backoff_is_bounded_by_cap() {
        let policy = RetryPolicy {
            initial_backoff: Duration::from_millis(800),
            max_backoff: Duration::from_secs(8),
            jitter_ratio: 0.3,
            ..RetryPolicy::default()
        };
        for tries in 1..=6u32 {
            let d = compute_backoff(&policy, tries, None);
            assert!(d >= Duration::from_millis(1));
            // 抖动后仍不超过上限 ×1.3
            assert!(d <= Duration::from_millis(8_000 * 13 / 10 + 1), "tries={tries} d={d:?}");
        }
    }
}
