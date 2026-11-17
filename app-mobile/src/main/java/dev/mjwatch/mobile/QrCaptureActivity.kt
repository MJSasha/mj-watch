package dev.mjwatch.mobile

import android.os.Bundle
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Keep scanning in the phone's natural portrait orientation. */
class QrCaptureActivity:com.journeyapps.barcodescanner.CaptureActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        findViewById<DecoratedBarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_scanner)
            .barcodeView.decoderFactory=connectionQrDecoderFactory
    }
}
