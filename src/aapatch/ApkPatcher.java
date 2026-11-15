package aapatch;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** End to end: read APK -> patch manifest -> repack (aligned, old signatures removed) -> sign with v2. */
public final class ApkPatcher {
    private ApkPatcher() {}

    public interface Progress { void step(String message); }

    public static ManifestPatcher.Result patch(File in, File out, ManifestPatcher.Config cfg, Keys.Identity id) throws Exception {
        byte[] manifest;
        try (ZipFile zf = new ZipFile(in)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) throw new IOException("No AndroidManifest.xml in " + in);
            try (InputStream is = zf.getInputStream(e)) { manifest = Io.readAll(is); }
        }
        ManifestPatcher.Result r = ManifestPatcher.patch(manifest, cfg);
        File dir = out.getAbsoluteFile().getParentFile();
        File tmp = File.createTempFile("aapatch", ".apk", dir);
        try {
            ApkRepack.repack(in, tmp, Collections.singletonMap("AndroidManifest.xml", r.manifest));
            ApkSignerV2.sign(tmp, out, id.key, id.cert);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return r;
    }

    /**
     * Patches an app that may consist of several APKs (base + split_config.* ...).
     * The base gets the full configuration; every split only gets the package rename (they must all end up with
     * the same package name) and everything is signed with the same key. Returns the output files, base first.
     */
    public static List<File> patchSet(List<File> inputs, File outDir, ManifestPatcher.Config cfg, Keys.Identity id,
                                      Progress progress) throws Exception {
        if (inputs.isEmpty()) throw new IllegalArgumentException("no input APKs");
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("cannot create " + outDir);
        List<File> outs = new ArrayList<>();

        File baseOut = new File(outDir, "base.apk");
        if (progress != null) progress.step("Parcheando " + inputs.get(0).getName());
        ManifestPatcher.Result base = patch(inputs.get(0), baseOut, cfg, id);
        outs.add(baseOut);

        ManifestPatcher.Config splitCfg = new ManifestPatcher.Config();
        splitCfg.newPackage = base.newPackage;
        splitCfg.injectCarLauncher = false;
        splitCfg.injectDistractionOptimized = false;
        splitCfg.injectGameCategory = false;
        splitCfg.makeResizeable = false;
        for (int i = 1; i < inputs.size(); i++) {
            File o = new File(outDir, "split" + i + ".apk");
            if (progress != null) progress.step("Parcheando " + inputs.get(i).getName());
            patch(inputs.get(i), o, splitCfg, id);
            outs.add(o);
        }
        return outs;
    }
}
