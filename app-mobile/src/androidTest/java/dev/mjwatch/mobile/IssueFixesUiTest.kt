package dev.mjwatch.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
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
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IssueFixesUiTest {
    private val config=Config(connectionMode="managed",managedUrl="https://127.0.0.1:1",serverId="issue-regression",userId="member",demo=true)
    private val item=Metadata("tmdb:tv:999999999","Тестовый сериал",null,"Описание сериала из кеша",kind="tv",year=2026)
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {override fun before()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
        context.getSharedPreferences("playback-preferences",0).edit().clear().commit()
        Settings(context).handleLegacyHistory();Settings(context).save(config);Settings(context).destination("settings")
        val db=Room.databaseBuilder(context,WatchDatabase::class.java,"watch.db").addMigrations(WatchDatabase.MIGRATION_1_2,WatchDatabase.MIGRATION_2_3).build()
        try {
            db.syncDao().clearEntities(config.scope);db.syncDao().clearOutbox(config.scope)
            db.syncDao().put(SyncCursor(config.scope,"fixture",1))
            db.dao().put(CachedMetadata("${config.scope}:${item.id}",item.json(),System.currentTimeMillis()))
            val entries=listOf(LibraryEntry(item.id,item.title,"TMDB",1,metadataId=item.id),
                LibraryEntry("issue:r1","Series S01 1080p LostFilm","tracker",2,metadataId=item.id),
                LibraryEntry("issue:r2","Series S01 2160p NewStudio","tracker",3,metadataId=item.id))
            for(entry in entries)db.syncDao().put(SyncEntity(config.scope,"opening",entry.id,entry.syncJson().toString(),1))
        } finally {db.close()}
    }}
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    private fun model():WatchModel {
        lateinit var value:WatchModel
        compose.runOnIdle {value=ViewModelProvider(compose.activity)[WatchModel::class.java]}
        compose.waitUntil(10000) {value.state.value.config.scope==config.scope}
        return value
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val i=InstrumentationRegistry.getInstrumentation()
        val dir=File(i.targetContext.getExternalFilesDir(null),"issue-fixes").apply {mkdirs()}
        Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun memberCanSaveParserWithoutServerFieldsOrChangingAccount() {
        val model=model()
        compose.onNodeWithText("TorrServer").assertDoesNotExist()
        compose.onNodeWithText("Каталог").assertDoesNotExist()
        compose.onNodeWithText("Управление сервером").assertDoesNotExist()
        screenshot("member-settings")
        // Reproduce a direct-mode draft left over before joining a managed server.
        compose.runOnIdle {model.draftConfig=Config(jackett="",torrServer="")}
        compose.onNodeWithText("Jackett / JacRed").performClick()
        compose.onNodeWithText("Адрес Jackett / JacRed").performTextReplacement("https://jac.red")
        compose.runOnIdle {
            if(android.os.Build.VERSION.SDK_INT>=30)compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Сохранить"))
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(10000) {model.state.value.page=="settings"&&!model.state.value.busy}
        Assert.assertEquals(config.scope,model.state.value.config.scope)
        Assert.assertEquals(config.managedUrl,model.state.value.config.managedUrl)
        Assert.assertEquals("https://jac.red",model.state.value.config.jackett)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Тема"))
        compose.onNodeWithText("Анимации").assertDoesNotExist()
    }
    @Test fun historyOpensOneFilmCardThenFindsReleases() {
        val model=model()
        compose.waitUntil(10000) {model.scopedLibrary.value.entries.size==3}
        compose.runOnIdle {model.page("history")}
        compose.onNodeWithText("Series S01 1080p LostFilm").assertDoesNotExist()
        compose.onAllNodesWithText(item.title).assertCountEquals(1)
        screenshot("history-film-card")
        compose.onNodeWithText(item.title).performClick()
        compose.waitUntil(10000) {model.state.value.page=="detail"}
        Assert.assertNull(model.state.value.selected)
        Assert.assertEquals(item.id,model.state.value.selectedMetadata?.id)
        compose.onNodeWithText(item.overview!!).assertExists()
        screenshot("history-film-detail")
        compose.onNodeWithText("Раздачи").performClick()
        compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.isNotEmpty()}
        compose.onNodeWithText("Сезон 1").assertExists()
    }
    @Test fun releaseSeasonAndQualityFiltersCannotBypassTvGate() {
        val model=model()
        compose.runOnIdle {model.search("тест")}
        compose.waitUntil(10000) {!model.state.value.busy&&model.state.value.results.size==2}
        compose.runOnIdle {model.page("releases")}
        compose.onNode(hasText("720p") and isSelectable()).performClick()
        compose.onNodeWithText(DemoReleases.items.first().title).assertExists()
        compose.onNodeWithText(DemoReleases.items.last().title).assertDoesNotExist()
        compose.onNodeWithText("Сезон 1").performClick()
        compose.onNodeWithText("Сбросить фильтры").performClick()
        compose.onNodeWithText("Сезон 1").performClick()
        compose.onNodeWithText(DemoReleases.items.last().title).assertExists()
        compose.onNodeWithText(DemoReleases.items.first().title).assertDoesNotExist()
        screenshot("release-season-filter")
        compose.onNodeWithText(DemoReleases.items.last().title).performClick()
        compose.onNodeWithTag("source-continue").assertDoesNotExist()
        compose.runOnIdle {model.playRelease(DemoReleases.items.last(),1);Assert.assertEquals("releases",model.state.value.page);Assert.assertNull(model.state.value.handle)}
        screenshot("season-selection-without-tv")
    }
    @Test fun adaptiveIconAndMonochromeUseTransparentForeground() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val icon=context.applicationInfo.loadIcon(context.packageManager)
        Assert.assertTrue(icon is AdaptiveIconDrawable)
        if(android.os.Build.VERSION.SDK_INT>=33)Assert.assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        val bitmap=Bitmap.createBitmap(640,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(42,45,52))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        for((column,size) in listOf(32,48,64,128).withIndex()) {
            for(row in 0..3) {
                val x=24f+column*155f;val y=16f+row*140f
                canvas.save()
                val rect=RectF(x,y,x+size,y+size)
                val path=android.graphics.Path().apply {if(row==0)addCircle(x+size/2f,y+size/2f,size/2f,android.graphics.Path.Direction.CW) else addRoundRect(rect,size*.22f,size*.22f,android.graphics.Path.Direction.CW)}
                canvas.clipPath(path)
                paint.color=when(row){2->Color.rgb(224,235,210);3->Color.rgb(40,55,30);else->Color.rgb(20,25,37)}
                canvas.drawRect(rect,paint)
                val drawable=context.getDrawable(dev.mjwatch.designsystem.R.drawable.ic_launcher_foreground)!!.mutate()
                if(row>=2)drawable.setTint(if(row==2)Color.rgb(40,55,30) else Color.rgb(224,235,210))
                drawable.setBounds((x-size/4).toInt(),(y-size/4).toInt(),(x+size*1.25f).toInt(),(y+size*1.25f).toInt());drawable.draw(canvas)
                canvas.restore()
            }
        }
        val dir=File(context.getExternalFilesDir(null),"issue-fixes").apply {mkdirs()}
        File(dir,"icon-previews.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
}
