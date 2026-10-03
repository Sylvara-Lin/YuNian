package com.yunian.ai.agent

import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.repository.AppMetaStore
import com.yunian.ai.domain.CapabilityGrant
import com.yunian.ai.domain.CapabilityGrantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 授权存储的日志出口（与 [AgentConfirmGuardLog] / `PluginLog` 同一手法）。
 *
 * 存在理由：`SecureLog` 底层是 `android.util.Log`，在纯 JVM 单测里是抛
 * `RuntimeException("Stub!")` 的空壳（本仓库无 Robolectric）。把日志收敛到可替换接口后，
 * 单测可以注入替身、纯 JVM 驱动真实的 [CapabilityGrantStoreImpl]（含坏数据分支）。
 *
 * 可见性说明：本接口是 :core:agent 的实现细节，但**必须 public**——[CapabilityGrantStoreImpl]
 * 的构造函数带本类型的默认参数，Kotlin 禁止 public 构造函数暴露 internal 参数类型。
 * 它不是对外契约的一部分，调用方不需要、也不应该实现它。
 *
 * 行为约定：实现**不得**抛出异常（日志失败不得影响授权语义）。
 */
interface CapabilityGrantStoreLog {
    /** 告警级日志（对齐 `SecureLog.w`）。 */
    fun w(tag: String, message: String)

    /** 错误级日志（对齐 `SecureLog.e`，可带 throwable）。 */
    fun e(tag: String, message: String, throwable: Throwable?)
}

/** 生产实现：转发到 [SecureLog]。 */
internal object SecureLogCapabilityGrantStoreLog : CapabilityGrantStoreLog {
    override fun w(tag: String, message: String) = SecureLog.w(tag, message)

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) SecureLog.e(tag, message) else SecureLog.e(tag, message, throwable)
    }
}

/**
 * [CapabilityGrantStore] 的持久化实现：**一段多行文本存在 KV 单键里**。
 *
 * ## 存储位
 * 走 `AppMetaStore`（core:database 的 `app_meta` KV 表，`getString` / `putString`），
 * 键 = [KEY]（`capability.grants`，沿用 `<模块>.<用途>` 约定）。
 *
 * 为什么不是 Preferences DataStore：**:core:agent 的编译期 classpath 上没有 DataStore**
 * （`core:common` 用的是 `implementation(libs.androidx.datastore.preferences)`，
 * 不向消费者传递；`core:agent` 自身也没有该依赖项）。引入它需要修改
 * `core/agent/build.gradle.kts` 的依赖块，属本任务明令禁止的动作，故改用仓库既有、
 * **零新增依赖**的合法持久化通道：
 * - 不新增 Room 表 / 字段 / 索引（`AppMetaStore` 复用已存在的 `app_meta` 表，
 *   符合 AppDatabase 的「schema 冻结基线：新增功能走 AppMetaStore(KV)」红线）；
 * - 同一模块已有先例：`core:agent` 的 `WorldbookMigrator`、`core:network` 的
 *   `RollingSummaryManager` 都这样持久化。
 *
 * ## 序列化格式
 * 每条决定一行，`|` 分隔三字段（完整定义见 [CapabilityGrantCodec]）：
 *
 * ```
 * <companionId>|<toolName>|0或1
 * *|automation_create|1
 * 42|screen_tap|0
 * ```
 *
 * 第一字段为 `*` 表示**通配**（适用所有伴侣）。
 *
 * ## 解析失败时的行为（硬约束）
 * **丢弃该行，绝不抛异常。** 具体地：
 * - [decisions] 对整段文本逐行解码，坏行被丢弃、好行照常返回，并按丢弃条数记一条告警日志；
 * - 文本整体为 null / 空（从没写过、或被清空）⇒ 返回空列表（= 没有任何显式决定）；
 * - KV **读取失败**（DB 异常等）⇒ 记错误日志后按「没有任何决定」处理，返回空列表；
 * - 解出的记录里工具名为空白的行不会被 [decisionsFor] 命中（等同无决定）；
 * - 旧的三段式（`companionId|channelKey|toolName`）行解析失败 ⇒ 被丢弃 ⇒ 等同无决定。
 *
 * 一句话：**授权表读到的任何异常都只会让它更保守（回到工具默认），不会放松确认门。**
 *
 * ## 写语义
 * [decide] / [clear] 先在 [writeLock] 串行化，再做「读 → 改 → 整体覆盖写」：
 * - [decide] 是**同键覆盖**（同 (companionId, toolName) 只留最新一条）；
 * - [clear] 移除该键（不存在时无副作用）。
 *
 * 编码时工具名为空白 / 含 `|` 的那条决定会被**跳过**（不写脏数据），其余照常写入。
 * 写失败会**抛出**（不静默吞，交由调用方提示），这与 `AppMetaStore.put` 的既定策略一致。
 */
class CapabilityGrantStoreImpl(
    private val metaStore: AppMetaStore,
    private val log: CapabilityGrantStoreLog = SecureLogCapabilityGrantStoreLog,
) : CapabilityGrantStore {

    /** 串行化「读-改-写」，避免并发 decide/clear 互相覆盖。 */
    private val writeLock = Mutex()

    override suspend fun decisions(): List<CapabilityGrant> {
        val raw = readRaw() ?: return emptyList()
        val decoded = CapabilityGrantCodec.decode(raw)
        if (decoded.droppedLineCount > 0) {
            log.w(TAG, "dropped ${decoded.droppedLineCount} malformed line(s); key=$KEY")
        }
        return decoded.decisions
    }

    /**
     * 通配先铺底、该伴侣的显式决定再覆盖：**该伴侣自己的决定优先**。
     *
     * 这样「所有伴侣都允许某工具、但伴侣 42 除外」可以被表达为
     * `*|tool|1` + `42|tool|0`，且不需要任何额外语法。
     */
    override suspend fun decisionsFor(companionId: Long): Map<String, Boolean> {
        val effective = LinkedHashMap<String, Boolean>()
        val all = decisions()
        for (decision in all) {
            if (decision.companionId == null) effective[decision.toolName] = decision.allowed
        }
        for (decision in all) {
            if (decision.companionId == companionId) effective[decision.toolName] = decision.allowed
        }
        return effective
    }

    override suspend fun decide(grant: CapabilityGrant) {
        writeLock.withLock {
            val kept = decisions().filterNot {
                it.companionId == grant.companionId && it.toolName == grant.toolName
            }
            write(kept + grant)
        }
    }

    override suspend fun clear(companionId: Long?, toolName: String) {
        writeLock.withLock {
            write(
                decisions().filterNot {
                    it.companionId == companionId && it.toolName == toolName
                },
            )
        }
    }

    /** 读原始文本；任何失败按「没有任何决定」处理（fail-closed），取消照常向上传播。 */
    private suspend fun readRaw(): String? = try {
        metaStore.getString(KEY)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        log.e(TAG, "read failed, treating as no decisions. key=$KEY", failure)
        null
    }

    /** 整体覆盖写；失败记日志后原样抛出（不静默吞）。 */
    private suspend fun write(decisions: List<CapabilityGrant>) {
        val encoded = CapabilityGrantCodec.encode(decisions)
        try {
            metaStore.putString(KEY, encoded)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            log.e(TAG, "write failed. key=$KEY", failure)
            throw failure
        }
    }

    companion object {
        /** KV 键（`<模块>.<用途>` 约定）。 */
        const val KEY: String = "capability.grants"

        private const val TAG = "CapabilityGrantStore"
    }
}
