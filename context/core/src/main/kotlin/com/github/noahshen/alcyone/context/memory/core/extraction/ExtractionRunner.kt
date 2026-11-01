package com.github.noahshen.alcyone.context.memory.core.extraction

import com.github.noahshen.alcyone.context.memory.api.SessionKey

/**
 * CT09 Extraction 的**一次执行职责**（Core Port）。
 *
 * 只表达「对某个会话执行一次提取」：不定义 Snapshot、LLM 调用、Turn 状态变更或调度策略；
 * 调度、去重、重试与关闭顺序由 Runtime 侧负责。
 *
 * 可见性为 **Kotlin public**（Runtime 需要跨模块实现/注入），但它**不属于 `context/api` SDK 门面**：
 * 公共 `AlcyoneContextFactory` 不暴露该类型，也不在本阶段创建生产 pipeline（CT11 才接通真实服务）。
 */
interface ExtractionRunner {
    /**
     * 对 [sessionKey] 执行一次提取。
     *
     * 允许抛出普通异常（调度方隔离后继续下一会话）或 `CancellationException`（关闭取消时原样传播）。
     */
    suspend fun execute(sessionKey: SessionKey)
}
