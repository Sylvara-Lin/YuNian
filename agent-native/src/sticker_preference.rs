// sticker_preference.rs — 表情包偏好层（Rust 决策）
//
// 职责：
// - StickerPreferenceStore：UniFFI foreign trait，Kotlin 实现（Room 读写，纯 IO）
// - StickerPreferenceEngine：偏好学习 / 衰减 / 漂移检测 / 采样（决策全在 Rust）
// - 纯增量：不触碰 agent.rs 的 AgentRunner / AgentToolRegistry；
//   Kotlin 侧 StickerManager 改造后经 Facade 调用本引擎
//
// 核心算法（docs/sticker-preference-layer.md 定稿）：
// - 打分：score_i = TagScore(i,Q) · π_i^α · freq_i^γ
//   · TagScore = 硬匹配（Q 与 S_i 任一规范化 tag 相等 → 1/sqrt(|S_i|)，OR 语义）
//   · π_i = 该 sticker 标签的用户偏好先验（仅 USER 来源，贝叶斯收缩）
//   · freq_i = sticker 使用频率（指数衰减 count·e^(-Δt/τ)）
// - 采样：候选集 C={i:Q∩S_i≠∅} → top-M 展示集 → Gibbs 加权随机 → ε 探索
// - 漂移：recent W 条 vs 更早的 USER 记录 JS 散度 > θ → Drifted（放弃旧先验）

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

use serde::{Deserialize, Serialize};

/// 表情包条目元数据（Kotlin Room 行 → Rust 引擎输入，snake_case JSON）
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct StickerEntryMeta {
    pub id: u64,
    /// 精确标签（匹配锚点）
    pub tags: Vec<String>,
    /// 用户使用次数（偏好先验只聚合此列，D4）
    pub user_usage_count: u64,
    /// 模型使用次数（单独存，不污染偏好）
    pub model_usage_count: u64,
    /// 最后使用时间 epoch ms
    pub last_used_ms: u64,
    /// 创建时间 epoch ms
    pub created_ms: u64,
}

/// 使用来源
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum StickerSource {
    User,
    Model,
}

/// 漂移状态
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum DriftStatus {
    None,
    Watching,
    Drifted,
}

/// 引擎参数（默认值 = 文档参数表）
#[derive(Clone, Debug, uniffi::Record)]
pub struct StickerPreferenceParams {
    /// 衰减半衰期 τ = 30 天
    pub decay_half_life_ms: u64,
    /// 收缩门槛 n0 = 20（低于此用均匀先验，防冷启动虚假偏好）
    pub n0: u64,
    /// 收缩门槛 n1 = 200（高于此纯经验）
    pub n1: u64,
    /// 探索率 ε = 0.05
    pub explore_epsilon: f64,
    /// 偏好指数 α = 1.0
    pub alpha: f64,
    /// 语境指数 β = 1.0（当前实现恒 1，未单独计）
    pub beta: f64,
    /// 频率指数 γ = 0.5
    pub gamma: f64,
    /// 漂移窗口 W = 100
    pub drift_window: u64,
    /// 漂移阈值 θ = 0.15（JS 散度）
    pub drift_threshold: f64,
    /// 类内展示上限 M = 5
    pub max_class_size: u64,
    /// 采样随机种子
    pub sample_seed: u64,
}

impl Default for StickerPreferenceParams {
    fn default() -> Self {
        let day_ms: u64 = 24 * 60 * 60 * 1000;
        StickerPreferenceParams {
            decay_half_life_ms: 30 * day_ms,
            n0: 20,
            n1: 200,
            explore_epsilon: 0.05,
            alpha: 1.0,
            beta: 1.0,
            gamma: 0.5,
            drift_window: 100,
            drift_threshold: 0.15,
            max_class_size: 5,
            sample_seed: 20260810,
        }
    }
}

