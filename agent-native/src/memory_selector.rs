// memory_selector.rs — Agent Memory 选择与整理层（Rust 决策）
//
// 职责：
// - MemoryStore：UniFFI foreign trait，Kotlin 实现（Room unified_memories 表 + EmbeddingProvider，纯 IO 无决策）
// - MemorySelector：召回 / 评分（关键词 + 语义 + 时间衰减）/ 排序 / 整理决策（决策 100% 在 Rust）
// - Agent Memory 工具：recall_memory / save_memory / consolidate_memory
//   记忆压缩与整理本质上是程序性记忆 Skill —— 通过 memory_skill_context() 注入 system prompt，
//   让 Agent 知道何时调用 consolidate_memory 等 Agent tool（ToolCategory::Memory 已预留）。
//
// 与既有架构的关系：
// - 不触碰 agent.rs 的 AgentRunner / AgentToolRegistry 既有内置工具
// - 不复用 Kotlin UnifiedMemoryProvider/Repository 的决策逻辑（零散未规划），Kotlin 仅提供纯 IO
// - 存储仍用 unified_memories 表（content 在 DB 列，零迁移），整理/召回/排序决策全部在 Rust

use std::collections::HashSet;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::agent::{ToolCategory, ToolDefinition};

/// 记忆元数据（MemoryStore.list_memories 返回 JSON 数组反序列化目标）
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct MemoryMeta {
    pub id: String,
    pub content: String,
    /// WORKING / EPISODIC / SEMANTIC / PREFERENCE / RELATIONSHIP / PROCEDURAL / FUZZY
    pub memory_type: String,
    /// GLOBAL / COMPANION / GROUP / PRIVATE
    pub scope: String,
    pub source_id: i64,
    pub importance: f32,
    pub confidence: f32,
    pub access_count: u32,
    pub observed_at: i64,
    pub last_accessed_at: i64,
    pub expires_at: Option<i64>,
    pub tags: String,
    /// 语义向量（Kotlin 桥接 EmbeddingProvider 生成），可能为 None
    pub embedding: Option<Vec<f32>>,
}

/// 选中的记忆上下文（注入 system prompt）
#[derive(Clone, Debug, uniffi::Record)]
pub struct MemoryContext {
    pub id: String,
    pub content: String,
    pub memory_type: String,
    pub scope: String,
    pub source_id: i64,
    pub observed_at: i64,
    pub importance: f32,
    pub score: f32,
}

/// 记忆存储回调（Kotlin 实现：Room unified_memories 表 + EmbeddingProvider，纯 IO 无决策）
///
/// 契约：
/// - list_memories：scope_json 形如 `{"scope":"COMPANION","source_id":123}`；
///   `{"scope":null}` 表示全部；返回 JSON 数组 `[MemoryMeta, ...]`（含 embedding）
/// - insert_memory：meta_json 的 id 为空串时由 Kotlin 生成，返回新记忆 id（字符串）
/// - update_memory / delete_memory：删除为软删除（isDeleted=1）
/// - get_activity_timestamps：返回 `{"last_activity_at":ms,"last_consolidated_at":ms}`（0 = 无记录）
/// - embed_text：返回 FloatArray JSON（如 `[0.1,0.2,...]`），失败返回 None
#[uniffi::export(with_foreign)]
pub trait MemoryStore: Send + Sync {
    fn list_memories(&self, scope_json: String) -> String;
    fn insert_memory(&self, meta_json: String) -> String;
    fn update_memory(&self, meta_json: String) -> bool;
    fn delete_memory(&self, id: String) -> bool;
    fn get_memory_content(&self, id: String) -> Option<String>;
    fn get_activity_timestamps(&self, companion_id: Option<i64>) -> String;
    fn set_last_consolidated_at(&self, now: i64, companion_id: Option<i64>) -> bool;
    fn embed_text(&self, text: String) -> Option<String>;
}

/// 记忆选择器：召回 → 评分 → 排序；整理决策（决策在 Rust）
#[derive(uniffi::Object)]
pub struct MemorySelector {
    store: Arc<dyn MemoryStore>,
}

/// 活动时间戳（get_activity_timestamps 解析目标，非导出）
#[derive(Deserialize, Default)]
struct ActivityTimestamps {
    #[serde(default)]
    last_activity_at: i64,
    #[serde(default)]
    last_consolidated_at: i64,
}

