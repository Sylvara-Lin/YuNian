// lorebook.rs — 世界书（Lorebook）注入引擎（聊天陪伴）。
//
// 复用开源规范而非自研规则：
// - 格式语义 = SillyTavern World Info（社区标准）：keys 激活 / constant / scan_depth /
//   recursive_scanning / token_budget / insertion_order / position（before_char/after_char）；
// - chara_card（Apache-2.0）用于世界书 JSON 格式校验（lint）；
// - 轻量可读模型按社区 Spec 字段定义（兼容 Tavern 的 entries-map 与数组两种容器）。
//
// 目标：技能/设定以世界书条目形式存在，激活/排序/裁剪全部由引擎完成——
// 不再需要为每个技能手写注入规则。

use crate::prompt_orchestrator::PromptFragment;
use serde::Deserialize;
use std::collections::HashMap;

/// 世界书条目（SillyTavern World Info Spec 字段；v2+v3 兼容子集）。
#[derive(Clone, Debug, Deserialize, Default)]
pub struct LorebookEntry {
    /// 条目 id（Tavern 用数字 uid；v3 允许字符串）
    #[serde(default)]
    pub id: Option<serde_json::Value>,
    /// 激活键：任一命中上下文即激活（默认大小写不敏感）
    #[serde(default)]
    pub keys: Vec<String>,
    /// 次要键（Spec：与 keys 同时匹配才激活；本版按“任一命中 keys 或 secondary_keys”宽松处理）
    #[serde(default)]
    pub secondary_keys: Vec<String>,
    /// 注入内容（原文；装饰器/CBS 占位符求值为后续增量）
    #[serde(default)]
    pub content: String,
    /// 启用开关（false 时 MUST NOT 匹配）
    #[serde(default = "default_true")]
    pub enabled: bool,
    /// 注入顺序（小者先注入；同时作为预算裁剪依据）
    #[serde(default)]
    pub insertion_order: u64,
    /// 恒激活（不可被预算裁剪）
    #[serde(default)]
    pub constant: Option<bool>,
    /// 大小写敏感匹配
    #[serde(default)]
    pub case_sensitive: Option<bool>,
    /// v3：keys 按正则解释
    #[serde(default)]
    pub use_regex: Option<bool>,
    /// 优先级（高者先保留；与 insertion_order 共同决定裁剪）
    #[serde(default)]
    pub priority: Option<i64>,
    /// 注入位置（Q2 扩展，对齐本地 `InjectionPosition` 5 值）：
    /// `before_char` / `after_char` / `top_of_chat` / `bottom_of_chat` / `at_depth`。
    /// 缺省 = 技能层默认（历史行为：并入 system 文本尾部）。
    #[serde(default)]
    pub position: Option<String>,
    /// 注入深度（仅 `at_depth` 有意义）：从最新消息往前数第 `depth` 条之前注入。
    /// Q2 新增；`#[serde(default)]` 保证旧世界书 JSON 继续可解析。
    #[serde(default)]
    pub depth: Option<u64>,
    /// 条目级注入角色（`system` / `user` / `assistant`）。
    /// 仅 `top_of_chat` / `bottom_of_chat` / `at_depth` 读取；
    /// 缺省 = `user`（对齐本地 `buildRoleMessages` 的 `else` 分支）。
    /// Q2 新增。
    #[serde(default)]
    pub role: Option<String>,
    /// 条目级扫描深度（仅最近 N 条消息参与激活判定）；缺省回落顶层 `scan_depth`。
    /// Q2 附加项（R14 无损化：本地每条目独立 `take(scanDepth)`）。
    #[serde(default)]
    pub scan_depth: Option<u64>,
    /// 应用扩展（透传不解析）
    #[serde(default)]
    pub extensions: Option<serde_json::Value>,
}

fn default_true() -> bool { true }

/// 世界书容器：兼容 Tavern 的 entries-map（{uid: entry}）与数组两种格式。
#[derive(Clone, Debug, Deserialize)]
#[serde(untagged)]
pub enum Entries {
    List(Vec<LorebookEntry>),
    Map(HashMap<String, LorebookEntry>),
}

impl Default for Entries {
    fn default() -> Self {
        Entries::List(Vec::new())
    }
}

impl Entries {
    fn iter(&self) -> Vec<LorebookEntry> {
        match self {
            Entries::List(list) => list.clone(),
            Entries::Map(map) => map
                .iter()
                .map(|(k, e)| {
                    let mut entry = e.clone();
                    // map 键作为 id 回填（Tavern uid）
                    if entry.id.is_none() {
                        entry.id = Some(serde_json::Value::String(k.clone()));
                    }
                    entry
                })
                .collect(),
        }
    }
}

