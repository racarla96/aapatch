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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
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
 * Pick an installed app, patch a copy of it so Android Auto can offer it, and install the copy.
 * Everything runs on the phone; nothing is uploaded anywhere.
 */
public class MainActivity extends Activity {

    private static final class AppEntry {
        String label, pkg;
        final List<File> apks = new ArrayList<>(); // base first, then splits

        @Override public String toString() { return label + "\n" + pkg; }
    }

    private final List<AppEntry> all = new ArrayList<>();
    private ArrayAdapter<AppEntry> adapter;
    private TextView status;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText("Elige una app: se crea una copia parcheada (sufijo .aa) y se instala junto a la original.\n"
                + "Requisito: en Android Auto, activa el modo desarrollador y \"Fuentes desconocidas\".");
        root.addView(help);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        status.setText("Cargando apps…");
        root.addView(status);

        EditText search = new EditText(this);
        search.setHint("Buscar…");
        search.setSingleLine(true);
        root.addView(search);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<AppEntry>());
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showOptions(adapter.getItem(position));
            }
        });

        exec.execute(new Runnable() {
            @Override public void run() { loadApps(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Installer.statusSink = new java.util.function.Consumer<String>() {
            @Override public void accept(final String message) { setStatus(message); }
        };
    }

    @Override
    protected void onPause() {
        Installer.statusSink = null;
        super.onPause();
    }

    // ------------------------------------------------------------------ app list

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
        Collections.sort(found, new java.util.Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) { return a.label.compareToIgnoreCase(b.label); }
        });
        runOnUiThread(new Runnable() {
            @Override public void run() {
                all.clear();
                all.addAll(found);
                applyFilter("");
                status.setText(found.size() + " apps. Toca una para parchearla.");
            }
        });
    }

    private void applyFilter(String q) {
        String needle = q.toLowerCase(Locale.ROOT).trim();
        adapter.clear();
        for (AppEntry e : all) {
            if (needle.isEmpty() || e.label.toLowerCase(Locale.ROOT).contains(needle)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(needle)) adapter.add(e);
        }
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ patch + install

    private void showOptions(final AppEntry app) {
        final String[] names = {
                "Añadir lanzador de coche (CAR_LAUNCHER)",
                "Marcar como optimizada para conducir",
                "Categoría \"juego\"",
                "Permitir redimensionar"};
        final boolean[] checked = {true, true, true, true};
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMultiChoiceItems(names, checked, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("Parchear e instalar", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        patchAndInstall(app, checked.clone());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void patchAndInstall(final AppEntry app, final boolean[] opt) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Permite instalar apps desconocidas para AA Patcher y vuelve a intentarlo.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        exec.execute(new Runnable() {
            @Override public void run() {
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
                    List<File> files = ApkPatcher.patchSet(app.apks, out, cfg, id, new ApkPatcher.Progress() {
                        @Override public void step(String message) { setStatus(message); }
                    });
                    setStatus("Instalando " + files.size() + " APK(s)… confirma en el diálogo del sistema.");
                    Installer.install(MainActivity.this, files);
                } catch (Throwable t) {
                    setStatus("Error: " + t);
                } finally {
                    deleteRecursive(out); // the session already holds its own copy
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void setStatus(final String message) {
        runOnUiThread(new Runnable() {
            @Override public void run() { status.setText(message); }
        });
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
