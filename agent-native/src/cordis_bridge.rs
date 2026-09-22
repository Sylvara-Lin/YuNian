// cordis_bridge.rs — 核心插件底座（方案 A：不重复造轮子）
//
// 直接使用 dshbox/cordis-rs（Cordis 4.0.1 的 Rust 移植，DeepSeek Harness 官方生态，
// 零第三方依赖）+ cordis-loader（②：cordis.yml 语义的 Rust 原生蓝图装载：条目树、
// patch 代数、group 级联禁用）作为 Rust 决策核心的插件宿主：
// - Context / Service 注入（provide_service_arc / provide，依赖纪元：依赖到达自动激活、
//   依赖消失自动卸载，替代手写 fail-fast 服务表）
// - 事件总线（ctx.on / ctx.emit）
// - Loader 蓝图装载：插件按名称注册为工厂，默认蓝图（内存 Document）驱动启用/禁用/分组
//
// 本模块职责边界：
// 1. 预置框架服务（turn_stats / core_tools / prompt_fragments / memory_selector / skill_selector）；
// 2. 内置核心插件（turn-observer / core-tools / core-prompt-fragments / turn-consolidation /
//    core-heartbeat 示范条目），经 cordis-loader 按蓝图启动；
// 3. 失败策略：宿主初始化失败不影响 Agent 回合主流程（仅 eprintln 告警一次）。

use cordis::{
    Context, CordisError, EffectHandle, ErrorCode, Event, EventValue, FiberState, Inject,
    PluginHandle, PluginOutput, Result as CordisResult, Service, plugin_sync,
};
use cordis_loader::{Document, EntryOptions, Loader, LoaderConfig, Node, PluginRegistry};
use crate::agent::{ToolCategory, ToolDefinition};
use crate::memory_selector::MemorySelector;
use crate::prompt_orchestrator::PromptFragment;
use crate::skill_selector::SkillSelector;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

/// 框架服务：回合统计（Service trait 契约，NAME = "turn_stats"）。
#[derive(Default)]
pub struct TurnStats {
    pub turns_started: AtomicU64,
    pub turns_completed: AtomicU64,
    /// turn-consolidation 插件检出的「整理时机已到」次数（仅计数，不触发副作用）。
    pub consolidations_due: AtomicU64,
    /// 分作用域统计（Scope ①）：scope 标签 → (started, completed)。
    /// 事件参数首项携带 scope（如 "companion:42" / "group:7" / "global"）。
    pub scope_counts: Mutex<std::collections::HashMap<String, (u64, u64)>>,
}

impl Service for TurnStats {
    const NAME: &'static str = "turn_stats";
}

/// 核心插件工具条目：定义 + 纯 Rust 执行器（execute_tool 第 4 级查找来源）。
#[derive(Clone)]
pub struct CoreToolEntry {
    pub definition: ToolDefinition,
    pub executor: Arc<dyn Fn(&str) -> String + Send + Sync>,
}

/// 框架服务：核心插件工具注册表（Service trait 契约，NAME = "core_tools"）。
#[derive(Default)]
pub struct CoreTools {
    pub(crate) tools: Mutex<Vec<CoreToolEntry>>,
}

impl Service for CoreTools {
    const NAME: &'static str = "core_tools";
}

/// 框架服务：核心插件提示词片段（追加到编排器 system prompt 之后，NAME = "prompt_fragments"）。
#[derive(Default)]
pub struct PromptFragments {
    pub(crate) fragments: Mutex<Vec<PromptFragment>>,
}

impl Service for PromptFragments {
    const NAME: &'static str = "prompt_fragments";
}

/// 核心选择器句柄集（由 AgentRuntime 从编排器透传；None = 无对应选择器）。
#[derive(Default)]
pub struct CoreSelectorSet {
    pub memory: Option<Arc<MemorySelector>>,
    pub skill: Option<Arc<SkillSelector>>,
}

/// 内置核心插件句柄工厂（cordis-loader 注册表：名称 → 工厂，每次解析产出独立句柄）。

