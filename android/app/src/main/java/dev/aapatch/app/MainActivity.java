package dev.aapatch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import aapatch.ApkPatcher;
import aapatch.Keys;
import aapatch.ManifestPatcher;

/**
 * Two ways to get an app into Android Auto, all on the phone:
 *  - "Mis apps": patch a copy of an installed app (new package + signature) and install it.
 *  - "GitHub": download the official, untouched APK of an Android-Auto-aware app from its GitHub release and
 *    install it asking Android to record Google Play as the installer. Package and signature stay original,
 *    so Google sign-in etc. keep working.
 */
public class MainActivity extends Activity {

    private static final class AppEntry {
        String label, pkg;
        final List<File> apks = new ArrayList<>(); // base first, then splits

        @Override public String toString() { return label + "\n" + pkg; }
    }

    private static final class CatalogEntry {
        String name, repo;

        @Override public String toString() { return name + "\n" + repo; }
    }

    private final List<AppEntry> all = new ArrayList<>();
    private final List<CatalogEntry> catalog = new ArrayList<>();
    private ArrayAdapter<AppEntry> appsAdapter;
    private ArrayAdapter<CatalogEntry> catAdapter;
    private ListView list;
    private EditText search;
    private TextView help, status;
    private boolean githubMode;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setTitle("AA Patcher v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (PackageManager.NameNotFoundException ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        Button btnApps = new Button(this);
        btnApps.setText("Mis apps (parchear)");
        Button btnGit = new Button(this);
        btnGit.setText("GitHub (originales)");
        modes.addView(btnApps, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(btnGit, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(modes);

        help = new TextView(this);
        help.setPadding(0, dp(8), 0, 0);
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setText("Cargando apps…");
        root.addView(status);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        root.addView(search);

        list = new ListView(this);
        appsAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<AppEntry>());
        catAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<CatalogEntry>());
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        loadCatalog();
        setMode(false);

        btnApps.setOnClickListener(v -> setMode(false));
        btnGit.setOnClickListener(v -> setMode(true));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { if (!githubMode) applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (githubMode) { openRepo(v.getText().toString().trim()); return true; }
            return false;
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (githubMode) openRepo(catAdapter.getItem(position).repo);
            else showOptions(appsAdapter.getItem(position));
        });

