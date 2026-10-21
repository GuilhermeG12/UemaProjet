package br.uema.estudos;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baixa a legenda de um vídeo do YouTube (manual ou automática) e devolve o texto.
 * Roda no próprio celular: o YouTube bloqueia servidores, mas não a internet de casa / 4G.
 */
final class Transcritor {
    static final class Resultado {
        String titulo = "";
        String idioma = "";
        String texto = "";
    }

    /** Trecho da legenda: início em segundos e texto. */
    static final class Trecho {
        final double inicio;
        final String texto;
        Trecho(double inicio, String texto) { this.inicio = inicio; this.texto = texto; }
    }

    private static final String UA_WEB = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final String UA_APP = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    private static final Pattern ID = Pattern.compile("(?:youtu\\.be/|[?&]v=|/shorts/|/embed/|/live/|/v/)([A-Za-z0-9_-]{11})");

    private Transcritor() { }

    /** Aceita youtu.be/ID, youtube.com/watch?v=ID, /shorts/ID, /embed/ID, /live/ID ou só o ID. */
    static String idDoVideo(String link) {
        if (link == null) return null;
        String s = link.trim();
        Matcher m = ID.matcher(s);
        if (m.find()) return m.group(1);
        if (s.matches("[A-Za-z0-9_-]{11}")) return s;
        return null;
    }

    static Resultado transcrever(String link) throws Exception {
        String id = idDoVideo(link);
        if (id == null) throw new Exception("Link do YouTube inválido.");

        String html = get("https://www.youtube.com/watch?v=" + id + "&hl=pt-BR", UA_WEB);
        Resultado r = new Resultado();
        JSONArray faixas = null;

        // 1) API do app Android do YouTube: as legendas vêm sem exigir token extra
        Matcher k = Pattern.compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").matcher(html);
        if (k.find()) {
            JSONObject corpo = new JSONObject()
                    .put("context", new JSONObject().put("client", new JSONObject()
                            .put("clientName", "ANDROID").put("clientVersion", "20.10.38")
                            .put("hl", "pt").put("gl", "BR")))
                    .put("videoId", id);
            JSONObject player = new JSONObject(post("https://www.youtube.com/youtubei/v1/player?key=" + k.group(1), corpo.toString()));
            r.titulo = player.optJSONObject("videoDetails") != null ? player.getJSONObject("videoDetails").optString("title") : "";
            faixas = faixas(player);
        }
        // 2) reserva: legendas que já vêm na página do vídeo
        if (faixas == null) {
            JSONObject pagina = playerDaPagina(html);
            if (pagina != null) {
                if (r.titulo.isEmpty() && pagina.optJSONObject("videoDetails") != null)
                    r.titulo = pagina.getJSONObject("videoDetails").optString("title");
                faixas = faixas(pagina);
            }
        }
        if (faixas == null || faixas.length() == 0)
            throw new Exception("Este vídeo não tem legenda (nem automática). Não dá para transcrever.");

        JSONObject escolhida = escolher(faixas);
        String url = escolhida.getString("baseUrl").replace("&fmt=srv3", "");
        String lingua = escolhida.optString("languageCode");
        boolean traduzir = !lingua.startsWith("pt") && escolhida.optBoolean("isTranslatable");
        if (traduzir) url += "&tlang=pt";
        r.idioma = nomeDaFaixa(escolhida) + (traduzir ? " → traduzida para português" : "");

        List<Trecho> trechos = lerLegenda(get(url, UA_WEB));
        if (trechos.isEmpty()) throw new Exception("O YouTube devolveu a legenda vazia. Tente de novo daqui a pouco.");
        r.texto = montarTexto(trechos);
        return r;
    }

    private static JSONArray faixas(JSONObject player) {
        JSONObject c = player.optJSONObject("captions");
        if (c == null) return null;
        JSONObject t = c.optJSONObject("playerCaptionsTracklistRenderer");
        return t == null ? null : t.optJSONArray("captionTracks");
    }

    static JSONObject playerDaPagina(String html) {
        int i = html.indexOf("ytInitialPlayerResponse = ");
        if (i < 0) return null;
        int ini = html.indexOf('{', i);
        int nivel = 0;
        boolean aspas = false;
        for (int j = ini; j < html.length(); j++) {
            char ch = html.charAt(j);
            if (aspas) {
                if (ch == '\\') j++;
                else if (ch == '"') aspas = false;
            } else if (ch == '"') aspas = true;
            else if (ch == '{') nivel++;
            else if (ch == '}' && --nivel == 0) {
                try { return new JSONObject(html.substring(ini, j + 1)); } catch (Exception e) { return null; }
            }
        }
        return null;
    }

