package com.traducteur.ecran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

data class TranslatedBlock(val rect: Rect, val text: String)

/** Lit le texte d'une image, regroupe les bulles, puis traduit (IA, ou hors-ligne en secours). */
class TextProcessor(private val ctx: Context) {

    private class Raw(var rect: Rect, var text: String, var lineH: Int)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val offline = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.FRENCH)
            .build()
    )
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }
    private var ai: AiTranslator? = null
    private var aiSignature = ""

    /** Appelé quand l'IA échoue (clé invalide, quota, réseau…). */
    var onError: ((String) -> Unit)? = null

    suspend fun prepare() {
        offline.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun process(bitmap: Bitmap): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val raws = ArrayList<Raw>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val lines = max(1, b.lines.size)
            raws += Raw(Rect(box), b.lines.joinToString(" ") { it.text }, box.height() / lines)
        }
        val items = merge(raws)
            .map { it to normalize(it.text) }
            .filter { isWorthTranslating(it.second) }
        if (items.isEmpty()) return emptyList()

        val missing = items.map { it.second }.filter { cache[it] == null }.distinct()
        var fresh: Map<String, String> = emptyMap()
        if (missing.isNotEmpty()) {
            val (translations, cacheable) = translateBatch(missing)
            fresh = missing.zip(translations).toMap()
            // Les traductions de secours ne sont pas gardées : l'IA réessaiera plus tard.
            if (cacheable) cache.putAll(fresh)
        }
        return items.map { (raw, src) -> TranslatedBlock(raw.rect, fresh[src] ?: cache[src] ?: src) }
    }

    private suspend fun translateBatch(lines: List<String>): Pair<List<String>, Boolean> {
        val engine = Prefs.engine(ctx)
        val key = Prefs.key(ctx, engine)
        if (engine != Prefs.OFFLINE && key.isNotBlank()) {
            val sig = "$engine|$key"
            if (ai == null || aiSignature != sig) {
                ai = AiTranslator(engine, key)
                aiSignature = sig
            }
            try {
                return ai!!.translate(lines) to true
            } catch (e: Exception) {
                onError?.invoke(describe(e))
            }
        }
        val out = lines.map { offline.translate(it).await() }
        return out to (engine == Prefs.OFFLINE || key.isBlank())
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            400, 401, 403 -> "Clé IA refusée : vérifie-la dans l'appli. Traduction hors-ligne en attendant."
            429 -> "Limite de l'IA atteinte pour le moment. Traduction hors-ligne en attendant."
            else -> "IA indisponible (erreur ${e.code}). Traduction hors-ligne en attendant."
        }
        is IOException -> "Pas de connexion : traduction hors-ligne en attendant."
        else -> "Réponse de l'IA illisible : traduction hors-ligne pour cet écran."
    }

    fun close() {
        recognizer.close()
        offline.close()
    }

    /** Regroupe les morceaux de texte qui appartiennent à la même bulle. */
    private fun merge(list: List<Raw>): List<Raw> {
        val out = ArrayList<Raw>()
        for (b in list.sortedBy { it.rect.top }) {
            val target = out.lastOrNull { m ->
                val overlap = min(m.rect.right, b.rect.right) - max(m.rect.left, b.rect.left)
                val narrow = min(m.rect.width(), b.rect.width())
                val gap = b.rect.top - m.rect.bottom
                val lh = max(m.lineH, b.lineH)
                narrow > 0 && overlap > narrow * 0.5f && gap < lh * 0.9f && gap > -lh
            }
            if (target != null) {
                target.text = target.text + " " + b.text
                target.rect.union(b.rect)
                target.lineH = max(target.lineH, b.lineH)
            } else {
                out += b
            }
        }
        return out.sortedWith(compareBy<Raw>({ it.rect.top }, { it.rect.left }))
    }

    private fun isWorthTranslating(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        return letters >= 2 && letters >= s.length * 0.4
    }

    /** Les webtoons écrivent souvent EN MAJUSCULES : on remet une casse normale. */
    private fun normalize(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(\\p{L})- (\\p{L})"), "$1$2")
        val letters = s.filter { it.isLetter() }
        val upper = letters.count { it.isUpperCase() }
        if (letters.isNotEmpty() && upper > letters.length * 0.7) {
            val sb = StringBuilder(s.lowercase())
            var capitalize = true
            for (i in sb.indices) {
                val c = sb[i]
                if (capitalize && c.isLetter()) {
                    sb.setCharAt(i, c.uppercaseChar()); capitalize = false
                } else if (c == '.' || c == '!' || c == '?') capitalize = true
            }
            s = sb.toString().replace(Regex("\\bi\\b"), "I")
        }
        return s
    }
}
