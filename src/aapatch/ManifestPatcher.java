package aapatch;

import java.util.*;

/**
 * Rewrites a compiled AndroidManifest.xml so that a copy of an app can be installed next to the original
 * and be offered to Android Auto.
 *
 * What it does (each step is independent and can be toggled in {@link Config}):
 *  1. Renames the package. Relative class names are first made fully-qualified against the OLD package,
 *     provider authorities and custom permissions are renamed so the copy does not collide with the original.
 *  2. Removes sharedUserId (a changed package cannot share the original's UID).
 *  3. Optionally changes the label.
 *  4. Adds CAR_LAUNCHER (+DEFAULT) to the launcher intent-filter, marks launcher activities as
 *     distraction optimized, tags the app as a game and makes it resizeable.
 */
public final class ManifestPatcher {
    private ManifestPatcher() {}

    // Resource IDs of the android: attributes we may have to create (checked against public.xml).
    static final int ATTR_NAME = 0x01010003, ATTR_LABEL = 0x01010001, ATTR_VALUE = 0x01010024,
            ATTR_APP_CATEGORY = 0x01010545, ATTR_RESIZEABLE = 0x010104f6;

    static final String ACTION_MAIN = "android.intent.action.MAIN";
    static final String CAT_LAUNCHER = "android.intent.category.LAUNCHER";
    static final String CAT_CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER";
    static final String CAT_DEFAULT = "android.intent.category.DEFAULT";
    static final int CATEGORY_GAME = 0;

    public static final class Config {
        /** null = original package + ".aa" */
        public String newPackage;
        public String suffix = "aa";
        public String label;
        public boolean injectCarLauncher = true;
        public boolean injectDistractionOptimized = true;
        public boolean injectGameCategory = true;
        public boolean makeResizeable = true;
    }

    public static final class Result {
        public String oldPackage, newPackage;
        public byte[] manifest;
        public final List<String> notes = new ArrayList<>();
    }

    private static final Set<String> COMPONENTS = new HashSet<>(Arrays.asList(
            "application", "activity", "activity-alias", "service", "receiver", "provider"));
    private static final String[] CLASS_ATTRS = {"name", "targetActivity", "parentActivityName",
            "backupAgent", "manageSpaceActivity"};
    private static final String[] PERMISSION_ATTRS = {"permission", "readPermission", "writePermission"};
    private static final Set<String> PERMISSION_ELEMENTS = new HashSet<>(Arrays.asList(
            "permission", "permission-tree", "permission-group", "uses-permission", "uses-permission-sdk-23"));

    public static Result patch(byte[] manifestBytes, Config cfg) {
        Axml.Document doc = Axml.parse(manifestBytes);
        Axml.Element manifest = doc.root;
        if (!"manifest".equals(manifest.name)) throw new IllegalArgumentException("Root element is not <manifest>");
        Axml.Attr pkgAttr = manifest.attr(null, "package");
        if (pkgAttr == null || !pkgAttr.isString()) throw new IllegalArgumentException("<manifest> has no package");

        Result res = new Result();
        res.oldPackage = pkgAttr.str;
        res.newPackage = cfg.newPackage != null ? cfg.newPackage : res.oldPackage + "." + cfg.suffix;
        boolean rename = !res.oldPackage.equals(res.newPackage);

        Axml.Element app = manifest.child("application");
        // Split APKs (config splits) may legitimately have no <application>; they only need the package rename.

        if (rename) {
            renameEverything(manifest, res.oldPackage, res.newPackage);
            pkgAttr.str = res.newPackage;
            manifest.removeAndroid("sharedUserId");
            manifest.removeAndroid("sharedUserLabel");
            manifest.removeAndroid("sharedUserMaxSdkVersion");
        }

        if (app == null) {
            res.manifest = Axml.write(doc);
            return res;
        }

        List<Axml.Element> launchers = launcherActivities(app);
        if (launchers.isEmpty()) res.notes.add("No launcher activity found; nothing to expose to the car launcher.");

        if (cfg.label != null) {
            app.setAndroidString("label", ATTR_LABEL, cfg.label);
            for (Axml.Element a : launchers) if (a.android("label") != null) a.setAndroidString("label", ATTR_LABEL, cfg.label);
        }
        if (cfg.makeResizeable) app.setAndroidBool("resizeableActivity", ATTR_RESIZEABLE, true);
        if (cfg.injectGameCategory) app.setAndroidInt("appCategory", ATTR_APP_CATEGORY, CATEGORY_GAME);

        for (Axml.Element activity : launchers) {
            if (cfg.injectCarLauncher) {
                for (Axml.Element f : activity.children("intent-filter")) {
                    if (isLauncherFilter(f)) {
                        addCategory(f, CAT_CAR_LAUNCHER);
                        addCategory(f, CAT_DEFAULT);
                    }
                }
            }
            if (cfg.injectDistractionOptimized) addMeta(activity, "distractionOptimized", "true");
        }

        res.manifest = Axml.write(doc);
        return res;
    }