/// 世界书（SillyTavern World Info 顶层结构）。
#[derive(Clone, Debug, Deserialize)]
pub struct Lorebook {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    /// 只扫描最近 N 条消息（缺省 = 全量）
    #[serde(default)]
    pub scan_depth: Option<u64>,
    /// 注入 token 预算（超预算按优先级/顺序裁剪；constant 不可裁剪）
    #[serde(default)]
    pub token_budget: Option<u64>,
    /// 命中内容可被再扫描（链式激活）
    #[serde(default)]
    pub recursive_scanning: Option<bool>,
    #[serde(default)]
    pub entries: Entries,
}

impl Lorebook {
    /// 解析世界书 JSON（社区格式；entries 支持 Tavern map 与 v3 数组）。
    /// 数组格式先经 chara_card 校验（格式 lint，Apache-2.0 开源库）；
    /// Tavern 导出的事实标准是 map（chara_card 只认数组），map 格式直接读入轻量模型。
    pub fn parse(json: &str) -> Result<Self, String> {
        let value: serde_json::Value =
            serde_json::from_str(json).map_err(|e| format!("世界书解析失败: {e}"))?;
        if let Some(entries) = value.get("entries") {
            if entries.is_array() {
                serde_json::from_str::<chara_card::raw::Lorebook>(json)
                    .map_err(|e| format!("世界书格式校验失败: {e}"))?;
            }
        }
        serde_json::from_str(json).map_err(|e| format!("世界书解析失败: {e}"))
    }
}

/// 注入位置（Q2 扩展）。与本地 `InjectionPosition` 5 值一一对应。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InjectionPosition {
    /// `before_char` → 合并进 system 文本**前段**
    BeforeChar,
    /// `after_char` → 合并进 system 文本**后段**
    AfterChar,
    /// `top_of_chat` → 消息流：首条非 system 消息**前**
    TopOfChat,
    /// `bottom_of_chat` → 消息流：末条消息**前**
    BottomOfChat,
    /// `at_depth` → 消息流：倒数第 `depth` 条消息**前**
    AtDepth,
}

impl InjectionPosition {
    /// 解析 ST JSON `position` 字段（大小写不敏感；未知值 → `AtDepth`，对齐本地 `else` 分支）。
    pub fn parse(raw: Option<&str>) -> Self {
        match raw.map(|s| s.trim().to_ascii_lowercase()).as_deref() {
            Some("before_char") => InjectionPosition::BeforeChar,
            Some("after_char") => InjectionPosition::AfterChar,
            Some("top_of_chat") => InjectionPosition::TopOfChat,
            Some("bottom_of_chat") => InjectionPosition::BottomOfChat,
            _ => InjectionPosition::AtDepth,
        }
    }

    /// 是否走 `messages[]` 消息流注入（false = 合并进 system 文本）。
    pub fn is_message_stream(self) -> bool {
        matches!(
            self,
            InjectionPosition::TopOfChat
                | InjectionPosition::BottomOfChat
                | InjectionPosition::AtDepth
        )
    }

    /// 分层编号（保留给 orchestrator 摘要展示；注入路径不读取）。
    pub fn layer(self) -> u8 {
        match self {
            InjectionPosition::BeforeChar => 1,
            InjectionPosition::AfterChar => 2,
            _ => 4,
        }
    }
}

/// 注入命中（排序与裁剪后的结果）。
#[derive(Clone, Debug)]
pub struct LorebookHit {
    pub id: String,
    pub content: String,
    /// 注入位置（Q2：取代原先仅 3 值的 `layer` 语义）
    pub position: InjectionPosition,
    /// 注入深度（仅 `AtDepth` 有意义；缺省 4，对齐本地 `?: 4`）
    pub depth: u32,
    /// 条目标注的注入角色（`top/bottom/at_depth` 生效）
    pub role: Option<String>,
    /// 分层编号（= `position.layer()`，兼容旧调用）
    pub layer: u8,
    pub order: u64,
    pub constant: bool,
    pub chars: usize,
}

/// 世界书注入引擎（纯函数，无 I/O）。
pub struct LorebookInjector;

