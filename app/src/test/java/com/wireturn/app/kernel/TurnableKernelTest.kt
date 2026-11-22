package com.wireturn.app.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnableKernelTest {

    @Test
    fun manualCaptcha_takesTheCallLinkAndTheScriptPort() {
        // Turnable's PrettyHandler line, ANSI colors already stripped (CoreService does that).
        val line = "2026-11-22 22:40:01.123 [INFO] manual captcha solve required port=40123 " +
            "userscript=http://localhost:40123/captcha_manual.user.js guide=http://localhost:40123/ " +
            "url=https://vk.com/call/join/abcDEF123#port=40123 timeout=10m0s"
        assertEquals(
            TurnableKernel.ManualCaptcha(
                callUrl = "https://vk.com/call/join/abcDEF123#port=40123",
                userScriptUrl = "http://127.0.0.1:40123/captcha_manual.user.js"
            ),
            TurnableKernel.manualCaptchaOf(line)
        )
    }

    @Test
    fun manualCaptcha_withoutPortOrLinkIsNothing() {
        assertNull(TurnableKernel.manualCaptchaOf("[INFO] manual captcha solve required url=https://vk.com/call/join/x"))
        assertNull(TurnableKernel.manualCaptchaOf("[INFO] manual captcha solve required port=1984"))
    }
}
