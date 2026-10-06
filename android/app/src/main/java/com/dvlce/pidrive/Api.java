package com.dvlce.pidrive;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class Api {
    final String base, token;
    Api(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("address", "http://100.69.174.33:8090").replaceAll("/+$", "");
        token = prefs.getString("token", "");
        URI uri = new URI(base);
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("Indirizzo dell’archivio non valido");
        if (token.isEmpty()) throw new IOException("Configura prima il codice di accesso");
    }
    JSONObject json(String method, String path, JSONObject data) throws Exception {
        return request(method, path, data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8), -1, null);
    }
    JSONObject request(String method, String path, byte[] data, long offset, String hash) throws Exception {
        HttpURLConnection c = open(method, path);
        try {
            if (data != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", offset < 0 ? "application/json" : "application/octet-stream");
                if (offset >= 0) {
                    c.setRequestProperty("Upload-Offset", Long.toString(offset));
                    c.setRequestProperty("Chunk-SHA256", hash);
                }
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int status = c.getResponseCode();
            InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "{}" : read(in, 4 * 1024 * 1024);
            JSONObject result = new JSONObject(body);
            if (status >= 400) throw new IOException(result.optString("error", "Archivio non disponibile"));
            return result;
        } finally { c.disconnect(); }
    }
    HttpURLConnection open(String method, String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }
    void download(String path, File file) throws Exception {
        HttpURLConnection c = open("GET", path);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Impossibile aprire il file");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[1024 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.getFD().sync();
            }
        } catch (Exception e) { file.delete(); throw e; }
        finally { c.disconnect(); }
    }
    static String read(InputStream in, int max) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = source.read(buffer)) != -1) {
                if (out.size() + count > max) throw new IOException("Risposta troppo grande");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
    static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    static String hash(InputStream in) throws Exception {
        try (InputStream source = in) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024]; int count;
            while ((count = source.read(buffer)) != -1) digest.update(buffer, 0, count);
            return hex(digest.digest());
        }
    }
}
