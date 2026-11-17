package dev.mjwatch.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Keep a four-module quiet zone and high correction for the small centre mark. */
internal fun connectionQrBitmap(value:String):Bitmap {
    val matrix=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,0,0,mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
        EncodeHintType.MARGIN to 4
    ))
    val bitmap=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    val cell=768f/matrix.width
    val ink=Color.rgb(12,14,21)
    val corners=listOf(4 to 4,(matrix.width-11) to 4,4 to (matrix.height-11))
    paint.color=ink
    for(y in 4 until matrix.height-4) for(x in 4 until matrix.width-4) {
        if(matrix[x,y]&&corners.none {(cx,cy)->x in cx until cx+7&&y in cy until cy+7})
            canvas.drawRoundRect(x*cell,y*cell,(x+1)*cell,(y+1)*cell,cell*.16f,cell*.16f,paint)
    }
    for((x,y) in corners) {
        paint.color=ink
        canvas.drawRoundRect(x*cell,y*cell,(x+7)*cell,(y+7)*cell,cell*1.1f,cell*1.1f,paint)
        paint.color=Color.WHITE
        canvas.drawRoundRect((x+1)*cell,(y+1)*cell,(x+6)*cell,(y+6)*cell,cell*.8f,cell*.8f,paint)
        paint.color=ink
        canvas.drawRoundRect((x+2)*cell,(y+2)*cell,(x+5)*cell,(y+5)*cell,cell*.6f,cell*.6f,paint)
    }
    val mark=cell*7
    val start=(768-mark)/2
    paint.color=Color.WHITE
    canvas.drawRoundRect(start-cell,start-cell,start+mark+cell,start+mark+cell,cell*2,cell*2,paint)
    paint.color=Color.rgb(199,255,104)
    canvas.drawRoundRect(start,start,start+mark,start+mark,cell*1.6f,cell*1.6f,paint)
    paint.color=ink;paint.typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD)
    paint.textSize=mark*.52f;paint.textAlign=Paint.Align.CENTER
    canvas.drawText("MJ",384f,384f-(paint.ascent()+paint.descent())/2,paint)
    return bitmap
}
