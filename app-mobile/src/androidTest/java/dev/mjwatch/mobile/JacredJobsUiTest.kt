package dev.mjwatch.mobile

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.HttpFailure
import dev.mjwatch.designsystem.JacredJobsPanel
import dev.mjwatch.designsystem.WatchTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class JacredJobsUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun activeTrackerCannotBeStartedAndConfirmedStartRefreshesSnapshot() {
        val offline = AtomicBoolean(false)
        val started = AtomicReference<Pair<String, String>?>(null)
        val args = InstrumentationRegistry.getArguments()
        val tv = args.getString("renderTv") == "true"
        ui.runOnUiThread {
            ui.activity.setContent {
                WatchTheme(args.getString("jacredTheme") ?: "dark") {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(
                            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            JacredJobsPanel(tv, load = {
                                if (offline.get()) throw HttpFailure(503)
                                JSONObject(if (started.get() == null) activeSnapshot else """{"jobs":[],"availableStarts":[]}""")
                            }, start = { tracker, job -> started.set(tracker to job) })
                        }
                    }
                }
            }
        }

        fun awaitText(label: String, substring: Boolean = false) {
            ui.waitUntil(5_000) { ui.onAllNodesWithText(label, substring = substring).fetchSemanticsNodes().isNotEmpty() }
        }

        fun activate(action: SemanticsNodeInteraction, scroll: Boolean = true) {
            val node = if (scroll) action.performScrollTo() else action
            if (tv) {
                node.performSemanticsAction(SemanticsActions.RequestFocus)
                    .performKeyInput { pressKey(Key.DirectionCenter) }
            } else node.performClick()
        }

        awaitText("rutracker")
        ui.onNodeWithText("20%").assertExists()
        ui.onNodeWithText("4 из 20 страниц").assertExists()
        ui.onNodeWithContentDescription("Запустить обновление rutracker").assertDoesNotExist()
        capture("jobs")

        offline.set(true)
        activate(ui.onNodeWithContentDescription("Обновить задачи"))
        awaitText("Последние данные на ", substring = true)
        ui.onNodeWithText("rutracker").assertExists()
        ui.onNodeWithContentDescription("Запустить обновление rutor").assertIsNotEnabled()

        offline.set(false)
        activate(ui.onNodeWithContentDescription("Обновить задачи"))
        awaitText("Обновлено в ", substring = true)
        activate(ui.onNodeWithContentDescription("Запустить обновление rutor"))
        activate(ui.onNodeWithText("Отмена"), scroll = false)
        assertEquals(null, started.get())
        activate(ui.onNodeWithContentDescription("Запустить обновление rutor"))
        capture("confirmation")
        activate(ui.onNode(hasText("Запустить") and hasAnyAncestor(isDialog())), scroll = false)
        awaitText("Нет активных задач")
        assertEquals("rutor" to "UpdateTasksParse", started.get())
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureJacred") != "true") return
        ui.waitForIdle()
        Thread.sleep(300)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "jacred-redesign").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(folder, "$name.png")))
    }

    private val activeSnapshot = """
        {"jobs":[{"id":"run-1","tracker":"rutracker","job":"ParseAllTask",
        "startedAtUtc":"2026-11-25T22:02:48Z","elapsedSeconds":86,"pagesCompleted":4,
        "pagesTotal":20,"percent":20,"currentCategory":"Фильмы","currentPage":4}],
        "availableStarts":[{"tracker":"rutracker","job":"UpdateTasksParse"},{"tracker":"rutor","job":"UpdateTasksParse"}]}
    """
}
