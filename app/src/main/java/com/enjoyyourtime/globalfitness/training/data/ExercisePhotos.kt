package com.enjoyyourtime.globalfitness.training.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photos of the user's machines, stored as small JPEG files in the app's private storage
 * (`files/exercise_photos`). The same bytes are uploaded to the cloud backup, so they are kept
 * well under the Firestore document limit. All methods are blocking (call them on IO).
 */
class ExercisePhotos(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val dir = File(context.filesDir, DIR).apply { mkdirs() }

    private val cameraDir = File(context.cacheDir, DIR).apply { mkdirs() }

    /**
     * Where the camera writes a new photo (shared through the FileProvider path `exercise_photos/`
     * of the cache) before [importFrom] compresses it into the private folder.
     */
    val cameraTarget: File get() = File(cameraDir, CAMERA_FILE)

    fun file(name: String): File = File(dir, name)

    fun exists(name: String?): Boolean = name != null && file(name).isFile

    /**
     * Reads the picked or captured image, fixes its rotation, scales it to at most [MAX_SIDE_PX]
     * and stores it for [exerciseId]. Returns the new file name, or null if the image cannot be read.
     */
    fun importFrom(uri: Uri, exerciseId: String): String? {
        val bitmap = decode(uri) ?: return null
        val bytes = compress(bitmap)
        bitmap.recycle()
        return store(exerciseId, bytes)
    }

    /** Stores JPEG bytes (from the cloud backup) for [exerciseId]; returns the file name. */
    fun store(exerciseId: String, bytes: ByteArray): String {
        val name = nameFor(exerciseId, System.currentTimeMillis())
        val target = file(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return name
    }

    fun bytes(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    fun delete(name: String?) {
        if (name != null) file(name).delete()
    }

    /** Deletes photo files no note refers to any more (after a restore or a failed edit). */
    fun deleteUnused(used: Set<String>) {
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in used) f.delete() }
    }

    /**
     * [deleteUnused] that keeps files written in the last [graceMs]: the cloud sync cleans up in the
     * background, while the user may be attaching a photo whose note is not saved yet.
     */
    fun deleteUnused(used: Set<String>, graceMs: Long) {
        val cutoff = System.currentTimeMillis() - graceMs
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in used && f.lastModified() < cutoff) f.delete() }
    }

    /** A bitmap no larger than [maxPx] on its long side, for the UI. */
    fun loadBitmap(name: String, maxPx: Int): Bitmap? {
        val f = file(name)
        if (!f.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx) }
        return BitmapFactory.decodeFile(f.path, opts)
    }

    private fun decode(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val long = max(info.size.width, info.size.height)
                if (long > MAX_SIDE_PX) {
                    val scale = MAX_SIDE_PX.toFloat() / long
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(uri)
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeLegacy(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE_PX) }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val degrees = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val long = max(raw.width, raw.height)
        val scale = if (long > MAX_SIDE_PX) MAX_SIDE_PX.toFloat() / long else 1f
        if (degrees == 0f && scale == 1f) return raw
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true).also { if (it !== raw) raw.recycle() }
    }

    /** JPEG at decreasing quality until it fits [MAX_BYTES]. */
    private fun compress(bitmap: Bitmap): ByteArray {
        var quality = START_QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES || quality <= MIN_QUALITY) return bytes
            quality -= 10
        }
    }

    companion object {
        const val DIR = "exercise_photos"
        private const val CAMERA_FILE = "camera.jpg"
        const val MAX_SIDE_PX = 1280
        const val MAX_BYTES = 450_000
        private const val START_QUALITY = 85
        private const val MIN_QUALITY = 45

        /** File names are safe for any exercise id (custom ids contain ':'). */
        fun nameFor(exerciseId: String, at: Long): String =
            exerciseId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + at + ".jpg"

        fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
            var sample = 1
            while (max(width, height) / (sample * 2) >= maxPx) sample *= 2
            return sample
        }
    }
}