// ── 常量 ──
/// 对话空闲阈值：最后一条消息距今超过 1 小时视为「对话结束」
const IDLE_HOUR_MS: i64 = 3_600_000;
/// 整理最小间隔：上次整理距今超过 30 分钟才允许再次整理
const CONSOLIDATE_MIN_INTERVAL_MS: i64 = 1_800_000;
/// 记忆遗忘阈值：importance < 0.3 且 30 天未访问 → 遗忘
const FORGET_IMPORTANCE: f32 = 0.3;
const FORGET_STALE_DAYS_MS: i64 = 30 * 86_400_000;
/// WORKING 合并上限：最多取最近 5 条工作记忆合成一条 EPISODIC
const WORKING_MERGE_MAX: usize = 5;
/// 去重阈值：bigram Jaccard > 0.75 视为重复
const DEDUP_JACCARD: f32 = 0.75;

#[uniffi::export]
impl MemorySelector {
    #[uniffi::constructor]
    pub fn new(store: Arc<dyn MemoryStore>) -> Arc<MemorySelector> {
        Arc::new(MemorySelector { store })
    }

    /// 召回记忆：索引/全量召回 → 评分（关键词 + 语义 + 时间衰减）→ 排序 → top N
    ///
    /// scope_json 由调用方组装（如 `{"scope":"COMPANION","source_id":123}` 或
    /// `{"scope":null}` 表示全局 + 全部）。空 query 时按时间衰减排序（最近重要的优先）。
    pub fn select(&self, query: String, scope_json: String, limit: u32) -> Vec<MemoryContext> {
        let list_json = self.store.list_memories(scope_json);
        let metas: Vec<MemoryMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let q = query.trim().to_lowercase();
        let now = now_ms();

        // query embedding（仅 query 非空时尝试；失败/不支持时跳过语义分）
        let q_emb: Option<Vec<f32>> = if q.is_empty() {
            None
        } else {
            self.store
                .embed_text(query.clone())
                .and_then(|s| serde_json::from_str::<Vec<f32>>(&s).ok())
        };

        let mut ranked: Vec<(f32, MemoryMeta)> = metas
            .into_iter()
            // L5 长期记忆层排除 WORKING（短期记忆由用户 L1 层 select_working 承担）
            .filter(|m| m.memory_type != "WORKING")
            .map(|m| (rank_memory(&m, &q, q_emb.as_deref(), now), m))
            .collect();
        // 分数降序，同分按观察时间倒序（新记忆优先）
        ranked.sort_by(|a, b| {
            b.0.partial_cmp(&a.0)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(b.1.observed_at.cmp(&a.1.observed_at))
        });

        ranked
            .into_iter()
            .take(limit.max(1) as usize)
            .filter(|(score, _)| *score > 0.0)
            .map(|(score, m)| MemoryContext {
                id: m.id,
                content: m.content,
                memory_type: m.memory_type,
                scope: m.scope,
                source_id: m.source_id,
                observed_at: m.observed_at,
                importance: m.importance,
                score,
            })
            .collect()
    }

    /// 短期记忆召回（用户 L1 层）：仅取 WORKING 类型，按观察时间倒序取前 limit 条。
    /// 用于 build_user_context 注入最新工作记忆（轻量、无打分开销）。
    pub fn select_working(&self, scope_json: String, limit: u32) -> Vec<MemoryContext> {
        let list_json = self.store.list_memories(scope_json);
        let metas: Vec<MemoryMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let mut working: Vec<MemoryMeta> = metas
            .into_iter()
            .filter(|m| m.memory_type == "WORKING")
            .collect();
        working.sort_by(|a, b| b.observed_at.cmp(&a.observed_at));
        working
            .into_iter()
            .take(limit.max(1) as usize)
            .map(|m| MemoryContext {
                id: m.id,
                content: m.content,
                memory_type: m.memory_type,
                scope: m.scope,
                source_id: m.source_id,
                observed_at: m.observed_at,
                importance: m.importance,
                score: m.importance,
            })
            .collect()
    }

    /// 将选中的记忆拼成 system prompt 片段（空列表返回空串）
    pub fn build_system_context(&self, contexts: Vec<MemoryContext>) -> String {
        if contexts.is_empty() {
            return String::new();
        }
        let mut out = String::from("[相关记忆]\n");
        for (i, ctx) in contexts.iter().enumerate() {
            out.push_str(&format!("{}. （{}）{}\n", i + 1, ctx.memory_type, ctx.content));
        }
        out.push_str("[记忆使用说明] 以上为跨会话长期记忆，回答时自然引用，不要暴露本段格式。\n");
        out
    }