    // ------------------------------------------------------------------ renaming

    private static void renameEverything(Axml.Element manifest, String oldPkg, String newPkg) {
        walk(manifest, e -> {
            if (COMPONENTS.contains(e.name)) {
                for (String a : CLASS_ATTRS) {
                    Axml.Attr at = e.android(a);
                    if (at != null && at.isString()) at.str = qualify(at.str, oldPkg);
                }
                for (String a : PERMISSION_ATTRS) renamePermission(e.android(a), oldPkg, newPkg);
            }
            if (e.name.equals("provider")) {
                Axml.Attr au = e.android("authorities");
                if (au != null && au.isString()) au.str = renameAuthorities(au.str, oldPkg, newPkg);
            }
            if (PERMISSION_ELEMENTS.contains(e.name)) renamePermission(e.android("name"), oldPkg, newPkg);
        });
    }

    static String qualify(String cls, String pkg) {
        if (cls.startsWith(".")) return pkg + cls;
        if (!cls.contains(".")) return pkg + "." + cls;
        return cls;
    }

    static void renamePermission(Axml.Attr a, String oldPkg, String newPkg) {
        if (a != null && a.isString() && a.str.startsWith(oldPkg + ".")) a.str = newPkg + a.str.substring(oldPkg.length());
    }

    /**
     * Authorities must be globally unique. Ones built from the package follow the package rename
     * (apps compute them as getPackageName()+".x"); anything else gets the suffix appended.
     */
    static String renameAuthorities(String authorities, String oldPkg, String newPkg) {
        String suffix = newPkg.startsWith(oldPkg) ? newPkg.substring(oldPkg.length()) : "." + Integer.toHexString(newPkg.hashCode());
        StringBuilder sb = new StringBuilder();
        for (String s : authorities.split(";")) {
            if (sb.length() > 0) sb.append(';');
            if (s.equals(oldPkg) || s.startsWith(oldPkg + ".")) sb.append(newPkg).append(s.substring(oldPkg.length()));
            else sb.append(s).append(suffix);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ injection helpers

    static List<Axml.Element> launcherActivities(Axml.Element app) {
        List<Axml.Element> out = new ArrayList<>();
        for (Axml.Element a : app.children) {
            if (!a.name.equals("activity") && !a.name.equals("activity-alias")) continue;
            for (Axml.Element f : a.children("intent-filter")) if (isLauncherFilter(f)) { out.add(a); break; }
        }
        return out;
    }

    static boolean isLauncherFilter(Axml.Element filter) {
        return hasChildNamed(filter, "action", ACTION_MAIN) && hasChildNamed(filter, "category", CAT_LAUNCHER);
    }

    static boolean hasChildNamed(Axml.Element parent, String element, String androidName) {
        for (Axml.Element c : parent.children) {
            if (c.name.equals(element) && androidName.equals(c.androidStr("name"))) return true;
        }
        return false;
    }

    static void addCategory(Axml.Element filter, String category) {
        if (hasChildNamed(filter, "category", category)) return;
        Axml.Element c = new Axml.Element(null, "category");
        c.setAndroidString("name", ATTR_NAME, category);
        int at = 0;
        for (int i = 0; i < filter.children.size(); i++) {
            String n = filter.children.get(i).name;
            if (n.equals("action") || n.equals("category")) at = i + 1;
        }
        filter.children.add(at, c);
    }

    static void addMeta(Axml.Element parent, String name, String value) {
        for (Axml.Element c : parent.children) {
            if (c.name.equals("meta-data") && name.equals(c.androidStr("name"))) return;
        }
        Axml.Element m = new Axml.Element(null, "meta-data");
        m.setAndroidString("name", ATTR_NAME, name);
        // boolean value, like `android:value="true"` compiled by aapt2
        Axml.Attr v = new Axml.Attr(Axml.ANDROID_NS, "value", ATTR_VALUE);
        v.type = Axml.TYPE_BOOLEAN; v.data = -1;
        m.attrs.add(v);
        parent.children.add(m);
    }

    private static void walk(Axml.Element e, java.util.function.Consumer<Axml.Element> f) {
        f.accept(e);
        for (Axml.Element c : e.children) walk(c, f);
    }
}