/// 偏好快照（供 Kotlin 注入系统提示 / 展示集）
#[derive(Clone, Debug, uniffi::Record)]
pub struct StickerPreferenceSnapshot {
    /// 规范化 tag → 收缩后先验概率 π_t
    pub tag_prior: HashMap<String, f64>,
    /// 有效样本数（USER 总衰减计数）
    pub effective_samples: u64,
    pub drift_status: DriftStatus,
}

/// 使用点（漂移检测 / 历史分析用，snake_case JSON）
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct StickerUsagePoint {
    pub sticker_id: u64,
    /// "user" / "model"
    pub source: String,
    pub timestamp_ms: u64,
    pub context_tags: Vec<String>,
}

/// 存储回调（Kotlin 实现：Room 读写，纯 IO 无决策）
///
/// 契约：
/// - list_entries：返回 JSON 数组 `[StickerEntryMeta, ...]`
/// - record_usage：插入 log 行 + 累加对应计数列，返回是否成功
/// - usage_history：返回 JSON 数组 `[StickerUsagePoint, ...]`（按时间升序，供漂移窗口分析）
#[uniffi::export(with_foreign)]
pub trait StickerPreferenceStore: Send + Sync {
    /// 全部条目元数据 JSON：[StickerEntryMeta, ...]
    fn list_entries(&self) -> String;
    /// 记录一次使用（落库 log + 计数列），source 传 "user"/"model"
    fn record_usage(
        &self,
        sticker_id: u64,
        source: String,
        timestamp_ms: u64,
        context_tags_json: String,
    ) -> bool;
    /// 历史使用记录 JSON：[StickerUsagePoint, ...]（升序，limit 上限条数）
    fn usage_history(&self, limit: u64) -> String;
}

/// 确定性 PRNG（SplitMix64，避免引入 rand 依赖；种由 params.sample_seed 决定）
struct SplitMix64(u64);
impl SplitMix64 {
    fn new(seed: u64) -> Self {
        SplitMix64(seed.max(1))
    }
    fn next_u64(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    fn next_f64(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }
}

/// 表情包偏好引擎（决策全在 Rust）
#[derive(uniffi::Object)]
pub struct StickerPreferenceEngine {
    store: Arc<dyn StickerPreferenceStore>,
    params: StickerPreferenceParams,
    /// id → 条目元数据（rebuild 载入，record_usage 更新）
    entries: Mutex<HashMap<u64, StickerEntryMeta>>,
    /// tag → (累计 USER 计数, 最后时间戳)；读取时按 τ 指数衰减（惰性）
    decay_cache: Mutex<HashMap<String, (f64, u64)>>,
    drift_status: Mutex<DriftStatus>,
    rng: Mutex<SplitMix64>,
}

#[uniffi::export]
impl StickerPreferenceEngine {
    #[uniffi::constructor]
    pub fn new(
        store: Arc<dyn StickerPreferenceStore>,
        params: StickerPreferenceParams,
    ) -> Arc<StickerPreferenceEngine> {
        Arc::new(StickerPreferenceEngine {
            store,
            entries: Mutex::new(HashMap::new()),
            decay_cache: Mutex::new(HashMap::new()),
            drift_status: Mutex::new(DriftStatus::None),
            rng: Mutex::new(SplitMix64::new(params.sample_seed)),
            params,
        })
    }

    /// 全量重建：从 store 拉条目 → 重建 entries + 衰减缓存 + 重置漂移。
    /// 应用启动 / 表情包导入后调用。
    pub fn rebuild(&self) {
        let list_json = self.store.list_entries();
        let metas: Vec<StickerEntryMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let mut entries: HashMap<u64, StickerEntryMeta> = HashMap::new();
        let mut cache: HashMap<String, (f64, u64)> = HashMap::new();
        for m in metas {
            let base_ts = m.last_used_ms.max(m.created_ms);
            for t in &m.tags {
                let n = normalize_tag(t);
                if n.is_empty() {
                    continue;
                }
                let e = cache.entry(n).or_insert((0.0, base_ts));
                e.0 += m.user_usage_count as f64;
                e.1 = e.1.max(base_ts);
            }
            entries.insert(m.id, m);
        }
        *self.entries.lock().unwrap() = entries;
        *self.decay_cache.lock().unwrap() = cache;
        *self.drift_status.lock().unwrap() = DriftStatus::None;
    }

