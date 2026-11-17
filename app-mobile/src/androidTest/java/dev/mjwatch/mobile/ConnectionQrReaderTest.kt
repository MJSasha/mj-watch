package dev.mjwatch.mobile

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import dev.mjwatch.core.TvQrLink
import org.junit.Assert.*
import org.junit.Test

class ConnectionQrReaderTest {
    @Test fun retriesFalseFinderAtMinimumDisplaySizesThroughCameraDecoder() {
        // Unbound synthetic key: seed32086, case195 reproduces the single-finder failure.
        val random=java.util.Random(32086)
        val key=ByteArray(32)
        repeat(196) {random.nextBytes(key)}
        val value=TvQrLink("10.0.2.16",20195,key).encode()
        val renderer=Class.forName("dev.mjwatch.designsystem.ConnectionQrKt")
            .getDeclaredMethod("connectionQrBitmap",String::class.java)
        val image=renderer.invoke(null,value) as Bitmap
        for(size in listOf(768,280,216,160)) {
            val sample=Bitmap.createScaledBitmap(image,size,size,true)
            val source=source(sample)
            if(size==216)assertThrows(ReaderException::class.java) {QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))}
            var points=0
            val hints=mapOf(DecodeHintType.NEED_RESULT_POINT_CALLBACK to ResultPointCallback {points++})
            val decoded=connectionQrDecoderFactory.createDecoder(hints).decode(source)
            assertNotNull("Camera decoder at $size px",decoded)
            assertEquals(BarcodeFormat.QR_CODE,decoded!!.barcodeFormat)
            assertTrue("Scanner callback at $size px",points>0)
            assertTrue("Synthetic link at $size px",value==decoded.text)
            if(sample!==image)sample.recycle()
        }
        image.recycle()
    }

    @Test fun captureActivityInstallsSharedDecoderAfterIntentInitialization() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=Intent(context,QrCaptureActivity::class.java).setAction(Intents.Scan.ACTION)
        ActivityScenario.launch<QrCaptureActivity>(intent).use {scenario->
            scenario.onActivity {activity->
                val view=activity.findViewById<DecoratedBarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_scanner)
                assertSame(connectionQrDecoderFactory,view.barcodeView.decoderFactory)
            }
        }
    }

    @Test fun cameraDecoderRejectsBlankAndNonQrBarcode() {
        val blank=Bitmap.createBitmap(216,216,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        assertNull(connectionQrDecoderFactory.createDecoder(emptyMap<DecodeHintType,Any>()).decode(source(blank)))
        val matrix=com.google.zxing.oned.Code128Writer().encode("12345678",BarcodeFormat.CODE_128,216,216)
        val pixels=IntArray(216*216) {i->if(matrix[i%216,i/216])Color.BLACK else Color.WHITE}
        assertNull(connectionQrDecoderFactory.createDecoder(emptyMap<DecodeHintType,Any>()).decode(RGBLuminanceSource(216,216,pixels)))
        blank.recycle()
    }

    private fun source(image:Bitmap):RGBLuminanceSource {
        val pixels=IntArray(image.width*image.height)
        image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
        return RGBLuminanceSource(image.width,image.height,pixels)
    }
}
