package com.wireturn.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandLogRedactorTest {

    @Test
    fun masksOnlyTheValuesOfSensitiveFlags() {
        assertEquals(
            "/lib/libqwdtt.so -peer h:1 -password <redacted> -n 9 -device-id <redacted>",
            CommandLogRedactor.redact(
                listOf("/lib/libqwdtt.so", "-peer", "h:1", "-password", "secret", "-n", "9", "-device-id", "aid"),
                setOf("-password", "-device-id")
            )
        )
    }

    @Test
    fun aSensitiveFlagLastHasNothingToMask() {
        assertEquals("bin -password", CommandLogRedactor.redact(listOf("bin", "-password"), setOf("-password")))
    }

    @Test
    fun theBinaryItselfIsNeverMasked() {
        assertEquals("-password x", CommandLogRedactor.redact(listOf("-password", "x"), setOf("x")))
    }
}
