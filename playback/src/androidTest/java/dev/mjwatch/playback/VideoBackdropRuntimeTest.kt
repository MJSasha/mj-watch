package dev.mjwatch.playback

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoBackdropRuntimeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun samplesRealSurfaceAndRetainsBlackUntilAFrameExists() {
        lateinit var backdrop:VideoBackdrop
        lateinit var surface:SurfaceView
        compose.runOnUiThread {
            val root=FrameLayout(compose.activity)
            backdrop=VideoBackdrop(compose.activity);surface=SurfaceView(compose.activity)
            root.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
            root.addView(surface,FrameLayout.LayoutParams(320,180,Gravity.CENTER))
            compose.activity.setContentView(root)
            assertArrayEquals(intArrayOf(Color.BLACK,Color.BLACK),(backdrop.background as GradientDrawable).colors)
        }
        compose.waitUntil(5000) {surface.holder.surface.isValid&&surface.width>0}
        compose.runOnUiThread {
            val canvas=surface.holder.lockCanvas()
            try {
                val paint=Paint().apply {color=Color.RED}
                canvas.drawRect(0f,0f,canvas.width.toFloat(),canvas.height/2f,paint)
                paint.color=Color.BLUE
                canvas.drawRect(0f,canvas.height/2f,canvas.width.toFloat(),canvas.height.toFloat(),paint)
            } finally {surface.holder.unlockCanvasAndPost(canvas)}
        }
        compose.waitUntil(5000) {
            var sampled=false
            compose.runOnUiThread {
                backdrop.sample(surface)
                val colors=(backdrop.background as GradientDrawable).colors!!
                sampled=Color.red(colors[0])>0&&Color.blue(colors[1])>0
            }
            sampled
        }
        compose.runOnUiThread {
            val colors=(backdrop.background as GradientDrawable).colors!!
            assertEquals(0,Color.green(colors[0]));assertEquals(0,Color.blue(colors[0]))
            assertEquals(0,Color.red(colors[1]));assertEquals(0,Color.green(colors[1]))
        }
    }
}
