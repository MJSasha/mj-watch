@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.designsystem.ServerUpdateForm
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.designsystem.WatchTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerUpdateFlowTest {
    @get:Rule(order=0) val seed = object : org.junit.rules.ExternalResource() {
        override fun before() = runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences("updates", 0).edit().putBoolean("automatic", false).commit()
            Settings(context).save(Config(demo=true, theme="dark"))
            Settings(context).destination("settings")
        }
    }
    @get:Rule(order=1) val compose = createAndroidComposeRule<MainActivity>()

    private fun SemanticsNodeInteraction.remoteClick(): SemanticsNodeInteraction {
        performSemanticsAction(SemanticsActions.RequestFocus)
        return performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun requiresOwnerPasswordAndConfirmationWithoutSsh() {
        var requested: String? = null
        compose.runOnUiThread {
            compose.activity.setContent {
                WatchTheme("dark") {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ServerUpdateForm("0.1.22", "0.1.23", true, false) { version, _, _ -> requested = version }
                    }
                }
            }
        }
        val start = compose.onNodeWithText("Начать обновление")
        start.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Пароль SSH").assertDoesNotExist()
        compose.onNodeWithContentDescription("Пароль владельца").performScrollTo().performTextInput("fixture-owner")
        compose.onNodeWithContentDescription("Парольная фраза резервной копии").performScrollTo().performTextInput("fixture-backup-phrase")
        compose.onNodeWithContentDescription("Повторите парольную фразу").performScrollTo().performTextInput("wrong")
        start.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Повторите парольную фразу").performTextReplacement("fixture-backup-phrase")
        start.performScrollTo().remoteClick()
        compose.onNodeWithText("Установить версию 0.1.23?").assertExists()
        compose.onNodeWithText("Отмена").remoteClick()
        compose.runOnIdle { Assert.assertNull(requested) }
        start.performScrollTo().remoteClick()
        compose.onNodeWithText("Установить", substring=false).remoteClick()
        compose.runOnIdle { Assert.assertEquals("0.1.23", requested) }
    }

    @Test fun hidesUpdateFromANonAdminDestination() {
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[WatchModel::class.java].page("server-update")
        }
        compose.onNodeWithText("Начать обновление").assertDoesNotExist()
        compose.onNodeWithContentDescription("Пароль владельца").assertDoesNotExist()
        compose.onNodeWithText("Проверяем права доступа к серверу.").assertExists()
    }
}
