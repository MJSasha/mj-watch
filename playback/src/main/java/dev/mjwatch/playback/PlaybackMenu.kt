package dev.mjwatch.playback

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

internal data class PlaybackMenuRow(
    val label: String,
    val icon: ImageVector? = null,
    val value: String? = null,
    val selected: Boolean = false,
    val opensMenu: Boolean = false,
)

private const val SURFACE = 0xFF222232.toInt()
private const val SURFACE_HIGH = 0xFF242B39.toInt()
private const val TEXT = 0xFFF4F4FA.toInt()
private const val MUTED = 0xFFABB5C5.toInt()
private const val ACCENT = 0xFF31F5C8.toInt()

/** Uses native list selection so Android TV and accessibility can navigate the same rows as touch. */
internal fun createPlaybackMenu(
    context: Context,
    title: String,
    icon: ImageVector,
    rows: List<PlaybackMenuRow>,
    tv: Boolean,
    subtitle: String? = null,
    toggleRows: Boolean = false,
    onCredits: (() -> Unit)? = null,
    choose: (Int, Boolean) -> Unit,
): AlertDialog {
    val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert)
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).roundToInt()
    fun surface(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    fun text(color: Int, size: Float, weight: Boolean = false) = TextView(themed).apply {
        setTextColor(color)
        textSize = size
        if (weight) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // The player uses flat Material ImageVectors. Rasterize each icon once for this native list.
    val icons = mutableMapOf<Pair<ImageVector, Int>, Bitmap>()
    fun ImageView.bind(vector: ImageVector?, color: Int) {
        setImageBitmap(vector?.let {
            icons.getOrPut(it to color) {
                val pixels = dp(24)
                Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    canvas.scale(pixels / it.viewportWidth, pixels / it.viewportHeight)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                    for (node in it.root) if (node is VectorPath) {
                        val path = PathParser().addPathNodes(node.pathData).toPath()
                        path.fillType = node.pathFillType
                        canvas.drawPath(path.asAndroidPath(), paint)
                    }
                }
            }
        })
    }
    fun iconView() = ImageView(themed).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    val header = LinearLayout(themed).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(18), dp(22), dp(12))
        addView(iconView().apply {
            background = surface(SURFACE_HIGH, 12)
            bind(icon, ACCENT)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        addView(LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(TEXT, 22f, weight = true).apply {
                this.text = title
                ViewCompat.setAccessibilityHeading(this, true)
            })
            if (subtitle != null) addView(text(MUTED, 14f).apply { this.text = subtitle })
        })
    }

    data class RowViews(val icon: ImageView, val label: TextView, val value: TextView, val end: ImageView)
    val checked = BooleanArray(rows.size) { rows[it].selected }
    val adapter = object : ArrayAdapter<String>(themed, android.R.layout.simple_list_item_1, rows.map { it.label }) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LinearLayout(themed).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(20), dp(6), dp(20), dp(6))
                val row = RowViews(iconView(), text(TEXT, 16f), text(MUTED, 14f), iconView())
                addView(row.icon, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
                addView(row.label.apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(row.value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(12) })
                addView(row.end, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(10) })
                tag = row
            }
            val views = view.tag as RowViews
            val row = rows[position]
            views.icon.visibility = if (row.icon == null) View.GONE else View.VISIBLE
            views.icon.bind(row.icon, if (checked[position]) ACCENT else MUTED)
            views.label.text = row.label
            ViewCompat.setStateDescription(view, when {
                toggleRows && !row.opensMenu -> if (checked[position]) "Включено" else "Выключено"
                checked[position] -> "Выбрано"
                else -> null
            })
            views.value.text = row.value
            views.value.visibility = if (row.value == null) View.GONE else View.VISIBLE
            val end = when {
                toggleRows && !row.opensMenu -> if (checked[position]) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank
                checked[position] -> Icons.Outlined.Check
                row.opensMenu -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
                else -> null
            }
            views.end.visibility = if (end == null) View.GONE else View.VISIBLE
            views.end.bind(end, if (checked[position]) ACCENT else MUTED)
            return view
        }
    }

    val builder = AlertDialog.Builder(themed).setCustomTitle(header)
    if (toggleRows) builder.setAdapter(adapter, null).setPositiveButton("Готово", null)
    else builder.setAdapter(adapter) { _, index -> choose(index, true) }
    if (onCredits != null) builder.setNeutralButton("SkipDB · ODbL") { _, _ -> onCredits() }
    val dialog = builder.create()
    // Install the list and set the final window bounds before the entrance animation starts.
    dialog.create()
    dialog.window?.apply {
        setBackgroundDrawable(surface(SURFACE, 20))
        setDimAmount(.65f)
        setLayout(dp(minOf(if (tv) 480 else 440, context.resources.configuration.screenWidthDp - 32)),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.listView.apply {
        divider = null
        setPadding(dp(8), 0, dp(8), dp(10))
        selector = InsetDrawable(surface(SURFACE_HIGH, 12).apply {
            if (tv) setStroke(dp(2), TEXT)
        }, dp(2))
        if (toggleRows) setOnItemClickListener { _, _, index, _ ->
            if (rows[index].opensMenu) {
                dialog.dismiss()
                choose(index, true)
                return@setOnItemClickListener
            }
            checked[index] = !checked[index]
            choose(index, checked[index])
            adapter.notifyDataSetChanged()
        }
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ACCENT)
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(MUTED)
    return dialog
}
