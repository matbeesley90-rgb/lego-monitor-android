package com.lego.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen photo opened by tapping the notification thumbnail
 * (2026-09-08). Native: pinch to zoom, drag to pan, double-tap to reset,
 * ✕ or Back to return to the shade. Loads the un-cropped, Pi-proxied
 * listing image (`photo_url`) — same Tailscale path as everything else.
 */
class PhotoViewerActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url   = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val sub   = intent.getStringExtra(EXTRA_SUB).orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ZoomImageView(this)
        root.addView(image, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val status = TextView(this).apply {
            text = "Loading photo…"
            setTextColor(Color.parseColor("#9AA0A6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER))

        // Close button, top-left, a dark disc like the system viewer's.
        val close = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B3141518"))
            }
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(14), dp(14) + statusBarInset(), 0, 0)
        })

        // Caption over a bottom gradient.
        val caption = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#C0000000"), Color.TRANSPARENT))
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
            })
            if (sub.isNotBlank()) addView(TextView(context).apply {
                text = sub
                setTextColor(Color.parseColor("#D0D0D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
        }
        root.addView(caption, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))
        setContentView(root)

        if (url.isBlank()) { status.text = "No photo for this listing"; return }
        Thread {
            val bmp = try { fetch(url) } catch (_: Exception) { null }
            runOnUiThread {
                if (bmp == null) {
                    status.text = "Couldn't load the photo — is Tailscale on?"
                } else {
                    status.visibility = View.GONE
                    image.setImageBitmap(bmp)
                }
            }
        }.start()
    }

    /** Download + decode with a sample size so a 4000px marketplace photo
     *  doesn't blow the bitmap budget; ~2048px is plenty for pinch-zoom. */
    private fun fetch(url: String): Bitmap? {
        val bytes = http.newCall(Request.Builder().url(url).get().build())
            .execute().use { r -> if (!r.isSuccessful) return null; r.body?.bytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun statusBarInset(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUB = "sub"
    }
}

/** Minimal pinch-zoom / pan / double-tap-reset image view. Fits the image
 *  on first layout, then lets the matrix drift within [1x, 5x] of that. */
class ZoomImageView(ctx: Context) : AppCompatImageView(ctx) {
    private val m = Matrix()
    private var baseScale = 1f
    private var scale = 1f
    private var fitted = false

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (scale * d.scaleFactor).coerceIn(1f, 5f)
            val f = target / scale
            m.postScale(f, f, d.focusX, d.focusY)
            scale = target
            clamp(); imageMatrix = m
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1f) return false
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1f) fit() else {
                val f = 2.5f / scale
                m.postScale(f, f, e.x, e.y); scale = 2.5f; clamp(); imageMatrix = m
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageBitmap(bm: Bitmap?) { super.setImageBitmap(bm); fitted = false; post { fit() } }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); post { fit() } }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val iw = d.intrinsicWidth.toFloat(); val ih = d.intrinsicHeight.toFloat()
        baseScale = min(width / iw, height / ih)
        m.reset()
        m.postScale(baseScale, baseScale)
        m.postTranslate((width - iw * baseScale) / 2f, (height - ih * baseScale) / 2f)
        scale = 1f; fitted = true
        imageMatrix = m
    }

    /** Keep the image covering the view when zoomed, centred when not. */
    private fun clamp() {
        val d = drawable ?: return
        val v = FloatArray(9); m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val cw = d.intrinsicWidth * s; val ch = d.intrinsicHeight * s
        var tx = v[Matrix.MTRANS_X]; var ty = v[Matrix.MTRANS_Y]
        tx = if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2f else ty.coerceIn(height - ch, 0f)
        v[Matrix.MTRANS_X] = tx; v[Matrix.MTRANS_Y] = ty
        m.setValues(v)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e); gestures.onTouchEvent(e)
        return true
    }
}