    /// 记录一次表情包使用（User 来源会更新偏好缓存；Model 来源只记数不污染偏好）。
    /// Kotlin 在 deliverSticker / StickerPanel 落地后调用。
    pub fn record_usage(&self, sticker_id: u64, source: StickerSource, context_tags: Vec<String>) {
        let now = now_ms();
        // 更新内存条目计数
        {
            let mut entries = self.entries.lock().unwrap();
            if let Some(meta) = entries.get_mut(&sticker_id) {
                match source {
                    StickerSource::User => meta.user_usage_count += 1,
                    StickerSource::Model => meta.model_usage_count += 1,
                }
                meta.last_used_ms = now;
            }
        }
        // USER 来源：按 sticker 标签更新偏好缓存（sticker 标签优先，回落 context_tags）
        if source == StickerSource::User {
            let tags: Vec<String> = {
                let entries = self.entries.lock().unwrap();
                entries
                    .get(&sticker_id)
                    .map(|m| m.tags.clone())
                    .unwrap_or_else(|| context_tags.clone())
            };
            let mut cache = self.decay_cache.lock().unwrap();
            for t in tags {
                let n = normalize_tag(&t);
                if n.is_empty() {
                    continue;
                }
                let e = cache.entry(n).or_insert((0.0, now));
                e.0 += 1.0;
                e.1 = now;
            }
        }
        // 落库（log 行 + 计数列）
        let ctx_json = serde_json::to_string(&context_tags).unwrap_or_else(|_| "[]".to_string());
        let _ = self
            .store
            .record_usage(sticker_id, source_str(source).to_string(), now, ctx_json);
    }

    /// 偏好快照：衰减 → 贝叶斯收缩 → tag 先验分布。
    /// Kotlin 注入系统提示（可用标签 Top-30）用。
    pub fn snapshot(&self) -> StickerPreferenceSnapshot {
        let now = now_ms();
        let decayed = self.decayed_tag_counts(now);
        let effective: u64 = decayed.values().sum::<f64>().round() as u64;
        let drift = *self.drift_status.lock().unwrap();
        let prior = self.shrunk_prior(&decayed, drift);
        StickerPreferenceSnapshot {
            tag_prior: prior,
            effective_samples: effective,
            drift_status: drift,
        }
    }

