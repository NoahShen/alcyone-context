package com.github.noahshen.alcyone.context.memory.core.id

import com.github.noahshen.alcyone.context.common.SequenceIdGenerator
import java.time.Clock

/**
 * MemoryIdGenerator 的业务前缀适配器（薄封装）：算法与线程安全逻辑全部委托
 * [SequenceIdGenerator]，本类只负责业务前缀（`R-MEM` / `O-MEM`）与接口映射。
 *
 * 生产默认 [Clock.systemUTC]，测试可注入固定/可控 [Clock]。
 */
class MemoryIdGeneratorImpl(
    clock: Clock = Clock.systemUTC(),
) : MemoryIdGenerator {
    private val generator = SequenceIdGenerator(clock)

    override fun nextRawMemoryId(): String = generator.nextId("R-MEM")

    override fun nextOrganizedMemoryId(): String = generator.nextId("O-MEM")
}
