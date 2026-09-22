// skill_selector.rs — Agent Skills 选择层（Rust 决策）
//
// 职责：
// - SkillStore：UniFFI foreign trait，Kotlin 实现（Room 索引 + 文件系统正文，纯 IO）
// - SkillSelector：根据用户 query 选择技能、读取正文、拼装 system prompt（决策在 Rust）
// - 混合存储：索引查得快（Room），正文放文件不拖垮 DB；Kotlin 侧 SkillStoreImpl 负责一致性协议
//
// 与既有架构的关系：
// - 不触碰 agent.rs 的 AgentRunner / AgentToolRegistry
// - 不触碰 Kotlin 现有 AiToolLoopRunner / ToolRegistry / MemoryDao
// - 纯增量：新增 trait + selector，供后续 AgentRunner 在 system prompt 阶段调用

use std::sync::Arc;

use serde::{Deserialize, Serialize};

/// 技能元数据（SkillStore.list_skills 返回 JSON 数组反序列化目标）
#[derive(Clone, Debug, Serialize, Deserialize, uniffi::Record)]
pub struct SkillMeta {
    pub skill_id: String,
    pub name: String,
    pub description: String,
    /// "CHAT" / "MEMORY" / "COMMERCE" / "CUSTOM"
    pub category: String,
    pub tags: String,
    /// 技能依赖/引用的工具名列表（汇合点：索引按 available_tools 过滤显示；
    /// 空 = 无工具依赖，恒显示）。对应 Hermes `conditions: tools: [...]`。
    #[serde(default)]
    pub tools: Vec<String>,
    pub enabled: bool,
    pub companion_id: Option<i64>,
    pub version: u32,
    pub updated_at: i64,
}

/// 选中的技能上下文（含正文，供拼入 system prompt）
#[derive(Clone, Debug, uniffi::Record)]
pub struct SkillContext {
    pub skill_id: String,
    pub name: String,
    pub content: String,
    pub category: String,
}

/// 技能菜单项（渐进式披露 L1：仅名称 + 一句话简介，不含正文）
///
/// 与 [SkillContext] 的区别：只带极低 token 的发现信息，供「技能目录」注入；
/// 完整正文由 load_skill 工具按需读取（L2）。
#[derive(Clone, Debug, uniffi::Record)]
pub struct SkillMenuEntry {
    pub skill_id: String,
    pub name: String,
    pub description: String,
    pub category: String,
    /// 技能依赖的工具名（供调用方判断当前工具集是否满足；空 = 无依赖）
    pub tools: Vec<String>,
}

/// 技能存储回调（Kotlin 实现：Room 索引 + 文件系统正文，纯 IO 无决策）
///
/// 契约（一致性协议）：
/// - list_skills：返回 JSON 数组 `[SkillMeta, ...]`（含禁用项，由 Rust 过滤）
/// - get_skill_content：正文不存在 / 已禁用 / SHA-256 校验失败 → None
/// - save_skill：Kotlin 侧先写文件（content.md + meta.json）再写 Room 索引，返回新版本号
/// - delete_skill：先删索引行再删目录，返回是否成功
#[uniffi::export(with_foreign)]
pub trait SkillStore: Send + Sync {
    /// 返回索引 JSON 数组：[SkillMeta, ...]
    fn list_skills(&self, companion_id: Option<i64>) -> String;
    /// 返回技能正文，不存在 / 禁用 / 校验失败 → None
    fn get_skill_content(&self, skill_id: String) -> Option<String>;
    /// 保存技能（写文件 + 写索引），返回新 version；失败返回 -1
    fn save_skill(&self, meta_json: String, content: String) -> i32;
    /// 删除技能（删行 + 删目录），返回是否成功
    fn delete_skill(&self, skill_id: String) -> bool;
    /// 关键字搜索索引，返回 JSON 数组：[SkillMeta, ...]
    fn search_skills(&self, query: String, limit: u32) -> String;
}

/// 技能选择器：根据用户 query 选择技能并读取正文（决策在 Rust）
#[derive(uniffi::Object)]
pub struct SkillSelector {
    store: Arc<dyn SkillStore>,
}

