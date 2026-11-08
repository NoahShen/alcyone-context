package com.github.noahshen.alcyone.context.memory.core.organization

import com.github.noahshen.alcyone.context.memory.api.OrganizedMemory
import com.github.noahshen.alcyone.context.memory.api.RawMemory

/**
 * CT13 候选召回 Port（Core public，**非** `context/api` SDK）：为一条 Raw Memory 找出可能相关的 Organized Memory。
 *
 * 只做**候选召回**：不判断 NEW/MERGE/UPDATE、不写数据库、不执行治理；返回空列表表示"没有相关候选"，
 * 与"召回失败"严格区分（失败以异常表达，绝不返回部分结果）。
 */
interface OrganizationCandidateRetriever {
    /**
     * 召回与 [rawMemory] 可能相关的 Organized Memory 候选。
     *
     * 契约：返回对象必须来自**同一次快照**（不重查成不同版本）；顺序按快照 `id ASC` 稳定；
     * 任一必需批次失败/非法响应/截断/取消 → 抛异常（不返回部分候选）。
     */
    suspend fun search(rawMemory: RawMemory): List<OrganizedMemory>
}