impl LorebookInjector {
    /// 扫描上下文并返回命中条目（激活 → 递归 → 排序 → 预算裁剪）。
    /// [texts] 为按时间序的最近消息文本。
    pub fn scan(lorebook: &Lorebook, texts: &[String]) -> Vec<LorebookHit> {
        // 1. scan_depth：只扫描最近 N 条
        let scan_texts: &[String] = match lorebook.scan_depth {
            Some(d) if (d as usize) < texts.len() => &texts[texts.len() - d as usize..],
            _ => texts,
        };

        // 2. 激活扫描（含 recursive_scanning 链式）
        let mut matched: Vec<LorebookEntry> = Vec::new();
        let mut haystack = scan_texts.join("\n");
        let recursive = lorebook.recursive_scanning.unwrap_or(false);
        let mut round = 0;
        const MAX_RECURSIVE_ROUNDS: usize = 5;
        loop {
            let mut new_hits: Vec<LorebookEntry> = Vec::new();
            for entry in lorebook.entries.iter() {
                if !entry.enabled {
                    continue;
                }
                if entry.constant.unwrap_or(false) {
                    if !matched.iter().any(|m| m.insertion_order == entry.insertion_order && m.content == entry.content) {
                        new_hits.push(entry);
                    }
                    continue;
                }
                if entry_matches(&entry, &haystack) && !matched.iter().any(|m| m.content == entry.content) {
                    new_hits.push(entry);
                }
            }
            if new_hits.is_empty() {
                break;
            }
            matched.extend(new_hits.iter().cloned());
            if !recursive {
                break;
            }
            round += 1;
            if round >= MAX_RECURSIVE_ROUNDS {
                break;
            }
            // 命中内容并入扫描文本（链式激活）
            let addition = new_hits
                .iter()
                .map(|e| e.content.as_str())
                .collect::<Vec<_>>()
                .join("\n");
            haystack.push_str("\n");
            haystack.push_str(&addition);
        }

        // 3. 排序：insertion_order 升序
        matched.sort_by_key(|e| e.insertion_order);

        // 4. token_budget 裁剪（优先级保留；constant 不可裁剪）
        let budget = lorebook.token_budget;
        let hits: Vec<LorebookHit> = matched
            .iter()
            .map(|e| {
                let position = InjectionPosition::parse(e.position.as_deref());
                LorebookHit {
                    id: entry_id(e),
                    content: e.content.clone(),
                    position,
                    depth: e.depth.unwrap_or(4).clamp(1, u32::MAX as u64) as u32,
                    role: e.role.clone(),
                    layer: position.layer(),
                    order: e.insertion_order,
                    constant: e.constant.unwrap_or(false),
                    chars: e.content.chars().count(),
                }
            })
            .collect();
        if let Some(budget_tokens) = budget {
            // token 估算：中英混合 ≈ 字符数 / 2
            let mut used: u64 = 0;
            let mut kept: Vec<LorebookHit> = Vec::new();
            // 恒激活条目先保留
            for h in hits.iter().filter(|h| h.constant) {
                kept.push(h.clone());
                used += (h.chars as u64) / 2 + 1;
            }
            for h in hits.iter().filter(|h| !h.constant) {
                let cost = (h.chars as u64) / 2 + 1;
                if used + cost > budget_tokens {
                    continue; // 裁剪（已按 insertion_order 排序：小者优先保留）
                }
                kept.push(h.clone());
                used += cost;
            }
            return kept;
        }
        hits
    }

    /// 命中 → PromptFragment（接入 L0-L5 分层；`position` 语义透传到 `role`）。
    ///
    /// ⚠️ Q2：`layer` 在真实注入路径（`agent.rs`）**不被读取**；实际生效的是
    /// `PromptFragment.role` + 片段在 `messages[]` 中的落点。`layer` 仅供审计摘要。
    pub fn to_fragments(hits: &[LorebookHit]) -> Vec<PromptFragment> {
        hits.iter()
            .map(|h| PromptFragment {
                layer: h.layer,
                id: format!("lorebook.{}", h.id),
                source: "lorebook".to_string(),
                lifetime: "external".to_string(),
                content: h.content.clone(),
                role: Some(normalize_role(h.role.as_deref())),
            })
            .collect()
    }
}

/// 角色规范化：返回规范化的三值角色（`system` / `user` / `assistant`）。
///
/// ⚠️ 注意与**消息注入折叠**的区别：`build_role_messages` 会把 `system`
/// 折入 `user`（对齐本地 `buildRoleMessages` 的 `else` 分支）。
/// 本函数返回三值，仅用于 `PromptFragment.role` 的信息透传。
pub fn normalize_role(raw: Option<&str>) -> String {
    match raw.map(|s| s.trim().to_ascii_lowercase()).as_deref() {
        Some("system") => "system".to_string(),
        Some("assistant") => "assistant".to_string(),
        _ => "user".to_string(),
    }
}