fn turn_observer_handle() -> PluginHandle {
    plugin_sync::<Node, _>("turn-observer", Inject::new(["turn_stats"]), |ctx, _config| {
        let stats = ctx.require::<TurnStats>("turn_stats")?;
        let s1 = stats.clone();
        let s2 = stats.clone();
        ctx.on("agent.turn.start", move |ev: Event| {
            s1.turns_started.fetch_add(1, Ordering::Relaxed);
            // Scope ①：事件参数首项为 scope 标签，按作用域记账
            let scope = ev.args().first()
                .and_then(|v| v.as_any().downcast_ref::<String>())
                .cloned()
                .unwrap_or_else(|| "global".to_string());
            s1.scope_counts.lock().unwrap().entry(scope).and_modify(|(a, _)| *a += 1).or_insert((1, 0));
            Ok::<Option<EventValue>, _>(None)
        })?;
        ctx.on("agent.turn.end", move |ev: Event| {
            s2.turns_completed.fetch_add(1, Ordering::Relaxed);
            let scope = ev.args().first()
                .and_then(|v| v.as_any().downcast_ref::<String>())
                .cloned()
                .unwrap_or_else(|| "global".to_string());
            s2.scope_counts.lock().unwrap().entry(scope).and_modify(|(_, b)| *b += 1).or_insert((0, 1));
            Ok(None)
        })?;
        Ok(PluginOutput::infallible(|| {
            eprintln!("[lianyu_agent] cordis plugin unloaded: turn-observer");
        }))
    })
}

fn core_tools_handle() -> PluginHandle {
    plugin_sync::<Node, _>("core-tools", Inject::new(["core_tools", "turn_stats"]), |ctx, _config| {
        let registry = ctx.require::<CoreTools>("core_tools")?;
        let stats_ref = ctx.require::<TurnStats>("turn_stats")?;
        let executor_stats = stats_ref.clone();
        let entry = CoreToolEntry {
            definition: ToolDefinition {
                name: "core_status".to_string(),
                description: "查询 Agent 核心插件运行时状态（回合统计）。调试/自省用，不影响对话。".to_string(),
                parameters_json: r#"{"type":"object","properties":{},"additionalProperties":false}"#.to_string(),
                category: ToolCategory::General,
                toolsets: vec![],
                available: true,
            },
            executor: Arc::new(move |_args: &str| {
                format!("{{\"turns_started\":{},\"turns_completed\":{}}}",
                    executor_stats.turns_started.load(Ordering::Relaxed),
                    executor_stats.turns_completed.load(Ordering::Relaxed),
                )
            }),
        };
        registry.tools.lock().unwrap().push(entry);
        // 卸载清理（Cordis「卸载不留鸡毛」）
        Ok(PluginOutput::infallible(move || {
            registry.tools.lock().unwrap().clear();
        }))
    })
}

fn prompt_fragments_handle() -> PluginHandle {
    plugin_sync::<Node, _>("core-prompt-fragments", Inject::new(["prompt_fragments"]), |ctx, _config| {
        let store = ctx.require::<PromptFragments>("prompt_fragments")?;
        store.fragments.lock().unwrap().push(PromptFragment {
            layer: 2,
            id: "cordis.core_behavior".to_string(),
            source: "cordis".to_string(),
            lifetime: "stable".to_string(),
            content: "（核心插件）回复保持简洁自然；执行工具后如无必要不再重复描述工具结果。".to_string(),
            role: None,
        });
        Ok(PluginOutput::infallible(move || {
            store.fragments.lock().unwrap().clear();
        }))
    })
}

fn turn_consolidation_handle() -> PluginHandle {
    plugin_sync::<Node, _>("turn-consolidation", Inject::new(["memory_selector", "turn_stats"]), |ctx, _config| {
        let mem = ctx.require::<Arc<MemorySelector>>("memory_selector")?;
        // provide_service_arc 以**内层类型**存储：require::<TurnStats>（返回 Arc<TurnStats>）
        let stats = ctx.require::<TurnStats>("turn_stats")?;
        let mem_end = mem.clone();
        let stats_end = stats.clone();
        ctx.on("agent.turn.end", move |_ev: Event| {
            let turns = stats_end.turns_completed.load(Ordering::Relaxed);
            if turns > 0 && turns % 5 == 0 && mem_end.should_consolidate(None) {
                stats_end.consolidations_due.fetch_add(1, Ordering::Relaxed);
                eprintln!("[lianyu_agent] cordis plugin turn-consolidation: 记忆整理时机已到 (turns={turns})");
            }
            Ok::<Option<EventValue>, _>(None)
        })?;
        Ok(PluginOutput::none())
    })
}

