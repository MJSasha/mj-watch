package dev.mjwatch.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View

/** Low-resolution, local-only video colours for the uncovered player area. */
internal class VideoBackdrop(context:Context):View(context) {
    private val frame=Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888)
    private val pixels=IntArray(32*18)
    private val handler=Handler(Looper.getMainLooper())
    private var pending=false
    private var closed=false
    private var colors=intArrayOf(0xFF000000.toInt(),0xFF000000.toInt())
    private val gradient=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors)
    init {background=gradient}
    fun sample(view:View?) {
        val surface=view as? SurfaceView ?: return
        if(closed||pending||!isShown||!surface.holder.surface.isValid)return
        pending=true
        try {PixelCopy.request(surface,frame,{result->
            pending=false
            if(closed) {frame.recycle();return@request}
            if(result==PixelCopy.SUCCESS) {
                frame.getPixels(pixels,0,32,0,0,32,18)
                val next=videoBackdropColors(pixels,32,18)
                colors=IntArray(2) {index->
                    var color=0xFF000000.toInt()
                    for(shift in listOf(16,8,0)) {
                        val before=colors[index] shr shift and 255
                        val after=next[index] shr shift and 255
                        color=color or ((before+(after-before)*.4f).toInt() shl shift)
                    }
                    color
                }
                gradient.colors=colors
            }
        },handler)} catch(_:IllegalArgumentException) {pending=false}
    }
    override fun onDetachedFromWindow() {closed=true;if(!pending)frame.recycle();super.onDetachedFromWindow()}
}

internal fun videoBackdropColors(pixels:IntArray,width:Int,height:Int):IntArray {
    val rows=(height/4).coerceAtLeast(1)
    return intArrayOf(0,height-rows).map {start->
        var red=0L;var green=0L;var blue=0L
        for(y in start until start+rows) for(x in 0 until width) {
            val color=pixels[y*width+x]
            red+=color shr 16 and 255;green+=color shr 8 and 255;blue+=color and 255
        }
        val count=width*rows*4L
        0xFF000000.toInt() or ((red/count).toInt() shl 16) or ((green/count).toInt() shl 8) or (blue/count).toInt()
    }.toIntArray()
}
