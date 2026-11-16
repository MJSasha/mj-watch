@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UsersTvUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("issueFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val response=JSONObject(java.net.URL("http://127.0.0.1:18544/bootstrap").readText())
        val config=Config(connectionMode="managed",managedUrl=response.getString("url"),serverId=response.getString("serverId"),userId=response.getString("userId"),theme="dark")
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",response.getString("ca"));ManagedApi(config,secrets).saveTokens(response)
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {SyncEngine(ManagedApi(config,secrets),db.syncDao()).connect()}finally{db.close()}
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun remoteOpensUserBesideListAndProtectsLastAdministrator() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.managed.state.value.me?.optString("role")=="admin"}
        compose.runOnIdle {model.page("server-users")}
        compose.waitUntil(15000){model.managed.users.state.value.loaded&&!model.managed.users.state.value.busy}
        compose.onNodeWithTag("users-list").performScrollToNode(hasText("owner"))
        compose.onNodeWithText("owner").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("owner").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(5000){model.state.value.page=="server-user"}
        compose.onNodeWithTag("users-list").assertIsDisplayed()
        compose.onNodeWithTag("user-detail").assertIsDisplayed()
        compose.onNodeWithText("Заблокировать owner").assertIsNotEnabled()
        compose.onNodeWithText("Удалить owner").assertIsNotEnabled()
        compose.onNodeWithText("Устройства owner").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Устройства owner").performKeyInput {pressKey(Key.DirectionCenter)}
        compose.waitUntil(10000){model.managed.users.state.value.devices[model.state.value.config.userId]!=null&&!model.managed.users.state.value.busy}
        compose.onNodeWithText("Обновить устройства").assertExists()
        val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"issues-6-7").apply {mkdirs()}
        compose.waitForIdle();Thread.sleep(350);Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"tv-user-devices.png")))
        compose.runOnIdle {model.back()}
        Assert.assertEquals("server-user",model.state.value.page)
    }
}