    /// 是否需要整理记忆（惰性触发，读取侧调用，无后台任务无轮询）
    ///
    /// 规则（用户确认版）：最后一条对话距今 > 1h 且 上次整理距今 > 30min。
    /// last_consolidated_at <= 0（无记录）视为刚整理过，避免冷启动立即触发。
    pub fn should_consolidate(&self, companion_id: Option<i64>) -> bool {
        let ts = self.read_timestamps(companion_id);
        self.eval_should_consolidate(ts.last_activity_at, ts.last_consolidated_at, now_ms())
    }

    /// 整理记忆（决策在 Rust）：遗忘过期/陈旧 → WORKING→EPISODIC 合并 → 去重 → 记录整理时间
    pub fn run_consolidation(&self, companion_id: Option<i64>) -> String {
        let now = now_ms();
        let scope_json = scope_json_for(companion_id);
        let list_json = self.store.list_memories(scope_json);
        let mut metas: Vec<MemoryMeta> = serde_json::from_str(&list_json).unwrap_or_default();

        let mut consolidated = 0u32;
        let mut deduped = 0u32;
        let mut forgotten = 0u32;

        // ── 1. 遗忘：过期 / 低重要性且长期未访问 ──
        let mut survivors: Vec<MemoryMeta> = Vec::with_capacity(metas.len());
        for m in metas.drain(..) {
            let expired = m.expires_at.map(|e| e < now).unwrap_or(false);
            let stale_low = m.importance < FORGET_IMPORTANCE && (now - m.last_accessed_at) > FORGET_STALE_DAYS_MS;
            if expired || stale_low {
                if self.store.delete_memory(m.id.clone()) {
                    forgotten += 1;
                }
                continue;
            }
            survivors.push(m);
        }

        // ── 2. WORKING → EPISODIC 合并（本地规则：最近 N 条工作记忆合成为一条 EPISODIC） ──
        let working: Vec<MemoryMeta> = survivors
            .iter()
            .filter(|m| m.memory_type == "WORKING")
            .cloned()
            .collect();
        if working.len() >= 2 {
            let mut sorted = working.clone();
            sorted.sort_by(|a, b| a.observed_at.cmp(&b.observed_at));
            let take = sorted.len().min(WORKING_MERGE_MAX);
            let parts: Vec<String> = sorted[..take]
                .iter()
                .map(|m| m.content.trim().to_string())
                .filter(|s| !s.is_empty())
                .collect();
            if !parts.is_empty() {
                let anchor = sorted[0].clone();
                let new_meta = MemoryMeta {
                    id: String::new(),
                    content: parts.join("；"),
                    memory_type: "EPISODIC".to_string(),
                    scope: anchor.scope.clone(),
                    source_id: anchor.source_id,
                    importance: (anchor.importance + 0.05).min(1.0),
                    confidence: 0.6,
                    access_count: 1,
                    observed_at: now,
                    last_accessed_at: now,
                    expires_at: None,
                    tags: "consolidated".to_string(),
                    embedding: None,
                };
                let id = self.store.insert_memory(serde_json::to_string(&new_meta).unwrap_or_default());
                if !id.is_empty() {
                    consolidated += 1;
                }
                for m in &sorted[..take] {
                    self.store.delete_memory(m.id.clone());
                }
            }
        }

        // ── 3. 去重：bigram Jaccard > 阈值 → 保留高 importance，软删另一条 ──
        let mut final_list: Vec<MemoryMeta> = Vec::with_capacity(survivors.len());
        for m in survivors {
            if m.memory_type == "WORKING" {
                continue; // 已合并处理
            }
            let mut duplicated = false;
            for existing in final_list.iter_mut() {
                if jaccard_bigram(&m.content, &existing.content) > DEDUP_JACCARD {
                    duplicated = true;
                    if m.importance > existing.importance {
                        let old = existing.clone();
                        *existing = m.clone();
                        self.store.delete_memory(old.id.clone());
                    } else {
                        self.store.delete_memory(m.id.clone());
                    }
                    deduped += 1;
                    break;
                }
            }
            if !duplicated {
                final_list.push(m);
            }
        }

        // ── 4. 记录整理时间 ──
        self.store.set_last_consolidated_at(now, companion_id);

        serde_json::json!({
            "ok": true,
            "consolidated": consolidated,
            "deduped": deduped,
            "forgotten": forgotten,
        })
        .to_string()
    }

