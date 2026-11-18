package io.github.kvth.hiroi.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.google.mlkit.nl.translate.Translation as MlKit

/**
 * ML Kit's on-device translation: once its models are downloaded (about 30 MB per language,
 * from Google), translating needs no network. Everything here blocks; call it off the main thread.
 */
object OnDeviceTranslation {
    private const val TRANSLATE_TIMEOUT_S = 30L
    private const val DOWNLOAD_TIMEOUT_S = 600L

    /** The open translator, kept for the next sentence: loading the models takes a moment. */
    private var open: Pair<String, Translator>? = null

    @Synchronized
    private fun translator(target: String): Translator {
        open?.let { (code, translator) -> if (code == target) return translator else translator.close() }
        val language = TranslateLanguage.fromLanguageTag(target) ?: throw TranslationException("ML Kit can't translate into $target")
        val options = TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(language).build()
        return MlKit.getClient(options).also { open = target to it }
    }

    /** The languages whose models are on the phone (English always is). */
    fun downloaded(): Set<String> = await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java), TRANSLATE_TIMEOUT_S)
        .mapTo(HashSet()) { it.language } + TranslateLanguage.ENGLISH

    /** Whether Japanese → [target] works without downloading. */
    fun isReady(target: String): Boolean = downloaded().containsAll(setOf(TranslateLanguage.JAPANESE, target))

    /** Downloads the models Japanese → [target] needs, if they aren't there yet. */
    fun download(target: String) {
        await(translator(target).downloadModelIfNeeded(DownloadConditions.Builder().build()), DOWNLOAD_TIMEOUT_S)
    }

    /** [text] in [target]; downloads the models first if needed (unless not [allowDownload]). */
    fun translate(text: String, target: String, allowDownload: Boolean = true): String {
        if (!isReady(target)) {
            if (!allowDownload) throw TranslationException("The on-device model isn't downloaded")
            download(target)
        }
        return await(translator(target).translate(text), TRANSLATE_TIMEOUT_S)
    }

    /** Deletes the downloaded models (all but English, which is built in). */
    fun deleteModels() {
        synchronized(this) {
            open?.second?.close()
            open = null
        }
        val manager = RemoteModelManager.getInstance()
        for (model in await(manager.getDownloadedModels(TranslateRemoteModel::class.java), TRANSLATE_TIMEOUT_S)) {
            if (model.language != TranslateLanguage.ENGLISH) await(manager.deleteDownloadedModel(model), TRANSLATE_TIMEOUT_S)
        }
    }

    private fun <T> await(task: com.google.android.gms.tasks.Task<T>, seconds: Long): T = try {
        Tasks.await(task, seconds, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        throw TranslationException(cause.message ?: cause.javaClass.simpleName, cause)
    } catch (e: TimeoutException) {
        throw TranslationException("ML Kit took too long", e)
    }
}
