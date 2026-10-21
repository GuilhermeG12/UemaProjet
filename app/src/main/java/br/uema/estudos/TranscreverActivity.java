package br.uema.estudos;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Tela "Transcrever vídeo do YouTube": cola o link (ou compartilha do app do YouTube)
 * e mostra o texto da legenda para copiar ou compartilhar.
 */
public class TranscreverActivity extends Activity {
    private static final String FUNDO = "#081428";
    private EditText link;
    private Button transcrever, enviar;
    private Transcritor.Resultado ultimo;
    private ProgressBar carregando;
    private TextView status, titulo, texto;
    private LinearLayout acoes;
    private String resultado = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor(FUNDO));
        getWindow().setNavigationBarColor(Color.parseColor(FUNDO));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));

        TextView h = rotulo("Transcrever vídeo do YouTube", 22, true);
        col.addView(h);
        TextView sub = rotulo("Cole o link do vídeo. Funciona com vídeos que têm legenda (feita à mão ou automática).", 14, false);
        sub.setTextColor(Color.parseColor("#DBE5FA"));
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        link = new EditText(this);
        link.setHint("https://youtu.be/...");
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        link.setImeOptions(EditorInfo.IME_ACTION_GO);
        link.setTextColor(Color.WHITE);
        link.setHintTextColor(Color.parseColor("#7F93BD"));
        link.setBackground(caixa("#10244A", "#2C4A88"));
        link.setPadding(dp(12), dp(10), dp(12), dp(10));
        link.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { iniciar(); return true; }
        });
        col.addView(link, larguraToda());

        transcrever = botao("Transcrever", "#1F9E57");
        transcrever.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { iniciar(); }
        });
        LinearLayout.LayoutParams pb = larguraToda();
        pb.topMargin = dp(10);
        col.addView(transcrever, pb);

        carregando = new ProgressBar(this);
        carregando.setVisibility(View.GONE);
        LinearLayout.LayoutParams pc = new LinearLayout.LayoutParams(dp(36), dp(36));
        pc.gravity = Gravity.CENTER_HORIZONTAL;
        pc.topMargin = dp(14);
        col.addView(carregando, pc);

        status = rotulo("", 14, false);
        status.setTextColor(Color.parseColor("#FF9DB4"));
        status.setPadding(0, dp(10), 0, 0);
        col.addView(status);

        titulo = rotulo("", 17, true);
        titulo.setTextColor(Color.parseColor("#FFC23D"));
        titulo.setPadding(0, dp(14), 0, dp(4));
        col.addView(titulo);

        enviar = botao("Enviar para o Claude", "#1F9E57");
        enviar.setVisibility(View.GONE);
        enviar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enviarParaClaude(); }
        });
        LinearLayout.LayoutParams pe = larguraToda();
        pe.topMargin = dp(8);
        col.addView(enviar, pe);

        acoes = new LinearLayout(this);
        acoes.setOrientation(LinearLayout.HORIZONTAL);
        acoes.setVisibility(View.GONE);
        Button copiar = botao("Copiar", "#153062");
        copiar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copiar(); }
        });
        Button compartilhar = botao("Compartilhar", "#153062");
        compartilhar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { compartilhar(); }
        });
        LinearLayout.LayoutParams pa = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        pa.rightMargin = dp(6);
        acoes.addView(copiar, pa);
        LinearLayout.LayoutParams pa2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        pa2.leftMargin = dp(6);
        acoes.addView(compartilhar, pa2);
        LinearLayout.LayoutParams pac = larguraToda();
        pac.topMargin = dp(6);
        pac.bottomMargin = dp(10);
        col.addView(acoes, pac);

        texto = rotulo("", 15, false);
        texto.setTextIsSelectable(true);
        texto.setLineSpacing(0, 1.25f);
        col.addView(texto);

        ScrollView rolagem = new ScrollView(this);
        rolagem.setBackgroundColor(Color.parseColor(FUNDO));
        rolagem.setFillViewport(true);
        rolagem.addView(col);
        setContentView(rolagem);

        String recebido = linkRecebido(getIntent());
        if (recebido != null) { link.setText(recebido); iniciar(); }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String recebido = linkRecebido(intent);
        if (recebido != null) { link.setText(recebido); iniciar(); }
    }

    /** Link vindo de "Compartilhar" no app do YouTube ou do menu do Estudos UEMA. */
    private static String linkRecebido(Intent i) {
        if (i == null) return null;
        String s = Intent.ACTION_SEND.equals(i.getAction()) ? i.getStringExtra(Intent.EXTRA_TEXT) : i.getDataString();
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : s.trim();
    }

    private void iniciar() {
        final String l = link.getText().toString();
        if (Transcritor.idDoVideo(l) == null) { status.setText("Esse link não parece ser de um vídeo do YouTube."); return; }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(link.getWindowToken(), 0);
        transcrever.setEnabled(false);
        carregando.setVisibility(View.VISIBLE);
        status.setText("");
        titulo.setText("");
        texto.setText("");
        acoes.setVisibility(View.GONE);
        enviar.setVisibility(View.GONE);
        ultimo = null;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final Transcritor.Resultado r = Transcritor.transcrever(l);
                    runOnUiThread(new Runnable() { @Override public void run() { mostrar(r); } });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() { @Override public void run() { erro(e); } });
                }
            }
        }).start();
    }

    private void mostrar(Transcritor.Resultado r) {
        transcrever.setEnabled(true);
        carregando.setVisibility(View.GONE);
        titulo.setText(r.titulo.isEmpty() ? "Transcrição" : r.titulo);
        status.setTextColor(Color.parseColor("#3DDC97"));
        status.setText("Legenda: " + r.idioma);
        texto.setText(r.texto);
        resultado = (r.titulo.isEmpty() ? "" : "Vídeo: " + r.titulo + "\n") + "Link: " + link.getText().toString().trim()
                + "\nLegenda: " + r.idioma + "\n\n" + r.texto;
        acoes.setVisibility(View.VISIBLE);
        enviar.setVisibility(View.VISIBLE);
        ultimo = r;
    }

    /** Guarda a transcrição e abre a página do Claude, que salva no banco dela (o Claude lê de lá). */
    private void enviarParaClaude() {
        if (ultimo == null) return;
        try {
            Pendente.salvar(this, link.getText().toString().trim(), ultimo);
        } catch (Exception e) {
            status.setText("Não deu para guardar a transcrição: " + e.getMessage());
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        Toast.makeText(this, "Enviando para a página do Claude…", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void erro(Exception e) {
        transcrever.setEnabled(true);
        carregando.setVisibility(View.GONE);
        status.setTextColor(Color.parseColor("#FF9DB4"));
        String msg = e.getMessage();
        if (e instanceof java.net.UnknownHostException) msg = "Sem internet. Conecte e tente de novo.";
        else if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
        status.setText("Não deu para transcrever: " + msg);
    }

    private void copiar() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("Transcrição", resultado));
        Toast.makeText(this, "Transcrição copiada", Toast.LENGTH_SHORT).show();
    }

    private void compartilhar() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, titulo.getText().toString());
        i.putExtra(Intent.EXTRA_TEXT, resultado);
        startActivity(Intent.createChooser(i, "Compartilhar transcrição"));
    }

    private TextView rotulo(String s, int sp, boolean negrito) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (negrito) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button botao(String s, String cor) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(caixa(cor, cor));
        return b;
    }

    private GradientDrawable caixa(String fundo, String borda) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fundo));
        g.setStroke(dp(1), Color.parseColor(borda));
        g.setCornerRadius(dp(10));
        return g;
    }

    private LinearLayout.LayoutParams larguraToda() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
