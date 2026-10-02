package com.traducteur.ecran

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

data class TranslatedBlock(val rect: Rect, val text: String)

/** Lit le texte d'une image (OCR) puis le traduit en français, avec un cache. */
class TextProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.FRENCH)
            .build()
    )

    // Garde les 600 dernières traductions : pas de retraduction quand on remonte.
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 600
    }

    suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun process(bitmap: Bitmap): List<TranslatedBlock> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = ArrayList<TranslatedBlock>()
        for (block in result.textBlocks) {
            val box = block.boundingBox ?: continue
            val clean = normalize(block.lines.joinToString(" ") { it.text })
            if (!isWorthTranslating(clean)) continue
            val fr = cache[clean] ?: translator.translate(clean).await().also { cache[clean] = it }
            out += TranslatedBlock(Rect(box), fr)
        }
        return out
    }

    fun close() {
        recognizer.close()
        translator.close()
    }

    private fun isWorthTranslating(s: String): Boolean {
        val letters = s.count { it.isLetter() }
        return letters >= 2 && letters >= s.length * 0.4
    }

    /** Les webtoons écrivent souvent EN MAJUSCULES : on remet une casse normale pour mieux traduire. */
    private fun normalize(raw: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("(\\p{L})- (\\p{L})"), "$1$2") // mots coupés en fin de ligne
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
