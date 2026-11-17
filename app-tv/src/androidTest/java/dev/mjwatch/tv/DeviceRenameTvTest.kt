@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import org.junit.*
import java.io.File

class DeviceRenameTvTest {
    @get:Rule(order=0)val isolated=object:org.junit.rules.ExternalResource(){override fun before(){Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedRemote")=="true")}}
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    @Test fun dpadRenameAndBackRestoreTheDeviceRow() {
        lateinit var model:WatchModel
        ui.runOnIdle {model=ViewModelProvider(ui.activity)[WatchModel::class.java];model.page("managed-devices")}
        ui.waitUntil(15000){model.managed.state.value.me!=null&&model.managed.state.value.data?.has("devices")==true&&!model.managed.state.value.busy}
        val own=model.managed.state.value.me!!.getString("deviceId")
        val before=managedDevices(model.managed.state.value.data!!).first {it.id==own}.name
        fun open() {
            ui.onNodeWithTag("device-menu-$own").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            ui.onNodeWithTag("device-menu-$own").performKeyInput {pressKey(Key.DirectionCenter)}
            ui.onNodeWithText("Переименовать").performSemanticsAction(SemanticsActions.RequestFocus)
            ui.onNodeWithText("Переименовать").performKeyInput {pressKey(Key.DirectionCenter)}
        }
        open();ui.onNodeWithTag("device-name").performTextReplacement("Не сохранять")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressBack();ui.waitForIdle()
        if(ui.onAllNodesWithText("Переименовать устройство").fetchSemanticsNodes().isNotEmpty())device.pressBack()
        ui.waitUntil(5000){ui.onAllNodesWithText("Переименовать устройство").fetchSemanticsNodes().isEmpty()}
        ui.waitUntil(5000){runCatching {ui.onNodeWithTag("device-menu-$own").assertIsFocused();true}.getOrDefault(false)}
        Assert.assertEquals(before,managedDevices(model.managed.state.value.data!!).first {it.id==own}.name)
        open();ui.onNodeWithTag("device-name").performTextReplacement("ТВ кабинет")
        ui.waitForIdle();Thread.sleep(350)
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"phone-tv-proof").apply {mkdirs()}
        Assert.assertTrue(device.takeScreenshot(File(dir,"11-tv-rename-dpad.png")))
        ui.onNodeWithText("Сохранить").performSemanticsAction(SemanticsActions.RequestFocus)
        ui.onNodeWithText("Сохранить").performKeyInput {pressKey(Key.DirectionCenter)}
        ui.waitUntil(15000){model.managed.state.value.data?.let {it.has("devices")&&managedDevices(it).any {d->d.id==own&&d.name=="ТВ кабинет"}}==true}
        Assert.assertEquals("managed-devices",model.state.value.page)
    }
}
