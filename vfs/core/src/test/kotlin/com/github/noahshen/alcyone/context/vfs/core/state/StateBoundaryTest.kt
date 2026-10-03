package com.github.noahshen.alcyone.context.vfs.core.state

import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.registry.NodeRegistry
import com.github.noahshen.alcyone.context.vfs.core.registry.withFile
import com.github.noahshen.alcyone.context.vfs.core.repository.InMemoryNodeRepository
import com.github.noahshen.alcyone.context.vfs.core.repository.MountRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRecord
import com.github.noahshen.alcyone.context.vfs.core.repository.NodeRepository
import com.github.noahshen.alcyone.context.vfs.core.router.MountRouter
import com.github.noahshen.alcyone.context.vfs.core.storage.StorageFakeImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * T13 A04：共享边界本身的互斥性，不碰真实 I/O，也不靠「还没跑完」这种负向断言。
 *
 * 两条用例看的是**实际发生过的顺序和最终值**：
 * 1. 临界区不重叠：64 个协程各自做一次「读 → 让出调度 → 写」，最后计数必须正好是 64。
 * 2. 竞争方在持锁者离开之后才碰到状态库：把两边对 Repository 的调用顺序记下来，逐条比对。
 *
 * 锁正常时这两条的结论都是确定的；锁失效时它们会错。这不是压力重试：单次运行，没有重试与放宽判据。
 */
@Timeout(60)
class StateBoundaryTest {
    @Test
    fun `critical sections never overlap`() =
        runBlocking {
            val boundary = StateBoundary()
            var counter = 0 // 故意不用原子类：要测的正是「读-改-写」会不会被插队

            (1..64)
                .map {
                    async(Dispatchers.Default) {
                        boundary.withLock {
                            val seen = counter
                            yield() // 在临界区中间让出调度，让别的协程有机会插队
                            counter = seen + 1
                        }
                    }
                }.awaitAll()

            assertEquals(64, counter, "临界区重叠了：读-改-写被插队，最后计数会小于 64")
        }

    @Test
    fun `a competing resolve reaches the state store only after the holder leaves`() =
        runBlocking {
            val log = CopyOnWriteArrayList<String>()
            val nodes = LoggingNodeRepository(InMemoryNodeRepository(), log)
            val disk = StorageFakeImpl().apply { withFile("a.txt") }
            val boundary = StateBoundary()
            val registry =
                NodeRegistry(
                    MountRouter.of(setOf("resources"), listOf(MountRecord(VfsPath.parse("/resources"), "disk"))),
                    nodes,
                    { _: String -> disk },
                    boundary,
                )
            val path = VfsPath.parse("/resources/a.txt")

            val holderInside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch(Dispatchers.Default) {
                    boundary.withLock {
                        nodes.readStateStore() // 持锁期间读一次状态库，顺序记在 log 里
                        holderInside.complete(Unit)
                        release.await()
                    }
                }
            holderInside.await()

            // 竞争方此刻去排队：它必须等持锁者走完，才可能碰到状态库
            val competitor = async(Dispatchers.Default) { registry.resolveOrRegister(path) }
            release.complete(Unit)
            holder.join()
            val id = competitor.await()

            assertEquals(listOf("holder", "findByPath", "register"), log.toList(), "竞争方的状态库访问必须排在持锁者之后")
            assertEquals(id.id, nodes.findByPath(path)?.id, "竞争方拿到的是自己那条登记记录")
        }
}

/** 把每次 Repository 调用记进一个列表：这里要看的是「谁先碰到状态库」，不是返回了什么。 */
private class LoggingNodeRepository(
    private val delegate: NodeRepository,
    private val log: MutableList<String>,
) : NodeRepository by delegate {
    /** 持锁者在临界区里读一次状态库，把自己的位置记进 log（空库读到空列表也没关系）。 */
    suspend fun readStateStore() {
        delegate.findSubtree(VfsPath.root)
        log += "holder"
    }

    override suspend fun findByPath(path: VfsPath): NodeRecord? {
        log += "findByPath"
        return delegate.findByPath(path)
    }

    override suspend fun register(record: NodeRecord): NodeRecord {
        log += "register"
        return delegate.register(record)
    }
}
