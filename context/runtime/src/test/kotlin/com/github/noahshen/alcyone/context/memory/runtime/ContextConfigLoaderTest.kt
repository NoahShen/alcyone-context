package com.github.noahshen.alcyone.context.memory.runtime

import com.github.noahshen.alcyone.context.memory.api.ContextConfigErrorCode
import com.github.noahshen.alcyone.context.memory.api.ContextConfigException
import com.github.noahshen.alcyone.context.vfs.VfsPath
import com.github.noahshen.alcyone.context.vfs.core.VfsLimits
import com.github.noahshen.alcyone.context.vfs.runtime.MountBackend
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * CT06-B1 配置加载：真实临时目录 + 真实 YAML 文件，覆盖最小配置/默认值/路径基准/ENV/
 * 未知字段/`server` 忽略/蓝图未实现字段/Memory Map/类型范围/LocalFs/WebDav 映射/脱敏/路径隔离。
 */
class ContextConfigLoaderTest {
    private companion object {
        const val PASSWORD = "ct06-b1-webdav-password-marker-7f21"
    }

    @Test
    fun `minimal config uses documented defaults`() =
        withDirectory { dir ->
            val config =
                load(
                    dir,
                    """
                    context:
                      database:
                        path: ./data/context.db
                    vfs:
                      database:
                        path: ./data/vfs.db
                    """,
                )

            assertEquals(dir.resolve("data/context.db"), config.databasePath)
            assertEquals(dir.resolve("data/vfs.db"), config.vfsDatabasePath)
            assertEquals(30.seconds, config.lifecycleShutdownTimeout)
            assertEquals(emptySet(), config.vfsConfig.namespaces)
            assertEquals(emptyList(), config.vfsConfig.mounts)
            assertEquals(VfsLimits.DEFAULT_LIMIT_BYTES, config.vfsConfig.limits.defaultReadMaxBytes)
            assertEquals(VfsLimits.DEFAULT_LIMIT_BYTES, config.vfsConfig.limits.defaultWriteMaxBytes)
            assertNull(config.vfsConfig.streamTotalLimit)
            assertEquals(256, config.vfsConfig.eventBufferCapacity)
            assertEquals(30.seconds, config.vfsConfig.closeGracePeriod)
        }

    @Test
    fun `empty yaml uses all defaults`() =
        withDirectory { dir ->
            val config = load(dir, "# only a comment\n")
            assertEquals(dir.resolve("data/context.db"), config.databasePath)
            assertEquals(dir.resolve("data/vfs.db"), config.vfsDatabasePath)
            assertEquals(30.seconds, config.lifecycleShutdownTimeout)
            assertEquals(256, config.vfsConfig.eventBufferCapacity)
        }

    @Test
    fun `relative paths are resolved against the yaml directory not the working directory`() =
        withDirectory { dir ->
            val nested = Files.createDirectories(dir.resolve("nested/config"))
            val config = load(nested, "context:\n  database:\n    path: ./data/context.db\n", name = "alcyone.yaml")
            assertTrue(config.databasePath.startsWith(nested), "实际: ${config.databasePath}")
            assertEquals(nested.resolve("data/context.db"), config.databasePath)
            assertNotEquals(
                Path
                    .of("")
                    .toAbsolutePath()
                    .normalize()
                    .resolve("data/context.db"),
                config.databasePath,
            )
        }

    @Test
    fun `environment variables are expanded and missing or blank values fail with path and name only`() =
        withDirectory { dir ->
            val expanded =
                load(
                    dir,
                    """
                    context:
                      database:
                        path: ${'$'}{CT06_B1_DB_PATH}
                    """,
                ) { name -> if (name == "CT06_B1_DB_PATH") dir.resolve("from-env/context.db").toString() else null }
            assertEquals(dir.resolve("from-env/context.db").toAbsolutePath().normalize(), expanded.databasePath)

            val missing =
                assertFailsWith<ContextConfigException> {
                    load(dir, "context:\n  database:\n    path: \${CT06_B1_MISSING_VALUE}\n")
                }
            assertEquals(ContextConfigErrorCode.MISSING_ENV, missing.errorCode)
            assertTrue(missing.message.orEmpty().contains("context.database.path"))
            assertTrue(missing.message.orEmpty().contains("CT06_B1_MISSING_VALUE"))
            assertNull(missing.cause)

            val blank =
                assertFailsWith<ContextConfigException> {
                    load(dir, "context:\n  database:\n    path: \${CT06_B1_BLANK_VALUE}\n") { "   " }
                }
            assertEquals(ContextConfigErrorCode.MISSING_ENV, blank.errorCode)
            assertTrue(blank.message.orEmpty().contains("CT06_B1_BLANK_VALUE"))
        }

