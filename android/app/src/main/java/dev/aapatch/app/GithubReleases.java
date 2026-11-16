package dev.aapatch.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal GitHub Releases client (no auth, no libraries): finds the APK assets of the latest release and downloads one. */
final class GithubReleases {
    private GithubReleases() {}

    static final class Asset {
        String name, url;
        long size;
    }

    static final class Release {
        String tag;
        final List<Asset> apks = new ArrayList<>();
    }

    interface Progress { void bytes(long done, long total); }

    /** Newest non-draft release that actually contains at least one .apk (pre-releases included). */
    static Release latest(String ownerRepo) throws IOException, JSONException {
        HttpURLConnection c = open("https://api.github.com/repos/" + ownerRepo + "/releases?per_page=10");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("GitHub respondió " + code + " para " + ownerRepo
                + (code == 404 ? " (¿existe el repositorio y es público?)" : code == 403 ? " (límite de peticiones; espera un rato)" : ""));
        JSONArray releases = new JSONArray(readAll(c.getInputStream()));
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.getJSONObject(i);
            if (rel.optBoolean("draft")) continue;
            Release r = new Release();
            r.tag = rel.optString("tag_name");
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            for (int k = 0; k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                String name = a.optString("name");
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                Asset as = new Asset();
                as.name = name;
                as.url = a.optString("browser_download_url");
                as.size = a.optLong("size");
                r.apks.add(as);
            }
            if (!r.apks.isEmpty()) return r;
        }
        throw new IOException("No hay ningún APK en las últimas versiones de " + ownerRepo);
    }

    static void download(String url, File dest, Progress progress) throws IOException {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Descarga fallida (HTTP " + code + ")");
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buf)) >= 0; ) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.bytes(done, total);
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "AA-Patcher");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) >= 0; ) o.write(buf, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }
}