// ── Q2：注入计划（system 文本段 vs messages[] 消息流）──

/// 单组注入字符上限（对齐本地 `MAX_INJECTION_CHARS`）
pub const MAX_INJECTION_CHARS: usize = 8000;
/// 单次注入总字符上限（对齐本地 `MAX_TOTAL_INJECTION_CHARS`）
pub const MAX_TOTAL_INJECTION_CHARS: usize = 20000;

/// 消息流插入锚点（对齐本地 `PromptInjectionTransformer` 的三种落点）
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InjectionAnchor {
    /// 首条非 system 消息**之前**
    TopOfChat,
    /// 末条消息**之前**
    BottomOfChat,
    /// 倒数第 `depth` 条消息**之前**（`depth >= 1`）
    AtDepth(u32),
}

/// 一条待插入消息（`role` 恒为 `user` 或 `assistant`）
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MessageInjection {
    pub anchor: InjectionAnchor,
    pub role: String,
    pub content: String,
}

/// 世界书注入计划：
/// `sys_before` / `sys_after` 合并进 system 文本，`injections` 按序落进 `messages[]`。
#[derive(Clone, Debug, Default)]
pub struct InjectionPlan {
    /// 合并进 system 文本**前段**（`before_char`）
    pub sys_before: String,
    /// 合并进 system 文本**后段**（`after_char`）
    pub sys_after: String,
    /// 消息流插入序列（**必须按此顺序应用**）
    pub injections: Vec<MessageInjection>,
}

impl InjectionPlan {
    pub fn is_empty(&self) -> bool {
        self.sys_before.is_empty() && self.sys_after.is_empty() && self.injections.is_empty()
    }
}

/// 把命中条目编排为注入计划。
///
/// 处理顺序严格对齐本地 `PromptInjectionTransformer`：
/// ① `before_char` / `after_char` → 合并进 system 文本（不产生独立 system 消息）
/// ② `top_of_chat` → 首条非 system 消息之前
/// ③ `bottom_of_chat` → 末条消息之前
/// ④ `at_depth` → 倒数第 `depth` 条之前；按 `depth` **降序**逐组插入
///    （与本地一致：从末尾计数时 size 增长会被自动补偿，不产生索引漂移）
///
/// 每组的 role 折叠规则见 [`build_role_messages`]。
pub fn plan_injections(hits: &[LorebookHit]) -> InjectionPlan {
    let mut plan = InjectionPlan::default();
    let mut total_chars: usize = 0;

    // ① system 文本段
    let before: Vec<&LorebookHit> = hits
        .iter()
        .filter(|h| h.position == InjectionPosition::BeforeChar)
        .collect();
    plan.sys_before = join_group(&before, total_chars);
    total_chars += plan.sys_before.chars().count();
    let after: Vec<&LorebookHit> = hits
        .iter()
        .filter(|h| h.position == InjectionPosition::AfterChar)
        .collect();
    plan.sys_after = join_group(&after, total_chars);
    total_chars += plan.sys_after.chars().count();

    // ② ③ 固定锚点消息流
    for (pos, anchor) in [
        (InjectionPosition::TopOfChat, InjectionAnchor::TopOfChat),
        (
            InjectionPosition::BottomOfChat,
            InjectionAnchor::BottomOfChat,
        ),
    ] {
        let group: Vec<&LorebookHit> = hits.iter().filter(|h| h.position == pos).collect();
        let msgs = build_role_messages(&group, total_chars);
        if msgs.is_empty() {
            continue;
        }
        total_chars += msgs.iter().map(|(_, c)| c.chars().count()).sum::<usize>();
        for (role, content) in msgs {
            plan.injections.push(MessageInjection {
                anchor,
                role,
                content,
            });
        }
    }

    // ④ at_depth：depth 降序（大者先插）
    let mut depths: Vec<u32> = hits
        .iter()
        .filter(|h| h.position == InjectionPosition::AtDepth)
        .map(|h| h.depth)
        .collect();
    depths.sort_unstable();
    depths.dedup();
    depths.reverse();
    for depth in depths {
        let group: Vec<&LorebookHit> = hits
            .iter()
            .filter(|h| h.position == InjectionPosition::AtDepth && h.depth == depth)
            .collect();
        let msgs = build_role_messages(&group, total_chars);
        if msgs.is_empty() {
            continue;
        }
        total_chars += msgs.iter().map(|(_, c)| c.chars().count()).sum::<usize>();
        for (role, content) in msgs {
            plan.injections.push(MessageInjection {
                anchor: InjectionAnchor::AtDepth(depth),
                role,
                content,
            });
        }
    }

    plan
}