    /// Agent Memory 工具定义（注入模型工具列表；ToolCategory::Memory）
    ///
    /// 记忆压缩与整理 = 程序性记忆 Skill —— 工具定义 + memory_skill_context()
    /// 双管齐下，让 Agent 知道该调用哪个 Agent tool。
    pub fn memory_tool_definitions(&self) -> Vec<ToolDefinition> {
        vec![
            ToolDefinition {
                name: "recall_memory".to_string(),
                description: "按查询词召回跨会话长期记忆（用户偏好、过往事件、关系设定、长期上下文等）。触发条件：当当前对话需要了解用户的历史信息、过往事件或长期偏好时调用。约束：查询词需具体；不要用本工具保存信息。".to_string(),
                parameters_json: r#"{"type":"object","properties":{"query":{"type":"string","minLength":1,"description":"要召回的查询词或主题，至少 1 个字符"},"limit":{"type":"integer","minimum":1,"maximum":10,"description":"最多返回条数，建议 3-8"}},"required":["query"],"additionalProperties":false}"#.to_string(),
                category: ToolCategory::Memory,
                toolsets: vec!["memory".to_string()],
                available: true,
            },
            ToolDefinition {
                name: "save_memory".to_string(),
                description: "将对话中值得长期记住的信息保存为记忆（用户偏好、重要事实、情绪事件、关系变化等）。触发条件：当对话中出现值得跨会话长期记住的信息时调用。约束：临时性、一次性内容不要保存；重要度需按 0-1 打分。".to_string(),
                parameters_json: r#"{"type":"object","properties":{"content":{"type":"string","minLength":1,"description":"要保存的记忆内容，至少 1 个字符"},"type":{"type":"string","enum":["SEMANTIC","PREFERENCE","EPISODIC","RELATIONSHIP","PROCEDURAL","WORKING"],"description":"记忆类型：SEMANTIC=语义知识，PREFERENCE=偏好，EPISODIC=事件，RELATIONSHIP=关系，PROCEDURAL=程序性，WORKING=短期工作记忆"},"importance":{"type":"number","minimum":0.0,"maximum":1.0,"description":"重要度 0-1，默认 0.5"}},"required":["content"],"additionalProperties":false}"#.to_string(),
                category: ToolCategory::Memory,
                toolsets: vec!["memory".to_string()],
                available: true,
            },
            ToolDefinition {
                name: "consolidate_memory".to_string(),
                description: "整理记忆：将工作记忆压缩合并为长期记忆、合并重复记忆、遗忘过时信息。触发条件：当对话告一段落、或需要整理近期积累的短期记忆时调用。约束：本工具无需参数；不要在对话中途频繁调用。".to_string(),
                parameters_json: r#"{"type":"object","properties":{},"additionalProperties":false}"#.to_string(),
                category: ToolCategory::Memory,
                toolsets: vec!["memory".to_string()],
                available: true,
            },
        ]
    }

