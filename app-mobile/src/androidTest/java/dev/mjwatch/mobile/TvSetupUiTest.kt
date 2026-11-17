package dev.mjwatch.mobile

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvSetupUiTest {
    private lateinit var fixture:JSONObject
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource(){override fun before()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("pairingFixture")=="true")
        val c=InstrumentationRegistry.getInstrumentation().targetContext
        val control=InstrumentationRegistry.getArguments().getString("pairingControlPort") ?: "18544"
        fixture=JSONObject(java.net.URL("http://127.0.0.1:$control/bootstrap").readText())
        c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        val config=Config(connectionMode="managed",managedUrl=fixture.getString("url"),serverId=fixture.getString("serverId"),userId=fixture.getString("userId"),theme="dark")
        val secrets=SecretStore(c);secrets.put("ca:${endpoint(config.managedUrl).origin()}",fixture.getString("ca"));ManagedApi(config,secrets).saveTokens(fixture)
        Settings(c).handleLegacyHistory();Settings(c).save(config);Settings(c).destination("settings")
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun phoneShowsTrustFingerprintAndExportsOnlyPublicSettings() {
        lateinit var model:WatchModel
        compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000){model.state.value.config.historyEnabled}
        compose.runOnIdle {model.page("managed-pairing")}
        compose.onNodeWithText("Ввести код вручную").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить настройки TV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(managedCaFingerprint(fixture.getString("ca"))).assertExists()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val values=ContentValues().apply {put(MediaStore.Downloads.DISPLAY_NAME,"mjwatch-tv-export-${System.nanoTime()}.json");put(MediaStore.Downloads.MIME_TYPE,"application/json");put(MediaStore.Downloads.RELATIVE_PATH,"Download")}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        try {
            compose.waitUntil(15000){!model.managed.state.value.busy}
            compose.runOnIdle {model.managed.exportTvSetup(uri)}
            compose.waitUntil(10000){!model.managed.state.value.busy&&model.managed.state.value.message.startsWith("Настройки TV сохранены")}
            val raw=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
            val json=JSONObject(raw)
            Assert.assertEquals(setOf("type","schema","url","ca"),json.keys().asSequence().toSet())
            Assert.assertEquals(endpoint(fixture.getString("url")).origin(),endpoint(decodeManagedTvSetup(raw).url).origin())
            for(key in listOf("accessToken","refreshToken","userId","serverId","invite"))Assert.assertFalse(json.has(key))
        }finally{context.contentResolver.delete(uri,null,null)}
    }
}
