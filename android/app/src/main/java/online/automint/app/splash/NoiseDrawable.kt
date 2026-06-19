package online.automint.app.splash

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import kotlin.random.Random

class NoiseDrawable(
    private val tileSize: Int = 128,
    density: Float = 0.35f,
    @ColorInt color: Int = 0xFFFFFFFF.toInt(),
    maxPixelAlpha: Int = 18,
    seed: Long = 0x4175746F6D696E74L,
) : Drawable() {

    private val paint = Paint()
    private val tile: Bitmap = buildTile(tileSize, density, color, maxPixelAlpha, seed)

    override fun draw(canvas: Canvas) {
        if (paint.shader == null) {
            paint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun buildTile(
        size: Int,
        density: Float,
        @ColorInt color: Int,
        maxAlpha: Int,
        seed: Long,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rgb = color and 0x00FFFFFF
        val rand = Random(seed)
        val pixels = IntArray(size * size)
        for (i in pixels.indices) {
            if (rand.nextFloat() < density) {
                val a = rand.nextInt(maxAlpha + 1)
                pixels[i] = (a shl 24) or rgb
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        return bitmap
    }
}
