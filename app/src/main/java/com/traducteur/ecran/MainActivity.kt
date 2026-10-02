package com.traducteur.ecran

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var startBtn: Button

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startFlow() }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            startBtn.isEnabled = true
            if (res.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(TranslatorService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                Toast.makeText(this, "C'est parti ! Ouvre ton webtoon.", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                status.text = "Capture refusée. Appuie sur Démarrer et choisis « Écran entier »."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Traducteur d'écran"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "\nTraduit en français tout le texte anglais affiché à l'écran.\n\n" +
                "• Défile normalement : les traductions disparaissent pendant le défilement.\n" +
                "• Arrête-toi pour lire : elles s'affichent par-dessus les bulles en ~1 seconde.\n" +
                "• Bulle verte : appui court = pause / reprise, appui long = fermer, glisser = déplacer.\n"
            textSize = 15f
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)

        startBtn = Button(this).apply {
            text = "Démarrer la traduction"
            setOnClickListener { onStartClicked() }
        }
        root.addView(startBtn)
        root.addView(Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                startService(Intent(this@MainActivity, TranslatorService::class.java)
                    .setAction(TranslatorService.ACTION_STOP))
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this))
            "✅ Affichage par-dessus les autres applis : autorisé"
        else
            "⚠️ Il faudra autoriser l'affichage par-dessus les autres applis"
    }

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
        status.text = "Préparation du dictionnaire anglais → français (1re fois : ~30 Mo, Wi-Fi conseillé)…"
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.FRENCH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.close()
                status.text = "✅ Dictionnaire prêt. Choisis « Écran entier » puis valide."
                launchCapture()
            }
            .addOnFailureListener {
                translator.close()
                startBtn.isEnabled = true
                status.text = "❌ Téléchargement impossible. Vérifie ta connexion et réessaie."
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
