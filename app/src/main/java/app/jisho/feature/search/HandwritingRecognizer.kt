package app.jisho.feature.search

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One point of a pen stroke, in pixels of the writing area. */
data class InkPoint(val x: Float, val y: Float, val t: Long)

/** What the handwriting panel needs from a recognizer: ML Kit in the app, a fake in tests. */
interface InkEngine {
    suspend fun isModelReady(): Boolean
    suspend fun downloadModel()
    /** Removes the downloaded model (settings → 数据与关于); the panel offers the download again. */
    suspend fun deleteModel()
    /** Up to [max] candidate strings for the strokes; [preContext] is the text already typed. */
    suspend fun recognize(strokes: List<List<InkPoint>>, width: Float, height: Float, preContext: String, max: Int = 8): List<String>
    fun close()
}

/**
 * ML Kit Digital Ink Recognition with the Japanese model (design-spec 4.3). The model
 * (about 20 MB) is downloaded on first use and stored in the app's own folder; checking for
 * it and recognizing run offline. ML Kit's first calls in a process are slow (building the
 * model object about 0.15 s, the first download check about 0.7 s), so everything runs on
 * [background], never on the main thread.
 */
class HandwritingRecognizer(private val background: CoroutineDispatcher = Dispatchers.Default) : InkEngine {
    private val model by lazy { DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.JA).build() }
    private val models by lazy { RemoteModelManager.getInstance() }
    private var recognizer: DigitalInkRecognizer? = null

    override suspend fun isModelReady(): Boolean = withContext(background) { models.isModelDownloaded(model).await() }

    override suspend fun downloadModel() = withContext(background) {
        models.download(model, DownloadConditions.Builder().build()).await()
        Unit
    }

    override suspend fun deleteModel() = withContext(background) {
        recognizer?.close()
        recognizer = null
        models.deleteDownloadedModel(model).await()
        Unit
    }

    override suspend fun recognize(strokes: List<List<InkPoint>>, width: Float, height: Float, preContext: String, max: Int): List<String> =
        withContext(background) { recognizeNow(strokes, width, height, preContext, max) }

    private suspend fun recognizeNow(strokes: List<List<InkPoint>>, width: Float, height: Float, preContext: String, max: Int): List<String> {
        if (strokes.isEmpty()) return emptyList()
        val ink = Ink.builder().apply {
            strokes.forEach { s ->
                addStroke(Ink.Stroke.builder().apply { s.forEach { addPoint(Ink.Point.create(it.x, it.y, it.t)) } }.build())
            }
        }.build()
        val context = RecognitionContext.builder()
            .setPreContext(preContext.takeLast(20))
            .setWritingArea(WritingArea(width, height))
            .build()
        val client = recognizer ?: DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).setMaxResultCount(max).build(),
        ).also { recognizer = it }
        return client.recognize(ink, context).await().candidates.map { it.text }.distinct().take(max)
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
