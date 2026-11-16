package dev.mjwatch.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Dedicated fixture on 18543/18544. No real invitations are sent. */
@RunWith(AndroidJUnit4::class)
class InvitationLinkUiTest {
    private lateinit var owner:ManagedApi
    private lateinit var address:String
    private lateinit var ca:String
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("invitationFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val data=JSONObject(java.net.URL("http://127.0.0.1:18544/bootstrap").readText())
        address=data.getString("url");ca=data.getString("ca")
        val config=Config(connectionMode="managed",managedUrl=address,serverId=data.getString("serverId"),userId=data.getString("userId"))
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(address).origin()}",ca)
        owner=ManagedApi(config,secrets);owner.saveTokens(data)
        secrets.put("ca:${endpoint(address).origin()}","")
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        Settings(c).handleLegacyHistory();Settings(c).save(Config(theme="dark"));Settings(c).destination("home")
    }}
    @get:Rule(order=1) val compose=createEmptyComposeRule()
    private fun newUser():Pair<String,String> = runBlocking {
        val row=owner.authorized("admin/users","POST",JSONObject().put("name","invite-${System.nanoTime()}"))
        row.getString("userId") to invitationLink(address,row.getString("invite"),ca)
    }
    private fun view(link:String)=Intent(Intent.ACTION_VIEW,Uri.parse(link)).setClass(InstrumentationRegistry.getInstrumentation().targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun fillAndRegister() {
        compose.onNodeWithContentDescription("Придумайте пароль · от 10 символов").performTextInput("fixture-password-123")
        compose.onNodeWithContentDescription("Повторите пароль").performTextInput("fixture-password-123")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach {it.window.insetsController?.hide(android.view.WindowInsets.Type.ime())} }
        compose.onNodeWithText("Создать аккаунт и войти").performScrollTo().performClick()
    }
    @Test fun coldAndWarmLinksOpenRegistrationThenJoinWithoutPastingSettings() {
        val first=newUser();val second=newUser()
        ActivityScenario.launch<MainActivity>(view(first.second)).use {scenario->
            lateinit var model:WatchModel
            scenario.onActivity {model=ViewModelProvider(it)[WatchModel::class.java]}
            compose.waitUntil(10000){model.state.value.page=="invitation-register"}
            compose.onNodeWithText("Вас пригласили в MJWatch").assertExists()
            compose.onNodeWithText("Имя пользователя").assertDoesNotExist()
            compose.waitForIdle();Thread.sleep(350)
            val i=InstrumentationRegistry.getInstrumentation();val dir=File(i.targetContext.getExternalFilesDir(null),"invitation-links").apply {mkdirs()}
            Assert.assertTrue(androidx.test.uiautomator.UiDevice.getInstance(i).takeScreenshot(File(dir,"registration.png")))
            Assert.assertFalse(model.state.value.config.historyEnabled)
            Assert.assertEquals("",model.secrets.get("ca:${endpoint(address).origin()}"))
            fillAndRegister()
            compose.waitUntil(20000){model.state.value.config.userId==first.first&&model.state.value.page=="home"}
            Assert.assertEquals(managedCaFingerprint(ca),managedCaFingerprint(model.secrets.get("ca:${endpoint(address).origin()}")))
            scenario.onActivity {it.startActivity(view(second.second))}
            compose.waitUntil(10000){model.invitation.state.value.link?.token==decodeInvitationLink(second.second).token}
            Assert.assertEquals("",compose.onNodeWithContentDescription("Придумайте пароль · от 10 символов").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            fillAndRegister()
            compose.waitUntil(20000){model.state.value.config.userId==second.first&&model.state.value.page=="home"}
            scenario.onActivity {it.startActivity(view(first.second))}
            compose.waitUntil(10000){model.state.value.page=="invitation-register"}
            fillAndRegister()
            compose.waitUntil(10000){model.invitation.state.value.message.contains("уже использовано")}
            Assert.assertEquals(second.first,model.state.value.config.userId)
        }
    }
    @Test fun wrongCertificatePinNeverSendsPasswordOrChangesTrust() {
        val user=newUser();val parsed=decodeInvitationLink(user.second)
        val bad=user.second.replace(parsed.pin,"0".repeat(64))
        ActivityScenario.launch<MainActivity>(view(bad)).use {scenario->
            lateinit var model:WatchModel
            scenario.onActivity {model=ViewModelProvider(it)[WatchModel::class.java]}
            compose.waitUntil(10000){model.state.value.page=="invitation-register"}
            fillAndRegister()
            compose.waitUntil(15000){!model.invitation.state.value.busy&&model.invitation.state.value.message.isNotBlank()}
            Assert.assertFalse(model.state.value.config.historyEnabled)
            Assert.assertEquals("",model.secrets.get("ca:${endpoint(address).origin()}"))
            runBlocking {Assert.assertTrue(managedDevices(owner.authorized("admin/users/${user.first}/devices")).isEmpty())}
        }
    }
}