    /// 执行记忆工具（Rust 决策；副作用通过 store 回调）
    ///
    /// context_json 形如 `{"companion_id":123,"group_id":null}`
    pub fn execute_memory_tool(&self, name: String, args_json: String, context_json: String) -> String {
        let ctx: serde_json::Value = serde_json::from_str(&context_json).unwrap_or(serde_json::Value::Null);
        let companion_id = ctx.get("companion_id").and_then(|v| v.as_i64());
        let group_id = ctx.get("group_id").and_then(|v| v.as_i64());

        match name.as_str() {
            "recall_memory" => {
                let args: serde_json::Value = serde_json::from_str(&args_json).unwrap_or(serde_json::Value::Null);
                let query = args.get("query").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let limit = args.get("limit").and_then(|v| v.as_u64()).unwrap_or(5).clamp(1, 10) as u32;
                let scope_json = scope_json_for_ids(companion_id, group_id);
                let contexts = self.select(query.clone(), scope_json, limit);
                serde_json::json!({
                    "ok": true,
                    "query": query,
                    "empty": contexts.is_empty(),
                    "memories": contexts.iter().map(|c| serde_json::json!({
                        "id": c.id,
                        "content": c.content,
                        "type": c.memory_type,
                        "importance": c.importance,
                        "score": c.score,
                    })).collect::<Vec<_>>(),
                })
                .to_string()
            }
            "save_memory" => {
                let args: serde_json::Value = serde_json::from_str(&args_json).unwrap_or(serde_json::Value::Null);
                let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
                if content.is_empty() {
                    return serde_json::json!({"ok": false, "error": "content 不能为空"}).to_string();
                }
                let mtype = args.get("type").and_then(|v| v.as_str()).unwrap_or("SEMANTIC").to_string();
                let importance = args.get("importance").and_then(|v| v.as_f64()).unwrap_or(0.5) as f32;
                let meta = MemoryMeta {
                    id: String::new(),
                    content: content.clone(),
                    memory_type: mtype,
                    scope: if group_id.is_some() { "GROUP" } else { "COMPANION" }.to_string(),
                    source_id: group_id.or(companion_id).unwrap_or(0),
                    importance: importance.clamp(0.0, 1.0),
                    confidence: 0.8,
                    access_count: 1,
                    observed_at: now_ms(),
                    last_accessed_at: now_ms(),
                    expires_at: None,
                    tags: "agent-tool".to_string(),
                    embedding: None,
                };
                let id = self.store.insert_memory(serde_json::to_string(&meta).unwrap_or_default());
                serde_json::json!({"ok": !id.is_empty(), "id": id}).to_string()
            }
            "consolidate_memory" => self.run_consolidation(companion_id),
            _ => serde_json::json!({"ok": false, "error": format!("未注册的记忆工具 {name}")}).to_string(),
        }
    }
}

impl MemorySelector {
    fn read_timestamps(&self, companion_id: Option<i64>) -> ActivityTimestamps {
        let raw = self.store.get_activity_timestamps(companion_id);
        serde_json::from_str(&raw).unwrap_or_default()
    }

    fn eval_should_consolidate(&self, last_activity_at: i64, last_consolidated_at: i64, now: i64) -> bool {
        let idle = now - last_activity_at;
        // last_consolidated_at <= 0（无记录）视为刚整理过
        let since_last = if last_consolidated_at <= 0 { 0 } else { now - last_consolidated_at };
        idle > IDLE_HOUR_MS && since_last > CONSOLIDATE_MIN_INTERVAL_MS
    }
}

/// 记忆技能描述（程序性记忆 Skill，注入 Agent system prompt）
///
/// 本质：告诉 Agent「记忆压缩/整理是一个可调用的技能」，通过记忆工具执行。
#[uniffi::export]
pub fn memory_skill_context() -> String {
    "【记忆技能】
你具备跨会话记忆能力，可用以下记忆工具：
1. recall_memory：当需要回忆用户的偏好、过往事件、关系设定或长期上下文时调用（传入查询词）。
2. save_memory：当对话中出现值得长期记住的信息（用户偏好、重要事实、情绪事件、关系变化）时调用。
3. consolidate_memory：当对话告一段落（最后一条消息约 1 小时前）时调用，将工作记忆压缩为长期记忆、合并重复记忆、遗忘过时信息。
原则：自然调用，不向用户暴露工具名；记忆内容保持客观陈述。"
        .to_string()
}

/// 组装作用域查询 JSON（单聊 / 全局）
fn scope_json_for(companion_id: Option<i64>) -> String {
    match companion_id {
        Some(id) => serde_json::json!({"scope": "COMPANION", "source_id": id}).to_string(),
        None => serde_json::json!({"scope": null, "source_id": null}).to_string(),
    }
}

/// 组装作用域查询 JSON（工具执行用：群聊优先）
pub(crate) fn scope_json_for_ids(companion_id: Option<i64>, group_id: Option<i64>) -> String {
    if let Some(gid) = group_id {
        serde_json::json!({"scope": "GROUP", "source_id": gid}).to_string()
    } else if let Some(cid) = companion_id {
        serde_json::json!({"scope": "COMPANION", "source_id": cid}).to_string()
    } else {
        serde_json::json!({"scope": null, "source_id": null}).to_string()
    }
}

// ── 评分函数（决策在 Rust） ──

