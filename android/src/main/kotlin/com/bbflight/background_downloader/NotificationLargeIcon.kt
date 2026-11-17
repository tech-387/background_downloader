package com.bbflight.background_downloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.bbflight.background_downloader.BDPlugin.Companion.TAG
import org.json.JSONObject
import java.io.File
import kotlin.math.min

/**
 * Large icon of a task's notification (fork addition).
 *
 * A task whose `metaData` is a JSON object with a string `notificationLargeIcon`, the
 * absolute path of an image file, shows that image as the large icon of its regular
 * notifications, e.g. a thumbnail of the uploaded media. The image is cropped to a square
 * from its center and scaled down. Any other `metaData` changes nothing, and a missing
 * or unreadable file shows no large icon.
 */
object NotificationLargeIcon {
    /** Key of the image path in the task's metaData JSON. */
    const val META_DATA_KEY = "notificationLargeIcon"

    /** Side of the square bitmap, enough for the large icon on any screen density. */
    private const val SIZE_PX = 256

    /** Bitmaps by path. A notification is rebuilt on every progress update. */
    private val cache = LruCache<String, Bitmap>(4)

    /** Paths that could not be read, so they are not decoded again on every update. */
    private val failedPaths = LruCache<String, Boolean>(16)

    /** The large icon of [task], or null when it has none. */
    fun forTask(task: Task): Bitmap? = pathOf(task.metaData)?.let { forPath(it) }

    /**
     * The image at the absolute [path], cropped and scaled like the large icon of a task.
     * Null when it cannot be read. Apps can use it for their own notifications.
     */
    fun forPath(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (failedPaths.get(path) != null) return null
        val bitmap = decodeSquare(path, SIZE_PX)
        if (bitmap == null) {
            failedPaths.put(path, true)
        } else {
            cache.put(path, bitmap)
        }
        return bitmap
    }

    /** The image path in [metaData], or null when it is not a JSON object with one. */
    internal fun pathOf(metaData: String): String? {
        val trimmed = metaData.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed).optString(META_DATA_KEY).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /** The image at [path], cropped to a square from its center and at most [size] wide. */
    private fun decodeSquare(path: String, size: Int): Bitmap? = try {
        if (!File(path).isFile) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val shortSide = min(bounds.outWidth, bounds.outHeight)
            if (shortSide <= 0) {
                null
            } else {
                var sampleSize = 1
                while (shortSide / (sampleSize * 2) >= size) sampleSize *= 2
                val decoded = BitmapFactory.decodeFile(
                    path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                decoded?.let { centerSquare(it, size) }
            }
        }
    } catch (e: Throwable) {
        // E.g. OutOfMemoryError: the notification shows without the icon.
        Log.w(TAG, "Could not read the notification large icon: ${e.javaClass.simpleName}")
        null
    }

    private fun centerSquare(source: Bitmap, size: Int): Bitmap {
        val side = min(source.width, source.height)
        val cropped = Bitmap.createBitmap(
            source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled =
            if (side > size) Bitmap.createScaledBitmap(cropped, size, size, true) else cropped
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        if (source !== cropped && source !== scaled) source.recycle()
        return scaled
    }
}