/// 示范条目插件（group 级联禁用测试用）：无行为，仅证明装载/级联。
fn core_heartbeat_handle() -> PluginHandle {
    plugin_sync::<Node, _>("core-heartbeat", Inject::default(), |_ctx, _config| Ok(PluginOutput::none()))
}

/// 默认核心插件蓝图（cordis.yml 语义的内存文档；②）。
/// 4 个内置插件默认启用；observability-demo 组默认禁用（级联示范）。
fn default_blueprint() -> Document {
    let heartbeat_child = EntryOptions::new("core-heartbeat").with_id("hb");
    Document::with_entries(vec![
        EntryOptions::new("turn-observer").with_id("turn-observer"),
        EntryOptions::new("core-tools").with_id("core-tools"),
        EntryOptions::new("core-prompt-fragments").with_id("core-prompt-fragments"),
        EntryOptions::new("turn-consolidation").with_id("turn-consolidation"),
        EntryOptions::new("group")
            .with_id("observability-demo")
            .with_disabled(true) // 级联：整组禁用 → hb 不启动
            .with_group(vec![heartbeat_child]),
    ])
}

/// 核心插件宿主（每个 AgentRuntime 一个，懒创建；失败不致命）。
pub struct CorePluginHost {
    pub(crate) ctx: Context,
    pub(crate) stats: Arc<TurnStats>,
    pub(crate) tools: Arc<CoreTools>,
    pub(crate) fragments: Arc<PromptFragments>,
    /// turn_stats 服务的提供句柄（依赖纪元：dispose → 观察者 Pending → 重新 provide → Active；
    /// 非测试构建暂未消费，保留供后续 FFI/管理入口）
    #[allow(dead_code)]
    pub(crate) provider: EffectHandle,
    #[allow(dead_code)]
    pub(crate) tools_provider: EffectHandle,
    #[allow(dead_code)]
    pub(crate) frag_provider: EffectHandle,
    /// cordis-loader 蓝图装载器（②：条目树 + fiber 状态机 + group 级联）
    pub(crate) loader: Loader,
}

impl CorePluginHost {
    /// 创建宿主：预置框架服务 + 按默认蓝图装载内置核心插件（依赖纪元自动激活）。
    /// [selectors] 提供记忆/技能选择器句柄（可选）：存在时 turn-consolidation 插件激活，
    /// 缺失时保持 Pending（依赖纪元语义）。
    pub fn new(selectors: CoreSelectorSet) -> CordisResult<Arc<Self>> {
        let ctx = Context::new();

        // 1. 框架服务
        let stats = Arc::new(TurnStats::default());
        let provider = ctx.provide_service_arc(stats.clone())?;
        let tools = Arc::new(CoreTools::default());
        let tools_provider = ctx.provide_service_arc(tools.clone())?;
        let fragments = Arc::new(PromptFragments::default());
        let frag_provider = ctx.provide_service_arc(fragments.clone())?;
        if let Some(mem) = &selectors.memory {
            ctx.provide("memory_selector", mem.clone())?;
        }
        if let Some(skill) = &selectors.skill {
            ctx.provide("skill_selector", skill.clone())?;
        }

        // 2. 插件注册表（cordis-loader：名称 → 工厂；group 内建已预注册）
        let mut registry = PluginRegistry::new();
        registry.register("turn-observer", turn_observer_handle);
        registry.register("core-tools", core_tools_handle);
        registry.register("core-prompt-fragments", prompt_fragments_handle);
        registry.register("turn-consolidation", turn_consolidation_handle);
        registry.register("core-heartbeat", core_heartbeat_handle);

        // 3. 蓝图装载（内存文档，无文件 IO；② cordis.yml 语义）
        let loader = Loader::open(
            &ctx,
            LoaderConfig::new("embedded.core.yml")
                .with_registry(registry)
                .with_document(default_blueprint()),
        )
        .map_err(|e| CordisError::with_message(ErrorCode::Plugin, e.to_string()))?;

        Ok(Arc::new(Self {
            ctx,
            stats,
            tools,
            fragments,
            provider,
            tools_provider,
            frag_provider,
            loader,
        }))
    }