/// 综合评分：时间衰减 + 关键词 + 语义余弦（空 query 仅用时间衰减）
fn rank_memory(meta: &MemoryMeta, query_lower: &str, q_emb: Option<&[f32]>, now: i64) -> f32 {
    let decay = decay_score(meta, now);
    if query_lower.is_empty() {
        return decay;
    }
    let kw = keyword_score(meta, query_lower);
    let sem = match (q_emb, meta.embedding.as_deref()) {
        (Some(q), Some(e)) => cosine_similarity(q, e),
        _ => 0.0,
    };
    // 权重：时间衰减 0.35 + 关键词 0.45 + 语义 0.20；访问次数微调
    let mut score = decay * 0.35 + kw * 0.45 + sem * 0.20;
    score += (meta.access_count as f32) * 0.005;
    score
}

/// 时间衰减分：importance × exp(-age_days / 7)（半衰期约 7 天）
fn decay_score(meta: &MemoryMeta, now: i64) -> f32 {
    let age_ms = (now - meta.observed_at).max(0) as f64;
    let age_days = age_ms / 86_400_000.0;
    let decay = (-age_days / 7.0).exp();
    (meta.importance * decay as f32).max(0.0)
}

/// 关键词匹配分：content 命中 +1 / 词，tags 命中 +0.5 / 词
fn keyword_score(meta: &MemoryMeta, query_lower: &str) -> f32 {
    if query_lower.is_empty() {
        return 0.0;
    }
    let content_lower = meta.content.to_lowercase();
    let tags_lower = meta.tags.to_lowercase();
    let mut score = 0.0f32;
    for token in query_lower.split_whitespace() {
        if content_lower.contains(token) {
            score += 1.0;
        }
        if tags_lower.contains(token) {
            score += 0.5;
        }
    }
    score
}

/// 余弦相似度
fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    if a.is_empty() || a.len() != b.len() {
        return 0.0;
    }
    let mut dot = 0.0f32;
    let mut na = 0.0f32;
    let mut nb = 0.0f32;
    for i in 0..a.len() {
        dot += a[i] * b[i];
        na += a[i] * a[i];
        nb += b[i] * b[i];
    }
    if na == 0.0 || nb == 0.0 {
        return 0.0;
    }
    dot / (na.sqrt() * nb.sqrt())
}

/// bigram 字符 Jaccard 相似度（去重判定）
fn jaccard_bigram(a: &str, b: &str) -> f32 {
    if a.is_empty() && b.is_empty() {
        return 1.0;
    }
    if a.is_empty() || b.is_empty() {
        return 0.0;
    }
    let bigrams = |s: &str| -> HashSet<String> {
        let chars: Vec<char> = s.chars().collect();
        if chars.len() < 2 {
            return HashSet::new();
        }
        chars.windows(2).map(|w| w.iter().collect::<String>()).collect()
    };
    let ga = bigrams(a);
    let gb = bigrams(b);
    if ga.is_empty() && gb.is_empty() {
        return 1.0;
    }
    let inter = ga.intersection(&gb).count();
    let union = ga.union(&gb).count();
    if union == 0 {
        0.0
    } else {
        inter as f32 / union as f32
    }
}