#[uniffi::export]
impl SkillSelector {
    #[uniffi::constructor]
    pub fn new(store: Arc<dyn SkillStore>) -> Arc<SkillSelector> {
        Arc::new(SkillSelector { store })
    }

    /// 选择技能：索引召回 → 评分排序 → 读取正文（校验失败/禁用自动剔除）
    pub fn select(&self, query: String, companion_id: Option<i64>, limit: u32) -> Vec<SkillContext> {
        let list_json = self.store.list_skills(companion_id);
        let metas: Vec<SkillMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let q = query.trim().to_lowercase();

        let mut ranked: Vec<(i32, SkillMeta)> = metas
            .into_iter()
            .filter(|m| m.enabled)
            .map(|m| (rank_skill(&m, &q), m))
            .collect();
        // 分数降序，同分按更新时间倒序（新技能优先）
        ranked.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.updated_at.cmp(&a.1.updated_at)));

        ranked
            .into_iter()
            .take(limit.max(1) as usize)
            .filter_map(|(score, m)| {
                if score <= 0 {
                    return None;
                }
                match self.store.get_skill_content(m.skill_id.clone()) {
                    Some(content) if !content.trim().is_empty() => Some(SkillContext {
                        skill_id: m.skill_id,
                        name: m.name,
                        content,
                        category: m.category,
                    }),
                    _ => None,
                }
            })
            .collect()
    }

    /// 将选中的技能拼成 system prompt 片段（空列表返回空串，调用方自行忽略）
    pub fn build_system_context(&self, contexts: Vec<SkillContext>) -> String {
        if contexts.is_empty() {
            return String::new();
        }
        let mut out = String::from("[可用技能]\n");
        for (i, ctx) in contexts.iter().enumerate() {
            out.push_str(&format!("### 技能{}：{}\n{}\n\n", i + 1, ctx.name, ctx.content));
        }
        out.push_str(
            "[技能使用说明] 当用户请求与上述技能相关时，按技能内容执行；与技能无关时忽略本段。\n",
        );
        out
    }

    /// 渐进式披露 L1——技能目录（仅 name + description，不含正文）。
    ///
    /// 返回全部已启用技能（不按 query 过滤）：让 Agent 知道自己有哪些能力可用。
    /// 排序：更新时间倒序（新技能靠前）。上限由调用方（options.skill_limit）控制。
    ///
    /// 汇合点（对齐 Hermes「技能索引按 available_tools 过滤显示」）：
    /// - `available_tools` 非空时，技能声明依赖的工具（meta.tools）若全部不在
    ///   当前可用工具集中，则该技能不出现在目录（模型不知道 = 无法用）；
    /// - 技能声明空（无工具依赖，如纯知识型技能）恒显示；
    /// - `available_tools` 为空 = 不过滤（向后兼容）。
    pub fn discover(
        &self,
        companion_id: Option<i64>,
        limit: u32,
        available_tools: Vec<String>,
    ) -> Vec<SkillMenuEntry> {
        let list_json = self.store.list_skills(companion_id);
        let metas: Vec<SkillMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let mut enabled: Vec<SkillMeta> = metas
            .into_iter()
            .filter(|m| m.enabled)
            .filter(|m| {
                if available_tools.is_empty() {
                    return true;
                }
                if m.tools.is_empty() {
                    return true; // 无工具依赖，恒显示
                }
                // 技能依赖的工具至少有一个可用才显示
                m.tools.iter().any(|t| available_tools.iter().any(|a| a == t))
            })
            .collect();
        enabled.sort_by(|a, b| b.updated_at.cmp(&a.updated_at));
        enabled
            .into_iter()
            .take(limit.max(1) as usize)
            .map(|m| SkillMenuEntry {
                skill_id: m.skill_id,
                name: m.name,
                description: m.description,
                category: m.category,
                tools: m.tools,
            })
            .collect()
    }

    /// 渐进式披露 L2——按需加载单个技能完整正文（供 load_skill 工具回调）。
    ///
    /// 正文不存在 / 已禁用 / SHA-256 校验失败 → None（返回「技能不存在」提示给模型）。
    pub fn load_content(&self, skill_id: String, companion_id: Option<i64>) -> Option<String> {
        let list_json = self.store.list_skills(companion_id);
        let metas: Vec<SkillMeta> = serde_json::from_str(&list_json).unwrap_or_default();
        let enabled = metas
            .iter()
            .any(|m| m.skill_id == skill_id && m.enabled);
        if !enabled {
            return None;
        }
        self.store.get_skill_content(skill_id)
    }

    /// 渐进式披露 L1——把技能目录拼成 system prompt 片段（仅菜名 + 简介，token 极低）。
    ///
    /// 引导 Agent：需要某个技能的完整操作说明时，调用 load_skill 工具按需加载。
    pub fn build_menu_context(&self, menu: Vec<SkillMenuEntry>) -> String {
        if menu.is_empty() {
            return String::new();
        }
        let mut out = String::from("[可用技能目录]\n");
        for (i, entry) in menu.iter().enumerate() {
            out.push_str(&format!(
                "{}. {}（{}）：{}\n",
                i + 1,
                entry.skill_id,
                entry.name,
                entry.description,
            ));
        }
        out.push_str(
            "[技能使用说明] 以上为技能目录（仅简介）。当用户请求与某技能描述的场景匹配时，你必须先调用 load_skill 工具加载该技能的完整操作说明，再按其执行；禁止在未加载技能的情况下直接输出纯文本完成技能行为。与当前对话无关的技能不要加载。\n",
        );
        out
    }
}