/// 拼接单个 system 文本段（`\n` 分隔；受单组 + 总量双上限约束）。
/// 返回空串表示全部条目被裁剪。
fn join_group(entries: &[&LorebookHit], consumed: usize) -> String {
    let mut out = String::new();
    let mut total = 0usize;
    for h in entries {
        let content = h.content.trim();
        if content.is_empty() {
            continue;
        }
        let len = content.chars().count();
        if total + len > MAX_INJECTION_CHARS {
            break;
        }
        if !out.is_empty() {
            out.push('\n');
        }
        out.push_str(content);
        total += len;
    }
    if out.is_empty() || consumed + total > MAX_TOTAL_INJECTION_CHARS {
        return String::new();
    }
    out
}

/// 按 role 折叠为注入消息：`assistant` 一组合并，其余（含 `system` / `user`）归入 `user`。
/// 返回 `[(role, content)]`，顺序恒为 `user` 在前、`assistant` 在后（保证对话顺序自然）。
/// 与本地 `buildRoleMessages` 逐字对齐。
fn build_role_messages(entries: &[&LorebookHit], consumed: usize) -> Vec<(String, String)> {
    let mut user = String::new();
    let mut assistant = String::new();
    let mut total = 0usize;
    for h in entries {
        let content = h.content.trim();
        if content.is_empty() {
            continue;
        }
        let len = content.chars().count();
        if total + len > MAX_INJECTION_CHARS {
            break;
        }
        let slot = if normalize_role(h.role.as_deref()) == "assistant" {
            &mut assistant
        } else {
            &mut user
        };
        if !slot.is_empty() {
            slot.push('\n');
        }
        slot.push_str(content);
        total += len;
    }
    if (user.is_empty() && assistant.is_empty()) || consumed + total > MAX_TOTAL_INJECTION_CHARS {
        return Vec::new();
    }
    let mut out = Vec::new();
    if !user.is_empty() {
        out.push(("user".to_string(), user));
    }
    if !assistant.is_empty() {
        out.push(("assistant".to_string(), assistant));
    }
    out
}

/// 条目激活判定：constant 之外，任一 key 命中上下文（大小写/正则按字段）。
fn entry_matches(entry: &LorebookEntry, haystack: &str) -> bool {
    let use_regex = entry.use_regex.unwrap_or(false);
    let case_sensitive = entry.case_sensitive.unwrap_or(false);
    let all_keys: Vec<&String> = entry.keys.iter().chain(entry.secondary_keys.iter()).collect();
    if all_keys.is_empty() {
        return false;
    }
    let target = if case_sensitive { haystack.to_string() } else { haystack.to_lowercase() };
    for key in all_keys {
        if use_regex {
            // 大小写语义对齐 case_sensitive（默认不敏感 → (?i)）
            let pattern = if case_sensitive {
                key.clone()
            } else {
                format!("(?i:{key})")
            };
            if let Ok(re) = regex::Regex::new(&pattern) {
                if re.is_match(haystack) {
                    return true;
                }
            }
            // 非法正则：Spec 要求视为不匹配
            continue;
        }
        let needle = if case_sensitive { key.clone() } else { key.to_lowercase() };
        if target.contains(&needle) {
            return true;
        }
    }
    false
}