/// 当前毫秒时间戳
fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockStore {
        memories: std::sync::Mutex<Vec<MemoryMeta>>,
        last_activity_at: i64,
        last_consolidated_at: i64,
    }

    impl MockStore {
        fn new(memories: Vec<MemoryMeta>) -> Self {
            Self {
                memories: std::sync::Mutex::new(memories),
                last_activity_at: 0,
                last_consolidated_at: 0,
            }
        }
        fn with_activity(memories: Vec<MemoryMeta>, last_activity_at: i64, last_consolidated_at: i64) -> Self {
            Self {
                memories: std::sync::Mutex::new(memories),
                last_activity_at,
                last_consolidated_at,
            }
        }
    }

    impl MemoryStore for MockStore {
        fn list_memories(&self, _scope_json: String) -> String {
            serde_json::to_string(&*self.memories.lock().unwrap()).unwrap_or_default()
        }
        fn insert_memory(&self, meta_json: String) -> String {
            let mut m: MemoryMeta = serde_json::from_str(&meta_json).unwrap();
            m.id = format!("new-{}", self.memories.lock().unwrap().len() + 1);
            self.memories.lock().unwrap().push(m.clone());
            m.id
        }
        fn update_memory(&self, _meta_json: String) -> bool {
            true
        }
        fn delete_memory(&self, id: String) -> bool {
            self.memories.lock().unwrap().retain(|m| m.id != id);
            true
        }
        fn get_memory_content(&self, _id: String) -> Option<String> {
            None
        }
        fn get_activity_timestamps(&self, _companion_id: Option<i64>) -> String {
            serde_json::json!({
                "last_activity_at": self.last_activity_at,
                "last_consolidated_at": self.last_consolidated_at,
            })
            .to_string()
        }
        fn set_last_consolidated_at(&self, _now: i64, _companion_id: Option<i64>) -> bool {
            // 通过内部可变不可直接改字段，这里用记忆锁写哨兵，测试仅验证返回值
            true
        }
        fn embed_text(&self, _text: String) -> Option<String> {
            None
        }
    }

    fn meta(id: &str, content: &str, mtype: &str, importance: f32, observed_at: i64) -> MemoryMeta {
        MemoryMeta {
            id: id.to_string(),
            content: content.to_string(),
            memory_type: mtype.to_string(),
            scope: "COMPANION".to_string(),
            source_id: 1,
            importance,
            confidence: 1.0,
            access_count: 1,
            observed_at,
            last_accessed_at: observed_at,
            expires_at: None,
            tags: String::new(),
            embedding: None,
        }
    }

    fn now() -> i64 {
        now_ms()
    }

    #[test]
    fn select_ranks_by_keyword() {
        let now = now();
        let store = MockStore::new(vec![
            meta("m1", "用户喜欢喝奶茶", "PREFERENCE", 0.8, now - 1000),
            meta("m2", "用户是软件工程师", "SEMANTIC", 0.7, now - 1000),
        ]);
        let selector = MemorySelector::new(Arc::new(store));
        let ctxs = selector.select("奶茶".to_string(), r#"{"scope":"COMPANION","source_id":1}"#.to_string(), 5);
        assert_eq!(ctxs.len(), 2);
        assert_eq!(ctxs[0].id, "m1"); // 关键词命中排第一
    }

    #[test]
    fn select_empty_query_uses_decay() {
        let now = now();
        let store = MockStore::new(vec![
            meta("old", "很久以前的事实", "SEMANTIC", 0.9, now - 20 * 86_400_000),
            meta("recent", "最近的事实", "SEMANTIC", 0.5, now - 1000),
        ]);
        let selector = MemorySelector::new(Arc::new(store));
        let ctxs = selector.select(String::new(), r#"{"scope":"COMPANION","source_id":1}"#.to_string(), 5);
        assert_eq!(ctxs.len(), 2);
        assert_eq!(ctxs[0].id, "recent"); // 时间衰减让新记忆优先
    }

    #[test]
    fn select_uses_embedding_similarity() {
        let now = now();
        let mut m1 = meta("m1", "喜欢宠物猫", "PREFERENCE", 0.5, now - 1000);
        m1.embedding = Some(vec![1.0, 0.0, 0.0]);
        let m2 = meta("m2", "喜欢编程", "PREFERENCE", 0.5, now - 1000);
        let store = MockStore::new(vec![m1, m2]);
        let selector = MemorySelector::new(Arc::new(store));
        // 用 mock 不提供 query embedding，语义分跳过；验证 build 不 panic
        let ctxs = selector.select("猫".to_string(), r#"{"scope":"COMPANION","source_id":1}"#.to_string(), 5);
        assert!(!ctxs.is_empty());
    }

    #[test]
    fn should_consolidate_boundaries() {
        let now = now();
        // 场景1：空闲 >1h 且 距上次整理 >30min → true
        let store = MockStore::with_activity(vec![], now - 2 * 3_600_000, now - 2 * 1_800_000);
        let selector = MemorySelector::new(Arc::new(store));
        assert!(selector.should_consolidate(Some(1)));

        // 场景2：空闲 <1h（刚对话过）→ false
        let store = MockStore::with_activity(vec![], now - 30 * 60_000, now - 2 * 1_800_000);
        let selector = MemorySelector::new(Arc::new(store));
        assert!(!selector.should_consolidate(Some(1)));

        // 场景3：空闲 >1h 但 30min 内刚整理过 → false
        let store = MockStore::with_activity(vec![], now - 2 * 3_600_000, now - 10 * 60_000);
        let selector = MemorySelector::new(Arc::new(store));
        assert!(!selector.should_consolidate(Some(1)));

        // 场景4：无整理记录（冷启动）→ 视为刚整理 → false
        let store = MockStore::with_activity(vec![], now - 2 * 3_600_000, 0);
        let selector = MemorySelector::new(Arc::new(store));
        assert!(!selector.should_consolidate(Some(1)));

        // 场景5：完全无记录（0,0）→ 不触发
        let store = MockStore::with_activity(vec![], 0, 0);
        let selector = MemorySelector::new(Arc::new(store));
        assert!(!selector.should_consolidate(Some(1)));
    }

    #[test]
    fn run_consolidation_merges_working_and_dedups() {
        let now = now();
        let store = MockStore::new(vec![
            meta("w1", "用户说喜欢辣的食物", "WORKING", 0.5, now - 60_000),
            meta("w2", "用户今晚吃了火锅", "WORKING", 0.5, now - 30_000),
            meta("s1", "用户是软件工程师", "SEMANTIC", 0.9, now - 1000),
            meta("s2", "用户是软件工程师", "SEMANTIC", 0.5, now - 2000), // 与 s1 重复
            meta("stale", "过时喜好", "PREFERENCE", 0.1, now - 100 * 86_400_000), // 低重要性长期未访问
        ]);
        let selector = MemorySelector::new(Arc::new(store));
        let result = selector.run_consolidation(Some(1));
        let v: serde_json::Value = serde_json::from_str(&result).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["consolidated"].as_u64().unwrap(), 1); // WORKING 合并为 1 条 EPISODIC
        assert_eq!(v["deduped"].as_u64().unwrap(), 1); // s1/s2 去重
        assert_eq!(v["forgotten"].as_u64().unwrap(), 1); // stale 遗忘
    }

    #[test]
    fn memory_tool_definitions_registered() {
        let selector = MemorySelector::new(Arc::new(MockStore::new(vec![])));
        let defs = selector.memory_tool_definitions();
        assert_eq!(defs.len(), 3);
        assert!(defs.iter().all(|d| d.category == ToolCategory::Memory));
        assert!(defs.iter().any(|d| d.name == "recall_memory"));
        assert!(defs.iter().any(|d| d.name == "save_memory"));
        assert!(defs.iter().any(|d| d.name == "consolidate_memory"));
    }

    #[test]
    fn execute_memory_tool_recall_and_save() {
        let now = now();
        let store = MockStore::new(vec![meta("m1", "用户喜欢奶茶", "PREFERENCE", 0.8, now - 1000)]);
        let selector = MemorySelector::new(Arc::new(store));

        // recall
        let out = selector.execute_memory_tool(
            "recall_memory".to_string(),
            r#"{"query":"奶茶","limit":3}"#.to_string(),
            r#"{"companion_id":1,"group_id":null}"#.to_string(),
        );
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], true);
        assert_eq!(v["memories"].as_array().unwrap().len(), 1);

        // save
        let out = selector.execute_memory_tool(
            "save_memory".to_string(),
            r#"{"content":"用户养了一只猫","type":"SEMANTIC","importance":0.7}"#.to_string(),
            r#"{"companion_id":1,"group_id":null}"#.to_string(),
        );
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["ok"], true);
        assert!(!v["id"].as_str().unwrap().is_empty());
    }

    #[test]
    fn build_system_context_assembles_prompt() {
        let selector = MemorySelector::new(Arc::new(MockStore::new(vec![])));
        let ctxs = vec![MemoryContext {
            id: "m1".to_string(),
            content: "用户喜欢喝奶茶".to_string(),
            memory_type: "PREFERENCE".to_string(),
            scope: "COMPANION".to_string(),
            source_id: 1,
            observed_at: now(),
            importance: 0.8,
            score: 0.9,
        }];
        let prompt = selector.build_system_context(ctxs);
        assert!(prompt.contains("相关记忆"));
        assert!(prompt.contains("用户喜欢喝奶茶"));
        assert!(prompt.contains("记忆使用说明"));

        assert!(selector.build_system_context(vec![]).is_empty());
    }

    #[test]
    fn memory_skill_context_describes_tools() {
        let skill = memory_skill_context();
        assert!(skill.contains("recall_memory"));
        assert!(skill.contains("save_memory"));
        assert!(skill.contains("consolidate_memory"));
    }
}
