package com.phloots.crittercafe.ui.skin.critter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The raster pieces of one Critter Café theme, made by art/tools/theme_kit.py from Gemini pieces and named
 * `kit_<id>_<piece>`. What is generated and what stays code is in docs/ART-PIPELINE.md ("Level themes"): pieces
 * whose shape follows the level (belts, loop, board size) are drawn by code, using the generated strips and tiles
 * as textures; fixed decoration (backdrop, kitchen, bench) is drawn as is. A missing piece is null and the skin
 * falls back to its vector drawing.
 */
class ThemeKit(val id: String, tint: Color? = null) {
    /**
     * Screen colour behind and around the backdrop (status bar): the backdrop's plain middle, once loaded; without a
     * backdrop the theme's [tint] (so a theme with no art yet still looks different), else the classic background.
     */
    var background: Color = tint ?: CritterPalette.background; private set
    var backdrop: ImageBitmap? = null; private set
    var loop: ImageBitmap? = null; private set
    var kitchen: ImageBitmap? = null; private set
    var bench: ImageBitmap? = null; private set
    var belt: ImageBitmap? = null; private set
    var counter: ImageBitmap? = null; private set
    var frame: ImageBitmap? = null; private set
    var corner: ImageBitmap? = null; private set
    var mat: ShaderBrush? = null; private set
    private var loaded = false

    /** Decodes the pieces once; later calls do nothing. */
    @SuppressLint("DiscouragedApi") // pieces are named by theme; looked up once
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val res = context.resources
        fun piece(name: String): ImageBitmap? {
            val rid = res.getIdentifier("kit_${id}_$name", "drawable", context.packageName)
            if (rid == 0) return null
            val opts = BitmapFactory.Options().apply { inScaled = false }
            return BitmapFactory.decodeResource(res, rid, opts)?.asImageBitmap()
        }
        backdrop = piece("backdrop")?.also { background = middleColor(it) }
        loop = piece("loop")
        kitchen = piece("kitchen")
        bench = piece("bench")
        belt = piece("belt")
        counter = piece("counter")
        frame = piece("frame")
        corner = piece("prop_corner")
        mat = piece("mat")?.let { tile ->
            // one repeat of the weave is MAT_TILE_DP across
            val px = (MAT_TILE_DP * res.displayMetrics.density).roundToInt()
            val scaled = android.graphics.Bitmap.createScaledBitmap(tile.asAndroidBitmap(), px, px, true)
            ShaderBrush(ImageShader(scaled.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
        }
    }

    companion object {
        const val MAT_TILE_DP = 132f

        /** True when the app has [id]'s kit (at least its backdrop). */
        @SuppressLint("DiscouragedApi") // kits are named by theme id
        fun available(context: Context, id: String): Boolean =
            context.resources.getIdentifier("kit_${id}_backdrop", "drawable", context.packageName) != 0

        /** Mean colour of the backdrop's middle (35..65% across, 40..60% down), which the kit prompt keeps plain. */
        private fun middleColor(img: ImageBitmap): Color {
            val b = img.asAndroidBitmap()
            var r = 0L
            var g = 0L
            var bl = 0L
            var n = 0L
            for (y in (b.height * 0.4f).toInt() until (b.height * 0.6f).toInt() step 4) {
                for (x in (b.width * 0.35f).toInt() until (b.width * 0.65f).toInt() step 4) {
                    val c = b.getPixel(x, y)
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    bl += c and 0xFF
                    n++
                }
            }
            if (n == 0L) return CritterPalette.background
            return Color((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
    }
}

private val meshPaint = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
}

/**
 * Bends the horizontal [strip] along a path: [point] gives the path's centre-line [d] px along it, the strip is drawn
 * [width] wide across it from [from] to [to]. The strip repeats every [repeat] px of path (by default its own length
 * at that width); [shift] slides the texture along the path (a moving belt). Each repeat is one bitmap mesh, so the
 * strip follows curves without gaps; [point] is also called a little past [from] and [to] (clamp it on an open path,
 * where the overshoot then collapses to nothing).
 */
fun DrawScope.drawStripAlong(
    strip: ImageBitmap,
    point: (Float) -> Offset,
    from: Float,
    to: Float,
    width: Float,
    repeat: Float = strip.width * width / strip.height,
    shift: Float = 0f,
    alpha: Float = 1f,
) {
    if (to <= from || repeat <= 0f) return
    val bitmap = strip.asAndroidBitmap()
    val cols = ceil(repeat / (5f * density)).toInt().coerceIn(2, 96)
    val verts = FloatArray((cols + 1) * 2 * 2)
    val half = width / 2
    meshPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
    var start = from - (from + shift).mod(repeat)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        while (start < to) {
            for (i in 0..cols) {
                val d = start + repeat * i / cols
                val p = point(d)
                val a = point(d - 1f)
                val b = point(d + 1f)
                val tx = b.x - a.x
                val ty = b.y - a.y
                val len = hypot(tx, ty)
                val nx = if (len < 1e-3f) 0f else -ty / len * half
                val ny = if (len < 1e-3f) 0f else tx / len * half
                verts[i * 2] = p.x - nx
                verts[i * 2 + 1] = p.y - ny
                verts[(cols + 1 + i) * 2] = p.x + nx
                verts[(cols + 1 + i) * 2 + 1] = p.y + ny
            }
            native.drawBitmapMesh(bitmap, cols, 1, verts, 0, null, 0, meshPaint)
            start += repeat
        }
    }
}

/** [repeat] stretched so a closed path of [length] holds a whole number of repeats. */
fun closedRepeat(length: Float, repeat: Float): Float = length / floor(length / repeat + 0.5f).coerceAtLeast(1f)
