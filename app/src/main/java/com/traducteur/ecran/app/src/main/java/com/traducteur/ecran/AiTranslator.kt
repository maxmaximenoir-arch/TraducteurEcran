package com.traducteur.ecran

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * Traduction par IA : toutes les bulles d'un écran sont envoyées ensemble,
 * avec les dernières répliques en contexte, pour une traduction naturelle
 * qui respecte le ton et les émotions.
 */
class AiTranslator(
    private val engine: String,
    private val apiKey: String,
    private val history: ArrayDeque<Pair<String, String>> = ArrayDeque()
) {

    private var geminiModel: String? = null

    private val systemPrompt = """
Tu es un traducteur professionnel de webtoons et de manhwas, de l'anglais vers le français.
On te donne les bulles d'un écran, dans l'ordre de lecture (de haut en bas).

Règles :
- Traduis le SENS et l'ÉMOTION, jamais mot à mot. Le résultat doit sonner comme un vrai dialogue français écrit par un traducteur pro.
- Adapte le ton à la scène : colère, peur, joie, sarcasme, gêne, hésitation, chuchotement, cri. Garde les « ! », les « ... », les bégaiements (« J-je... ») et l'intensité.
- Entre jeunes, amis ou coéquipiers : tutoiement et français oral naturel, sans en faire trop. Vouvoiement pour les adultes respectés, les inconnus polis, la hiérarchie.
- Remplace les expressions et l'argot anglais par de vrais équivalents français.
- Le texte vient d'une lecture automatique (OCR) : il peut contenir des fautes, des lettres mal lues, des mots collés ou coupés. Retrouve le texte voulu grâce au contexte.
- Garde les noms propres. Utilise le vocabulaire français habituel des termes techniques (par exemple en sport : but, passe décisive, hors-jeu, remplaçant).
- Onomatopées et bruitages : un équivalent français court et percutant.
- Si un élément n'est pas un vrai texte (bruit de lecture, chiffres seuls, menu d'application), renvoie-le tel quel.
- Reste concis : la traduction doit tenir dans une bulle.

Réponds UNIQUEMENT avec un tableau JSON de chaînes, avec exactement le même nombre d'éléments et le même ordre que l'entrée. Aucun autre texte.
""".trimIndent()

    suspend fun translate(lines: List<String>): List<String> = withContext(Dispatchers.IO) {
        val user = buildString {
            if (history.isNotEmpty()) {
                append("Contexte (répliques précédentes déjà traduites, ne pas les renvoyer) :\n")
                for ((en, fr) in history) append("EN: ").append(en).append("  →  FR: ").append(fr).append('\n')
                append('\n')
            }
            append("Bulles à traduire :\n")
            append(JSONArray(lines).toString())
        }
        val raw = if (engine == Prefs.CLAUDE) callClaude(user) else callGemini(user)
        val result = parseArray(raw, lines.size)
        for (i in lines.indices) {
            history.addLast(lines[i] to result[i])
            while (history.size > 12) history.removeFirst()
        }
        result
    }

    // ---------- Gemini (Google AI Studio, offre gratuite) ----------

    private fun callGemini(user: String): String {
        val model = geminiModel ?: pickGeminiModel().also { geminiModel = it }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
            ))
            .put("generationConfig", JSONObject().put("temperature", 0.6).put("responseMimeType", "application/json"))
        val res = http(
            "https://generativelanguage.googleapis.com/v1beta/$model:generateContent",
            body.toString(), mapOf("x-goog-api-key" to apiKey)
        )
        val parts = JSONObject(res).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought", false)) append(p.optString("text"))
            }
        }
    }

    /** Choisit automatiquement le modèle « Flash-Lite » le plus récent (le plus généreux en gratuit). */
    private fun pickGeminiModel(): String {
        val fallback = "models/gemini-2.5-flash-lite"
        return try {
            val res = http(
                "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
                null, mapOf("x-goog-api-key" to apiKey)
            )
            val arr = JSONObject(res).getJSONArray("models")
            val names = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                var ok = false
                for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") ok = true
                val name = m.getString("name")
                val banned = listOf("tts", "image", "embedding", "live", "audio", "vision", "robotics", "computer")
                if (ok && name.contains("gemini") && banned.none { name.contains(it) }) names += name
            }
            fun version(n: String) =
                Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            names.filter { it.contains("flash-lite") && !it.contains("preview") && !it.contains("exp") }.maxByOrNull { version(it) }
                ?: names.filter { it.contains("flash-lite") }.maxByOrNull { version(it) }
                ?: names.filter { it.contains("flash") }.maxByOrNull { version(it) }
                ?: fallback
        } catch (e: ApiException) {
            throw e // clé invalide : on le signale
        } catch (e: Exception) {
            fallback
        }
    }

    // ---------- Claude (Anthropic, payant) ----------

    private fun callClaude(user: String): String {
        val body = JSONObject()
            .put("model", "claude-haiku-4-5-20251001")
            .put("max_tokens", 2000)
            .put("system", systemPrompt)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val res = http(
            "https://api.anthropic.com/v1/messages", body.toString(),
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")
        )
        val content = JSONObject(res).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                if (c.optString("type") == "text") append(c.optString("text"))
            }
        }
    }

    // ---------- Outils ----------

    private fun parseArray(raw: String, expected: Int): List<String> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) throw IllegalStateException("Réponse sans tableau JSON")
        val arr = JSONArray(raw.substring(start, end + 1))
        if (arr.length() != expected) throw IllegalStateException("Nombre de bulles différent")
        return List(expected) { arr.optString(it).trim() }
    }

    private fun http(url: String, body: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, text.take(300))
            return text
        } finally {
            c.disconnect()
        }
    }
}
