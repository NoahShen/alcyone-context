package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleException
import com.github.noahshen.alcyone.context.memory.api.ContextLifecycleState
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextPersistenceException
import com.github.noahshen.alcyone.context.memory.api.WorkerState
import com.github.noahshen.alcyone.context.memory.core.llm.LlmResponseFormat
import com.github.noahshen.alcyone.context.memory.core.llm.LlmTask
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingConfig
import com.github.noahshen.alcyone.context.memory.runtime.dreaming.DreamingRuntime
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmProfileConfig
import com.github.noahshen.alcyone.context.memory.runtime.llm.LlmTaskConfig
import com.github.noahshen.alcyone.context.memory.runtime.organization.OrganizationConfig
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.runtime.MountConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * CT06-B2 工厂：create 零副作用与隔离校验、显式初始化、普通 start 的拒绝保护。
 */
class AlcyoneContextFactoryTest {
    @Test
    fun `create has no filesystem side effects and reports CREATED without probing`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())

            assertEquals(before, fixture.snapshot(), "create 不得创建文件/目录/锁")
            val status = context.status()
            assertEquals(ContextLifecycleState.CREATED, status.lifecycle)
            assertFalse(status.storage.databaseInitialized, "create 后未验证数据库，不得声称已初始化")
            assertTrue(status.workers.isEmpty())
            assertEquals(fixture.contextPath, Path.of(status.storage.databasePath))

            // 状态查询本身也不得隐式取得资源。
            context.status()
            assertEquals(before, fixture.snapshot())
            assertFalse(Files.exists(fixture.contextPath))
            assertFalse(Files.exists(fixture.vfsPath))
        }

    @Test
    fun `create rejects the same context and vfs database file`() =
        ContextFixture().use { fixture ->
            val vfsConfig = fixture.vfsConfig(stateDatabase = fixture.contextPath)
            val failure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("database.path"))
            assertFalse(Files.exists(fixture.contextPath))
        }

    @Test
    fun `create rejects a database inside a local mount root`() =
        ContextFixture().use { fixture ->
            Files.createDirectories(fixture.directory.resolve("data"))
            val mount = fixture.localMount(root = fixture.directory.resolve("data"))
            val vfsConfig = fixture.vfsConfig(mounts = listOf(mount))
            val failure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("context.database.path"))

            // vfs.db 落在另一块挂载根内同样拒绝（context.db 不在该根内）。
            val vfsStorage = Files.createDirectories(fixture.directory.resolve("vfs-storage"))
            val vfsMount = fixture.localMount(vfsPath = "/resources", storageKey = "resources", root = vfsStorage)
            val insideVfs =
                fixture.vfsConfig(stateDatabase = vfsStorage.resolve("vfs.db"), mounts = listOf(vfsMount))
            val vfsFailure =
                assertFailsWith<ContextConfigException> {
                    AlcyoneContextFactory.create(fixture.config(vfsConfig = insideVfs), insideVfs)
                }
            assertTrue(vfsFailure.message.orEmpty().contains("vfs.database.path"), "实际: ${vfsFailure.message}")
        }

    @Test
    fun `create rejects case-insensitive aliases of a local mount root`() =
        ContextFixture().use { fixture ->
            Files.createDirectory(fixture.directory.resolve("CaseRoot"))
            val caseRootReal = runCatching { fixture.directory.resolve("CaseRoot").toRealPath() }.getOrNull()
            val caseAliasReal = runCatching { fixture.directory.resolve("caseroot").toRealPath() }.getOrNull()
            val caseInsensitive =
                Files.exists(fixture.directory.resolve("caseroot")) && caseRootReal != null && caseRootReal == caseAliasReal

            val mount = fixture.localMount(root = fixture.directory.resolve("CaseRoot"))
            val vfsConfig = fixture.vfsConfig(mounts = listOf(mount))
            val config = ContextConfig(fixture.directory.resolve("caseroot/context.db"), 30.seconds, vfsConfig = vfsConfig)
            if (caseInsensitive) {
                val failure = assertFailsWith<ContextConfigException> { AlcyoneContextFactory.create(config, vfsConfig) }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            } else {
                // 大小写敏感文件系统：这是另一个（不存在的）目录，属于合法配置，create 不创建它。
                val context = AlcyoneContextFactory.create(config, vfsConfig)
                assertEquals(ContextLifecycleState.CREATED, context.status().lifecycle)
                assertFalse(Files.exists(fixture.directory.resolve("caseroot")))
            }
        }

    @Test
    fun `create rejects structurally invalid vfs configuration`() =
        ContextFixture().use { fixture ->
            val cases: List<Pair<String, com.github.noahshen.alcyone.context.vfs.runtime.VfsRuntimeConfig>> =
                listOf(
                    "vfs.mounts[0].vfsPath" to
                        fixture.vfsConfig(mounts = listOf(MountConfig(VfsPath.root, "root", fixture.mountRoot))),
                    "vfs.mounts[0].storageKey" to
                        fixture.vfsConfig(mounts = listOf(MountConfig(VfsPath.parse("/memory"), "  ", fixture.mountRoot))),
                    "vfs.streamTotalLimit" to fixture.vfsConfig(streamTotalLimit = -1),
                    "vfs.eventBufferCapacity" to fixture.vfsConfig(eventBufferCapacity = 0),
                    "vfs.closeGracePeriod" to fixture.vfsConfig(closeGracePeriod = (-1).milliseconds),
                )
            cases.forEach { (path, vfsConfig) ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        AlcyoneContextFactory.create(fixture.config(vfsConfig = vfsConfig), vfsConfig)
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望路径 $path，实际: ${failure.message}")
            }
        }

    @Test
    fun `initializeDatabase creates the file and a second call refuses to overwrite`() =
        ContextFixture().use { fixture ->
            runBlocking { AlcyoneContextFactory.initializeDatabase(fixture.config()) }
            assertTrue(Files.exists(fixture.contextPath))

            val failure =
                assertFailsWith<ContextPersistenceException> {
                    runBlocking { AlcyoneContextFactory.initializeDatabase(fixture.config()) }
                }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, failure.errorCode)
        }

    @Test
    fun `start without initialization is rejected and creates no files`() =
        ContextFixture().use { fixture ->
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val failure = assertFailsWith<ContextPersistenceException> { runBlocking { context.start() } }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, failure.errorCode)
            assertFalse(Files.exists(fixture.contextPath), "start 不得自动建库")
            assertFalse(Files.exists(fixture.vfsPath), "Context start 失败时不得启动 VFS")
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `start rejects empty wrong-version and corrupt databases without changing bytes`() =
        ContextFixture().use { fixture ->
            Files.createDirectories(fixture.contextPath.parent)
            Files.createFile(fixture.contextPath)
            val emptyContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val emptyFailure = assertFailsWith<ContextPersistenceException> { runBlocking { emptyContext.start() } }
            assertEquals(ContextPersistenceErrorCode.DATABASE_NOT_INITIALIZED, emptyFailure.errorCode)
            assertEquals(0L, Files.size(fixture.contextPath))

            Files.delete(fixture.contextPath)
            fixture.initialize()
            fixture.setUserVersion(99)
            val wrongVersionBytes = fixture.bytesOf(fixture.contextPath)
            val wrongContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val versionFailure = assertFailsWith<ContextPersistenceException> { runBlocking { wrongContext.start() } }
            assertEquals(ContextPersistenceErrorCode.SCHEMA_VERSION_MISMATCH, versionFailure.errorCode)
            assertContentEquals(wrongVersionBytes, fixture.bytesOf(fixture.contextPath), "错版库不得被改动")

            Files.delete(fixture.contextPath)
            Files.write(fixture.contextPath, "definitely not a sqlite database".toByteArray())
            val corruptBytes = fixture.bytesOf(fixture.contextPath)
            val corruptContext = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            val corruptFailure = assertFailsWith<ContextPersistenceException> { runBlocking { corruptContext.start() } }
            assertEquals(ContextPersistenceErrorCode.PERSISTENCE_UNAVAILABLE, corruptFailure.errorCode)
            assertContentEquals(corruptBytes, fixture.bytesOf(fixture.contextPath), "损坏库不得被改动")
        }

    @Test
    fun `start succeeds after explicit initialization and reports verified storage`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            runBlocking { context.start() }

            val status = context.status()
            assertEquals(ContextLifecycleState.RUNNING, status.lifecycle)
            assertTrue(status.storage.databaseInitialized)
            assertEquals(fixture.contextPath.toAbsolutePath().normalize(), Path.of(status.storage.databasePath))
            assertTrue(status.workers.isEmpty())
            assertTrue(Files.exists(fixture.vfsPath), "VFS 状态库应由 VFS 自己创建")

            context.close()
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
        }

    @Test
    fun `start after close is rejected with CLOSED`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            val context = AlcyoneContextFactory.create(fixture.config(), fixture.vfsConfig())
            context.close()
            val failure = assertFailsWith<ContextLifecycleException> { runBlocking { context.start() } }
            assertEquals(ContextLifecycleErrorCode.CLOSED, failure.errorCode)
        }

    @Test
    fun `explicit organization binding failures reject create before any resource side effect`() =
        ContextFixture().use { fixture ->
            val cases =
                listOf(
                    null,
                    organizationLlm(includeScanner = false),
                    organizationLlm(includeGovernance = false),
                    organizationLlm(scannerMode = LlmResponseFormat.PROMPT_ONLY),
                    organizationLlm(governanceMode = LlmResponseFormat.PROMPT_ONLY),
                )
            val before = fixture.snapshot()
            cases.forEach { llm ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        AlcyoneContextFactory.create(
                            fixture.config().copy(organization = OrganizationConfig(), llm = llm),
                            fixture.vfsConfig(),
                        )
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertFalse(failure.message.orEmpty().contains("ct16-secret-key"))
                assertEquals(before, fixture.snapshot(), "非法 Organization 绑定不得产生任何文件/目录")
            }
            assertFalse(Files.exists(fixture.contextPath))
            assertFalse(Files.exists(fixture.vfsPath))
        }

    @Test
    fun `a legal explicit organization config keeps create side effect free and exposes idle worker`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(organization = OrganizationConfig(), llm = organizationLlm()),
                    fixture.vfsConfig(),
                )

            assertEquals(before, fixture.snapshot())
            assertEquals(ContextLifecycleState.CREATED, context.status().lifecycle)
            assertEquals(mapOf("organization" to WorkerState.IDLE), context.status().workers.mapValues { it.value.state })
            assertFalse(Files.exists(fixture.contextPath))
            assertFalse(Files.exists(fixture.vfsPath))
        }

    private fun organizationLlm(
        includeScanner: Boolean = true,
        includeGovernance: Boolean = true,
        scannerMode: LlmResponseFormat = LlmResponseFormat.JSON_OBJECT,
        governanceMode: LlmResponseFormat = LlmResponseFormat.JSON_SCHEMA,
    ): LlmConfig {
        val profiles =
            mapOf(
                "scanner" to profile("scanner", scannerMode),
                "governance" to profile("governance", governanceMode),
            )
        val tasks =
            buildMap {
                if (includeScanner) {
                    put(
                        LlmTask.CANDIDATE_SCANNER,
                        LlmTaskConfig(profile = "scanner", maxInputTokens = 4_000, maxOutputTokens = 1_000),
                    )
                }
                if (includeGovernance) {
                    put(
                        LlmTask.GOVERNANCE,
                        LlmTaskConfig(profile = "governance", maxInputTokens = 4_000, maxOutputTokens = 1_000),
                    )
                }
            }
        return LlmConfig(profiles = profiles, tasks = tasks)
    }

    @Test
    fun `automatic dreaming rejects missing or incompatible llm bindings before side effects`() =
        ContextFixture().use { fixture ->
            val automatic = DreamingConfig(cron = "0 3 * * *")
            val before = fixture.snapshot()
            listOf(null, dreamingLlm(includeReflection = false), dreamingLlm(mode = LlmResponseFormat.PROMPT_ONLY))
                .forEach { llm ->
                    val failure =
                        assertFailsWith<ContextConfigException> {
                            AlcyoneContextFactory.create(
                                fixture.config().copy(dreaming = automatic, llm = llm),
                                fixture.vfsConfig(),
                            )
                        }
                    assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                    assertFalse(failure.message.orEmpty().contains("ct16-secret-key"))
                    assertEquals(before, fixture.snapshot())
                }
            val valid =
                AlcyoneContextFactory.create(
                    fixture.config().copy(dreaming = automatic, llm = dreamingLlm()),
                    fixture.vfsConfig(),
                )
            assertEquals(ContextLifecycleState.CREATED, valid.status().lifecycle)
            assertEquals(before, fixture.snapshot())
        }

    private fun dreamingLlm(
        includeReflection: Boolean = true,
        mode: LlmResponseFormat = LlmResponseFormat.JSON_SCHEMA,
    ): LlmConfig {
        val profile = profile("dreaming", mode)
        val tasks =
            buildMap {
                listOf(LlmTask.DREAMING_TOPIC, LlmTask.MEMORY_SEARCH, LlmTask.DREAMING_EVIDENCE).forEach {
                    put(it, LlmTaskConfig("dreaming", 4_000, 1_000))
                }
                if (includeReflection) put(LlmTask.DREAMING_REFLECTION, LlmTaskConfig("dreaming", 4_000, 1_000))
            }
        return LlmConfig(mapOf("dreaming" to profile), tasks)
    }

    @Test
    fun `partial manual dreaming bindings fail before resource acquisition`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val complete = dreamingLlm()
            val invalid =
                buildList {
                    listOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
                        .forEach { task -> add(LlmConfig(complete.profiles, mapOf(task to complete.tasks.getValue(task)))) }
                    add(LlmConfig(complete.profiles, complete.tasks - LlmTask.MEMORY_SEARCH))
                    add(dreamingLlm(mode = LlmResponseFormat.PROMPT_ONLY))
                }
            invalid.forEach { llm ->
                val failure =
                    assertFailsWith<ContextConfigException> {
                        AlcyoneContextFactory.create(fixture.config().copy(llm = llm), fixture.vfsConfig())
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertFalse(failure.message.orEmpty().contains("ct16-secret-key"))
                assertEquals(before, fixture.snapshot())
            }
            AlcyoneContextFactory.create(fixture.config().copy(llm = complete), fixture.vfsConfig())
            assertEquals(before, fixture.snapshot())
        }

    @Test
    fun `every dreaming task profile rejects prompt only before resource acquisition`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val complete = dreamingLlm()
            complete.tasks.forEach { (task, binding) ->
                val profile = profile("invalid-$task", LlmResponseFormat.PROMPT_ONLY)
                val llm =
                    LlmConfig(
                        complete.profiles + (profile.name to profile),
                        complete.tasks + (task to LlmTaskConfig(profile.name, binding.maxInputTokens, binding.maxOutputTokens)),
                    )
                val failure =
                    assertFailsWith<ContextConfigException> {
                        AlcyoneContextFactory.create(fixture.config().copy(llm = llm), fixture.vfsConfig())
                    }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertTrue(failure.message.orEmpty().contains("$task profile jsonMode must be JSON_SCHEMA or JSON_OBJECT"))
                assertFalse(failure.message.orEmpty().contains("ct16-secret-key"))
                assertEquals(before, fixture.snapshot())
            }
        }

    @Test
    fun `exclusive tasks require every remaining binding and each missing task or profile is named`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            val tasks =
                listOf(LlmTask.DREAMING_TOPIC, LlmTask.MEMORY_SEARCH, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION)
            listOf(LlmTask.DREAMING_TOPIC, LlmTask.DREAMING_EVIDENCE, LlmTask.DREAMING_REFLECTION).forEach { exclusive ->
                tasks.forEach { required ->
                    val complete = dreamingLlm()
                    val llm = LlmConfig(complete.profiles, complete.tasks.filterKeys { task -> task == exclusive || task == required })
                    val failure =
                        assertFailsWith<ContextConfigException> {
                            AlcyoneContextFactory.create(fixture.config().copy(llm = llm), fixture.vfsConfig())
                        }
                    assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                    val expected =
                        tasks.first { task -> task != exclusive && task != required }
                    assertTrue(failure.message.orEmpty().contains("context.llm.tasks.$expected is required for dreaming"))
                    assertFalse(failure.message.orEmpty().contains("ct16-secret-key"))
                    assertEquals(before, fixture.snapshot())
                }
            }

            val missingProfile =
                dreamingLlm().let { complete ->
                    assertFailsWith<ContextConfigException> {
                        LlmConfig(
                            complete.profiles,
                            complete.tasks + (LlmTask.DREAMING_EVIDENCE to LlmTaskConfig("missing", 4_000, 1_000)),
                        )
                    }
                }
            assertEquals(
                "[INVALID_CONFIG] context.llm.tasks.DREAMING_EVIDENCE.profile refers to an unknown profile: missing",
                missingProfile.message,
            )
            val noProfiles = assertFailsWith<ContextConfigException> { LlmConfig(emptyMap(), dreamingLlm().tasks) }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, noProfiles.errorCode)
            assertEquals(before, fixture.snapshot())
        }

    @Test
    fun `standalone memory search preserves retrieval-only binding modes without a dreaming worker`() =
        ContextFixture().use { fixture ->
            val before = fixture.snapshot()
            listOf(LlmResponseFormat.JSON_SCHEMA, LlmResponseFormat.JSON_OBJECT, LlmResponseFormat.PROMPT_ONLY)
                .forEach { mode ->
                    val complete = dreamingLlm(mode = mode)
                    val searchOnly =
                        LlmConfig(
                            complete.profiles,
                            mapOf(
                                LlmTask.MEMORY_SEARCH to complete.tasks.getValue(LlmTask.MEMORY_SEARCH),
                            ),
                        )
                    val context = AlcyoneContextFactory.create(fixture.config().copy(llm = searchOnly), fixture.vfsConfig())
                    assertTrue(context.status().workers.isEmpty())
                    assertEquals(before, fixture.snapshot())
                }
        }

    @Test
    fun `manual dreaming runs through public context and close joins its runtime`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            var runtime: DreamingRuntime? = null
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(llm = dreamingLlm()),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    dreamingRuntimeConfigurator = { runtime = it },
                )
            runBlocking {
                context.start()
                val result = context.runDreaming()
                assertEquals(0L, result.startCheckpoint)
                assertEquals(0L, result.endCheckpoint)
                assertEquals(0, result.scannedTurns)
                assertEquals("RUNNING", runtime?.stateForTesting())
                assertNull(runtime?.periodicCronForTesting())
                context.closeAsync()
            }
            assertEquals("CLOSED", runtime?.stateForTesting())
        }

    @Test
    fun `dreaming construction failure releases runtime and does not publish context`() =
        ContextFixture().use { fixture ->
            fixture.initialize()
            var runtime: DreamingRuntime? = null
            val failure = IllegalStateException("ct27-start-failure")
            val context =
                AlcyoneContextFactory.create(
                    fixture.config().copy(llm = dreamingLlm()),
                    fixture.vfsConfig(),
                    object : AlcyoneContextFactory.StartProbe {
                        override suspend fun afterResourcesAcquired() = Unit
                    },
                    dreamingRuntimeConfigurator = {
                        runtime = it
                        it.startFailureForTesting = failure
                    },
                )
            assertSame(failure, assertFailsWith<IllegalStateException> { runBlocking { context.start() } })
            assertEquals(ContextLifecycleState.CLOSED, context.status().lifecycle)
            assertEquals("CLOSED", runtime?.stateForTesting())
        }

    private fun profile(
        name: String,
        mode: LlmResponseFormat,
    ): LlmProfileConfig =
        LlmProfileConfig(
            name = name,
            baseUrl = "https://example.test/v1",
            apiKey = "ct16-secret-key",
            model = "test-model",
            contextWindowTokens = 16_000,
            jsonMode = mode,
        )
}