/// 技能-查询匹配评分（双向关键词匹配；后续可替换为 embedding 召回，接口不变）
///
/// 方向一（主）：用户 query 包含技能的 name / description / tags 关键词
/// （如用户说"讲个故事" → tags 含"故事"命中）；
/// 方向二（辅）：技能元数据包含整个 query（短查询，如用户只发"气泡"）。
fn rank_skill(meta: &SkillMeta, query_lower: &str) -> i32 {
    if query_lower.is_empty() {
        return 1; // 无查询：全量返回（低优先级，按时间倒序）
    }
    let name = meta.name.to_lowercase();
    let desc = meta.description.to_lowercase();
    let tags = meta.tags.to_lowercase();

    let mut score = 0;
    // 方向一：query 包含技能关键词（长句消息命中技能的关键描述词）
    if query_lower.contains(&name) {
        score += 100;
    }
    if query_lower.contains(&desc) {
        score += 30;
    }
    for tag in meta.tags.split(',') {
        let t = tag.trim().to_lowercase();
        if !t.is_empty() && query_lower.contains(&t) {
            score += 10;
        }
    }
    // 方向二：技能元数据包含整个 query（短查询反向命中）
    if name.contains(query_lower) {
        score += 50;
    }
    if desc.contains(query_lower) {
        score += 20;
    }
    if tags.contains(query_lower) {
        score += 8;
    }
    score
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockStore(Vec<SkillMeta>);

    impl SkillStore for MockStore {
        fn list_skills(&self, _companion_id: Option<i64>) -> String {
            serde_json::to_string(&self.0).unwrap()
        }
        fn get_skill_content(&self, skill_id: String) -> Option<String> {
            if skill_id == "missing" {
                None
            } else {
                Some(format!("技能正文：{skill_id}"))
            }
        }
        fn save_skill(&self, _meta_json: String, _content: String) -> i32 {
            1
        }
        fn delete_skill(&self, _skill_id: String) -> bool {
            true
        }
        fn search_skills(&self, _query: String, _limit: u32) -> String {
            "[]".to_string()
        }
    }

    fn meta(id: &str, name: &str, desc: &str, tags: &str, enabled: bool) -> SkillMeta {
        SkillMeta {
            skill_id: id.to_string(),
            name: name.to_string(),
            description: desc.to_string(),
            category: "CUSTOM".to_string(),
            tags: tags.to_string(),
            tools: Vec::new(),
            enabled,
            companion_id: None,
            version: 1,
            updated_at: 1,
        }
    }

    /// 带工具依赖声明的技能（汇合点测试用）
    fn meta_with_tools(id: &str, tools: &[&str], enabled: bool) -> SkillMeta {
        SkillMeta {
            skill_id: id.to_string(),
            name: id.to_string(),
            description: "描述".to_string(),
            category: "CUSTOM".to_string(),
            tags: String::new(),
            tools: tools.iter().map(|s| s.to_string()).collect(),
            enabled,
            companion_id: None,
            version: 1,
            updated_at: 1,
        }
    }

    #[test]
    fn select_filters_disabled_and_missing() {
        let store = MockStore(vec![
            meta("s1", "抽卡", "二次元抽卡系统", "game,抽卡", true),
            meta("s2", "禁用的技能", "不应出现", "", false),
            meta("missing", "正文缺失", "不应出现", "", true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        let ctxs = selector.select("抽卡".to_string(), None, 10);
        assert_eq!(ctxs.len(), 1);
        assert_eq!(ctxs[0].skill_id, "s1");
        assert_eq!(ctxs[0].content, "技能正文：s1");
    }

    #[test]
    fn select_matches_query_contains_tag_word() {
        // 用户长句消息包含技能 tags 关键词 → 应命中（方向一：query.contains(tag)）
        let store = MockStore(vec![meta(
            "s1",
            "内置聊天工具调用协议",
            "气泡连发、分段输出、表情包工具的调用协议",
            "气泡,连发,分段,故事,哄我",
            true,
        )]);
        let selector = SkillSelector::new(Arc::new(store));
        let ctxs = selector.select("除非你讲个故事哄我".to_string(), None, 10);
        assert_eq!(ctxs.len(), 1, "query 含 tags 关键词应命中");
        assert_eq!(ctxs[0].skill_id, "s1");
    }

    #[test]
    fn select_matches_short_query_against_meta() {
        // 短查询反向命中（方向二：meta.contains(query)），如用户只发"气泡"
        let store = MockStore(vec![meta(
            "s1",
            "内置聊天工具调用协议",
            "气泡连发协议",
            "气泡,连发",
            true,
        )]);
        let selector = SkillSelector::new(Arc::new(store));
        let ctxs = selector.select("气泡".to_string(), None, 10);
        assert_eq!(ctxs.len(), 1, "短查询应命中技能元数据");
        assert_eq!(ctxs[0].skill_id, "s1");
    }

    #[test]
    fn select_empty_query_returns_all_enabled() {
        let store = MockStore(vec![
            meta("s1", "抽卡", "二次元抽卡系统", "game", true),
            meta("s2", "捏脸", "角色捏脸系统", "avatar", true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        let ctxs = selector.select(String::new(), None, 10);
        assert_eq!(ctxs.len(), 2);
    }

    #[test]
    fn build_system_context_assembles_prompt() {
        let ctxs = vec![SkillContext {
            skill_id: "s1".to_string(),
            name: "抽卡".to_string(),
            content: "抽卡规则：十连保底。".to_string(),
            category: "CUSTOM".to_string(),
        }];
        let selector = SkillSelector::new(Arc::new(MockStore(vec![])));
        let prompt = selector.build_system_context(ctxs);
        assert!(prompt.contains("可用技能"));
        assert!(prompt.contains("抽卡规则"));
        assert!(prompt.contains("技能使用说明"));
    }

    #[test]
    fn build_system_context_empty() {
        let selector = SkillSelector::new(Arc::new(MockStore(vec![])));
        assert!(selector.build_system_context(vec![]).is_empty());
    }

    // ── 渐进式披露：L1 菜单 discover / L2 按需 load_content ──

    #[test]
    fn discover_returns_menu_without_content() {
        let store = MockStore(vec![
            meta("s1", "抽卡", "二次元抽卡系统", "game", true),
            meta("s2", "禁用的技能", "不应出现", "", false),
            meta("s3", "捏脸", "角色捏脸系统", "avatar", true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        let menu = selector.discover(None, 10, Vec::new());
        // 只返回已启用技能，且不读正文（菜单项无 content 字段）
        assert_eq!(menu.len(), 2);
        let s1 = menu.iter().find(|m| m.skill_id == "s1").unwrap();
        assert_eq!(s1.name, "抽卡");
        assert_eq!(s1.description, "二次元抽卡系统");
    }

    #[test]
    fn discover_respects_limit() {
        let store = MockStore(vec![
            meta("s1", "技能一", "描述一", "", true),
            meta("s2", "技能二", "描述二", "", true),
            meta("s3", "技能三", "描述三", "", true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        assert_eq!(selector.discover(None, 2, Vec::new()).len(), 2);
    }

    #[test]
    fn discover_filters_by_available_tools() {
        // 汇合点：技能声明依赖的工具不在当前可用工具集 → 不出现在目录
        let store = MockStore(vec![
            meta_with_tools("s_tool", &["emit_bubble"], true),
            meta_with_tools("s_other", &["recall_memory"], true),
            meta_with_tools("s_plain", &[], true), // 无依赖恒显示
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        // 当前只暴露 emit_bubble → s_other 被过滤
        let menu = selector.discover(None, 10, vec!["emit_bubble".to_string()]);
        let ids: Vec<&str> = menu.iter().map(|m| m.skill_id.as_str()).collect();
        assert!(ids.contains(&"s_tool"), "依赖可用工具的技能应显示");
        assert!(ids.contains(&"s_plain"), "无依赖技能恒显示");
        assert!(!ids.contains(&"s_other"), "依赖不可用工具的技能应被过滤");
    }

    #[test]
    fn discover_empty_available_tools_no_filter() {
        // available_tools 为空 = 不过滤（向后兼容）
        let store = MockStore(vec![
            meta_with_tools("s_tool", &["emit_bubble"], true),
            meta_with_tools("s_other", &["recall_memory"], true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        let menu = selector.discover(None, 10, Vec::new());
        assert_eq!(menu.len(), 2);
        // 菜单项带 tools 元数据
        let s_tool = menu.iter().find(|m| m.skill_id == "s_tool").unwrap();
        assert_eq!(s_tool.tools, vec!["emit_bubble".to_string()]);
    }

    #[test]
    fn load_content_reads_full_body_for_enabled() {
        let store = MockStore(vec![
            meta("s1", "抽卡", "二次元抽卡系统", "game", true),
            meta("disabled", "禁用技能", "不应加载", "", false),
            meta("missing", "正文缺失", "不应加载", "", true),
        ]);
        let selector = SkillSelector::new(Arc::new(store));
        assert_eq!(
            selector.load_content("s1".to_string(), None),
            Some("技能正文：s1".to_string())
        );
        // 禁用 → None
        assert_eq!(selector.load_content("disabled".to_string(), None), None);
        // 正文缺失 → None
        assert_eq!(selector.load_content("missing".to_string(), None), None);
        // 不存在 → None
        assert_eq!(selector.load_content("nope".to_string(), None), None);
    }

    #[test]
    fn build_menu_context_lists_entries_with_load_hint() {
        let menu = vec![
            SkillMenuEntry {
                skill_id: "builtin_chat_tool_protocol".to_string(),
                name: "内置聊天工具调用协议".to_string(),
                description: "气泡连发、分段输出、表情包工具的调用协议".to_string(),
                category: "CHAT".to_string(),
                tools: vec!["emit_bubble".to_string()],
            },
            SkillMenuEntry {
                skill_id: "s2".to_string(),
                name: "抽卡".to_string(),
                description: "二次元抽卡系统".to_string(),
                category: "CUSTOM".to_string(),
                tools: Vec::new(),
            },
        ];
        let selector = SkillSelector::new(Arc::new(MockStore(vec![])));
        let prompt = selector.build_menu_context(menu);
        assert!(prompt.contains("可用技能目录"));
        assert!(prompt.contains("builtin_chat_tool_protocol"));
        assert!(prompt.contains("气泡连发、分段输出、表情包工具的调用协议"));
        assert!(prompt.contains("load_skill"));
        assert!(prompt.contains("必须先调用 load_skill"), "菜单应含强制触发引导");
        assert!(prompt.contains("禁止在未加载技能的情况下直接输出纯文本"), "菜单应含禁止纯文本规则");
        assert!(!prompt.contains("技能正文"), "菜单不应含完整正文");
    }

    #[test]
    fn build_menu_context_empty() {
        let selector = SkillSelector::new(Arc::new(MockStore(vec![])));
        assert!(selector.build_menu_context(vec![]).is_empty());
    }
}