    @Test
    fun `remaining blueprint backup section is accepted without validation or env expansion`() =
        withDirectory { dir ->
            val requested = mutableListOf<String>()
            val config =
                ContextConfigLoader.load(
                    write(
                        dir,
                        """
                        context:
                          backup:
                            enabled: false
                        vfs:
                          database:
                            path: ./data/vfs.db
                        """,
                    ),
                ) { name ->
                    requested += name
                    null
                }

            assertTrue(requested.isEmpty(), "未实现字段不得触发 ENV 读取，实际: $requested")
            assertEquals(dir.resolve("data/context.db"), config.databasePath)
            // CT08-B1 起 `context.llm` 首次消费并严格校验，不再属于“接受但不消费”的蓝图分区。
            assertNull(config.llm, "未配置 context.llm 时必须是 null")
            assertNull(config.organization, "未配置 context.organization 时必须是 null")
            assertNull(config.memoryMap, "未配置 context.memoryMap 时必须是 null")
        }

    @Test
    fun `memory map is disabled by default while an empty mapping uses periodic defaults`() =
        withDirectory { dir ->
            assertNull(load(dir, "context:\n  database:\n    path: ./data/context.db\n").memoryMap)

            val config = load(dir, "context:\n  memoryMap: {}\n")
            val memoryMap = requireNotNull(config.memoryMap)
            assertTrue(memoryMap.periodic.enabled)
            assertEquals("*/10 * * * *", memoryMap.periodic.cron)
        }

    @Test
    fun `memory map periodic overrides and cron environment expansion are mapped strictly`() =
        withDirectory { dir ->
            val config =
                ContextConfigLoader.load(
                    write(
                        dir,
                        """
                        context:
                          memoryMap:
                            refresh:
                              periodic:
                                enabled: false
                                cron: ${'$'}{CT19_MAP_CRON}
                        """,
                    ),
                ) { name -> if (name == "CT19_MAP_CRON") "*/30 * * * *" else null }

            val memoryMap = requireNotNull(config.memoryMap)
            assertFalse(memoryMap.periodic.enabled)
            assertEquals("*/30 * * * *", memoryMap.periodic.cron)
        }