        exec.execute(this::loadApps);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Installer.statusSink = this::setStatus;
    }

    @Override
    protected void onPause() {
        Installer.statusSink = null;
        super.onPause();
    }

    private void setMode(boolean git) {
        githubMode = git;
        search.setText("");
        if (git) {
            list.setAdapter(catAdapter);
            search.setHint("propietario/repositorio y pulsa Intro");
            help.setText("Instala el APK OFICIAL, sin modificar, de la última versión de un repositorio de GitHub y pide a Android "
                    + "que lo registre como instalado desde Play Store (Android 14+). Sirve para apps que ya soportan Android Auto "
                    + "(Fermata, CarStream…). Conserva paquete y firma, así que el inicio de sesión de Google sigue funcionando.");
        } else {
            list.setAdapter(appsAdapter);
            search.setHint("Buscar…");
            help.setText("Elige una app: se crea una copia parcheada (sufijo .aa) y se instala junto a la original. "
                    + "La copia tiene otra firma: el login de Google o Firebase no funcionará.");
        }
        if (!all.isEmpty() || git) status.setText(git ? "Toca una entrada o escribe un repositorio." : all.size() + " apps. Toca una para parchearla.");
    }

    // ------------------------------------------------------------------ installed apps (patch mode)

    private void loadApps() {
        PackageManager pm = getPackageManager();
        final List<AppEntry> found = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            AppEntry e = new AppEntry();
            e.label = String.valueOf(pm.getApplicationLabel(ai));
            e.pkg = ai.packageName;
            e.apks.add(new File(ai.sourceDir));
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) e.apks.add(new File(s));
            found.add(e);
        }
        Collections.sort(found, (a, b) -> a.label.compareToIgnoreCase(b.label));
        runOnUiThread(() -> {
            all.clear();
            all.addAll(found);
            applyFilter("");
            if (!githubMode) status.setText(found.size() + " apps. Toca una para parchearla.");
        });
    }

    private void applyFilter(String q) {
        String needle = q.toLowerCase(Locale.ROOT).trim();
        appsAdapter.clear();
        for (AppEntry e : all) {
            if (needle.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(needle)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(needle)) appsAdapter.add(e);
        }
        appsAdapter.notifyDataSetChanged();
    }

    private void showOptions(final AppEntry app) {
        final String[] names = {
                "Añadir lanzador de coche (CAR_LAUNCHER)",
                "Marcar como optimizada para conducir",
                "Categoría \"juego\"",
                "Permitir redimensionar"};
        final boolean[] checked = {true, true, true, true};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Parchear e instalar", (d, which) -> patchAndInstall(app, checked.clone()))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void patchAndInstall(final AppEntry app, final boolean[] opt) {
        if (!ensureInstallPermission()) return;
        exec.execute(() -> {
            File out = new File(getCacheDir(), "patched/" + app.pkg);
            try {
                ManifestPatcher.Config cfg = new ManifestPatcher.Config();
                cfg.label = app.label + " (AA)";
                cfg.injectCarLauncher = opt[0];
                cfg.injectDistractionOptimized = opt[1];
                cfg.injectGameCategory = opt[2];
                cfg.makeResizeable = opt[3];

                setStatus("Preparando la clave de firma…");
                Keys.Identity id = Keys.loadOrCreate(new File(getFilesDir(), "patcher.p12"),
                        "changeit".toCharArray(), "AA Patcher");

                deleteRecursive(out);
                List<File> files = ApkPatcher.patchSet(app.apks, out, cfg, id, this::setStatus);
                setStatus("Instalando " + files.size() + " APK(s)… confirma en el diálogo del sistema.");
                Installer.install(MainActivity.this, files, false);
            } catch (Throwable t) {
                setStatus("Error: " + t);
            } finally {
                deleteRecursive(out); // the session already holds its own copy
            }
        });
    }

    // ------------------------------------------------------------------ GitHub (original APKs)

    private void loadCatalog() {
        try (InputStream in = getAssets().open("catalog.json")) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) >= 0; ) o.write(buf, 0, n);
            JSONArray arr = new JSONArray(new String(o.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                CatalogEntry e = new CatalogEntry();
                e.name = j.getString("name");
                e.repo = j.getString("repo");
                catalog.add(e);
            }
        } catch (Exception e) {
            // no catalog: the user can still type owner/repo
        }
        catAdapter.addAll(catalog);
    }

    private void openRepo(final String repo) {
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            Toast.makeText(this, "Formato: propietario/repositorio", Toast.LENGTH_SHORT).show();
            return;
        }
        setStatus("Consultando " + repo + "…");
        exec.execute(() -> {
            try {
                GithubReleases.Release r = GithubReleases.latest(repo);
                runOnUiThread(() -> pickAsset(repo, r));
            } catch (Throwable t) {
                setStatus("Error: " + t.getMessage());
            }
        });
    }

    private void pickAsset(String repo, GithubReleases.Release r) {
        final String[] labels = new String[r.apks.size()];
        for (int i = 0; i < labels.length; i++) {
            GithubReleases.Asset a = r.apks.get(i);
            labels[i] = a.name + "  (" + String.format(Locale.ROOT, "%.1f", a.size / 1048576.0) + " MB)";
        }
        setStatus(repo + " " + r.tag + ": elige un APK.");
        new AlertDialog.Builder(this)
                .setTitle(repo + " " + r.tag)
                .setItems(labels, (d, which) -> downloadAndInstall(r.apks.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void downloadAndInstall(final GithubReleases.Asset asset) {
        if (!ensureInstallPermission()) return;
        exec.execute(() -> {
            File dir = new File(getCacheDir(), "dl");
            File file = new File(dir, asset.name.replaceAll("[^A-Za-z0-9._-]", "_"));
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                GithubReleases.download(asset.url, file, (done, total) ->
                        setStatus("Descargando " + asset.name + "… " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " MB"));
                setStatus("Instalando " + asset.name + "… confirma en el diálogo del sistema.");
                Installer.install(MainActivity.this, Collections.singletonList(file), true);
            } catch (Throwable t) {
                setStatus("Error: " + t);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private boolean ensureInstallPermission() {
        if (getPackageManager().canRequestPackageInstalls()) return true;
        Toast.makeText(this, "Permite instalar apps desconocidas para AA Patcher y vuelve a intentarlo.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void setStatus(final String message) {
        runOnUiThread(() -> status.setText(message));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
