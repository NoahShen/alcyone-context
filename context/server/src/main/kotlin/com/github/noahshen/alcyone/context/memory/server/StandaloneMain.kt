package com.github.noahshen.alcyone.context.memory.server

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // This distribution intentionally has no logging provider; library callers keep their own settings.
    installStandaloneUncaughtExceptionHandler()
    exitProcess(StandaloneCommand.run(args, System.out, System.err))
}

internal fun installStandaloneUncaughtExceptionHandler() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        if (failure is VirtualMachineError || failure is ThreadDeath || failure is LinkageError) {
            if (previous != null) {
                previous.uncaughtException(thread, failure)
            } else {
                failure.printStackTrace(System.err)
            }
        }
        // Ordinary background failures are already reflected by Managed/Command's fixed exit result.
    }
}
