package com.traducteur.ecran

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : ComponentActivity() {

    private val bgColor = Color.parseColor("#0F1115")
    private val cardColor = Color.parseColor("#1A1D24")
    private val accent = Color.parseColor("#2ECC71")
    private val muted = Color.parseColor("#9AA3AF")
    private val textColor = Color.parseColor("#F3F4F6")

    private lateinit var statusText: TextView
    private lateinit var startBtn: TextView
    private lateinit var keyBox: LinearLayout
    private lateinit var keyInput: EditText
    private lateinit var keyHelp: TextView
    private lateinit var keyLink: TextView
    private var fillingKey = false

    private fun px(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startFlow() }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            startBtn.isEnabled = true
            startBtn.alpha = 1f
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(TranslatorService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(this, "C'est parti ! Ouvre ton webtoon.", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                statusText.text = "Capture refusée. Appuie sur Démarrer et choisis « Écran entier »."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = bgColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(28), px(18), px(28))
        }

        // ----- En-tête -----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) },
            LinearLayout.LayoutParams(px(52), px(52)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
        }
        titles.addView(label("Traducteur Écran", 24f, textColor, true))
        titles.addView(label("Anglais → Français, par-dessus ton écran", 14f, muted))
        header.addView(titles)
        root.addView(header)
        space(root, 20)

        // ----- Moteur de traduction -----
        val engineCard = card(root, "Moteur de traduction")
        val group = RadioGroup(this)
        val engines = listOf(
            Prefs.GEMINI to "IA Gemini · naturel et gratuit (recommandé)",
            Prefs.CLAUDE to "IA Claude · qualité maximale (payant)",
            Prefs.OFFLINE to "Hors-ligne · basique, sans Internet"
        )
        val current = Prefs.engine(this)
        for ((engineId, title) in engines) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.tag = engineId
            rb.text = title
            rb.textSize = 15f
            rb.setTextColor(textColor)
            rb.buttonTintList = ColorStateList.valueOf(accent)
            rb.setPadding(px(6), px(10), 0, px(10))
            group.addView(rb)
            if (engineId == current) rb.isChecked = true
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val rb = g.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            Prefs.setEngine(this, rb.tag as String)
            refreshKeyUi()
            refreshStatus()
        }
        engineCard.addView(group)

        keyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(8), 0, 0)
        }
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(muted)
            background = rounded(Color.parseColor("#11141A"), 12f, Color.parseColor("#2A2F3A"))
            setPadding(px(14), px(12), px(14), px(12))
        }
        keyInput.doAfterTextChanged { txt ->
            if (!fillingKey) {
                Prefs.setKey(this, Prefs.engine(this), txt?.toString() ?: "")
                refreshStatus()
            }
        }
        keyHelp = label("", 13f, muted).apply { setPadding(0, px(8), 0, 0) }
        keyLink = label("", 14f, accent, true).apply { setPadding(0, px(8), 0, px(2)) }
        keyBox.addView(keyInput)
        keyBox.addView(keyHelp)
        keyBox.addView(keyLink)
        engineCard.addView(keyBox)

        // ----- Affichage -----
        val displayCard = card(root, "Affichage des traductions")
        val sizeLabel = label("", 15f, textColor)
        displayCard.addView(sizeLabel)
        sizeLabel.text = "Taille max du texte : ${Prefs.textSize(this)}"
        val seek = SeekBar(this)
        seek.max = 10
        seek.progress = Prefs.textSize(this) - 10
        seek.progressTintList = ColorStateList.valueOf(accent)
        seek.thumbTintList = ColorStateList.valueOf(accent)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + 10
                Prefs.setTextSize(this@MainActivity, v)
                sizeLabel.text = "Taille max du texte : $v"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        displayCard.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(6); bottomMargin = px(12) })
        val darkSwitch = Switch(this)
        darkSwitch.text = "Bulles sombres"
        darkSwitch.textSize = 15f
        darkSwitch.setTextColor(textColor)
        darkSwitch.isChecked = Prefs.dark(this)
        darkSwitch.setOnCheckedChangeListener { _, checked -> Prefs.setDark(this, checked) }
        displayCard.addView(darkSwitch)

        // ----- État + boutons -----
        statusText = label("", 14f, muted).apply {
            setPadding(px(4), px(4), px(4), px(14))
            setLineSpacing(0f, 1.3f)
        }
        root.addView(statusText)

        startBtn = button("▶  Démarrer la traduction", accent, Color.parseColor("#0B1F12"), true) { onStartClicked() }
        root.addView(startBtn, full(56))
        space(root, 10)
        root.addView(button("Arrêter", cardColor, textColor, false) {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            Toast.makeText(this, "Traduction arrêtée", Toast.LENGTH_SHORT).show()
        }, full(50))
        space(root, 18)
        root.addView(label(
            "Astuce : défile normalement, arrête-toi pour lire. Bulle verte : appui court = pause, " +
                "appui long = fermer, glisser = déplacer. Un anneau orange tourne pendant la traduction.",
            13f, muted
        ).apply { setLineSpacing(0f, 1.25f) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            addView(root)
        })
        refreshKeyUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- Petits outils d'interface ----------

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = radiusDp * resources.displayMetrics.density
        if (stroke != null) d.setStroke(px(1), stroke)
        return d
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(cardColor, 18f)
        c.setPadding(px(16), px(14), px(16), px(14))
        val t = label(title.uppercase(), 12f, muted, true)
        t.letterSpacing = 0.08f
        t.setPadding(0, 0, 0, px(6))
        c.addView(t)
        parent.addView(c, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = px(14) })
        return c
    }

    private fun button(t: String, bg: Int, fg: Int, bold: Boolean, onClick: () -> Unit): TextView {
        val b = label(t, 16f, fg, bold)
        b.gravity = Gravity.CENTER
        b.background = rounded(bg, 16f)
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun full(hDp: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(hDp))

    private fun space(parent: LinearLayout, hDp: Int) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, px(hDp)))
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    private fun refreshKeyUi() {
        val engine = Prefs.engine(this)
        keyBox.visibility = if (engine == Prefs.OFFLINE) View.GONE else View.VISIBLE
        fillingKey = true
        keyInput.setText(Prefs.key(this, engine))
        fillingKey = false
        when (engine) {
            Prefs.GEMINI -> {
                keyInput.hint = "Colle ta clé API Gemini ici"
                keyHelp.text = "Gratuite, sans carte bancaire. Elle reste uniquement sur ton téléphone."
                keyLink.text = "→ Créer ma clé gratuite (Google AI Studio)"
                keyLink.setOnClickListener { open("https://aistudio.google.com/apikey") }
            }
            Prefs.CLAUDE -> {
                keyInput.hint = "Colle ta clé API Claude ici"
                keyHelp.text = "Payant à l'usage (quelques centimes par chapitre). Elle reste uniquement sur ton téléphone."
                keyLink.text = "→ Créer ma clé (console Anthropic)"
                keyLink.setOnClickListener { open("https://console.anthropic.com/settings/keys") }
            }
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val engine = Prefs.engine(this)
        val keyOk = Prefs.key(this, engine).isNotBlank()
        statusText.text = buildString {
            append(if (overlayOk) "✅ Superposition autorisée" else "⚠️ Superposition à autoriser (on te guidera)")
            append('\n')
            append(
                when {
                    engine == Prefs.OFFLINE -> "ℹ️ Mode hors-ligne : traduction basique"
                    keyOk -> "✅ Clé IA enregistrée"
                    else -> "⚠️ Ajoute ta clé IA, sinon la traduction sera basique"
                }
            )
        }
    }

    // ---------- Démarrage ----------

    private fun onStartClicked() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startFlow()
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Active « Traducteur Écran » puis reviens ici", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startBtn.isEnabled = false
        startBtn.alpha = 0.6f
        statusText.text = "Préparation du dictionnaire de secours (1re fois : ~30 Mo, Wi-Fi conseillé)…"
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                statusText.text = "✅ Prêt. Choisis « Écran entier » puis valide."
                launchCapture()
            }
            .addOnFailureListener {
                translator.close()
                startBtn.isEnabled = true
                startBtn.alpha = 1f
                statusText.text = "❌ Téléchargement impossible. Vérifie ta connexion et réessaie."
            }
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else
            mpm.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }
}
