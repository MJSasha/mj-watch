package dev.mjwatch.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Requires a dedicated fixture: adb reverse tcp:18543/18544 before running. */
@RunWith(AndroidJUnit4::class)
class UserManagementUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("issueFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val bootstrap=JSONObject(java.net.URL("http://127.0.0.1:18544/bootstrap").readText())
        val config=Config(connectionMode="managed",managedUrl=bootstrap.getString("url"),serverId=bootstrap.getString("serverId"),userId=bootstrap.getString("userId"),theme="dark")
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",bootstrap.getString("ca"));ManagedApi(config,secrets).saveTokens(bootstrap)
        val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {db.dao().clearLegacyHistory();SyncEngine(ManagedApi(config,secrets),db.syncDao()).connect()}finally{db.close()}
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String) {
        compose.waitForIdle();Thread.sleep(350);val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"issues-6-7").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    private fun hideKeyboard() {
        compose.runOnIdle {if(android.os.Build.VERSION.SDK_INT>=30)compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())}
        compose.waitForIdle()
    }
    @Test fun usersInvitationsAndDevicesKeepTheirContextAndErrors() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.managed.state.value.me?.optString("role")=="admin"}
        compose.runOnIdle {model.page("server-users")}
        val controller=model.managed.users
        compose.waitUntil(15000){controller.state.value.loaded&&!controller.state.value.busy}
        compose.onNodeWithTag("users-list").performScrollToNode(hasText("owner"))
        compose.onNodeWithText("owner").performClick()
        compose.onNodeWithText("Заблокировать owner").assertIsNotEnabled()
        compose.onNodeWithText("Удалить owner").assertIsNotEnabled()
        compose.onNodeWithText("Это последний администратор сервера. Его нельзя заблокировать или удалить.").assertExists()
        shot("owner-protection")
        compose.runOnIdle {model.back()}
        compose.onNodeWithText("Добавить пользователя").performClick()
        compose.onNodeWithText("Имя пользователя").performTextReplacement("owner");hideKeyboard()
        compose.onNodeWithText("Создать и получить приглашение").performClick()
        compose.waitUntil(10000){controller.state.value.error}
        compose.onNodeWithText("Это имя уже используется. Выберите другое.").assertExists()
        Assert.assertEquals("owner",controller.state.value.draftName)
        compose.runOnIdle {model.managed.syncNow()}
        compose.waitUntil(15000){!model.managed.state.value.busy}
        compose.onNodeWithText("Это имя уже используется. Выберите другое.").assertExists()
        shot("creation-error-after-sync")
        val name="Анна-${System.currentTimeMillis()}"
        compose.onNodeWithText("Имя пользователя").performTextReplacement(name);hideKeyboard()
        compose.onNodeWithText("Создать и получить приглашение").performClick()
        compose.waitUntil(10000){model.state.value.page=="server-user-invite"&&!controller.state.value.busy}
        compose.onNodeWithText("Приглашение для $name").assertExists()
        val id=controller.state.value.selectedId!!
        val original=controller.state.value.invitations[id]!!
        Assert.assertTrue(controller.state.value.users.any {it.name=="owner"})
        compose.onNodeWithText("Скопировать ссылку").performClick()
        compose.onNodeWithText("Ссылка для $name скопирована").assertExists()
        compose.runOnIdle {controller.load()}
        compose.waitUntil(10000){!controller.state.value.busy}
        Assert.assertEquals(original,controller.state.value.invitations[id])
        shot("named-invitation")
        compose.runOnIdle {model.back()}
        shot("user-overview")
        compose.onNodeWithText("Устройства $name").performClick()
        compose.waitUntil(10000){controller.state.value.devices[id]!=null&&!controller.state.value.busy}
        compose.onNodeWithText("Устройств пока нет").assertExists()
        compose.onNodeWithText("Имя пользователя").assertDoesNotExist()
        shot("named-empty-devices")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Устройства $name").assertExists()
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java];model.back()}
        Assert.assertEquals("server-user",model.state.value.page)
        compose.onNodeWithText("Приглашение для $name").performClick()
        compose.onNodeWithText("Создать новое приглашение").performScrollTo().performClick()
        compose.onNodeWithText("Прежнее приглашение перестанет работать.").assertExists()
        compose.onNodeWithText("Создать новое").performClick()
        compose.waitUntil(10000){model.managed.users.state.value.invitations[id]?.token!=original.token&&!model.managed.users.state.value.busy}
        runBlocking {
            val config=model.state.value.config
            val failure=runCatching {ManagedApi(config,model.secrets).request("auth/accept-invite","POST",JSONObject().put("invite",original.token).put("password","fixture-password-123").put("device","old-invite-test"))}.exceptionOrNull()
            Assert.assertTrue(failure is HttpFailure&&(failure as HttpFailure).status==401)
        }
        compose.runOnIdle {model.back()}
        compose.onNodeWithText("Заблокировать $name").performScrollTo().performClick()
        compose.onNodeWithText("Заблокировать $name?").assertExists()
        compose.onNodeWithText("Заблокировать").performClick()
        compose.waitUntil(10000){model.managed.users.state.value.selected?.blocked==true&&!model.managed.users.state.value.busy}
        compose.onNodeWithText("Доступ заблокирован").assertExists()
        compose.onNodeWithText("Удалить $name").performScrollTo().performClick()
        compose.onNodeWithText("Удалить $name?").assertExists()
        compose.onNode(hasText("Удалить $name") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000){model.state.value.page=="server-users"&&!model.managed.users.state.value.busy}
        Assert.assertFalse(model.managed.users.state.value.users.any {it.id==id})
        shot("compact-user-list")
    }
    @Test fun accountSeparatesDevicesTvAndLogoutAndShowsRadioSelection() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.managed.state.value.me!=null}
        compose.runOnIdle {model.page("managed-account")}
        compose.onNodeWithText("Код телевизора").assertDoesNotExist()
        compose.onNodeWithText("Устройства").performClick()
        compose.waitUntil(10000){!model.managed.state.value.busy&&model.managed.state.value.data?.has("devices")==true}
        compose.onNodeWithText("Это устройство",substring=true).assertExists()
        compose.runOnIdle {model.back()}
        compose.onNodeWithText("Дополнительные действия").performClick()
        compose.onNodeWithText("Импорт прежней истории · 0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Нет прежней истории для импорта").assertExists()
        compose.onNodeWithText("Выйти из аккаунта").performScrollTo().performClick()
        compose.onNode(hasText("Сохранить данные на этом устройстве") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle {model.page("settings")}
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Субтитры"))
        compose.onNodeWithText("Субтитры").performClick()
        compose.onNode(hasText("Русский") and isSelectable()).performClick()
        compose.onNodeWithText("Субтитры").performClick()
        compose.onNode(hasText("Русский") and isSelectable()).assertIsSelected()
        shot("subtitle-radio-selection")
    }
    @Test fun largeTextKeepsLongNamesAndDeviceNavigationReachable() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.managed.state.value.me?.optString("role")=="admin"}
        val controller=model.managed.users
        compose.runOnIdle {controller.load()}
        compose.waitUntil(10000){controller.state.value.loaded&&!controller.state.value.busy}
        val name="Александра Оченьдлинноеимя ${System.currentTimeMillis()%100000}"
        compose.runOnIdle {controller.editName(name);controller.create {model.page("server-user")}}
        compose.waitUntil(10000){model.state.value.page=="server-user"&&!controller.state.value.busy}
        val id=controller.state.value.selectedId
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Устройства $name").performScrollTo().performClick()
        compose.waitUntil(10000){controller.state.value.devices[id]!=null&&!controller.state.value.busy}
        compose.onNodeWithText("Устройств пока нет").performScrollTo().assertIsDisplayed()
        shot("large-text-user-devices")
        compose.onNodeWithContentDescription("Назад").performClick()
        Assert.assertEquals("server-user",model.state.value.page)
        Assert.assertEquals(id,controller.state.value.selectedId)
    }
    @Test fun responseStaysWithItsUserAndAccountChangeClearsInvitations() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(15000){model.managed.state.value.me!=null}
        val controller=model.managed.users
        compose.runOnIdle {controller.load()}
        compose.waitUntil(10000){controller.state.value.loaded&&!controller.state.value.busy}
        compose.runOnIdle {controller.editName("Контекст-${System.currentTimeMillis()}");controller.create {}}
        compose.waitUntil(10000){controller.state.value.invitations.isNotEmpty()&&!controller.state.value.busy}
        val created=controller.state.value.selectedId!!;val owner=model.state.value.config.userId
        compose.runOnIdle {controller.loadDevices(created);controller.select(owner)}
        compose.waitUntil(10000){controller.state.value.devices[created]!=null&&!controller.state.value.busy}
        Assert.assertEquals(owner,controller.state.value.selectedId)
        Assert.assertTrue(controller.state.value.devices[created]!!.isEmpty())
        Assert.assertNull(controller.state.value.devices[owner])
        compose.runOnIdle {model.managed.logout(false)}
        compose.waitUntil(10000){!model.state.value.config.managed&&controller.state.value.scope.isEmpty()}
        Assert.assertTrue(controller.state.value.invitations.isEmpty())
        Assert.assertTrue(controller.state.value.devices.isEmpty())
    }
}
