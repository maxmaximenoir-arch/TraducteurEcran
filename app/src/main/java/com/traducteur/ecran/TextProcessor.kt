package com.traducteur.ecran

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class TranslatedBlock(val rect: Rect, val text: String)

/** Une bulle lue à l'écran : position, texte anglais nettoyé, clé de cache. */
data class Bubble(val rect: Rect, val src: String, val key: String)

/**
 * Lecture du texte + traduction en tâche de fond.
 * Les bulles sont envoyées à l'IA dès qu'elles apparaissent pendant le défilement,
 * pour que la traduction soit déjà prête quand tu t'arrêtes pour lire.
 */
class TextProcessor(private val ctx: Context, private val scope: CoroutineScope) {

    private class Raw(var rect: Rect, var text: String, var lineH: Int)

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val offline = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.FRENCH)
            .build()
    )

    private fun lru(maxSize: Int) = object : LinkedHashMap<String, String>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxSize
    }

    private val aiCache = lru(1500)        // traductions définitives (IA, ou hors-ligne en mode hors-ligne)
    private val offlineCache = lru(1500)   // traductions de secours, affichées en attendant l'IA
    private val recentKeys = ArrayDeque<String>()
    private val pending = LinkedHashMap<String, String>()
    private val inFlight = HashSet<String>()
    private var worker: Job? = null
    private val sharedHistory = ArrayDeque<Pair<String, String>>()
    private val translators = HashMap<String, AiTranslator>()
    private var geminiNextAt = 0L
    private var claudeNextAt = 0L
    private var geminiBlockedUntil = 0L

    /** Appelé quand l'IA échoue (clé invalide, limite atteinte, réseau…). */
    var onError: ((String) -> Unit)? = null
    /** Appelé quand de nouvelles traductions sont prêtes. */
    var onTranslated: (() -> Unit)? = null

    suspend fun prepare() {
        offline.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    fun aiEnabled(): Boolean {
        val g = Prefs.key(ctx, Prefs.GEMINI).isNotBlank()
        val c = Prefs.key(ctx, Prefs.CLAUDE).isNotBlank()
        return when (Prefs.engine(ctx)) {
            Prefs.GEMINI -> g
            Prefs.CLAUDE -> c
            Prefs.MIX -> g || c
            else -> false
        }
    }

    /** Choisit qui traduit : en mode relais, Gemini (gratuit) tant qu'il répond, sinon Claude. */
    private fun pickProvider(now: Long): Pair<String, String>? {
        val g = Prefs.key(ctx, Prefs.GEMINI)
        val c = Prefs.key(ctx, Prefs.CLAUDE)
        return when (Prefs.engine(ctx)) {
            Prefs.GEMINI -> if (g.isNotBlank()) Prefs.GEMINI to g else null
            Prefs.CLAUDE -> if (c.isNotBlank()) Prefs.CLAUDE to c else null
            Prefs.MIX -> when {
                g.isNotBlank() && now >= geminiBlockedUntil -> Prefs.GEMINI to g
                c.isNotBlank() -> Prefs.CLAUDE to c
                g.isNotBlank() -> Prefs.GEMINI to g
                else -> null
            }
            else -> null
        }
    }

    /** OCR : lit et regroupe les bulles de l'image. */
    suspend fun recognize(bitmap: Bitmap): List<Bubble> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val raws = ArrayList<Raw>()
        for (b in result.textBlocks) {
            val box = b.boundingBox ?: continue
            val lines = max(1, b.lines.size)
            raws += Raw(Rect(box), b.lines.joinToString(" ") { it.text }, box.height() / lines)
        }
        return merge(raws).mapNotNull { r ->
            val src = normalize(r.text)
            if (!isWorthTranslating(src)) null else Bubble(Rect(r.rect), src, keyOf(src))
        }
    }

    /** Traduction définitive déjà connue (tolère les petites erreurs de lecture). */
    fun lookup(key: String): String? = aiCache[key] ?: fuzzy(key)

    fun missingAi(bubbles: List<Bubble>): Boolean = aiEnabled() && bubbles.any { lookup(it.key) == null }

    /** Met en file les bulles pas encore traduites ; elles partent par lots en tâche de fond. */
    fun request(bubbles: List<Bubble>) {
        for (b in bubbles) {
            if (b.key in inFlight || pending.containsKey(b.key) || lookup(b.key) != null) continue
            pending[b.key] = b.src
        }
        while (pending.size > 80) pending.remove(pending.keys.first())
        if (pending.isNotEmpty()) ensureWorker()
    }

    /** Textes à afficher : traduction IA si prête, sinon traduction hors-ligne immédiate. */
    suspend fun textsFor(bubbles: List<Bubble>): List<String> = bubbles.map { b ->
        lookup(b.key) ?: offlineCache[b.key] ?: try {
            offline.translate(b.src).await().also { offlineCache[b.key] = it }
        } catch (e: Exception) {
            b.src
        }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (pending.isNotEmpty()) {
                val now = SystemClock.uptimeMillis()
                val provider = pickProvider(now)

                if (provider == null) {
                    // Pas d'IA : traduction hors-ligne.
                    for (k in pending.keys.take(30)) {
                        val src = pending.remove(k) ?: continue
                        try {
                            aiCache[k] = offline.translate(src).await()
                            remember(k)
                        } catch (_: Exception) {}
                    }
                    onTranslated?.invoke()
                    continue
                }

                val engine = provider.first
                val apiKey = provider.second
                val eco = Prefs.eco(ctx)

                // Respecte la limite de requêtes : pendant l'attente, les bulles s'accumulent en un seul lot.
                val nextAt = if (engine == Prefs.CLAUDE) claudeNextAt else geminiNextAt
                if (nextAt > now) {
                    delay(min(nextAt - now, 2000L)) // on réévalue régulièrement (le relais peut changer)
                    continue
                }

                val keys = pending.keys.take(if (eco) 40 else 30)
                val srcs = keys.map { pending.remove(it) ?: "" }
                inFlight.addAll(keys)
                val translator = translators.getOrPut("$engine|$apiKey") {
                    AiTranslator(engine, apiKey, sharedHistory)
                }
                try {
                    val out = translator.translate(srcs)
                    keys.forEachIndexed { i, k -> aiCache[k] = out[i]; remember(k) }
                    val gap = if (engine == Prefs.CLAUDE) {
                        if (eco) 2000L else 800L
                    } else {
                        if (eco) 8000L else 4000L
                    }
                    val t = SystemClock.uptimeMillis()
                    if (engine == Prefs.CLAUDE) claudeNextAt = t + gap else geminiNextAt = t + gap
                    onTranslated?.invoke()
                } catch (e: Exception) {
                    val t = SystemClock.uptimeMillis()
                    val code = (e as? ApiException)?.code
                    if (e is ApiException || e is IOException) {
                        // On réessaiera plus tard (ou avec le relais).
                        keys.forEachIndexed { i, k -> if (!pending.containsKey(k)) pending[k] = srcs[i] }
                        val backoff = when (code) {
                            429 -> 20_000L
                            400, 401, 403 -> 60_000L
                            else -> 8_000L
                        }
                        val relay = Prefs.engine(ctx) == Prefs.MIX &&
                            Prefs.key(ctx, Prefs.CLAUDE).isNotBlank()
                        if (engine == Prefs.GEMINI) {
                            geminiNextAt = t + backoff
                            if (Prefs.engine(ctx) == Prefs.MIX && e is ApiException) {
                                // Gemini bloqué (limite atteinte ou clé refusée) : Claude prend le relais un moment.
                                geminiBlockedUntil = t + when (code) {
                                    429 -> 15 * 60_000L
                                    400, 401, 403 -> 30 * 60_000L
                                    else -> 2 * 60_000L
                                }
                                geminiNextAt = geminiBlockedUntil
                            }
                            onError?.invoke(
                                if (relay && e is ApiException) "Limite Gemini atteinte : Claude prend le relais."
                                else describe(e)
                            )
                        } else {
                            claudeNextAt = t + backoff
                            onError?.invoke(describe(e))
                        }
                    } else {
                        // Réponse illisible : traduction hors-ligne pour ce lot, pour ne pas boucler.
                        keys.forEachIndexed { i, k ->
                            try { aiCache[k] = offline.translate(srcs[i]).await(); remember(k) } catch (_: Exception) {}
                        }
                        if (engine == Prefs.CLAUDE) claudeNextAt = t + 2000L else geminiNextAt = t + 4000L
                        onTranslated?.invoke()
                    }
                } finally {
                    inFlight.removeAll(keys.toSet())
                }
            }
        }
    }

    private fun remember(k: String) {
        recentKeys.addLast(k)
        while (recentKeys.size > 200) recentKeys.removeFirst()
    }

    private fun fuzzy(key: String): String? {
        if (key.length < 8) return null
        var best: String? = null
        var bestSim = 0.86
        for (k in recentKeys) {
            if (abs(k.length - key.length) > key.length * 0.15) continue
            val sim = 1.0 - levenshtein(k, key).toDouble() / max(k.length, key.length)
            if (sim >= bestSim) {
                val v = aiCache[k] ?: continue
                best = v
                bestSim = sim
            }
        }
        return best
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> when (e.code) {
            400, 401, 403 -> "Clé IA refusée : vérifie-la dans l'appli. Traduction hors-ligne en attendant."
            429 -> "Limite de l'IA atteinte pour le moment. Traduction hors-ligne en attendant."
            else -> "IA indisponible (erreur ${e.code}). Traduction hors-ligne en attendant."
        }
        is IOException -> "Pas de connexion : traduction hors-ligne en attendant."
        else -> "Réponse de l'IA illisible : traduction hors-ligne pour quelques bulles."
    }

    fun close() {
        worker?.cancel()
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

    private fun keyOf(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

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