    @Test
    fun `memory map loader rejects unknown keys timezone types and invalid cron safely`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "context:\n  memoryMap:\n    unknown: 1\n" to ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  memoryMap: []\n" to ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  memoryMap:\n    refresh:\n      unknown: 1\n" to
                        ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  memoryMap:\n    refresh:\n      periodic:\n        timezone: Asia/Shanghai\n" to
                        ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  memoryMap:\n    refresh:\n      periodic:\n        enabled: 1\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  memoryMap:\n    refresh:\n      periodic:\n        cron: invalid-cron-secret\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                )

            cases.forEach { (yaml, code) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(code, failure.errorCode)
                assertFalse(failure.message.orEmpty().contains("invalid-cron-secret"))
                assertNull(failure.cause)
            }
        }

    @Test
    fun `retrieval is defaulted while empty mapping and maxLimit overrides are strict`() =
        withDirectory { dir ->
            assertEquals(50, load(dir, "context: {}\n").retrieval.search.maxLimit)
            assertEquals(50, load(dir, "context:\n  retrieval: {}\n").retrieval.search.maxLimit)
            assertEquals(50, load(dir, "context:\n  retrieval:\n    search: {}\n").retrieval.search.maxLimit)
            assertEquals(
                1,
                load(dir, "context:\n  retrieval:\n    search:\n      maxLimit: 1\n").retrieval.search.maxLimit,
            )
            assertEquals(
                1_000,
                load(dir, "context:\n  retrieval:\n    search:\n      maxLimit: 1000\n").retrieval.search.maxLimit,
            )
            assertNull(load(dir, "context:\n  retrieval:\n    search:\n      maxLimit: 1000\n").llm)
        }

    @Test
    fun `retrieval loader rejects unknown keys types and out of range values safely`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "context:\n  retrieval: []\n" to ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  retrieval:\n    unknown: 1\n" to ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  retrieval:\n    search:\n      unknown: 1\n" to ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  retrieval:\n    search:\n      maxLimit: invalid-secret\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  retrieval:\n    search:\n      maxLimit: 0\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  retrieval:\n    search:\n      maxLimit: 1001\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                )
            cases.forEach { (yaml, code) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(code, failure.errorCode)
                assertFalse(failure.message.orEmpty().contains("invalid-secret"))
                assertNull(failure.cause)
            }
        }

    @Test
    fun `organization is disabled by default and an empty mapping uses trigger defaults`() =
        withDirectory { dir ->
            assertNull(load(dir, "context:\n  database:\n    path: ./data/context.db\n").organization)

            val config =
                load(
                    dir,
                    """
                    context:
                      organization: {}
                    """,
                )
            val organization = config.organization
            assertTrue(organization != null)
            assertEquals(true, organization.immediateEnabled)
            assertEquals(true, organization.periodicEnabled)
            assertEquals("*/10 * * * *", organization.cron)
        }

    @Test
    fun `organization trigger overrides and cron env expansion are mapped strictly`() =
        withDirectory { dir ->
            val config =
                ContextConfigLoader.load(
                    write(
                        dir,
                        """
                        context:
                          organization:
                            triggers:
                              immediate:
                                enabled: false
                              periodic:
                                enabled: true
                                cron: ${'$'}{CT16_ORG_CRON}
                        """,
                    ),
                ) { name -> if (name == "CT16_ORG_CRON") "*/15 * * * *" else null }

            val organization = config.organization
            assertTrue(organization != null)
            assertEquals(false, organization.immediateEnabled)
            assertEquals(true, organization.periodicEnabled)
            assertEquals("*/15 * * * *", organization.cron)
        }

    @Test
    fun `organization loader rejects unknown keys types timezone invalid cron and disabled triggers safely`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "context:\n  organization:\n    unknown: 1\n" to ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  organization: []\n" to ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  organization:\n    triggers:\n      unknown: 1\n" to ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  organization:\n    triggers:\n      periodic:\n        timezone: Asia/Shanghai\n" to
                        ContextConfigErrorCode.UNKNOWN_FIELD,
                    "context:\n  organization:\n    triggers:\n      immediate:\n        enabled: 1\n" to
                        ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  organization:\n    triggers:\n      immediate:\n        enabled: false\n" +
                        "      periodic:\n        enabled: false\n" to ContextConfigErrorCode.INVALID_CONFIG,
                    "context:\n  organization:\n    triggers:\n      periodic:\n        enabled: false\n" +
                        "        cron: invalid-cron-secret\n" to ContextConfigErrorCode.INVALID_CONFIG,
                )

            cases.forEach { (yaml, code) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(code, failure.errorCode)
                assertFalse(failure.message.orEmpty().contains("invalid-cron-secret"))
                assertNull(failure.cause)
            }
        }

    @Test
    fun `context ingestion limits are validated and owned by the instance since ct07`() =
        withDirectory { dir ->
            // CT07 起读取限额：非法值在加载层拒绝。
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(dir, "context:\n  ingestion:\n    maxTurnBytes: 0\n")
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("context.ingestion.maxTurnBytes"))

            val unknown =
                assertFailsWith<ContextConfigException> {
                    load(dir, "context:\n  ingestion:\n    unknownField: 1\n")
                }
            assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, unknown.errorCode)
            assertTrue(unknown.message.orEmpty().contains("context.ingestion.unknownField"))

            val config = load(dir, "context:\n  ingestion:\n    maxTurnBytes: 1048576\n")
            assertEquals(
                com.github.noahshen.alcyone.context.memory.core.ingestion.IngestionLimits(
                    maxTurnBytes = 1048576,
                    maxMessages = 256,
                    maxIdLength = 256,
                ),
                config.ingestionLimits,
                "限额必须归实例所有（不可变值复制）",
            )

            // 快照独立性：Loader 返回的值对象与实例配置互不影响。
            val other = load(dir, "context:\n  ingestion:\n    maxMessages: 12\n")
            assertEquals(12, other.ingestionLimits.maxMessages)
            assertEquals(256, config.ingestionLimits.maxMessages)
        }

    @Test
    fun `unknown fields in managed sections are rejected with their path`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "context:\n  database:\n    unknown: 1\n" to "context.database.unknown",
                    "context:\n  unknownTop: 1\n" to "context.unknownTop",
                    "context:\n  lifecycle:\n    unknown: 1\n" to "context.lifecycle.unknown",
                    "vfs:\n  unknownTop: 1\n" to "vfs.unknownTop",
                    "vfs:\n  limits:\n    unknown: 1\n" to "vfs.limits.unknown",
                    "vfs:\n  mounts:\n    - vfsPath: /memory\n      storageKey: s\n      backend:\n" +
                        "        type: local\n        root: ./r\n        unknown: 1\n" to
                        "vfs.mounts[0].backend.unknown",
                )
            cases.forEach { (yaml, path) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(ContextConfigErrorCode.UNKNOWN_FIELD, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望路径 $path，实际: ${failure.message}")
            }
        }

    @Test
    fun `server partition is ignored entirely`() =
        withDirectory { dir ->
            val requested = mutableListOf<String>()
            val config =
                ContextConfigLoader.load(
                    write(
                        dir,
                        """
                        context:
                          database:
                            path: ./data/context.db
                        server:
                          host: 127.0.0.1
                          port: 8080
                          ingestion:
                            clients:
                              hermes:
                                apiKey: ${'$'}{CT06_B1_SERVER_KEY}
                        """,
                    ),
                ) { name ->
                    requested += name
                    null
                }
            assertTrue(requested.isEmpty(), "server 分区不得被读取，实际: $requested")
            assertEquals(dir.resolve("data/context.db"), config.databasePath)
        }

    @Test
    fun `type and range violations fail safely with field paths`() =
        withDirectory { dir ->
            val cases =
                listOf(
                    "context:\n  database:\n    path: []\n" to "context.database.path",
                    "context:\n  lifecycle:\n    shutdownTimeout: -1s\n" to "context.lifecycle.shutdownTimeout",
                    "vfs:\n  limits:\n    defaultReadMaxBytes: 0\n" to "vfs.limits.defaultReadMaxBytes",
                    "vfs:\n  limits:\n    defaultWriteMaxBytes: -5\n" to "vfs.limits.defaultWriteMaxBytes",
                    "vfs:\n  limits:\n    defaultReadMaxBytes: big\n" to "vfs.limits.defaultReadMaxBytes",
                    "vfs:\n  eventBufferCapacity: 0\n" to "vfs.eventBufferCapacity",
                    "vfs:\n  streamTotalLimit: -1\n" to "vfs.streamTotalLimit",
                    "vfs:\n  closeGracePeriod: -1s\n" to "vfs.closeGracePeriod",
                    "vfs:\n  namespaces: [1]\n" to "vfs.namespaces[0]",
                    "vfs:\n  mounts: not-a-list\n" to "vfs.mounts",
                    "vfs:\n  mounts:\n    - vfsPath: /\n      storageKey: s\n      backend: { type: local, root: ./r }\n" to
                        "vfs.mounts[0].vfsPath",
                    "vfs:\n  mounts:\n    - vfsPath: /memory\n      storageKey: '  '\n      backend: { type: local, root: ./r }\n" to
                        "vfs.mounts[0].storageKey",
                    "vfs:\n  mounts:\n    - vfsPath: /memory\n      storageKey: s\n      backend: { type: s3, root: ./r }\n" to
                        "vfs.mounts[0].backend.type",
                    "vfs:\n  mounts:\n    - vfsPath: /memory\n      storageKey: s\n      backend: { type: local }\n" to
                        "vfs.mounts[0].backend.root",
                )
            cases.forEach { (yaml, path) ->
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode, "case: $path")
                assertTrue(failure.message.orEmpty().contains(path), "期望路径 $path，实际: ${failure.message}")
                assertNull(failure.cause)
            }
        }

    @Test
    fun `duplicate yaml keys are rejected safely`() =
        withDirectory { dir ->
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(dir, "context:\n  database:\n    path: ./a.db\n    path: ./b.db\n")
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertFalse(failure.message.orEmpty().contains("./a.db"))
            assertFalse(failure.message.orEmpty().contains("./b.db"))
        }

    @Test
    fun `local and webdav mounts map field by field`() =
        withDirectory { dir ->
            Files.createDirectories(dir.resolve("storage/memory"))
            val config =
                load(
                    dir,
                    """
                    vfs:
                      database:
                        path: ./state/vfs.db
                      namespaces: [memory, resources]
                      mounts:
                        - vfsPath: /memory
                          storageKey: local-memory
                          backend:
                            type: local
                            root: ./storage/memory
                        - vfsPath: /resources
                          storageKey: webdav-resources
                          backend:
                            type: webdav
                            endpoint: https://dav.example.test/team
                            root: /dav/team
                            username: alice
                            password: ${'$'}{CT06_B1_WEBDAV_PASSWORD}
                    """,
                ) { name -> if (name == "CT06_B1_WEBDAV_PASSWORD") PASSWORD else null }

            assertEquals(dir.resolve("state/vfs.db"), config.vfsConfig.stateDatabase)
            assertEquals(dir.resolve("data/context.db"), config.databasePath)
            assertEquals(setOf("memory", "resources"), config.vfsConfig.namespaces)
            assertEquals(2, config.vfsConfig.mounts.size)

            val local = config.vfsConfig.mounts[0]
            assertEquals(VfsPath.parse("/memory"), local.vfsPath)
            assertEquals("local-memory", local.storageKey)
            val localBackend = assertIs<MountBackend.LocalFs>(local.backend)
            assertEquals(dir.resolve("storage/memory"), localBackend.root)

            val webdav = config.vfsConfig.mounts[1]
            assertEquals(VfsPath.parse("/resources"), webdav.vfsPath)
            assertEquals("webdav-resources", webdav.storageKey)
            val webdavBackend = assertIs<MountBackend.WebDav>(webdav.backend)
            assertEquals("https://dav.example.test/team", webdavBackend.endpoint)
            assertEquals("/dav/team", webdavBackend.root)
            assertEquals("alice", webdavBackend.username)
            assertEquals(PASSWORD, webdavBackend.password)
        }

    @Test
    fun `webdav credentials never appear in errors or toString`() =
        withDirectory { dir ->
            val valid =
                load(
                    dir,
                    """
                    vfs:
                      mounts:
                        - vfsPath: /resources
                          storageKey: webdav-resources
                          backend:
                            type: webdav
                            endpoint: https://dav.example.test/team
                            root: /dav/team
                            username: alice
                            password: ${'$'}{CT06_B1_WEBDAV_PASSWORD}
                    """,
                ) { PASSWORD }
            val webdavBackend =
                assertIs<MountBackend.WebDav>(
                    valid.vfsConfig.mounts
                        .single()
                        .backend,
                )
            assertEquals(PASSWORD, webdavBackend.password)
            assertFalse(valid.toString().contains(PASSWORD), "ContextConfig.toString 不得回显密码")
            assertFalse(valid.vfsConfig.toString().contains(PASSWORD), "VfsRuntimeConfig.toString 不得回显密码")
            assertFalse(
                valid.vfsConfig.mounts
                    .toString()
                    .contains(PASSWORD),
                "挂载列表 toString 不得回显密码",
            )

            val failure =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        vfs:
                          limits:
                            defaultReadMaxBytes: 0
                          mounts:
                            - vfsPath: /resources
                              storageKey: webdav-resources
                              backend:
                                type: webdav
                                endpoint: https://dav.example.test/team
                                root: /dav/team
                                password: ${'$'}{CT06_B1_WEBDAV_PASSWORD}
                        """,
                    ) { PASSWORD }
                }
            assertFalse(describeChain(failure).contains(PASSWORD), "异常链不得回显密码")
        }

    @Test
    fun `non-existent multi-level parents and symlinked ancestors are canonicalized without requiring existence`() =
        withDirectory { dir ->
            val real = Files.createDirectories(dir.resolve("real"))
            Files.createSymbolicLink(dir.resolve("link"), real)

            // 多级父目录都不存在：加载合法，且一个文件/目录都不创建。
            val config =
                load(
                    dir,
                    """
                    context:
                      database:
                        path: ./link/new/deep/context.db
                    vfs:
                      database:
                        path: ./real/new/deep/vfs.db
                      mounts:
                        - vfsPath: /memory
                          storageKey: local-memory
                          backend: { type: local, root: ./not-created/mount-root }
                    """,
                )
            assertEquals(dir.resolve("link/new/deep/context.db"), config.databasePath)
            assertEquals(dir.resolve("real/new/deep/vfs.db"), config.vfsDatabasePath)
            assertFalse(Files.exists(dir.resolve("link/new")), "加载不得创建任何目录")
            assertFalse(Files.exists(dir.resolve("not-created")), "加载不得创建挂载根")

            // 同一文件计划路径经符号链接祖先 + 多级不存在父目录后仍然判等并拒绝。
            val sameFile =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./link/new/deep/context.db
                        vfs:
                          database:
                            path: ./real/new/deep/context.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, sameFile.errorCode)
            assertFalse(Files.exists(real.resolve("new/deep/context.db")))
        }

    @Test
    fun `context and vfs databases must differ including symlink aliases`() =
        withDirectory { dir ->
            val samePath =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./data/same.db
                        vfs:
                          database:
                            path: ./data/same.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, samePath.errorCode)
            assertTrue(samePath.message.orEmpty().contains("context.database.path"))
            assertTrue(samePath.message.orEmpty().contains("vfs.database.path"))
            assertFalse(Files.exists(dir.resolve("data/same.db")), "配置加载不得创建数据库文件")

            val real = Files.createDirectories(dir.resolve("real"))
            val link = dir.resolve("link")
            Files.createSymbolicLink(link, real)
            val parentAlias =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./real/context.db
                        vfs:
                          database:
                            path: ./link/context.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, parentAlias.errorCode)
            assertFalse(Files.exists(real.resolve("context.db")))

            val existing = Files.createFile(dir.resolve("existing.db"))
            Files.createSymbolicLink(dir.resolve("existing-link.db"), existing)
            val fileAlias =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./existing.db
                        vfs:
                          database:
                            path: ./existing-link.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, fileAlias.errorCode)
        }

    @Test
    fun `databases must not live inside local mount roots but sibling prefixes are allowed`() =
        withDirectory { dir ->
            Files.createDirectories(dir.resolve("storage"))
            Files.createDirectories(dir.resolve("real-root"))
            Files.createSymbolicLink(dir.resolve("root-link"), dir.resolve("real-root"))

            val insideContext =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./storage/context.db
                        vfs:
                          mounts:
                            - vfsPath: /memory
                              storageKey: local-memory
                              backend: { type: local, root: ./storage }
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, insideContext.errorCode)
            assertTrue(insideContext.message.orEmpty().contains("context.database.path"))
            assertFalse(Files.exists(dir.resolve("storage/context.db")))

            val insideVfs =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        vfs:
                          database:
                            path: ./storage/state/vfs.db
                          mounts:
                            - vfsPath: /memory
                              storageKey: local-memory
                              backend: { type: local, root: ./storage }
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, insideVfs.errorCode)
            assertTrue(insideVfs.message.orEmpty().contains("vfs.database.path"))

            val symlinkedRoot =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./real-root/context.db
                        vfs:
                          mounts:
                            - vfsPath: /memory
                              storageKey: local-memory
                              backend: { type: local, root: ./root-link }
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, symlinkedRoot.errorCode)

            // 同目录但不同文件、以及前缀相同的兄弟目录都不是「在挂载根内」。
            val allowed =
                load(
                    dir,
                    """
                    context:
                      database:
                        path: ./storage-other/context.db
                    vfs:
                      database:
                        path: ./data/vfs.db
                      mounts:
                        - vfsPath: /memory
                          storageKey: local-memory
                          backend: { type: local, root: ./storage }
                    """,
                )
            assertEquals(dir.resolve("storage-other/context.db"), allowed.databasePath)
        }

    @Test
    fun `loading a config never creates database files or directories`() =
        withDirectory { dir ->
            load(
                dir,
                """
                context:
                  database:
                    path: ./data/context.db
                vfs:
                  database:
                    path: ./state/vfs.db
                """,
            )
            assertFalse(Files.exists(dir.resolve("data")), "加载不得创建目录")
            assertFalse(Files.exists(dir.resolve("state")), "加载不得创建目录")
            assertFalse(Files.exists(dir.resolve("data/context.db")))
            assertFalse(Files.exists(dir.resolve("state/vfs.db")))
        }

    @Test
    fun `dangling symlink alias cannot mask the same database file`() =
        withDirectory { dir ->
            Files.createSymbolicLink(dir.resolve("alias.db"), dir.resolve("target.db"))
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./alias.db
                        vfs:
                          database:
                            path: ./target.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("database.path"), "实际: ${failure.message}")
            assertNull(failure.cause)
            assertFalse(Files.exists(dir.resolve("target.db")), "加载不得创建数据库文件")
            assertFalse(Files.exists(dir.resolve("alias.db")))
        }

    @Test
    fun `dangling mount symlink cannot hide that the database will live inside the mount`() =
        withDirectory { dir ->
            Files.createSymbolicLink(dir.resolve("mount-link"), dir.resolve("future-root"))
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./future-root/context.db
                        vfs:
                          namespaces: [memory]
                          mounts:
                            - vfsPath: /memory
                              storageKey: local
                              backend: { type: local, root: ./mount-link }
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("context.database.path"), "实际: ${failure.message}")
            assertNull(failure.cause)
            assertFalse(Files.exists(dir.resolve("future-root")), "加载不得创建挂载根")
        }

    @Test
    fun `dangling symlink cycles are rejected safely`() =
        withDirectory { dir ->
            Files.createSymbolicLink(dir.resolve("a"), dir.resolve("b"))
            Files.createSymbolicLink(dir.resolve("b"), dir.resolve("a"))
            val failure =
                assertFailsWith<ContextConfigException> {
                    load(
                        dir,
                        """
                        context:
                          database:
                            path: ./a/context.db
                        vfs:
                          database:
                            path: ./data/vfs.db
                        """,
                    )
                }
            assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
            assertTrue(failure.message.orEmpty().contains("context.database.path"), "实际: ${failure.message}")
            assertNull(failure.cause)
            assertFalse(Files.exists(dir.resolve("data")), "加载不得创建任何目录")
        }

    @Test
    fun `case-insensitive filesystem aliases cannot hide a database inside the mount root`() =
        withDirectory { dir ->
            Files.createDirectory(dir.resolve("CaseRoot"))
            val caseRootReal = runCatching { dir.resolve("CaseRoot").toRealPath() }.getOrNull()
            val caseAliasReal = runCatching { dir.resolve("caseroot").toRealPath() }.getOrNull()
            val caseInsensitive = Files.exists(dir.resolve("caseroot")) && caseRootReal != null && caseRootReal == caseAliasReal
            val yaml =
                """
                context:
                  database:
                    path: ./caseroot/context.db
                vfs:
                  namespaces: [memory]
                  mounts:
                    - vfsPath: /memory
                      storageKey: local
                      backend: { type: local, root: ./CaseRoot }
                """.trimIndent()

            if (caseInsensitive) {
                val failure = assertFailsWith<ContextConfigException> { load(dir, yaml) }
                assertEquals(ContextConfigErrorCode.INVALID_CONFIG, failure.errorCode)
                assertTrue(failure.message.orEmpty().contains("context.database.path"), "实际: ${failure.message}")
                assertNull(failure.cause)
                assertFalse(Files.exists(dir.resolve("CaseRoot/context.db")), "加载不得创建数据库文件")
            } else {
                // 大小写敏感文件系统：caseroot 是另一个（尚不存在的）目录，属于合法配置。
                val config = load(dir, yaml)
                assertEquals(dir.resolve("caseroot/context.db"), config.databasePath)
            }
        }

    // ------------------------------------------------------------------ 夹具

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("ct06-b1-")
        try {
            block(directory)
        } finally {
            deleteRecursively(directory)
        }
    }

    private fun load(
        directory: Path,
        yaml: String,
        name: String = "config.yaml",
    ): ContextConfig = ContextConfigLoader.load(write(directory, yaml, name))

    private fun load(
        directory: Path,
        yaml: String,
        environment: (String) -> String?,
    ): ContextConfig = ContextConfigLoader.load(write(directory, yaml), environment)

    private fun write(
        directory: Path,
        yaml: String,
        name: String = "config.yaml",
    ): Path {
        val file = directory.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, yaml)
        return file
    }

    private fun describeChain(failure: Throwable): String =
        buildString {
            var current: Throwable? = failure
            while (current != null) {
                appendLine(current.message.orEmpty())
                appendLine(current.stackTraceToString())
                current = current.cause
            }
            failure.suppressed.forEach { appendLine(it.stackTraceToString()) }
        }

    private fun deleteRecursively(directory: Path) {
        if (!Files.exists(directory)) return
        val failures = mutableListOf<Throwable>()
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (failure: Throwable) {
                    failures += IllegalStateException("failed to delete test artifact $path", failure)
                }
            }
        }
        if (failures.isNotEmpty()) {
            val primary = failures.first()
            failures.drop(1).forEach(primary::addSuppressed)
            throw primary
        }
    }
}
