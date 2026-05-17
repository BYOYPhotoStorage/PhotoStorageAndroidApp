package com.hriyaan.photostorage.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import androidx.media3.common.MediaItem as Media3MediaItem

data class TranscodeResult(
    val outputFile: File,
    val outputSizeBytes: Long,
    val durationMs: Long
)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class Transcoder(private val context: Context) {

    suspend fun transcode(
        input: Uri,
        output: File,
        targetResolution: String,
        progress: (Float) -> Unit = {}
    ): Result<TranscodeResult> = suspendCancellableCoroutine { cont ->
        val targetHeight = when (targetResolution) {
            RES_720P -> 720
            RES_1080P -> 1080
            else -> {
                cont.resume(Result.failure(IllegalArgumentException("Unknown targetResolution: $targetResolution")))
                return@suspendCancellableCoroutine
            }
        }
        val bitrate = if (targetHeight == 720) BITRATE_720P else BITRATE_1080P

        val inputSize = runCatching {
            context.contentResolver.openAssetFileDescriptor(input, "r")?.use { it.length }
        }.getOrNull() ?: 0L
        val available = output.parentFile?.usableSpace ?: 0L
        if (inputSize > 0 && available < (inputSize * 12 / 10)) {
            cont.resume(Result.failure(IOException("No space available")))
            return@suspendCancellableCoroutine
        }

        val handler = Handler(Looper.getMainLooper())

        handler.post {
            val transformer = try {
                buildTransformer(bitrate, output, cont, handler)
            } catch (t: Throwable) {
                output.delete()
                if (cont.isActive) cont.resume(Result.failure(IOException(t)))
                return@post
            }

            val editedMediaItem = try {
                EditedMediaItem.Builder(Media3MediaItem.fromUri(input))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                    .build()
            } catch (t: Throwable) {
                output.delete()
                if (cont.isActive) cont.resume(Result.failure(IOException(t)))
                return@post
            }

            val progressHolder = ProgressHolder()
            val pollProgress = object : Runnable {
                override fun run() {
                    val state = transformer.getProgress(progressHolder)
                    if (state != Transformer.PROGRESS_STATE_NOT_STARTED &&
                        state != Transformer.PROGRESS_STATE_UNAVAILABLE
                    ) {
                        progress(progressHolder.progress / 100f)
                    }
                    if (cont.isActive) handler.postDelayed(this, PROGRESS_POLL_MS)
                }
            }
            handler.postDelayed(pollProgress, PROGRESS_POLL_MS)

            cont.invokeOnCancellation {
                handler.removeCallbacks(pollProgress)
                handler.post {
                    runCatching { transformer.cancel() }
                    output.delete()
                }
            }

            try {
                transformer.start(editedMediaItem, output.absolutePath)
            } catch (t: Throwable) {
                handler.removeCallbacks(pollProgress)
                output.delete()
                if (cont.isActive) cont.resume(Result.failure(IOException(t)))
            }
        }
    }

    private fun buildTransformer(
        bitrate: Int,
        output: File,
        cont: kotlinx.coroutines.CancellableContinuation<Result<TranscodeResult>>,
        handler: Handler
    ): Transformer {
        val encoderSettings = VideoEncoderSettings.Builder()
            .setBitrate(bitrate)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(encoderSettings)
            .build()

        return Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    if (cont.isActive) {
                        cont.resume(
                            Result.success(
                                TranscodeResult(
                                    outputFile = output,
                                    outputSizeBytes = output.length(),
                                    durationMs = exportResult.durationMs
                                )
                            )
                        )
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacksAndMessages(null)
                    output.delete()
                    if (cont.isActive) cont.resume(Result.failure(IOException(exportException)))
                }
            })
            .build()
    }

    companion object {
        const val RES_720P = "720p"
        const val RES_1080P = "1080p"
        private const val BITRATE_720P = 4_000_000
        private const val BITRATE_1080P = 8_000_000
        private const val PROGRESS_POLL_MS = 250L
    }
}
