package com.yunian.ai.common.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 全应用调度器（线程池）的**单一事实来源**。
 *
 * 背景（QA 审计 P1-1）：全仓曾 0 处 `limitedParallelism`，CPU 重活（解码 / 加解密 / 摘要）
 * 与网络 / 磁盘阻塞任务全部挤在 [Dispatchers.IO]（默认 64 线程共享池）；同时 15+ 处对象级
 * `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 各自为政，调度归属无法统一治理。
 *
 * 使用规则：
 * - 阻塞型（网络 / 磁盘 / 数据库）→ [io]；
 * - CPU 密集型（位图解码 / 加解密 / 摘要 / 安全打分）→ [cpu]；
 * - 本地引擎（LLM / TTS / ASR）→ [inference] / [tts] / [asr]，各自串行，避免本地引擎与渲染争核。
 *
 * **禁止**再在业务代码里 `new CoroutineScope(Dispatchers.IO)`：对象级作用域请显式组合
 * `SupervisorJob() + AppDispatchers.xxx`（App 级、永不取消的合并到
 * [com.yunian.ai.common.ApplicationScopeProvider.scope]）。
 *
 * 注：`core:common` 的协程编译类路径为 1.8.1，`limitedParallelism` 在 1.9.0 前仍是
 * [ExperimentalCoroutinesApi]（命名重载 1.9.0 才引入），故此处显式 opt-in 并使用仅并行度重载
 * （语义与命名重载完全一致，仅少一个调试线程名）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AppDispatchers {

    /**
     * CPU 密集视图的并行度：从物理核数预留 2 核给 UI / 渲染与 IO 阻塞任务，上限 6 规避超订阅、
     * 下限 2 保证低端机吞吐。
     *
     * 取值只用 [Runtime.availableProcessors]（廉价的本地读取），**刻意不读** `HardwareInfo.tier`：
     * `tier` 首次访问会 fork `getprop` 子进程 + 读 sysfs，若在未预热的线程上首次访问本对象会造成阻塞。
     */
    private val cpuParallelism: Int = run {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        (cores - 2).coerceIn(2, 6)
    }

    /** 阻塞型工作（网络 / 磁盘 / 数据库）。与 [Dispatchers.IO] 同一实例，替换零语义变化。 */
    val io: CoroutineDispatcher = Dispatchers.IO

    /** CPU 密集型工作（位图解码 / 加解密 / 摘要 / 安全打分）：[Dispatchers.Default] 上的受限视图。 */
    val cpu: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(cpuParallelism)

    /** 本地 LLM 推理：串行（引擎本身串行，同一时刻只跑一段推理）。 */
    val inference: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** 本地 VITS 合成：串行（同一时刻只合成一段音频）。 */
    val tts: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** 本地 ASR 录音 / 解码：串行（与网络 / 文件阻塞任务隔离、避免抢占大核）。 */
    val asr: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
}
