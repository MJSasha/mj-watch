package dev.mjwatch.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {
    @Test fun recordsOnlyWhenEnabledAndKeepsNewestTwentyWithoutExceptionMessages() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val diagnostics = Diagnostics.get(app)
        diagnostics.record(Diagnostics.Event.CATALOG) { error("disabled events must do no work") }
        assertNull(diagnostics.report())

        diagnostics.setEnabled(true)
        withTimeout(5_000) { diagnostics.enabled.first { it } }
        assertTrue(Settings(app).diagnosticsEnabled.first())
        repeat(25) { index ->
            diagnostics.record(Diagnostics.Event.PLAYER_ERROR) { "code=$index" }
        }
        diagnostics.failure(Diagnostics.Event.DOWNLOAD, IOException("private URL"))
        val report = withTimeout(5_000) {
            var snapshot = diagnostics.report().orEmpty()
            while (!snapshot.contains("DOWNLOAD error=IOException")) {
                delay(10)
                snapshot = diagnostics.report().orEmpty()
            }
            snapshot
        }
        assertEquals(21, report.lineSequence().count()) // Device header and twenty events.
        assertFalse(report.contains("code=0\n"))
        assertFalse(report.contains("private URL"))
        assertTrue(report.contains("code=24\n"))

        diagnostics.setEnabled(false)
        withTimeout(5_000) { diagnostics.enabled.first { !it } }
        diagnostics.record(Diagnostics.Event.CATALOG) { error("disabled events must do no work") }
        assertEquals(report, diagnostics.report())
    }
}
