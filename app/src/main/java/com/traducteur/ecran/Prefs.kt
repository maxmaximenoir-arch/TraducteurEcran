package com.traducteur.ecran

import android.content.Context

/** Réglages de l'appli, enregistrés uniquement sur le téléphone. */
object Prefs {
    const val GEMINI = "gemini"
    const val CLAUDE = "claude"
    const val OFFLINE = "offline"
    const val MIX = "mix" // Gemini en priorité, Claude en relais

    private fun sp(c: Context) = c.getSharedPreferences("reglages", Context.MODE_PRIVATE)

    fun engine(c: Context): String = sp(c).getString("engine", MIX) ?: MIX
    fun setEngine(c: Context, v: String) = sp(c).edit().putString("engine", v).apply()

    fun key(c: Context, engine: String): String = (sp(c).getString("key_$engine", "") ?: "").trim()
    fun setKey(c: Context, engine: String, v: String) = sp(c).edit().putString("key_$engine", v.trim()).apply()

    fun textSize(c: Context): Int = sp(c).getInt("text_size", 15)
    fun setTextSize(c: Context, v: Int) = sp(c).edit().putInt("text_size", v).apply()

    fun eco(c: Context): Boolean = sp(c).getBoolean("eco", false)
    fun setEco(c: Context, v: Boolean) = sp(c).edit().putBoolean("eco", v).apply()

    fun dark(c: Context): Boolean = sp(c).getBoolean("dark", false)
    fun setDark(c: Context, v: Boolean) = sp(c).edit().putBoolean("dark", v).apply()
}
