package aapatch;

import java.io.File;

public final class Cli {
    public static void main(String[] a) throws Exception {
        if (a.length < 3 || !a[0].equals("patch")) {
            System.err.println("usage: patch <in.apk> <out.apk> [--keystore f.p12] [--suffix aa] [--package x.y.z] [--label L]\n"
                    + "             [--no-car-launcher] [--no-distraction-optimized] [--no-game-category] [--no-resizeable]");
            System.exit(2);
        }
        File in = new File(a[1]), out = new File(a[2]), ks = new File("aapatch-keystore.p12");
        ManifestPatcher.Config cfg = new ManifestPatcher.Config();
        for (int i = 3; i < a.length; i++) {
            switch (a[i]) {
                case "--keystore": ks = new File(a[++i]); break;
                case "--suffix": cfg.suffix = a[++i]; break;
                case "--package": cfg.newPackage = a[++i]; break;
                case "--label": cfg.label = a[++i]; break;
                case "--no-car-launcher": cfg.injectCarLauncher = false; break;
                case "--no-distraction-optimized": cfg.injectDistractionOptimized = false; break;
                case "--no-game-category": cfg.injectGameCategory = false; break;
                case "--no-resizeable": cfg.makeResizeable = false; break;
                default: throw new IllegalArgumentException("unknown option " + a[i]);
            }
        }
        Keys.Identity id = Keys.loadOrCreate(ks, "changeit".toCharArray(), "AA Patcher");
        ManifestPatcher.Result r = ApkPatcher.patch(in, out, cfg, id);
        System.out.println(r.oldPackage + " -> " + r.newPackage + "  (" + out.length() + " bytes)");
        for (String n : r.notes) System.out.println("note: " + n);
    }
}
