package com.kakario.comicviewer.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.nio.ByteBuffer

/**
 * 이미지 풀기 공통.
 *  - 먼저 BitmapFactory(빠름), 안 되면 ImageDecoder(안드로이드 9+, AVIF·HEIC·DNG 등 더 많은 형식).
 *  - 움직이는 GIF·WebP·AVIF는 애니메이션으로.
 */
object ImageCodec {
    /** 가장 긴 변이 maxSide 근처가 되게 줄여서 푼다. */
    fun decode(bytes: ByteArray, maxSide: Int, config: Bitmap.Config? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    if (config != null) inPreferredConfig = config
                },
            )?.let { return it }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                if (sample > 1) d.setTargetSampleSize(sample)
            }
        }.getOrNull()
    }

    /** 움직이는 이미지면 재생용 그림을, 아니면 null. */
    fun animated(bytes: ByteArray, maxSide: Int): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (!maybeAnimated(bytes)) return null
        return runCatching {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { dec, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= maxSide) sample *= 2
                if (sample > 1) dec.setTargetSampleSize(sample)
            }
            (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
        }.getOrNull()
    }

    /** 머리 바이트로 GIF / WebP(애니메이션 표시) / AVIF 시퀀스인지 대강 본다. */
    private fun maybeAnimated(b: ByteArray): Boolean {
        if (b.size < 32) return false
        fun at(o: Int, s: String) = s.indices.all { b[o + it] == s[it].code.toByte() }
        return when {
            at(0, "GIF8") -> true
            at(0, "RIFF") && at(8, "WEBP") -> {
                // VP8X 헤더의 애니메이션 플래그
                at(12, "VP8X") && (b[20].toInt() and 0x02) != 0
            }
            at(4, "ftyp") -> at(8, "avis")
            else -> false
        }
    }
}
