package br.uema.estudos;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/** App "Estudos UEMA": abre o site de estudos (assets/index.html) offline, com voz nativa do Android. */
public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsPronto = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#081428"));
        getWindow().setNavigationBarColor(Color.parseColor("#081428"));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("pt", "BR"));
                ttsPronto = true;
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { avisarFim(id); }
            @Override public void onError(String id) { avisarFim(id); }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#081428"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // guarda o progresso (localStorage)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Voz(), "AndroidTTS");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                // links externos (YouTube etc.) abrem no navegador / app do celular
                startActivity(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    private void avisarFim(String id) {
        runOnUiThread(() -> web.evaluateJavascript(
                "window.__ttsDone && window.__ttsDone('" + id.replace("'", "") + "')", null));
    }

    /** Ponte de voz chamada pelo site: AndroidTTS.speak(id, texto, velocidade) e AndroidTTS.stop(). */
    public class Voz {
        @JavascriptInterface
        public void speak(String id, String texto, float velocidade) {
            if (!ttsPronto) { avisarFim(id); return; }
            tts.setSpeechRate(velocidade);
            Bundle p = new Bundle();
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, p, id);
        }
        @JavascriptInterface
        public void stop() { if (tts != null) tts.stop(); }
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); if (tts != null) tts.stop(); }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        web.destroy();
        super.onDestroy();
    }
}
