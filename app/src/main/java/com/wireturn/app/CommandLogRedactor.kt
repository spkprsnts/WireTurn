package com.wireturn.app

/**
 * Masks the values of specific flags in a process command line for the app's own log, which the
 * user may end up sharing for support - the actual arg list passed to the process is untouched.
 */
object CommandLogRedactor {
    fun redact(cmdArgs: List<String>, sensitiveFlags: Set<String>): String {
        return cmdArgs.mapIndexed { i, arg ->
            if (i > 0 && cmdArgs[i - 1] in sensitiveFlags) "<redacted>" else arg
        }.joinToString(" ")
    }
}
