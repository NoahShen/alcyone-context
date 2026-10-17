package com.github.noahshen.alcyone.context.vfs.gitbackup

import com.github.noahshen.alcyone.context.vfs.VfsUri
import java.nio.file.Path
import java.time.Duration
import java.util.Collections

/**
 * Git 本地目录备份插件配置。
 */
class GitBackupConfig(
    val repositoryRoot: Path,
    eventRoots: Set<VfsUri>,
    val remoteName: String = "origin",
    val branch: String,
    val quietPeriod: Duration = Duration.ofSeconds(60),
    val cron: String = "0 3 * * *",
    val gitExecutable: String = "git",
    val localCommandTimeout: Duration = Duration.ofSeconds(30),
    val pushTimeout: Duration = Duration.ofSeconds(120),
    val shutdownGracePeriod: Duration = Duration.ofSeconds(10),
) {
    val normalizedRepositoryRoot: Path = repositoryRoot.toAbsolutePath().normalize()

    // 防御性拷贝，避免外部集合变异影响配置
    val eventRoots: Set<VfsUri> = Collections.unmodifiableSet(eventRoots.toHashSet())

    init {
        require(repositoryRoot.toString().isNotBlank()) {
            "repositoryRoot must not be blank"
        }
        require(eventRoots.isNotEmpty()) {
            "eventRoots must not be empty"
        }
        @Suppress("SENSELESS_COMPARISON")
        require(!eventRoots.any { it == null }) {
            "eventRoots must not contain null"
        }
        require(remoteName.isNotBlank()) {
            "remoteName must not be blank"
        }
        require(branch.isNotBlank()) {
            "branch must not be blank"
        }
        require(quietPeriod.isPositive && !quietPeriod.isZero) {
            "quietPeriod must be positive, got: $quietPeriod"
        }
        require(gitExecutable.isNotBlank()) {
            "gitExecutable must not be blank"
        }
        require(localCommandTimeout.isPositive && !localCommandTimeout.isZero) {
            "localCommandTimeout must be positive, got: $localCommandTimeout"
        }
        require(pushTimeout.isPositive && !pushTimeout.isZero) {
            "pushTimeout must be positive, got: $pushTimeout"
        }
        require(shutdownGracePeriod.isPositive && !shutdownGracePeriod.isZero) {
            "shutdownGracePeriod must be positive, got: $shutdownGracePeriod"
        }
        // 校验 Cron 格式、可解析性与未来可达性
        CronBackupSchedule(cron)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GitBackupConfig) return false

        if (repositoryRoot != other.repositoryRoot) return false
        if (eventRoots != other.eventRoots) return false
        if (remoteName != other.remoteName) return false
        if (branch != other.branch) return false
        if (quietPeriod != other.quietPeriod) return false
        if (cron != other.cron) return false
        if (gitExecutable != other.gitExecutable) return false
        if (localCommandTimeout != other.localCommandTimeout) return false
        if (pushTimeout != other.pushTimeout) return false
        if (shutdownGracePeriod != other.shutdownGracePeriod) return false

        return true
    }

    override fun hashCode(): Int {
        var result = repositoryRoot.hashCode()
        result = 31 * result + eventRoots.hashCode()
        result = 31 * result + remoteName.hashCode()
        result = 31 * result + branch.hashCode()
        result = 31 * result + quietPeriod.hashCode()
        result = 31 * result + cron.hashCode()
        result = 31 * result + gitExecutable.hashCode()
        result = 31 * result + localCommandTimeout.hashCode()
        result = 31 * result + pushTimeout.hashCode()
        result = 31 * result + shutdownGracePeriod.hashCode()
        return result
    }

    override fun toString(): String =
        "GitBackupConfig(repositoryRoot=$repositoryRoot, eventRoots=$eventRoots, remoteName='$remoteName', " +
            "branch='$branch', quietPeriod=$quietPeriod, cron='$cron', gitExecutable='$gitExecutable', " +
            "localCommandTimeout=$localCommandTimeout, pushTimeout=$pushTimeout, shutdownGracePeriod=$shutdownGracePeriod)"
}