    /// 回合开始事件（幂等，失败忽略）；scope 标签随事件参数下发（Scope ①）。
    pub fn emit_turn_start(&self, scope: &str) {
        let _ = self.ctx.emit("agent.turn.start", [EventValue::new(scope.to_string())]);
    }

    /// 回合结束事件（幂等，失败忽略）；scope 标签随事件参数下发（Scope ①）。
    pub fn emit_turn_end(&self, scope: &str) {
        let _ = self.ctx.emit("agent.turn.end", [EventValue::new(scope.to_string())]);
    }

    /// 核心插件工具定义列表（execute_tool 第 4 级查找来源；按注册序）。
    pub fn core_tool_definitions(&self) -> Vec<ToolDefinition> {
        self.tools.tools.lock().unwrap().iter().map(|e| e.definition.clone()).collect()
    }

    /// 按名取核心插件工具执行器（None = 未注册）。
    pub fn core_tool_executor(&self, name: &str) -> Option<Arc<dyn Fn(&str) -> String + Send + Sync>> {
        self.tools.tools.lock().unwrap().iter()
            .find(|e| e.definition.name == name)
            .map(|e| e.executor.clone())
    }

    /// 核心插件提示词片段列表（追加到编排器 system prompt 之后）。
    pub fn prompt_fragments(&self) -> Vec<PromptFragment> {
        self.fragments.fragments.lock().unwrap().clone()
    }

    /// 条目 fiber 状态（未启动/未找到返回 None）。
    pub fn entry_state(&self, id: &str) -> Option<FiberState> {
        self.loader.tree().resolve(id).and_then(|e| e.fiber()).map(|f| f.state())
    }

    /// JSON 快照（审计/调试用）。
    pub fn snapshot_json(&self) -> String {
        let tool_names: Vec<String> = self.core_tool_definitions().iter().map(|t| t.name.clone()).collect();
        let fragment_ids: Vec<String> = self.prompt_fragments().iter().map(|f| f.id.clone()).collect();
        let entries: Vec<serde_json::Value> = self.loader.tree().entries().iter()
            .map(|e| serde_json::json!({
                "id": e.id(),
                "state": format!("{:?}", e.fiber().map(|f| f.state()).unwrap_or(FiberState::Disposed)),
            }))
            .collect();
        let scopes: serde_json::Value = serde_json::json!(self.stats.scope_counts.lock().unwrap().clone());
        serde_json::json!({
            "turns_started": self.stats.turns_started.load(Ordering::Relaxed),
            "turns_completed": self.stats.turns_completed.load(Ordering::Relaxed),
            "consolidations_due": self.stats.consolidations_due.load(Ordering::Relaxed),
            "scopes": scopes,
            "core_tools": tool_names,
            "prompt_fragments": fragment_ids,
            "entries": entries,
        })
        .to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::memory_selector::MemoryStore;
    use std::sync::atomic::Ordering as AOrdering;

    /// 事件计数：两次完整回合 → started=2, completed=2（observer 经 loader 启动，共享根事件总线）
    #[test]
    fn observer_counts_turn_events() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");
        assert_eq!(host.entry_state("turn-observer"), Some(FiberState::Active));
        host.emit_turn_start("global");
        host.emit_turn_end("global");
        host.emit_turn_start("global");
        host.emit_turn_end("global");
        assert_eq!(host.stats.turns_started.load(AOrdering::Relaxed), 2);
        assert_eq!(host.stats.turns_completed.load(AOrdering::Relaxed), 2);
        let snap = host.snapshot_json();
        assert!(snap.contains("\"turns_started\":2"), "snapshot: {snap}");
    }

    /// 依赖纪元（Cordis Inject 语义）：服务消失 → 观察者自动卸载（Pending）；
    /// 服务回归 → 自动重载（Active）且监听器恢复工作。
    #[test]
    fn dependency_epoch_unloads_and_reloads_observer() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");

        host.provider.dispose().expect("dispose provider");
        assert_eq!(host.entry_state("turn-observer"), Some(FiberState::Pending));

