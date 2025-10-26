package com.foxsrv.coincard.io;

import org.bukkit.Bukkit;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Cliente mínimo para POST /api/transfer/card (sem Gson).
 * Envia JSON manualmente e valida "success" e "txId" por busca de chaves.
 */
public class ApiClient {
    private final String baseUrl;
    private final int timeoutMs;
    private final Logger log;

    public static class CardTransferResult {
        public final boolean success;
        public final String txId;
        public final String raw;

        public CardTransferResult(boolean success, String txId, String raw) {
            this.success = success; this.txId = txId; this.raw = raw;
        }
    }

    public ApiClient(String baseUrl, int timeoutMs, Logger logger) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : (baseUrl + "/");
        this.timeoutMs = timeoutMs;
        this.log = logger;
    }

    public CardTransferResult transferByCard(String cardCode, String toId, double amount) {
        String endpoint = baseUrl + "api/transfer/card";
        String body = "{\"cardCode\":\"" + esc(cardCode) + "\",\"toId\":\"" + esc(toId) + "\",\"amount\":" + amount + "}";
        try {
            String resp = postJson(endpoint, body);
            boolean ok = parseBoolean(resp, "success");
            String tx = parseString(resp, "txId");
            return new CardTransferResult(ok, tx, resp);
        } catch (IOException e) {
            log.warning("HTTP error: " + e.getMessage());
            return new CardTransferResult(false, null, null);
        }
    }

    private String postJson(String urlStr, String json) throws IOException {
        URL url = URI.create(urlStr).toURL(); // evita o deprecation do new URL(String)
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(timeoutMs);
        con.setReadTimeout(timeoutMs);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type","application/json; charset=UTF-8");

        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String resp = readAll(is);
        if (resp == null) resp = "";
        return resp;
    }

    private String readAll(InputStream is) throws IOException {
        if (is == null) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            for (String line; (line = br.readLine()) != null; ) sb.append(line);
            return sb.toString();
        }
    }

    // --- parse super simples (sem lib) ---
    private boolean parseBoolean(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return false;
        int c = json.indexOf(':', i);
        if (c < 0) return false;
        String tail = json.substring(c + 1).trim();
        return tail.startsWith("true");
    }

    private String parseString(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int c = json.indexOf(':', i);
        if (c < 0) return null;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
