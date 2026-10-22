package ar.runas.nordicas

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class MainActivity : Activity() {

    private lateinit var web: WebView
    @Volatile private var captureVolume = false
    private var pendingPerm: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var speech: SpeechRecognizer? = null
    private var pendingSpeech: Intent? = null
    private var notifId = 100
    private val prefs by lazy { getSharedPreferences("runas", MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0b0907")
        window.navigationBarColor = Color.parseColor("#0b0907")

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0b0907"))
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            // Agente propio: ngrok no muestra su pantalla de advertencia a clientes no-navegador
            userAgentString = "RunasNordicasApp/1.0 (Android ${Build.VERSION.RELEASE}; Mobile)"
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null && url.startsWith("http")) {
                    view?.evaluateJavascript(SPEECH_POLYFILL, null)
                    if (url.contains("/runas_")) view?.evaluateJavascript(BACK_BUTTON, null)
                }
            }
            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame == true)
                    showSetup("No pude conectar con el servidor (${err?.description}). ¿Están corriendo server_relay.py y ngrok?")
            }
            override fun onReceivedHttpError(view: WebView?, req: WebResourceRequest?, resp: WebResourceResponse?) {
                if (req?.isForMainFrame == true && (resp?.statusCode ?: 0) >= 400)
                    showSetup("El servidor respondió error ${resp?.statusCode}. Revisá la dirección y que server_relay.py y ngrok estén corriendo.")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needed += Manifest.permission.CAMERA
                    if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needed += Manifest.permission.RECORD_AUDIO
                    val missing = needed.filter { !has(it) }
                    if (missing.isEmpty()) req.grant(req.resources)
                    else { pendingPerm = req; requestPermissions(missing.toTypedArray(), REQ_MEDIA) }
                }
            }
            override fun onShowFileChooser(
                webView: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params!!.createIntent(), REQ_FILE); true
                } catch (e: Exception) { fileCallback = null; false }
            }
        }

        if (prefs.getString("url", "").isNullOrEmpty()) showSetup("") else loadMain()
    }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun loadMain() {
        val url = (prefs.getString("url", "") ?: "").trimEnd('/')
        val role = prefs.getString("role", "jugador")
        if (role == "md" && Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS))
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        val path = if (role == "md") "/md" else "/jugador.html"
        web.loadUrl(url + path, mapOf("ngrok-skip-browser-warning" to "1"))
    }

    private fun showSetup(msg: String) {
        web.loadUrl("file:///android_asset/setup.html?e=" + URLEncoder.encode(msg, "UTF-8"))
    }

    // ── Botones de volumen: se "consumen" mientras la cerradura está activa ──
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "VOL_UP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL_DOWN"
            else -> null
        }
        if (code != null && captureVolume) {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (!down || event.repeatCount == 0)
                web.evaluateJavascript("window.__rnKey&&window.__rnKey('$code',$down)", null)
            return true   // no llega al sistema: el volumen no cambia
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        when (code) {
            REQ_MEDIA -> pendingPerm?.let { req ->
                val allowed = req.resources.filter { r ->
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                        else -> false
                    }
                }
                if (allowed.isEmpty()) req.deny() else req.grant(allowed.toTypedArray())
                pendingPerm = null
            }
            REQ_SPEECH -> {
                val i = pendingSpeech; pendingSpeech = null
                if (i != null && has(Manifest.permission.RECORD_AUDIO)) startSpeech(i)
                else { speechEvent("error", "'not-allowed'"); speechEvent("end", "null") }
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            fileCallback = null
        }
    }

    // ── Reconocimiento de voz nativo (el WebView no trae uno propio) ──
    private fun speechEvent(type: String, data: String) = runOnUiThread {
        web.evaluateJavascript("window.__rnSpeechEvt&&window.__rnSpeechEvt('$type',$data)", null)
    }

    private fun startSpeech(intent: Intent) {
        if (!has(Manifest.permission.RECORD_AUDIO)) {
            pendingSpeech = intent
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_SPEECH)
            return
        }
        val sr = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            speech = it; it.setRecognitionListener(listener)
        }
        try { sr.startListening(intent) } catch (e: Exception) {
            speechEvent("error", "'aborted'"); speechEvent("end", "null")
        }
    }

    private fun sendResults(b: Bundle?, isFinal: Boolean) {
        val list = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.isEmpty()) return
        speechEvent("result", JSONObject().put("alts", JSONArray(list)).put("final", isFinal).toString())
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) = speechEvent("audio", "null")
        override fun onBeginningOfSpeech() = speechEvent("sound", "null")
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
            val e = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
                else -> "aborted"
            }
            speechEvent("error", "'$e'"); speechEvent("end", "null")
        }
        override fun onResults(b: Bundle?) { sendResults(b, true); speechEvent("end", "null") }
        override fun onPartialResults(b: Bundle?) = sendResults(b, false)
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    @Suppress("DEPRECATION")
    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("md") == null)
            nm.createNotificationChannel(NotificationChannel("md", "Avisos de la partida", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "md") else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setContentIntent(pi)
        try { nm.notify(notifId++, b.build()) } catch (e: SecurityException) { }
    }

    // ── Puente que usan las páginas (window.AndroidBridge) ──
    inner class Bridge {
        @JavascriptInterface fun setCaptureVolume(on: Boolean) { captureVolume = on }

        @Suppress("DEPRECATION")
        @JavascriptInterface fun vibrate(ms: Int) {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            val t = ms.coerceIn(1, 2000).toLong()
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(t, VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(t)
        }

        @JavascriptInterface fun notificar(title: String, body: String) = runOnUiThread { showNotification(title, body) }

        @JavascriptInterface fun saveConfig(url: String, role: String) {
            prefs.edit().putString("url", url.trim().trimEnd('/')).putString("role", role).apply()
            runOnUiThread { loadMain() }
        }

        @JavascriptInterface fun resetConfig() = runOnUiThread { showSetup("") }

        @JavascriptInterface fun getConfig(): String =
            JSONObject().put("url", prefs.getString("url", "")).put("role", prefs.getString("role", "jugador")).toString()

        @JavascriptInterface fun speechAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(this@MainActivity)

        @JavascriptInterface fun speechStart(lang: String, interim: Boolean, max: Int) {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, max.coerceIn(1, 10))
            }
            runOnUiThread { startSpeech(i) }
        }

        @JavascriptInterface fun speechStop() = runOnUiThread { speech?.cancel() }
    }

    override fun onPause() {
        super.onPause()
        if (prefs.getString("role", "") != "md") web.onPause()   // el MD sigue escuchando avisos
    }

    override fun onResume() { super.onResume(); web.onResume() }

    override fun onDestroy() { speech?.destroy(); web.destroy(); super.onDestroy() }

    companion object {
        const val REQ_MEDIA = 11
        const val REQ_FILE = 12
        const val REQ_SPEECH = 13
        const val REQ_NOTIF = 14

        // Reemplaza webkitSpeechRecognition por el reconocedor nativo de Android
        val SPEECH_POLYFILL = """
(function(){
  if (window.__rnSpeech || !window.AndroidBridge || !AndroidBridge.speechAvailable()) return;
  window.__rnSpeech = true;
  var cur = null;
  function SR(){ this.lang='es-ES'; this.continuous=false; this.interimResults=false; this.maxAlternatives=5; this.grammars=null;
    this.onresult=null; this.onerror=null; this.onend=null; this.onsoundstart=null; this.onaudiostart=null; }
  SR.prototype.start=function(){ cur=this; AndroidBridge.speechStart(this.lang||'es-ES', !!this.interimResults, this.maxAlternatives||5); };
  SR.prototype.stop=function(){ if(cur===this) cur=null; AndroidBridge.speechStop(); };
  SR.prototype.abort=SR.prototype.stop;
  window.__rnSpeechEvt=function(type,data){
    var r=cur; if(!r) return;
    if(type==='result'){ var alts=data.alts.map(function(t){return {transcript:t,confidence:0.9};}); alts.isFinal=data.final;
      if(r.onresult) r.onresult({resultIndex:0,results:[alts]}); }
    else if(type==='audio'){ if(r.onaudiostart) r.onaudiostart(); }
    else if(type==='sound'){ if(r.onsoundstart) r.onsoundstart(); }
    else if(type==='error'){ if(r.onerror) r.onerror({error:data}); }
    else if(type==='end'){ setTimeout(function(){ if(cur===r && r.onend) r.onend(); }, 350); }
  };
  window.SpeechRecognition = window.webkitSpeechRecognition = SR;
  window.SpeechGrammarList = window.webkitSpeechGrammarList = function(){ this.length=0; this.addFromString=function(){}; };
})();
"""

        val BACK_BUTTON = """
(function(){
  if (document.getElementById('rnBack')) return;
  var b=document.createElement('button'); b.id='rnBack'; b.textContent='← Volver a mi ficha';
  b.style.cssText='position:fixed;left:12px;bottom:14px;z-index:99999;background:#0c0814ee;color:#f0e0b0;border:1px solid #c8a45a88;border-radius:20px;padding:9px 16px;font-family:Cinzel,serif;font-size:12px;letter-spacing:1px';
  b.onclick=function(){ location.href='/jugador.html'; };
  document.body.appendChild(b);
})();
"""
    }
}