    /** Prefere português feito à mão, depois português automático, depois qualquer outra (traduzida). */
    static JSONObject escolher(JSONArray faixas) {
        JSONObject ptAuto = null, outra = null;
        for (int i = 0; i < faixas.length(); i++) {
            JSONObject f = faixas.optJSONObject(i);
            if (f == null) continue;
            boolean pt = f.optString("languageCode").toLowerCase(Locale.ROOT).startsWith("pt");
            boolean auto = "asr".equals(f.optString("kind"));
            if (pt && !auto) return f;
            if (pt && ptAuto == null) ptAuto = f;
            if (outra == null) outra = f;
        }
        return ptAuto != null ? ptAuto : outra;
    }

    private static String nomeDaFaixa(JSONObject f) {
        JSONObject n = f.optJSONObject("name");
        String nome = "";
        if (n != null) {
            nome = n.optString("simpleText");
            JSONArray runs = n.optJSONArray("runs");
            if (nome.isEmpty() && runs != null && runs.length() > 0) nome = runs.optJSONObject(0).optString("text");
        }
        if (nome.isEmpty()) nome = f.optString("languageCode");
        return nome + ("asr".equals(f.optString("kind")) && !nome.contains("autom") ? " (automática)" : "");
    }

    /** Lê os formatos de legenda do YouTube: &lt;text start="1.2"&gt; (srv1) ou &lt;p t="1200"&gt; (srv3). */
    static List<Trecho> lerLegenda(String xml) {
        List<Trecho> out = new ArrayList<>();
        Matcher m = Pattern.compile("<text[^>]*\\bstart=\"([\\d.]+)\"[^>]*>(.*?)</text>", Pattern.DOTALL).matcher(xml);
        while (m.find()) add(out, Double.parseDouble(m.group(1)), m.group(2));
        if (out.isEmpty()) {
            m = Pattern.compile("<p[^>]*\\bt=\"(\\d+)\"[^>]*>(.*?)</p>", Pattern.DOTALL).matcher(xml);
            while (m.find()) add(out, Long.parseLong(m.group(1)) / 1000.0, m.group(2));
        }
        return out;
    }

    private static void add(List<Trecho> out, double inicio, String bruto) {
        String t = entidades(entidades(bruto.replaceAll("<[^>]+>", ""))).replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return;
        // legendas automáticas repetem a linha anterior; não duplica
        if (!out.isEmpty() && out.get(out.size() - 1).texto.equals(t)) return;
        out.add(new Trecho(inicio, t));
    }

    static String entidades(String s) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r;
            if (e.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
            else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
            else if (e.equals("amp")) r = "&";
            else if (e.equals("lt")) r = "<";
            else if (e.equals("gt")) r = ">";
            else if (e.equals("quot")) r = "\"";
            else if (e.equals("apos")) r = "'";
            else r = " ";
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Um parágrafo por minuto, com a marca de tempo no começo: [03:00] ... */
    static String montarTexto(List<Trecho> trechos) {
        StringBuilder sb = new StringBuilder();
        int minuto = -1;
        for (Trecho t : trechos) {
            int m = (int) (t.inicio / 60);
            if (m != minuto) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(m >= 60
                        ? String.format(Locale.ROOT, "[%d:%02d:00] ", m / 60, m % 60)
                        : String.format(Locale.ROOT, "[%02d:00] ", m));
                minuto = m;
            } else sb.append(' ');
            sb.append(t.texto);
        }
        return sb.toString();
    }

    private static String get(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        c.setRequestProperty("Cookie", "CONSENT=YES+1");
        return ler(c);
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", UA_APP);
        c.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
        OutputStream o = c.getOutputStream();
        o.write(json.getBytes(StandardCharsets.UTF_8));
        o.close();
        return ler(c);
    }

    private static String ler(HttpURLConnection c) throws IOException {
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (code == 429) throw new IOException("O YouTube pediu para esperar (muitos pedidos). Tente de novo em alguns minutos.");
        if (code >= 400 || in == null) throw new IOException("O YouTube respondeu com erro " + code + ".");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
