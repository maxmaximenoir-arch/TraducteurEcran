package com.traducteur.ecran

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Capture l'écran, attend que l'utilisateur arrête de défiler,
 * lit le texte anglais, le traduit et l'affiche par-dessus.
 */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "com.traducteur.ecran.START"
        const val ACTION_STOP = "com.traducteur.ecran.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "traduction"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 150L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: OverlayController? = null
    private var processor: TextProcessor? = null
    private var screenW = 0
    private var screenH = 0
    private var latest: Bitmap? = null
    @Volatile private var active = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startAsForeground()
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
                    if (data == null) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    begin(code, data)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Traduction d'écran", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Traducteur actif")
            .setContentText("Touche la bulle verte pour mettre en pause")
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun begin(code: Int, data: Intent) {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels; screenH = dm.heightPixels
        }

        // Capture en 1080 px de large max : assez net pour lire, assez léger pour être rapide.
        val scale = min(1f, 1080f / screenW)
        val capW = (screenW * scale).toInt()
        val capH = (screenH * scale).toInt()
        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        if (p == null) {
            stopSelf()
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        virtualDisplay = p.createVirtualDisplay(
            "traducteur", capW, capH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )

        overlay = OverlayController(this, onToggle = { toggle() }, onClose = { stopSelf() }).also { it.show() }
        processor = TextProcessor(this, scope).also {
            it.onError = { msg -> showError(msg) }
            it.onTranslated = { refreshShownLabels() }
        }

        scope.launch {
            try {
                processor?.prepare()
            } catch (e: Exception) {
                Toast.makeText(
                    this@TranslatorService,
                    "Dictionnaire indisponible : connecte-toi à Internet une première fois.",
                    Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return@launch
            }
            Toast.makeText(
                this@TranslatorService,
                "Traduction active : arrête-toi de défiler pour lire.",
                Toast.LENGTH_SHORT
            ).show()
            runLoop()
        }
    }

    private var lastErrorAt = 0L

    private fun showError(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastErrorAt < 30_000) return
        lastErrorAt = now
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toggle() {
        active = !active
        overlay?.setActive(active)
        if (!active) {
            overlay?.clearLabels()
            showing = false
            shownBubbles = null
        }
    }

    // État de l'affichage (partagé avec le rafraîchissement quand l'IA répond).
    private var showing = false
    private var shownBubbles: List<Bubble>? = null
    private var shownTexts: List<String>? = null
    private var shownFrameW = 1
    private var shownFrameH = 1
    private var refSig: IntArray? = null
    private var ignoreUntil = 0L

    /**
     * Boucle principale :
     *  - pendant le défilement : rien n'est affiché, mais les bulles qui apparaissent
     *    sont lues et envoyées à l'IA en avance ;
     *  - dès que l'écran est immobile (~0,2 s) : affichage, quasi instantané si c'est déjà traduit ;
     *  - si une bulle n'était pas encore prête : traduction de secours tout de suite,
     *    remplacée automatiquement par la version IA dès qu'elle arrive.
     */
    private suspend fun runLoop() {
        var prevSig: IntArray? = null
        var stableTicks = 0
        var wasActive = true
        var lastPrefetchAt = 0L
        var prefetchJob: Job? = null

        while (currentCoroutineContext().isActive) {
            delay(TICK_MS)
            if (!active) { wasActive = false; continue }
            if (!wasActive) {
                showing = false; shownBubbles = null; prevSig = null; stableTicks = 0; wasActive = true
            }
            val p = processor ?: continue

            val snap = withContext(Dispatchers.Default) { snapshot() } ?: continue
            val frame = snap.first
            val sig = snap.second
            val now = SystemClock.uptimeMillis()

            if (showing) {
                // On laisse le temps aux traductions de s'afficher, puis on mémorise l'écran "avec traductions".
                if (now < ignoreUntil || refSig == null) { refSig = sig; continue }
                if (changed(refSig, sig)) {
                    overlay?.clearLabels()
                    overlay?.setBusy(false)
                    showing = false; shownBubbles = null
                    prevSig = null; stableTicks = 0
                    ignoreUntil = now + 120
                }
                continue
            }

            if (now < ignoreUntil) { prevSig = sig; continue }
            val moving = prevSig == null || changed(prevSig, sig)
            stableTicks = if (moving) 0 else stableTicks + 1
            prevSig = sig

            if (moving) {
                // Pré-traduction : on lit ce qui entre à l'écran pendant que tu défiles.
                if (now - lastPrefetchAt > 350 && prefetchJob?.isActive != true) {
                    lastPrefetchAt = now
                    val f = frame
                    prefetchJob = scope.launch {
                        try {
                            val h = f.height
                            // On ignore les bulles coupées par le haut ou le bas de l'écran.
                            val full = p.recognize(f).filter { it.rect.top > h * 0.03f && it.rect.bottom < h * 0.97f }
                            p.request(full)
                        } catch (_: Exception) {}
                    }
                }
                continue
            }
            if (stableTicks < 1) continue

            // ---- L'écran est immobile : affichage ----
            val bubbles = try { p.recognize(frame) } catch (e: Exception) { emptyList() }
            p.request(bubbles)

            // Si des bulles ne sont pas encore traduites par l'IA, on lui laisse un court instant.
            var aborted = false
            val deadline = SystemClock.uptimeMillis() + 1200
            if (p.missingAi(bubbles)) overlay?.setBusy(true)
            while (p.missingAi(bubbles) && SystemClock.uptimeMillis() < deadline) {
                delay(100)
                val s = withContext(Dispatchers.Default) { snapshot() }
                if (s != null && changed(sig, s.second)) { aborted = true; break }
            }
            if (aborted || !active) {
                overlay?.setBusy(false)
                prevSig = null; stableTicks = 0
                continue
            }

            val texts = p.textsFor(bubbles)
            val check = withContext(Dispatchers.Default) { snapshot() }
            if (check != null && changed(sig, check.second)) {
                overlay?.setBusy(false)
                prevSig = check.second; stableTicks = 0
                continue
            }

            shownFrameW = frame.width
            shownFrameH = frame.height
            placeLabels(bubbles, texts)
            overlay?.setBusy(p.missingAi(bubbles))
            showing = true
            shownBubbles = bubbles
            shownTexts = texts
            refSig = null
            ignoreUntil = SystemClock.uptimeMillis() + 500
        }
    }

    /** Remplace les traductions de secours par la version IA dès qu'elle arrive. */
    private fun refreshShownLabels() {
        val bubbles = shownBubbles ?: return
        val p = processor ?: return
        if (!showing || !active) return
        scope.launch {
            val texts = p.textsFor(bubbles)
            if (!showing || shownBubbles !== bubbles) return@launch
            overlay?.setBusy(p.missingAi(bubbles))
            if (texts == shownTexts) return@launch
            shownTexts = texts
            placeLabels(bubbles, texts)
            refSig = null
            ignoreUntil = SystemClock.uptimeMillis() + 500
        }
    }

    private fun placeLabels(bubbles: List<Bubble>, texts: List<String>) {
        val sx = screenW.toFloat() / shownFrameW
        val sy = screenH.toFloat() / shownFrameH
        val bubbleRect = overlay?.bubbleRect()
        val placed = ArrayList<TranslatedBlock>()
        for (i in bubbles.indices) {
            val b = bubbles[i].rect
            val r = Rect((b.left * sx).toInt(), (b.top * sy).toInt(), (b.right * sx).toInt(), (b.bottom * sy).toInt())
            if (bubbleRect != null && Rect.intersects(r, bubbleRect)) continue
            placed += TranslatedBlock(r, texts[i])
        }
        overlay?.showLabels(placed)
    }

    /** Dernière image de l'écran + une "empreinte" miniature pour détecter le défilement. */
    private fun snapshot(): Pair<Bitmap, IntArray>? {
        grabFrame()?.let { latest = it }
        val frame = latest ?: return null
        return frame to signature(frame)
    }

    private fun grabFrame(): Bitmap? {
        val image = try { imageReader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun signature(b: Bitmap): IntArray {
        val w = 24; val h = 48
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        return IntArray(px.size) {
            val c = px[it]
            ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        }
    }

    /** "Changé" = plus de 3 % de l'écran a bougé (la bulle flottante seule ne compte pas). */
    private fun changed(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null) return true
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 25) n++
        return n > a.size * 0.03
    }

    override fun onDestroy() {
        scope.cancel()
        overlay?.remove(); overlay = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        val p = projection
        projection = null
        p?.stop()
        processor?.close(); processor = null
        super.onDestroy()
    }
}
