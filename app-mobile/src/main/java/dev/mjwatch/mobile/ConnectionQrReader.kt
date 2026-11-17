package dev.mjwatch.mobile

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.Reader
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory

internal val connectionQrDecoderFactory=DecoderFactory {hints->Decoder(ConnectionQrReader(hints))}

/** Retry with all finder candidates when ZXing's single-code search stops at a data pattern. */
internal class ConnectionQrReader(private val scannerHints:Map<DecodeHintType,*>?=null):Reader {
    private val primary=QRCodeReader()
    override fun reset()=primary.reset()
    override fun decode(image:BinaryBitmap):Result=decode(image,scannerHints)
    override fun decode(image:BinaryBitmap,hints:Map<DecodeHintType,*>?):Result = try {
        primary.decode(image,hints)
    } catch(e:ReaderException) {
        QRCodeMultiReader().decodeMultiple(image,hints).singleOrNull() ?: throw NotFoundException.getNotFoundInstance()
    }
}