        let stats2 = Arc::new(TurnStats::default());
        let _new_provider = host.ctx.provide_service_arc(stats2.clone()).expect("re-provide");
        assert_eq!(host.entry_state("turn-observer"), Some(FiberState::Active));

        host.emit_turn_start("global");
        host.emit_turn_end("global");
        assert_eq!(stats2.turns_started.load(AOrdering::Relaxed), 1);
        assert_eq!(stats2.turns_completed.load(AOrdering::Relaxed), 1);
    }

    /// core-tools 插件：core_status 工具注册 + 执行器返回回合统计 JSON
    #[test]
    fn core_tools_plugin_registers_and_executes() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");
        assert_eq!(host.entry_state("core-tools"), Some(FiberState::Active));

        let defs = host.core_tool_definitions();
        assert_eq!(defs.len(), 1, "core-tools 插件应注册 1 个工具");
        assert_eq!(defs[0].name, "core_status");

        host.emit_turn_start("global");
        let out = host.core_tool_executor("core_status")
            .expect("executor registered")("{}");
        assert!(out.contains("\"turns_started\":1"), "executor 输出: {out}");
        assert!(host.core_tool_executor("no_such_tool").is_none());
    }

    /// core-tools 依赖纪元：服务消失 → 插件自动卸载且工具清理；回归 → 重载注入**新**服务实例
    #[test]
    fn core_tools_epoch_unloads_and_reloads() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");

        host.tools_provider.dispose().expect("dispose tools provider");
        assert_eq!(host.entry_state("core-tools"), Some(FiberState::Pending));
        assert!(host.core_tool_definitions().is_empty(), "卸载应清空工具注册");

        let t2 = Arc::new(CoreTools::default());
        let _new_provider = host.ctx.provide_service_arc(t2.clone()).expect("re-provide");
        assert_eq!(host.entry_state("core-tools"), Some(FiberState::Active));
        let re_registered = t2.tools.lock().unwrap().clone();
        assert_eq!(re_registered.len(), 1, "重载后应重新注册 core_status");
        assert_eq!(re_registered[0].definition.name, "core_status");
    }

    /// core-prompt-fragments 插件：片段注册 + 快照可见
    #[test]
    fn prompt_fragments_plugin_injects() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");
        assert_eq!(host.entry_state("core-prompt-fragments"), Some(FiberState::Active));
        let frags = host.prompt_fragments();
        assert_eq!(frags.len(), 1, "core-prompt-fragments 应注册 1 个片段");
        assert_eq!(frags[0].id, "cordis.core_behavior");
        assert!(host.snapshot_json().contains("cordis.core_behavior"));
    }

    /// 测试用 MemoryStore 实现（无副作用；时间戳满足 should_consolidate 判定）
    struct MockMemoryStore {
        #[allow(dead_code)]
        consolidate: bool,
    }
    impl MemoryStore for MockMemoryStore {
        fn list_memories(&self, _scope_json: String) -> String { "[]".to_string() }
        fn insert_memory(&self, _meta_json: String) -> String { String::new() }
        fn update_memory(&self, _meta_json: String) -> bool { true }
        fn delete_memory(&self, _id: String) -> bool { true }
        fn get_memory_content(&self, _id: String) -> Option<String> { None }
        fn get_activity_timestamps(&self, _companion_id: Option<i64>) -> String {
            // 活动时间久远 + 上次整理更久远 → should_consolidate 判定为真
            // （last_consolidated_at <= 0 会被视为「刚整理过」，必须给远期时间戳）
            let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as i64;
            format!("{{\"last_activity_at\":{},\"last_consolidated_at\":{}}}", now - 100_000_000, now - 200_000_000)
        }
        fn set_last_consolidated_at(&self, _now: i64, _companion_id: Option<i64>) -> bool { true }
        fn embed_text(&self, _text: String) -> Option<String> { None }
    }

    /// turn-consolidation 插件：提供记忆选择器 → 激活；每 5 回合检出整理时机
    #[test]
    fn turn_consolidation_plugin_activates_and_counts() {
        let store = Arc::new(MockMemoryStore { consolidate: true });
        let memory = MemorySelector::new(store);
        let host = CorePluginHost::new(CoreSelectorSet { memory: Some(memory), ..Default::default() })
            .expect("host init");
        assert_eq!(host.entry_state("turn-consolidation"), Some(FiberState::Active));

        for _ in 0..4 { host.emit_turn_end("global"); }
        assert_eq!(host.stats.consolidations_due.load(AOrdering::Relaxed), 0);
        host.emit_turn_end("global");
        assert_eq!(host.stats.consolidations_due.load(AOrdering::Relaxed), 1);
        for _ in 0..5 { host.emit_turn_end("global"); }
        assert_eq!(host.stats.consolidations_due.load(AOrdering::Relaxed), 2);
        assert!(host.snapshot_json().contains("consolidations_due"));
    }

    /// 依赖纪元：宿主未提供记忆选择器 → 插件保持 Pending；之后提供 → 自动激活
    #[test]
    fn turn_consolidation_plugin_pending_without_selector_then_activates() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");
        assert_eq!(host.entry_state("turn-consolidation"), Some(FiberState::Pending));

        let store = Arc::new(MockMemoryStore { consolidate: true });
        let memory = MemorySelector::new(store);
        let _provider = host.ctx.provide("memory_selector", memory).expect("provide");
        assert_eq!(host.entry_state("turn-consolidation"), Some(FiberState::Active));
        host.emit_turn_end("global");
        assert_eq!(host.stats.consolidations_due.load(AOrdering::Relaxed), 0, "turns=1 不触发");
    }

    /// Scope ①：事件携带 scope 标签，observer 按作用域记账
    #[test]
    fn scope_tagged_events_record_per_scope_stats() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");

        // 两个作用域交错回合
        host.emit_turn_start("companion:42");
        host.emit_turn_end("companion:42");
        host.emit_turn_start("companion:42");
        host.emit_turn_end("companion:42");
        host.emit_turn_start("group:7");
        host.emit_turn_end("group:7");

        let scopes = host.stats.scope_counts.lock().unwrap().clone();
        assert_eq!(scopes.get("companion:42"), Some(&(2, 2)));
        assert_eq!(scopes.get("group:7"), Some(&(1, 1)));
        assert!(scopes.get("global").is_none(), "未使用 global 标签不应产生记录");
        // 全局计数照常（Scope 不破坏原有语义）
        assert_eq!(host.stats.turns_started.load(AOrdering::Relaxed), 3);
        // 快照含 scopes 明细
        let snap = host.snapshot_json();
        assert!(snap.contains("companion:42"), "快照应含作用域明细: {snap}");
    }

    /// ② cordis-group 级联禁用：默认蓝图禁用 observability-demo 组 → hb 不启动；
    /// recompose 启用组 → 子条目自动启动（Loader::recompose HMR 语义）。
    #[test]
    fn loader_group_cascading_disable_and_recompose() {
        let host = CorePluginHost::new(CoreSelectorSet::default()).expect("host init");

        // 默认：组禁用 → hb 无 fiber（子条目按复合 id 解析：observability-demo:hb）
        let hb_entry = host.loader.tree().resolve("observability-demo:hb").expect("hb entry exists");
        assert!(hb_entry.fiber().is_none(), "禁用的组不应启动子条目");
        assert_eq!(host.entry_state("observability-demo"), None, "禁用条目无 fiber");

        // recompose：启用组 → hb 自动启动
        let heartbeat_child = EntryOptions::new("core-heartbeat").with_id("hb");
        let doc2 = Document::with_entries(vec![
            EntryOptions::new("turn-observer").with_id("turn-observer"),
            EntryOptions::new("core-tools").with_id("core-tools"),
            EntryOptions::new("core-prompt-fragments").with_id("core-prompt-fragments"),
            EntryOptions::new("turn-consolidation").with_id("turn-consolidation"),
            EntryOptions::new("group")
                .with_id("observability-demo")
                .with_group(vec![heartbeat_child]), // 未禁用
        ]);
        host.loader.recompose(doc2).expect("recompose");
        let hb_fiber = hb_entry.fiber().expect("hb started after enabling group");
        hb_fiber.try_wait().expect("hb active");
        assert_eq!(hb_fiber.state(), FiberState::Active);
        assert_eq!(host.entry_state("observability-demo"), Some(FiberState::Active));
        assert!(host.snapshot_json().contains("\"hb\""), "快照应含 hb 条目");
    }
}
