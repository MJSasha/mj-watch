package dev.mjwatch.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.*
import dev.mjwatch.designsystem.WatchModel
import dev.mjwatch.playback.PlaybackActivity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Manual visual QA only: -e audit true. All catalogue data synthetic; local generated artwork. */
@RunWith(AndroidJUnit4::class)
class AuditScreensTest {
 private lateinit var item:Metadata
 @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
    Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("audit")=="true")
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
    val art="android.resource://${c.packageName}/"+c.resources.getIdentifier("demo_orbit","drawable",c.packageName)
    item=Metadata("tmdb:movie:999999999","Тихая орбита",art,"Вымышленный фильм для проверки интерфейса. На далёкой станции исследовательница принимает неожиданный сигнал.",year=2026,backdrop=art)
    val db=Room.databaseBuilder(c,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
    try {
      val now=System.currentTimeMillis()
      db.dao().cacheMetadata("home",listOf(item,item.copy(id="tmdb:movie:999999998",title="Архив звёзд",year=2025)),now)
      db.dao().cacheMetadata("search:${item.title}",listOf(item),now)
      db.dao().opened(LibraryEntry("demo:orbit",item.title,"Локальная демонстрация",now,metadataId=item.id))
      db.dao().put(Progress("demo:orbit|Сезон 1/Серия 01.mp4","demo:orbit",3,"Сезон 1/Серия 01.mp4",2000,10000,now,false))
    } finally {db.close()}
    Settings(c).save(Config(demo=true,theme="dark",metadataProvider="tmdb"));Settings(c).destination("home")
 }}
 @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
 private fun capture(name:String,settle:Boolean=true) {
    compose.waitForIdle();if(settle) {Thread.sleep(350);compose.mainClock.advanceTimeBy(500);compose.waitForIdle()};val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
    val dir=File(i.targetContext.getExternalFilesDir(null),"audit").apply {mkdirs()}
    Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,name+".png")))
 }
 @Test fun captureReferenceStates() {
    lateinit var model:WatchModel
    compose.runOnIdle {model=ViewModelProvider(compose.activity)[WatchModel::class.java]}
    compose.waitUntil(10000) {model.state.value.catalog.isNotEmpty()}
    capture("home")
    compose.runOnIdle {model.search(item.title)}
    compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.metadata.isNotEmpty()}
    capture("search")
    compose.runOnIdle {model.metadata(item)};capture("detail")
    compose.runOnIdle {model.findReleases()}
    compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
    capture("sources")
    compose.onNodeWithText(DemoReleases.items.first().title).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
    capture("sources-selected")
    compose.runOnIdle {model.open(DemoReleases.items.first());model.prepare()}
    capture("buffer",settle=false)
    compose.waitUntil(10000) {model.state.value.page=="files"}
    compose.onNodeWithText("Сезон 1/Серия 01.mp4").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
    capture("files")
    compose.onNodeWithText("Смотреть",substring=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    var firstFrame=false
    val deadline=System.currentTimeMillis()+20000
    while(!firstFrame&&System.currentTimeMillis()<deadline) {
        instrumentation.runOnMainSync {firstFrame=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().any {it.firstFrameSeen}}
        Thread.sleep(100)
    }
    Assert.assertTrue("Local video first frame",firstFrame)
    UiDevice.getInstance(instrumentation).wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)got it"))),1500)?.click()
    capture("player")
    UiDevice.getInstance(instrumentation).pressBack()
    compose.waitUntil(10000) {model.state.value.page=="detail"}
    compose.runOnIdle {model.page("library")};capture("library")
    compose.runOnIdle {model.page("settings")};capture("settings")
    val c=InstrumentationRegistry.getInstrumentation().targetContext
    runBlocking {Settings(c).save(Config(demo=false,theme="dark",torrServer="http://127.0.0.1:1",localHttp=true))}
    compose.waitUntil(10000) {!model.state.value.config.demo}
    compose.runOnIdle {model.open(Release("audit:error","Недоступный поток","Локальный fixture",hash="1111111111111111111111111111111111111111"));model.prepare()}
    compose.waitUntil(10000) {!model.state.value.busy};capture("error")
    compose.runOnIdle {model.page("home")};capture("direct-home")
    runBlocking {Settings(c).save(Config(demo=true,theme="dark",metadataProvider="tmdb"))}
    compose.waitUntil(10000) {model.state.value.config.demo}
    compose.runOnIdle {model.page("search");model.search("Ничего не найдено fixture")}
    compose.waitUntil(10000) {!model.state.value.busy};capture("empty")
    runBlocking {Settings(c).save(Config(demo=true,theme="light",metadataProvider="tmdb"))}
    compose.runOnIdle {model.page("home")}
    compose.waitUntil(10000) {model.state.value.config.theme=="light"};capture("light")
    runBlocking {Settings(c).save(Config(theme="dark"))}
    compose.waitUntil(10000) {!model.state.value.configured};compose.runOnIdle {model.page("settings")};capture("setup")
 }
}
