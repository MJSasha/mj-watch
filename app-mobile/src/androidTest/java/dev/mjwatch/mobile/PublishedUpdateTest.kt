package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

/** Manual only: -e published true . Uses public GitHub testing release, never server credentials. */
@RunWith(AndroidJUnit4::class)
class PublishedUpdateTest {
 @get:Rule(order=0) val settings=object:org.junit.rules.ExternalResource() {override fun before() {
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("published")=="true")
    runBlocking {Settings(InstrumentationRegistry.getInstrumentation().targetContext).destination("settings")}
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 @Test fun githubCheckDownloadVerifyAndSystemInstaller() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(65000) {!model.updates.state.value.checking}
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Проверить сейчас"))
    compose.onNodeWithText("Проверить сейчас").performClick()
    compose.waitUntil(65000) {!model.updates.state.value.checking}
    val update=model.updates.state.value.available
    Assert.assertNotNull("Published version must be newer than installed",update)
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Скачать обновление"))
    compose.onNodeWithText("Скачать обновление").performClick()
    compose.waitUntil(180000) {!model.updates.state.value.downloading}
    Assert.assertNotNull(model.updates.state.value.message,model.updates.state.value.ready)
    println("MJWatch published update proof: from=${model.updates.versionCode}, to=${update!!.versionCode}, bytes=${model.updates.state.value.ready!!.length()}, verified=true")
    val allow=model.getApplication<android.app.Application>().packageManager.canRequestPackageInstalls()
    val label=if(allow) "Установить обновление" else "Разрешить установку"
    compose.onNodeWithTag("settings-list").performScrollToNode(hasText(label))
    compose.onNodeWithText(label).performClick()
    val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    if(!allow) {
        Assert.assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")),10000))
        println("MJWatch published update proof: unknown-sources settings opened")
    } else {
        Assert.assertTrue(device.wait(Until.hasObject(By.textContains("update")),15000)||device.wait(Until.hasObject(By.text("Install")),3000))
        println("MJWatch published update proof: native package installer opened")
    }
 }
}
