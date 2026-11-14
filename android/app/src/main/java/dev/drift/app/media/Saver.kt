package dev.drift.app.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dev.drift.core.DriftCore
import dev.drift.core.Media
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One file to save: a direct file URL, or a stream that needs remuxing. */
sealed interface SaveItem {
    data class File(val url: String) : SaveItem
    data class Stream(val url: String, val mimeType: String) : SaveItem
}

/** What "Save" saves for a piece of media (all items of a gallery). */
fun Media.saveItems(): List<SaveItem> = when (this) {
    is Media.Image -> listOf(SaveItem.File(url))
    is Media.Gallery -> items.map { SaveItem.File(it.url) }
    is Media.Gif -> listOf(SaveItem.File(mp4Url))
    is Media.Video -> when {
        // Progressive MP4 from a non-Reddit host: a plain download.
        hlsUrl == null && dashUrl == null && mp4Url != null -> listOf(SaveItem.File(mp4Url!!))
        // Reddit's own fallback MP4 has no audio: remux the stream instead.
        hlsUrl != null -> listOf(SaveItem.Stream(hlsUrl!!, androidx.media3.common.MimeTypes.APPLICATION_M3U8))
        dashUrl != null -> listOf(SaveItem.Stream(dashUrl!!, androidx.media3.common.MimeTypes.APPLICATION_MPD))
        else -> emptyList()
    }
    else -> emptyList()
}

/**
 * Saves media into the gallery (Pictures/Drift, Movies/Drift). Downloads are
 * done by the Rust core into the app cache, then copied into MediaStore.
 */
class Saver(private val context: Context, private val core: DriftCore) {

    suspend fun save(items: List<SaveItem>): Int {
        var saved = 0
        for (item in items) {
            when (item) {
                is SaveItem.File -> saveFile(item.url)
                is SaveItem.Stream -> saveStream(item.url, item.mimeType)
            }
            saved++
        }
        return saved
    }

    private suspend fun saveFile(url: String) {
        val tmp = File(context.cacheDir, "save-${System.nanoTime()}")
        try {
            val dl = core.download(url, tmp.path)
            publish(tmp, dl.mimeType, dl.extension)
        } finally {
            tmp.delete()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun saveStream(url: String, mimeType: String) {
        val tmp = File(context.cacheDir, "save-${System.nanoTime()}.mp4")
        try {
            val item = MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
            // Transformer must be driven from a thread with a Looper.
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
                    val transformer = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                                cont.resumeWithException(e)
                            }
                        })
                        .build()
                    transformer.start(item, tmp.path)
                    cont.invokeOnCancellation { transformer.cancel() }
                }
            }
            publish(tmp, "video/mp4", "mp4")
        } finally {
            tmp.delete()
        }
    }

    /** Copy a finished file into the shared gallery. */
    private suspend fun publish(file: File, mimeType: String, extension: String) = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Saving needs Android 10 or newer" }
        val video = mimeType.startsWith("video/")
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "drift_${System.currentTimeMillis()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Drift",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: error("Couldn't create the file in the gallery")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't write to the gallery")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

/** Save with a short toast for progress and the result. */
suspend fun saveWithToast(context: Context, core: DriftCore, items: List<SaveItem>) {
    if (items.isEmpty()) {
        Toast.makeText(context, "Nothing to save here", Toast.LENGTH_SHORT).show()
        return
    }
    val video = items.any { it is SaveItem.Stream || (it as? SaveItem.File)?.url?.contains(".mp4") == true }
    Toast.makeText(context, if (video) "Saving video…" else "Saving…", Toast.LENGTH_SHORT).show()
    val message = try {
        val n = Saver(context, core).save(items)
        if (n == 1) "Saved to ${if (video) "Movies" else "Pictures"}/Drift" else "Saved $n files to the gallery"
    } catch (e: Throwable) {
        "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
