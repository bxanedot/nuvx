package com.licht.nuvx

import com.licht.nuvx.data.TrackLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLogRedactionTest {

    @Test
    fun sharedLogsKeepHostAndPathButNoTokensOrIp() {
        val line = "403 for https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ip=203.0.113.7&sig=AbC&pot=xyz range=0-1"
        assertEquals("403 for https://rr3---sn-abc.googlevideo.com/videoplayback?… range=0-1", TrackLog.redact(line))
    }

    @Test
    fun cookiesAndTokensAreBlanked() {
        val out = TrackLog.redact("headers Cookie: SAPISID=secret123; Authorization=Bearer abc token=t0k")
        assertFalse(out.contains("secret123"))
        assertFalse(out.contains("abc"))
        assertFalse(out.contains("t0k"))
    }

    @Test
    fun ordinaryLinesAreUntouched() {
        assertEquals("playback failed for a1 at 1200ms (ERROR_CODE_IO_BAD_HTTP_STATUS), attempt 2", TrackLog.redact("playback failed for a1 at 1200ms (ERROR_CODE_IO_BAD_HTTP_STATUS), attempt 2"))
    }

    @Test
    fun diagnosticsCarryOnlyRedactedWarningsAndErrors() {
        TrackLog.d("t", "quiet detail")
        TrackLog.w("t", "stream failed https://rr1---sn-x.googlevideo.com/videoplayback?ip=203.0.113.9&sig=abc", about = "vid12345678")
        TrackLog.e("t", "auth refused cookie=SAPISID123")
        val report = TrackLog.recentProblems()
        assertFalse(report.contains("quiet detail"))
        assertFalse(report.contains("203.0.113.9"))
        assertFalse(report.contains("SAPISID123"))
        assertTrue(report.contains("[vid12345678] stream failed https://rr1---sn-x.googlevideo.com/videoplayback?…"))
        assertTrue(report.lines().any { it.endsWith("auth refused cookie=…") })
    }
}
