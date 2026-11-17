package com.wireturn.app.kernel

import com.wireturn.app.data.kernel.FreeTurnConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FreeTurnKernelTest {

    @Test
    fun redactLogLine_masksTheCaptchaPageQuery() {
        // The core's debug line with the full page URL - the one the WebView loads.
        val line = "[DEBUG] captcha PoW capture server error: browser PoW page " +
            "http://localhost:8765/not_robot_captcha?domain=vk.com&session_token=eyJ.secret&variant=popup"
        assertEquals(
            "[DEBUG] captcha PoW capture server error: browser PoW page " +
                "http://localhost:8765/not_robot_captcha?<redacted>",
            FreeTurnKernel.redactLogLine(line)
        )
        // The regular line already comes without values (captcha.SafeURL) - nothing to mask.
        val safe = "[INFO] captcha PoW capture server error: waiting for browser PoW computation at " +
            "localhost:8765/not_robot_captcha?blank,domain,expired_at,session_token,variant"
        assertEquals(safe, FreeTurnKernel.redactLogLine(safe))
    }

    @Test
    fun redactLogLine_leavesOtherLinesAlone() {
        val manual = "[WARN] proxy HTTP server error: if the browser did not open, go to http://localhost:8765"
        assertEquals(manual, FreeTurnKernel.redactLogLine(manual))
        val other = "[INFO] [STREAM 1] TURN allocation up: relayed=91.231.135.168:58720"
        assertEquals(other, FreeTurnKernel.redactLogLine(other))
    }

    @Test
    fun captchaMode_mapsToTheCoresFlags() {
        val auto = FreeTurnConfig().withCaptchaMode(FreeTurnConfig.CAPTCHA_AUTO)
        assertFalse(auto.manualCaptcha || auto.advancedCaptcha)

        val advanced = FreeTurnConfig(manualCaptcha = true).withCaptchaMode(FreeTurnConfig.CAPTCHA_ADVANCED)
        assertTrue(advanced.advancedCaptcha && !advanced.manualCaptcha)
        assertEquals(FreeTurnConfig.CAPTCHA_ADVANCED, advanced.captchaMode)

        val manual = FreeTurnConfig(advancedCaptcha = true).withCaptchaMode(FreeTurnConfig.CAPTCHA_MANUAL)
        assertTrue(manual.manualCaptcha && !manual.advancedCaptcha)

        // A link's mcap on a profile set to advanced: manual wins, as the core has it.
        assertEquals(FreeTurnConfig.CAPTCHA_MANUAL, FreeTurnConfig(manualCaptcha = true, advancedCaptcha = true).captchaMode)
    }
}