    /// 采样：OR 命中候选集 → top-M → Gibbs 加权随机 → ε 探索。
    /// 返回按分数降序的候选 id（limit 个）；无命中返回空（Kotlin 触发无匹配报告）。
    pub fn sample_candidates(&self, limit: u64, query_tags: Vec<String>) -> Vec<u64> {
        let now = now_ms();
        let q: Vec<String> = query_tags
            .iter()
            .map(|t| normalize_tag(t))
            .filter(|t| !t.is_empty())
            .collect();
        if q.is_empty() {
            return Vec::new();
        }
        let entries = self.entries.lock().unwrap();
        if entries.is_empty() {
            return Vec::new();
        }
        let decayed = self.decayed_tag_counts(now);
        let drift = *self.drift_status.lock().unwrap();
        let prior = self.shrunk_prior(&decayed, drift);
        let tau = self.params.decay_half_life_ms.max(1) as f64;

        // 1. 候选集 C = {i : Q ∩ S_i ≠ ∅}（OR 语义）
        let mut scored: Vec<(u64, f64)> = Vec::new();
        for (id, meta) in entries.iter() {
            let s: Vec<String> = meta.tags.iter().map(|t| normalize_tag(t)).collect();
            if s.is_empty() || !q.iter().any(|qt| s.iter().any(|st| st == qt)) {
                continue;
            }
            let tag_score = 1.0 / (s.len().max(1) as f64).sqrt();
            let pi = if s.is_empty() {
                0.5
            } else {
                s.iter()
                    .map(|t| prior.get(t).copied().unwrap_or(0.0))
                    .sum::<f64>()
                    / s.len() as f64
            };
            let dt = now.saturating_sub(meta.last_used_ms) as f64;
            let freq = (meta.user_usage_count as f64) * (-dt / tau).exp();
            let score = tag_score
                * pi.powf(self.params.alpha)
                * freq.max(1e-9).powf(self.params.gamma);
            scored.push((*id, score));
        }
        if scored.is_empty() {
            return Vec::new();
        }
        scored.sort_by(|a, b| {
            b.1.partial_cmp(&a.1)
                .unwrap_or(std::cmp::Ordering::Equal)
        });

        // 2. top-M 展示集
        let m = self.params.max_class_size.max(1) as usize;
        let top: Vec<(u64, f64)> = scored.into_iter().take(m).collect();
        let limit_n = limit.max(1) as usize;

        let mut rng = self.rng.lock().unwrap();
        // 3. ε 探索：从全集随机取
        if rng.next_f64() < self.params.explore_epsilon {
            let all: Vec<u64> = entries.keys().copied().collect();
            let mut ids: Vec<u64> = Vec::new();
            for _ in 0..limit_n {
                if all.is_empty() {
                    break;
                }
                let idx = (rng.next_f64() * all.len() as f64) as usize % all.len();
                let id = all[idx];
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
            return ids;
        }

        // 4. Gibbs 加权随机（温度随样本量退火，样本越多越"贪心"）
        let n_total = decayed.values().sum::<f64>();
        let temp = 1.0 / (1.0 + (n_total / 100.0).min(9.0));
        let weights: Vec<f64> = top
            .iter()
            .map(|(_, s)| s.max(1e-9).powf(1.0 / temp.max(0.1)))
            .collect();
        let sum: f64 = weights.iter().sum();
        let mut result: Vec<u64> = Vec::new();
        if sum > 0.0 {
            let mut r = rng.next_f64() * sum;
            for ((id, _), w) in top.iter().zip(weights.iter()) {
                r -= w;
                if r <= 0.0 {
                    result.push(*id);
                    break;
                }
            }
        }
        if result.is_empty() {
            result.push(top[0].0);
        }
        for (id, _) in top.iter() {
            if result.len() >= limit_n {
                break;
            }
            if !result.contains(id) {
                result.push(*id);
            }
        }
        result
    }

    /// 漂移检测：recent W 条 vs 更早 USER 记录的 JS 散度。
    /// Drifted 后 snapshot 收缩 λ→0（立即放弃旧先验）。返回最新状态。
    pub fn check_drift(&self) -> DriftStatus {
        let history_json = self.store.usage_history(10_000);
        let points: Vec<StickerUsagePoint> =
            serde_json::from_str(&history_json).unwrap_or_default();
        let user: Vec<&StickerUsagePoint> = points.iter().filter(|p| p.source == "user").collect();
        let w = self.params.drift_window.max(10) as usize;
        let mut st = self.drift_status.lock().unwrap();
        if user.len() < 20 {
            *st = DriftStatus::None;
            return *st;
        }
        if user.len() < w * 2 {
            *st = DriftStatus::Watching;
            return *st;
        }
        let recent = &user[..w];
        let history = &user[w..];
        let js = js_divergence(recent, history, &self.entries.lock().unwrap());
        *st = if js > self.params.drift_threshold {
            DriftStatus::Drifted
        } else {
            DriftStatus::Watching
        };
        *st
    }

    // ── 内部 ──

    fn decayed_tag_counts(&self, now: u64) -> HashMap<String, f64> {
        let cache = self.decay_cache.lock().unwrap();
        let tau = self.params.decay_half_life_ms.max(1) as f64;
        cache
            .iter()
            .map(|(tag, (count, ts))| {
                let dt = now.saturating_sub(*ts) as f64;
                let decayed = count * (-dt / tau).exp();
                (tag.clone(), decayed)
            })
            .collect()
    }

    fn shrunk_prior(
        &self,
        decayed: &HashMap<String, f64>,
        drift: DriftStatus,
    ) -> HashMap<String, f64> {
        let mut prior: HashMap<String, f64> = HashMap::new();
        if decayed.is_empty() {
            return prior;
        }
        let total: f64 = decayed.values().sum();
        if total <= 0.0 {
            // 零样本（冷启动 / 全衰减）→ 均匀先验（n_total < n0 分支的边界情形）
            let k = decayed.len() as f64;
            let uniform = 1.0 / k;
            for tag in decayed.keys() {
                prior.insert(tag.clone(), uniform);
            }
            return prior;
        }
        let k = decayed.len() as f64;
        let uniform = 1.0 / k;
        let n_total = total;
        // 漂移 → λ=0 放弃旧先验；否则贝叶斯收缩
        let lambda = if drift == DriftStatus::Drifted {
            0.0
        } else if n_total < self.params.n0 as f64 {
            0.0
        } else if n_total > self.params.n1 as f64 {
            1.0
        } else {
            n_total / self.params.n1 as f64
        };
        for (tag, c) in decayed {
            let empirical = c / total;
            let p = lambda * empirical + (1.0 - lambda) * uniform;
            prior.insert(tag.clone(), p);
        }
        prior
    }
}

/// 规范化标签：去空白 + 去全部标点 + 截断 20 字符 + 统一小写（对齐 clean_sticker_description）
pub(crate) fn normalize_tag(raw: &str) -> String {
    const MAX_LEN: usize = 20;
    raw.chars()
        .filter(|c| !c.is_whitespace() && !is_punct(*c))
        .take(MAX_LEN)
        .flat_map(char::to_lowercase)
        .collect()
}

fn is_punct(c: char) -> bool {
    matches!(
        c,
        '，' | '。' | '！' | '？' | '、' | '；' | '：' | '“' | '”' | '‘' | '’'
            | '（' | '）' | '【' | '】' | '《' | '》' | '…' | '～' | '~' | '⋯'
            | ',' | '.' | '!' | '?' | ';' | ':' | '(' | ')' | '[' | ']' | '{'
            | '}' | '"' | '\'' | '`' | '-' | '_' | '*' | '#' | '+' | '=' | '>'
            | '<' | '|' | '\\' | '/'
    )
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn source_str(s: StickerSource) -> &'static str {
    match s {
        StickerSource::User => "user",
        StickerSource::Model => "model",
    }
}

/// 记录 → 标签列表（优先 context_tags，否则回落 sticker 的 tags，无则跳过）
fn point_tags<'a>(p: &'a StickerUsagePoint, entries: &'a HashMap<u64, StickerEntryMeta>) -> Vec<String> {
    if !p.context_tags.is_empty() {
        p.context_tags.clone()
    } else {
        entries
            .get(&p.sticker_id)
            .map(|m| m.tags.clone())
            .unwrap_or_default()
    }
}

/// 两组使用记录（标签分布）的 JS 散度（对称，∈[0, ln2]）
fn js_divergence(
    recent: &[&StickerUsagePoint],
    history: &[&StickerUsagePoint],
    entries: &HashMap<u64, StickerEntryMeta>,
) -> f64 {
    let mut pr: HashMap<String, f64> = HashMap::new();
    let mut ph: HashMap<String, f64> = HashMap::new();
    for p in recent {
        for t in point_tags(p, entries) {
            if !t.is_empty() {
                *pr.entry(t).or_insert(0.0) += 1.0;
            }
        }
    }
    for p in history {
        for t in point_tags(p, entries) {
            if !t.is_empty() {
                *ph.entry(t).or_insert(0.0) += 1.0;
            }
        }
    }
    let n_r: f64 = pr.values().sum();
    let n_h: f64 = ph.values().sum();
    if n_r <= 0.0 || n_h <= 0.0 {
        return 0.0;
    }
    for v in pr.values_mut() {
        *v /= n_r;
    }
    for v in ph.values_mut() {
        *v /= n_h;
    }
    let keys: Vec<String> = pr
        .keys()
        .chain(ph.keys())
        .cloned()
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    let eps = 1e-9;
    let mut kl_r = 0.0;
    let mut kl_h = 0.0;
    for k in keys {
        let p = pr.get(&k).copied().unwrap_or(0.0) + eps;
        let q = ph.get(&k).copied().unwrap_or(0.0) + eps;
        let m = 0.5 * (p + q);
        kl_r += p * (p / m).ln();
        kl_h += q * (q / m).ln();
    }
    0.5 * kl_r + 0.5 * kl_h
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockStore {
        entries: Mutex<Vec<StickerEntryMeta>>,
        usage: Mutex<Vec<StickerUsagePoint>>,
        recorded: Mutex<Vec<(u64, String, u64, String)>>,
    }

    impl MockStore {
        fn new(entries: Vec<StickerEntryMeta>) -> Self {
            MockStore {
                entries: Mutex::new(entries),
                usage: Mutex::new(Vec::new()),
                recorded: Mutex::new(Vec::new()),
            }
        }
    }

    impl StickerPreferenceStore for MockStore {
        fn list_entries(&self) -> String {
            serde_json::to_string(&*self.entries.lock().unwrap()).unwrap()
        }
        fn record_usage(
            &self,
            sticker_id: u64,
            source: String,
            timestamp_ms: u64,
            context_tags_json: String,
        ) -> bool {
            let mut u = self.usage.lock().unwrap();
            u.push(StickerUsagePoint {
                sticker_id,
                source,
                timestamp_ms,
                context_tags: serde_json::from_str(&context_tags_json).unwrap_or_default(),
            });
            self.recorded
                .lock()
                .unwrap()
                .push((sticker_id, context_tags_json, timestamp_ms, String::new()));
            true
        }
        fn usage_history(&self, _limit: u64) -> String {
            serde_json::to_string(&*self.usage.lock().unwrap()).unwrap()
        }
    }

    fn entry(id: u64, tags: &[&str], user_count: u64, last_used: u64) -> StickerEntryMeta {
        StickerEntryMeta {
            id,
            tags: tags.iter().map(|s| s.to_string()).collect(),
            user_usage_count: user_count,
            model_usage_count: 0,
            last_used_ms: last_used,
            created_ms: last_used,
        }
    }

    fn engine(
        entries: Vec<StickerEntryMeta>,
    ) -> (Arc<StickerPreferenceEngine>, Arc<MockStore>) {
        let store = Arc::new(MockStore::new(entries));
        let engine = StickerPreferenceEngine::new(
            store.clone(),
            StickerPreferenceParams::default(),
        );
        engine.rebuild();
        (engine, store)
    }

    fn engine_only(entries: Vec<StickerEntryMeta>) -> Arc<StickerPreferenceEngine> {
        engine(entries).0
    }

    #[test]
    fn normalize_removes_punct_and_case() {
        assert_eq!(normalize_tag("开心！"), "开心");
        assert_eq!(normalize_tag("  Happy!! "), "happy");
        assert_eq!(normalize_tag("   "), "");
        assert_eq!(normalize_tag("委屈巴巴～"), "委屈巴巴");
    }

    #[test]
    fn cold_start_prior_is_uniform() {
        let e = engine_only(vec![
            entry(1, &["开心"], 0, 0),
            entry(2, &["生气"], 0, 0),
        ]);
        let snap = e.snapshot();
        assert_eq!(snap.effective_samples, 0);
        assert!((snap.tag_prior["开心"] - 0.5).abs() < 1e-9);
        assert!((snap.tag_prior["生气"] - 0.5).abs() < 1e-9);
        assert_eq!(snap.drift_status, DriftStatus::None);
    }

    #[test]
    fn user_usage_moves_prior() {
        let e = engine_only(vec![entry(1, &["开心"], 0, 0), entry(2, &["生气"], 0, 0)]);
        // 记 250 次"开心"使用（> n1=200 → 纯经验频率）→ 开心先验占优
        for _ in 0..250 {
            e.record_usage(1, StickerSource::User, vec!["开心".to_string()]);
        }
        let snap = e.snapshot();
        assert!(
            snap.tag_prior["开心"] > 0.9,
            "先验应偏向开心: {:?}",
            snap.tag_prior
        );
    }

    #[test]
    fn model_usage_does_not_pollute_prior() {
        let e = engine_only(vec![
            entry(1, &["开心"], 0, 0),
            entry(2, &["生气"], 0, 0),
        ]);
        // 100 次 model 使用 → 先验仍均匀（D4）
        for _ in 0..100 {
            e.record_usage(1, StickerSource::Model, vec!["开心".to_string()]);
        }
        let snap = e.snapshot();
        assert!(
            (snap.tag_prior["开心"] - 0.5).abs() < 1e-6,
            "model 来源不应污染偏好: {:?}",
            snap.tag_prior
        );
    }

    #[test]
    fn sample_or_semantics_multi_tag() {
        // 图3 同时命中"开心"和"下班"两个 tag
        let e = engine_only(vec![
            entry(1, &["开心"], 0, 0),
            entry(2, &["生气"], 0, 0),
            entry(3, &["开心", "下班"], 0, 0),
        ]);
        let ids = e.sample_candidates(3, vec!["开心".to_string(), "下班".to_string()]);
        assert!(!ids.is_empty(), "OR 语义下应命中图1和图3");
        assert!(ids.contains(&1) || ids.contains(&3), "命中集应含图1或图3: {ids:?}");
        assert!(!ids.contains(&2), "生气图不应进入候选: {ids:?}");
    }

    #[test]
    fn sample_single_tag_filters() {
        let e = engine_only(vec![
            entry(1, &["开心"], 0, 0),
            entry(2, &["生气"], 0, 0),
        ]);
        let ids = e.sample_candidates(2, vec!["生气".to_string()]);
        assert_eq!(ids, vec![2]);
    }

    #[test]
    fn sample_empty_query_returns_empty() {
        let e = engine_only(vec![entry(1, &["开心"], 0, 0)]);
        assert!(e.sample_candidates(2, Vec::new()).is_empty());
    }

    #[test]
    fn sample_no_match_returns_empty() {
        let e = engine_only(vec![entry(1, &["开心"], 0, 0)]);
        assert!(e.sample_candidates(2, vec!["不存在".to_string()]).is_empty());
    }

    #[test]
    fn rebuild_preserves_usage_counts() {
        // 预置使用记录 → rebuild 后先验应反映（衰减初始化）
        let (e, _store) = engine(vec![entry(1, &["开心"], 50, now_ms())]);
        let snap = e.snapshot();
        assert!(
            snap.effective_samples >= 49,
            "有效样本应≥50: {}",
            snap.effective_samples
        );
    }

    #[test]
    fn drift_detects_shift_and_resets_prior() {
        let now = now_ms();
        let day = 24 * 60 * 60 * 1000u64;
        let (e, store) = engine(vec![
            entry(1, &["开心"], 0, now),
            entry(2, &["生气"], 0, now),
            entry(3, &["难过"], 0, now),
        ]);
        // 先记 100 条"开心"历史（窗口之外）
        for i in 0..100 {
            store.push_usage(1, "开心", now - 50 * day + i * day / 10);
        }
        // 再记 100 条"生气"近期（窗口内），与历史分布显著不同
        for i in 0..100 {
            store.push_usage(2, "生气", now - day + i * day / 10);
        }
        let st = e.check_drift();
        assert_eq!(st, DriftStatus::Drifted, "分布剧变应触发漂移");
        // 漂移后先验回落均匀（λ→0）
        let snap = e.snapshot();
        let p = snap.tag_prior["开心"];
        let q = snap.tag_prior["生气"];
        assert!((p - q).abs() < 0.35, "漂移后先验应收敛: {p} vs {q}");
    }

    impl MockStore {
        fn push_usage(&self, sticker_id: u64, tag: &str, ts: u64) {
            self.usage.lock().unwrap().push(StickerUsagePoint {
                sticker_id,
                source: "user".to_string(),
                timestamp_ms: ts,
                context_tags: vec![tag.to_string()],
            });
        }
    }
}
