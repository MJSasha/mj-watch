package dev.mjwatch.mobile

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mjwatch.core.GitHubUpdates
import dev.mjwatch.core.verifyUpdatePackage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit live check of the production delivery path; never installs the downloaded APK. */
@RunWith(AndroidJUnit4::class)
class UpdateDeliveryTest {
    @Suppress("DEPRECATION")
    @Test fun publicLatestDownloadsAndPassesInstalledPackageVerification()=runBlocking<Unit> {
        Assume.assumeTrue("Enable explicitly with -e liveUpdates true",InstrumentationRegistry.getArguments().getString("liveUpdates")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val installed=context.packageManager.getPackageInfo(context.packageName,0)
        val installedCode=if(Build.VERSION.SDK_INT>=28) installed.longVersionCode else installed.versionCode.toLong()
        val source=GitHubUpdates()
        val update=source.latest(context.packageName)
        assertNotNull("Public Latest must contain this platform",update)
        val available=requireNotNull(update)
        assertEquals(context.packageName,available.packageName)
        assertTrue(available.versionName.isNotBlank())
        assertTrue(available.size>0)
        Assume.assumeTrue("Install an older compatible build before checking delivery",available.versionCode>installedCode)
        val apk=File.createTempFile("live-update-delivery-",".apk",context.cacheDir)
        try {
            var delivered=0L
            source.download(available,apk) {delivered=it}
            assertEquals(available.size,delivered)
            assertEquals(available.size,apk.length())
            verifyUpdatePackage(context,apk,available)
            println("MJWATCH_LIVE_UPDATE_DELIVERY package=${context.packageName} installedCode=$installedCode availableCode=${available.versionCode} bytes=${apk.length()} verified=true release=${available.releaseUrl}")
        } finally {
            apk.delete()
        }
    }
}
