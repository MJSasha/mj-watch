package dev.mjwatch.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTest {
 @Test fun cancellationAndSecrets()=runBlocking {
    val i=InstrumentationRegistry.getInstrumentation(); val app=i.targetContext.applicationContext as Application
    Settings(app).save(Config(demo=true))
    val secret=SecretStore(app); secret.put("acceptance-sentinel","sentinel-only-for-test");Assert.assertEquals("sentinel-only-for-test",secret.get("acceptance-sentinel"))
    Assert.assertFalse(app.getSharedPreferences("credentials",0).all.values.any {it.toString().contains("sentinel-only-for-test")})
    Assert.assertEquals(0,app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    lateinit var model:WatchModel;val store=ViewModelStore()
    i.runOnMainSync {model=WatchModel(app);store.put("test",model)}
    withTimeout(5000) {while(!model.state.value.config.demo) delay(20)}
    i.runOnMainSync {model.open(DemoReleases.items.first());model.prepare();model.back()}
    delay(1000)
    Assert.assertEquals("releases",model.state.value.page);Assert.assertFalse(model.state.value.busy)
    Assert.assertNull(model.state.value.handle)
    i.runOnMainSync {store.clear()}
    Settings(app).save(Config(torrServer="https://new.invalid"))
    app.startActivity(android.content.Intent(app,dev.mjwatch.playback.PlaybackActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("uri","https://old.invalid/video.mp4"))
    val device=androidx.test.uiautomator.UiDevice.getInstance(i)
    Assert.assertNotNull(device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.textContains("Адрес сервера изменён")),5000))
    i.runOnMainSync {androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<dev.mjwatch.playback.PlaybackActivity>().forEach {Assert.assertFalse(it.firstFrameSeen);it.finish()}}

 }
}