fn entry_id(e: &LorebookEntry) -> String {
    match &e.id {
        Some(serde_json::Value::Number(n)) => n.to_string(),
        Some(serde_json::Value::String(s)) => s.clone(),
        _ => format!("e{}", e.insertion_order),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_lorebook_json() -> &'static str {
        r#"{"name":"测试世界","scan_depth":3,"recursive_scanning":true,"entries":{
        "1":{"keys":["咖啡馆"],"content":"咖啡馆：街角老店，老板是只猫。","insertion_order":10,"enabled":true},
        "2":{"keys":["小猫"],"content":"小猫叫团子，是店宠。","insertion_order":20,"enabled":true,"constant":true},
        "3":{"keys":["团长"],"content":"团长是群管理员。","insertion_order":30,"enabled":true,"position":"before_char"},
        "4":{"keys":["regex-test"],"content":"正则条目","insertion_order":40,"enabled":true,"use_regex":true},
        "5":{"keys":["旧关键词"],"content":"超出扫描深度不该出现","insertion_order":50,"enabled":true}
    }}"#
    }

    #[test]
    fn parses_tavern_map_format_and_validates_via_chara_card() {
        let lb = Lorebook::parse(sample_lorebook_json()).expect("parse ok");
        assert_eq!(lb.scan_depth, Some(3));
        assert!(lb.recursive_scanning.unwrap_or(false));
    }

    #[test]
    fn key_hits_activate_and_scan_depth_limits_context() {
        let lb = Lorebook::parse(sample_lorebook_json()).unwrap();
        // 最近 3 条：含“咖啡馆”和“小猫”；“旧关键词”在更早消息（超出 scan_depth）
        let texts = vec![
            "很久以前提到旧关键词".to_string(), // 最早消息：超出 scan_depth=3
            "更早的无关消息".to_string(),
            "我们去了街角的咖啡馆。".to_string(),
            "小猫团子今天很乖。".to_string(),
        ];
        let hits = LorebookInjector::scan(&lb, &texts);
        let ids: Vec<String> = hits.iter().map(|h| h.id.clone()).collect();
        assert!(ids.contains(&"1".to_string()), "咖啡馆应命中: {ids:?}");
        assert!(ids.contains(&"2".to_string()), "constant 小猫应命中: {ids:?}");
        assert!(!ids.contains(&"5".to_string()), "旧关键词超出 scan_depth: {ids:?}");
    }

    #[test]
    fn regex_activation_and_position_mapping() {
        let lb = Lorebook::parse(sample_lorebook_json()).unwrap();
        let texts = vec!["团长今天聊到 REGEX-TEST 话题".to_string()];
        let hits = LorebookInjector::scan(&lb, &texts);
        let regex_hit = hits.iter().find(|h| h.id == "4").expect("正则命中");
        assert_eq!(regex_hit.position, InjectionPosition::AtDepth, "未标注 position → at_depth");
        assert_eq!(regex_hit.layer, 4);
        let before = hits.iter().find(|h| h.id == "3").expect("团长条目");
        assert_eq!(before.position, InjectionPosition::BeforeChar, "before_char → 系统文本前段");
        assert_eq!(before.layer, 1, "before_char → L1");
    }

    #[test]
    fn q2_position_depth_and_role_are_carried_through() {
        let json = r#"{"entries":{
            "1":{"keys":["A"],"content":"顶","insertion_order":1,"enabled":true,"position":"top_of_chat","role":"assistant"},
            "2":{"keys":["A"],"content":"底","insertion_order":2,"enabled":true,"position":"bottom_of_chat","role":"user"},
            "3":{"keys":["A"],"content":"深","insertion_order":3,"enabled":true,"position":"at_depth","depth":7,"role":"system"}
        }}"#;
        let lb = Lorebook::parse(json).unwrap();
        let hits = LorebookInjector::scan(&lb, &vec!["A".to_string()]);
        let top = hits.iter().find(|h| h.id == "1").expect("top");
        assert_eq!(top.position, InjectionPosition::TopOfChat);
        assert!(top.position.is_message_stream());
        assert_eq!(top.depth, 4, "未标 depth → 默认 4");
        let bottom = hits.iter().find(|h| h.id == "2").expect("bottom");
        assert_eq!(bottom.position, InjectionPosition::BottomOfChat);
        let deep = hits.iter().find(|h| h.id == "3").expect("at_depth");
        assert_eq!(deep.depth, 7, "显式 depth 直填");

        // role 透传到 PromptFragment（规范化后三值）
        let frags = LorebookInjector::to_fragments(&hits);
        let top_frag = frags.iter().find(|f| f.id == "lorebook.1").unwrap();
        assert_eq!(top_frag.role.as_deref(), Some("assistant"));
        let deep_frag = frags.iter().find(|f| f.id == "lorebook.3").unwrap();
        assert_eq!(deep_frag.role.as_deref(), Some("system"));
    }

    #[test]
    fn q2_plan_routes_positions_to_correct_containers() {
        let json = r#"{"entries":{
            "1":{"keys":["A"],"content":"前","insertion_order":1,"enabled":true,"position":"before_char"},
            "2":{"keys":["A"],"content":"后","insertion_order":2,"enabled":true,"position":"after_char"},
            "3":{"keys":["A"],"content":"顶","insertion_order":3,"enabled":true,"position":"top_of_chat","role":"user"},
            "4":{"keys":["A"],"content":"底","insertion_order":4,"enabled":true,"position":"bottom_of_chat","role":"assistant"},
            "5":{"keys":["A"],"content":"深2","insertion_order":5,"enabled":true,"position":"at_depth","depth":2},
            "6":{"keys":["A"],"content":"深5","insertion_order":6,"enabled":true,"position":"at_depth","depth":5}
        }}"#;
        let lb = Lorebook::parse(json).unwrap();
        let hits = LorebookInjector::scan(&lb, &vec!["A".to_string()]);
        let plan = crate::lorebook::plan_injections(&hits);

        assert_eq!(plan.sys_before, "前");
        assert_eq!(plan.sys_after, "后");
        // ② top → ③ bottom → ④ depth 降序（5 先于 2）
        let anchors: Vec<InjectionAnchor> = plan.injections.iter().map(|i| i.anchor).collect();
        assert_eq!(
            anchors,
            vec![
                InjectionAnchor::TopOfChat,
                InjectionAnchor::BottomOfChat,
                InjectionAnchor::AtDepth(5),
                InjectionAnchor::AtDepth(2),
            ],
            "锚点顺序必须是 top → bottom → depth 降序"
        );
        assert_eq!(plan.injections[0].role, "user");
        assert_eq!(plan.injections[1].role, "assistant");
    }

    #[test]
    fn q2_role_folding_merges_same_role_and_pairs_user_first() {
        let json = r#"{"entries":{
            "1":{"keys":["A"],"content":"u1","insertion_order":1,"enabled":true,"position":"top_of_chat","role":"user"},
            "2":{"keys":["A"],"content":"a1","insertion_order":2,"enabled":true,"position":"top_of_chat","role":"assistant"},
            "3":{"keys":["A"],"content":"s1","insertion_order":3,"enabled":true,"position":"top_of_chat","role":"system"},
            "4":{"keys":["A"],"content":"u2","insertion_order":4,"enabled":true,"position":"top_of_chat","role":"user"}
        }}"#;
        let lb = Lorebook::parse(json).unwrap();
        let hits = LorebookInjector::scan(&lb, &vec!["A".to_string()]);
        let plan = crate::lorebook::plan_injections(&hits);
        assert_eq!(plan.injections.len(), 2, "同 role 合并 → 至多 2 条消息");
        // 入参按 insertion_order 升序：u1, a1, s1, u2
        // user 组 = u1, s1, u2（system 折入 user）；assistant 组 = a1
        assert_eq!(plan.injections[0].role, "user");
        assert_eq!(plan.injections[0].content, "u1\ns1\nu2", "同 role 用 \\n 拼接");
        assert_eq!(plan.injections[1].role, "assistant");
        assert_eq!(plan.injections[1].content, "a1");
    }

    #[test]
    fn q2_plan_is_empty_when_only_system_positions_absent() {
        let hits: Vec<crate::lorebook::LorebookHit> = Vec::new();
        let plan = crate::lorebook::plan_injections(&hits);
        assert!(plan.is_empty());
    }

    #[test]
    fn q2_position_parse_is_lenient() {
        assert_eq!(InjectionPosition::parse(Some("BEFORE_CHAR")), InjectionPosition::BeforeChar);
        assert_eq!(InjectionPosition::parse(Some(" top_of_chat ")), InjectionPosition::TopOfChat);
        assert_eq!(InjectionPosition::parse(Some("bottom_of_chat")), InjectionPosition::BottomOfChat);
        assert_eq!(InjectionPosition::parse(None), InjectionPosition::AtDepth);
        assert_eq!(InjectionPosition::parse(Some("未知值")), InjectionPosition::AtDepth);
        assert_eq!(normalize_role(Some("ASSISTANT")), "assistant");
        assert_eq!(normalize_role(None), "user");
        assert_eq!(normalize_role(Some("garbage")), "user");
    }

    #[test]
    fn recursive_scanning_chains_hits() {
        let json = r#"{"recursive_scanning":true,"entries":{"1":{"keys":["A"],"content":"提到B就会想起这个","insertion_order":1,"enabled":true},"2":{"keys":["B"],"content":"B是后续线索","insertion_order":2,"enabled":true}}}"#;
        let lb = Lorebook::parse(json).unwrap();
        let hits = LorebookInjector::scan(&lb, &vec!["先说 A".to_string()]);
        assert_eq!(hits.len(), 2, "B 应被 A 的内容链式激活: {:?}", hits.iter().map(|h| h.id.clone()).collect::<Vec<_>>());
    }

    #[test]
    fn token_budget_trims_low_priority_but_keeps_constant() {
        let json = r#"{"token_budget":20,"entries":{"1":{"keys":["x"],"content":"这是一条非常长的内容用于测试预算裁剪效果是否生效xxxxxxxxxxxx","insertion_order":1,"enabled":true},"2":{"keys":["y"],"content":"短","insertion_order":2,"enabled":true,"constant":true}}}"#;
        let lb = Lorebook::parse(json).unwrap();
        let texts = vec!["x y".to_string()];
        let hits = LorebookInjector::scan(&lb, &texts);
        assert!(hits.iter().any(|h| h.id == "2"), "constant 不可裁剪");
        // 长条目可能被裁剪（预算 20 token ≈ 40 字符）
        let long = hits.iter().find(|h| h.id == "1");
        if let Some(h) = long {
            assert!(h.chars as u64 <= 40, "长条目应被预算约束: chars={}", h.chars);
        }
    }

    #[test]
    fn invalid_json_is_rejected() {
        assert!(Lorebook::parse("{not json").is_err());
        assert!(Lorebook::parse(r#"{"entries":{}}"#).is_ok());
    }

    /// ★ 契约锁定：map 格式必须能承载 5 值 `position` + `role` + `depth` + `scan_depth`。
    /// 数组格式会走 `chara_card` 校验（其 `EntryPosition` 仅 `before_char`/`after_char`），
    /// 因此本地迁移产出的 ST JSON **必须**使用 `entries` 对象形式。
    #[test]
    fn map_format_supports_all_five_positions() {
        let json = r#"{"name":"迁移书","scan_depth":10,"token_budget":10000,"entries":{
            "1":{"keys":["k1"],"secondary_keys":[],"content":"前段","insertion_order":1,"enabled":true,"position":"before_char","extensions":{"_bookId":7,"_bookName":"旧书"}},
            "2":{"keys":["k2"],"secondary_keys":[],"content":"后段","insertion_order":2,"enabled":true,"position":"after_char"},
            "3":{"keys":["k3"],"secondary_keys":[],"content":"顶部","insertion_order":3,"enabled":true,"position":"top_of_chat"},
            "4":{"keys":["k4"],"secondary_keys":[],"content":"底部","insertion_order":4,"enabled":true,"position":"bottom_of_chat"},
            "5":{"keys":["k5"],"secondary_keys":[],"content":"深度","insertion_order":5,"enabled":true,"position":"at_depth","depth":2,"role":"assistant","scan_depth":5}
        }}"#;
        let lb = Lorebook::parse(json).expect("map 格式必须无需 chara_card 校验即可解析");
        let entries: Vec<LorebookEntry> = lb.entries.iter();
        assert_eq!(entries.len(), 5, "5 条条目必须全部保留");

        let by_order = |o: u64| {
            entries
                .iter()
                .find(|e| e.insertion_order == o)
                .unwrap_or_else(|| panic!("insertion_order={o} 丢失"))
        };
        assert_eq!(by_order(1).position.as_deref(), Some("before_char"));
        assert_eq!(by_order(2).position.as_deref(), Some("after_char"));
        assert_eq!(by_order(3).position.as_deref(), Some("top_of_chat"));
        assert_eq!(by_order(4).position.as_deref(), Some("bottom_of_chat"));
        assert_eq!(by_order(5).position.as_deref(), Some("at_depth"));
        assert_eq!(by_order(5).depth, Some(2), "depth 必须透传");
        assert_eq!(by_order(5).role.as_deref(), Some("assistant"), "role 必须透传");
        assert_eq!(by_order(5).scan_depth, Some(5), "条目级 scan_depth 必须透传");
        assert_eq!(lb.token_budget, Some(10000), "token_budget 必须透传");

        // 命中后 `position` 解析必须落在 5 个不同分支上
        let keys: Vec<String> = (1..=5).map(|i| format!("k{i}")).collect();
        let texts = vec![keys.join(" ")];
        let hits = LorebookInjector::scan(&lb, &texts);
        let pos_of = |o: u64| {
            hits.iter()
                .find(|h| h.id == o.to_string())
                .map(|h| h.position)
                .unwrap_or_else(|| panic!("insertion_order={o} 未命中"))
        };
        assert_eq!(pos_of(1), InjectionPosition::BeforeChar);
        assert_eq!(pos_of(2), InjectionPosition::AfterChar);
        assert_eq!(pos_of(3), InjectionPosition::TopOfChat);
        assert_eq!(pos_of(4), InjectionPosition::BottomOfChat);
        assert_eq!(pos_of(5), InjectionPosition::AtDepth);
        assert_eq!(hits.iter().find(|h| h.id == "5").unwrap().depth, 2);
    }
}