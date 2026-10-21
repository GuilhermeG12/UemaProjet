package br.uema.estudos;

import android.content.Context;

import org.json.JSONObject;

/** Transcrição esperando a página do Claude salvar no banco (fica guardada mesmo se o app fechar). */
final class Pendente {
    private static final String ARQ = "transcricao", CHAVE = "pendente";
    /** O banco da página aceita até 256 KiB por documento. */
    private static final int MAX = 180000;

    private Pendente() { }

    static void salvar(Context c, String link, Transcritor.Resultado r) throws Exception {
        String texto = r.texto.length() > MAX ? r.texto.substring(0, MAX) + " […]" : r.texto;
        JSONObject j = new JSONObject()
                .put("link", link)
                .put("id", Transcritor.idDoVideo(link))
                .put("titulo", r.titulo)
                .put("idioma", r.idioma)
                .put("texto", texto);
        c.getSharedPreferences(ARQ, Context.MODE_PRIVATE).edit().putString(CHAVE, j.toString()).apply();
    }

    static String ler(Context c) {
        return c.getSharedPreferences(ARQ, Context.MODE_PRIVATE).getString(CHAVE, "");
    }

    static void limpar(Context c) {
        c.getSharedPreferences(ARQ, Context.MODE_PRIVATE).edit().remove(CHAVE).apply();
    }
}
