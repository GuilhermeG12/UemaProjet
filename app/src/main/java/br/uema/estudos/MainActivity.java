package br.uema.estudos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * App "Estudos UEMA": abre a página do Claude (artefato) para sincronizar o progresso pela conta,
 * com voz nativa do Android. Sem internet, usa a cópia local em assets/index.html.
 */
public class MainActivity extends Activity {
    private static final String ONLINE_URL = "https://claude.ai/artifact/SEfvK4R2BHW3rdx2JYav44";
    private static final String OFFLINE_URL = "file:///android_asset/index.html";
    private static final String FUNDO = "#081428";
    /** Domínios que ficam dentro do app (claude.ai, login e o iframe do artefato). */
    private static final String[] INTERNOS = {
            "claude.ai", "claude.com", "anthropic.com", "claudeusercontent.com", "cloudflare.com"
    };

    private FrameLayout raiz;
    private WebView web;
    private WebView popup;
    private TextView aviso;
    private ProgressBar barra;
    private boolean offline = false;
    private TextToSpeech tts;
    private boolean ttsPronto = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor(FUNDO));
        getWindow().setNavigationBarColor(Color.parseColor(FUNDO));

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage(new Locale("pt", "BR"));
                    ttsPronto = true;
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { avisarFim(id); }
            @Override public void onError(String id) { avisarFim(id); }
        });

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.parseColor(FUNDO));

        web = novaWebView();
        web.addJavascriptInterface(new Voz(), "AndroidTTS");
        web.addJavascriptInterface(new App(), "AndroidApp");
        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Janelas());
        raiz.addView(web, cheio());

        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        raiz.addView(barra, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));

        aviso = new TextView(this);
        aviso.setText("offline: sem sincronizar · toque para tentar de novo");
        aviso.setTextColor(Color.parseColor("#DBE5FA"));
        aviso.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        aviso.setGravity(Gravity.CENTER);
        aviso.setPadding(dp(8), dp(4), dp(8), dp(4));
        aviso.setBackgroundColor(Color.parseColor("#CC1A3366"));
        aviso.setVisibility(View.GONE);
        aviso.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { abrirOnline(); }
        });
        raiz.addView(aviso, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        raiz.addView(botaoMenu(), botaoParams());
        setContentView(raiz);

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            offline = savedInstanceState.getBoolean("offline");
            web.getSettings().setAllowFileAccess(offline);
            aviso.setVisibility(offline ? View.VISIBLE : View.GONE);
        } else if (temInternet()) {
            abrirOnline();
        } else {
            abrirOffline();
        }
    }

    private WebView novaWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.parseColor(FUNDO));
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // guarda o progresso (localStorage)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);           // liberado só para a cópia offline
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);     // popups do login
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        return w;
    }

    private void abrirOnline() {
        offline = false;
        aviso.setVisibility(View.GONE);
        web.getSettings().setAllowFileAccess(false);
        web.loadUrl(ONLINE_URL);
    }

    private void abrirOffline() {
        offline = true;
        aviso.setVisibility(View.VISIBLE);
        web.getSettings().setAllowFileAccess(true);
        web.loadUrl(OFFLINE_URL);
    }

    private boolean temInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    private static boolean interno(Uri u) {
        String esquema = u.getScheme();
        if (esquema == null) return true;
        if (esquema.equals("file") || esquema.equals("about") || esquema.equals("data") || esquema.equals("blob")) return true;
        if (!esquema.equals("https") && !esquema.equals("http")) return false;
        String host = u.getHost();
        if (host == null) return true;
        host = host.toLowerCase(Locale.ROOT);
        for (String d : INTERNOS) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    /** Links externos (YouTube etc.) abrem no navegador / app do celular. */
    private void abrirFora(Uri u) {
        try {
            Intent i = u.getScheme() != null && u.getScheme().equals("intent")
                    ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, u);
            startActivity(i);
        } catch (Exception e) {
            // sem app para abrir o link: ignora
        }
    }

    private class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri u = Uri.parse(url);
            if (interno(u)) return false;
            abrirFora(u);
            return true;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            // a página do Claude não carregou (sem internet etc.): usa a cópia local
            if (req.isForMainFrame() && !offline) abrirOffline();
        }
    }

    private class Janelas extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int progress) {
            barra.setProgress(progress);
            barra.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
        }

        /** window.open / target=_blank: login fica num popup dentro do app; o resto abre no navegador. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final WebView filho = novaWebView();
            filho.setWebChromeClient(new WebChromeClient() {
                @Override public void onCloseWindow(WebView w) { fecharPopup(); }
            });
            filho.setWebViewClient(new WebViewClient() {
                private boolean decidido = false;

                private boolean decidir(String url) {
                    Uri u = Uri.parse(url);
                    if (interno(u)) {
                        if (!decidido) { decidido = true; mostrarPopup(filho); }
                        return false;
                    }
                    abrirFora(u);
                    if (!decidido) {
                        decidido = true;
                        raiz.post(new Runnable() { @Override public void run() { filho.destroy(); } });
                    }
                    return true;
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView w, String url) { return decidir(url); }

                @Override
                public void onPageStarted(WebView w, String url, android.graphics.Bitmap icon) {
                    if (url != null && !url.equals("about:blank") && decidir(url)) w.stopLoading();
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(filho);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) { fecharPopup(); }
    }

    private void mostrarPopup(WebView filho) {
        fecharPopup();
        popup = filho;
        raiz.addView(popup, raiz.getChildCount() - 1, cheio());
    }

    private void fecharPopup() {
        if (popup == null) return;
        raiz.removeView(popup);
        popup.destroy();
        popup = null;
    }

    private View botaoMenu() {
        final TextView b = new TextView(this);
        b.setText("⋮");
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("Menu");
        GradientDrawable fundo = new GradientDrawable();
        fundo.setShape(GradientDrawable.OVAL);
        fundo.setColor(Color.parseColor("#99153062"));
        fundo.setStroke(dp(1), Color.parseColor("#4D2C4A88"));
        b.setBackground(fundo);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { menu(); }
        });
        return b;
    }

    private FrameLayout.LayoutParams botaoParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM | Gravity.START);
        p.setMargins(dp(10), 0, 0, dp(34));
        return p;
    }

    private void menu() {
        String[] itens = {
                "Recarregar",
                "Abrir no navegador",
                offline ? "Tentar online (sincronizar)" : "Usar versão offline",
                "Transcrever vídeo do YouTube"
        };
        new AlertDialog.Builder(this)
                .setTitle("Estudos UEMA")
                .setItems(itens, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int qual) {
                        if (qual == 1) { abrirFora(Uri.parse(ONLINE_URL)); return; }
                        if (qual == 3) { startActivity(new Intent(MainActivity.this, TranscreverActivity.class)); return; }
                        fecharPopup();
                        if (qual == 0 && !offline) web.reload();
                        else if (offline) abrirOnline();
                        else abrirOffline();
                    }
                })
                .show();
    }

    /**
     * Fim da fala: chama window.__ttsDone(id) na página e também repassa por postMessage
     * para todos os iframes (o artefato do Claude roda dentro de um iframe).
     */
    private void avisarFim(String id) {
        String limpo = id.replaceAll("[^A-Za-z0-9_-]", "");
        postarParaFrames("try{window.__ttsDone&&window.__ttsDone('" + limpo + "')}catch(e){}",
                "{type:'__ttsDone',id:'" + limpo + "'}");
    }

    /** Avisa a página que há uma transcrição esperando para ser salva no banco da página. */
    private void avisarTranscricao() {
        if (Pendente.ler(this).isEmpty()) return;
        postarParaFrames("", "{type:'__transcricao'}");
    }

    /** Roda `js` na página e manda `mensagem` por postMessage para todos os iframes. */
    private void postarParaFrames(String js, String mensagem) {
        final String tudo = "(function(){" + js
                + "function w(f){for(var i=0;i<f.frames.length;i++){var c=f.frames[i];"
                + "try{c.postMessage(" + mensagem + ",'*')}catch(e){}try{w(c)}catch(e){}}}"
                + "w(window)})()";
        runOnUiThread(new Runnable() {
            @Override public void run() { if (web != null) web.evaluateJavascript(tudo, null); }
        });
    }

    /** Ponte para a página entregar ao Claude a transcrição feita no app: AndroidApp.pendente() / entregue(ok). */
    public class App {
        @JavascriptInterface
        public String pendente() { return Pendente.ler(MainActivity.this); }

        @JavascriptInterface
        public void entregue(boolean ok) {
            if (ok) Pendente.limpar(MainActivity.this);
        }
    }

    /** Ponte de voz chamada pelo site: AndroidTTS.speak(id, texto, velocidade) e AndroidTTS.stop(). */
    public class Voz {
        @JavascriptInterface
        public void speak(String id, String texto, float velocidade) {
            if (!ttsPronto) { avisarFim(id); return; }
            tts.setSpeechRate(velocidade);
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        }

        @JavascriptInterface
        public void stop() { if (tts != null) tts.stop(); }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static FrameLayout.LayoutParams cheio() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
        out.putBoolean("offline", offline);
    }

    @Override
    public void onBackPressed() {
        if (popup != null) {
            if (popup.canGoBack()) popup.goBack(); else fecharPopup();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        avisarTranscricao();
    }

    @Override
    protected void onResume() {
        super.onResume();
        avisarTranscricao();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts != null) tts.stop();
        CookieManager.getInstance().flush();   // mantém o login salvo
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        fecharPopup();
        web.destroy();
        super.onDestroy();
    }
}
