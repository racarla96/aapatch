package aapatch;

import java.util.*;

/** Plain-JDK checks (no JUnit needed): relative class names, authorities, permissions, round trip. */
public final class SelfTest {
    static int failures;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS  " : "FAIL  ") + what); if (!ok) failures++; }

    static Axml.Element el(String name, String... androidAttrs) {
        Axml.Element e = new Axml.Element(null, name);
        for (int i = 0; i < androidAttrs.length; i += 2) {
            int id = androidAttrs[i].equals("name") ? 0x01010003 : androidAttrs[i].equals("authorities") ? 0x01010018 : androidAttrs[i].equals("permission") ? 0x01010006 : 0x01010001;
            e.setAndroidString(androidAttrs[i], id, androidAttrs[i + 1]);
        }
        return e;
    }

    public static void main(String[] x) {
        Axml.Document d = new Axml.Document();
        d.namespaces.add(new String[]{"android", Axml.ANDROID_NS});
        Axml.Element m = new Axml.Element(null, "manifest");
        Axml.Attr pk = new Axml.Attr(null, "package", 0); pk.type = Axml.TYPE_STRING; pk.str = "org.demo.player"; m.attrs.add(pk);
        Axml.Attr su = new Axml.Attr(Axml.ANDROID_NS, "sharedUserId", 0x0101000b); su.type = Axml.TYPE_STRING; su.str = "org.demo.uid"; m.attrs.add(su);
        m.children.add(el("permission", "name", "org.demo.player.PRIVATE"));
        m.children.add(el("uses-permission", "name", "org.demo.player.PRIVATE"));
        m.children.add(el("uses-permission", "name", "android.permission.INTERNET"));
        Axml.Element app = el("application", "name", ".App", "label", "Demo");
        Axml.Element act = el("activity", "name", "MainActivity", "permission", "org.demo.player.PRIVATE");
        Axml.Element f = new Axml.Element(null, "intent-filter");
        f.children.add(el("action", "name", ManifestPatcher.ACTION_MAIN));
        f.children.add(el("category", "name", ManifestPatcher.CAT_LAUNCHER));
        act.children.add(f);
        app.children.add(act);
        app.children.add(el("activity", "name", "org.other.Full"));
        app.children.add(el("provider", "name", ".Files", "authorities", "org.demo.player.files;com.thirdparty.cp"));
        m.children.add(app);
        d.root = m;

        byte[] bin = Axml.write(d);
        ManifestPatcher.Config cfg = new ManifestPatcher.Config();
        ManifestPatcher.Result r = ManifestPatcher.patch(bin, cfg);
        Axml.Document o = Axml.parse(r.manifest);
        Axml.Element om = o.root, oapp = om.child("application");

        check(om.attr(null, "package").str.equals("org.demo.player.aa"), "package renamed to ...aa");
        check(om.android("sharedUserId") == null, "sharedUserId removed");
        check(oapp.androidStr("name").equals("org.demo.player.App"), "'.App' qualified against OLD package");
        check(oapp.children("activity").get(0).androidStr("name").equals("org.demo.player.MainActivity"), "bare 'MainActivity' qualified against OLD package");
        check(oapp.children("activity").get(1).androidStr("name").equals("org.other.Full"), "fully-qualified class untouched");
        check(oapp.child("provider").androidStr("name").equals("org.demo.player.Files"), "provider class qualified");
        check(oapp.child("provider").androidStr("authorities").equals("org.demo.player.aa.files;com.thirdparty.cp.aa"),
                "authorities: own follows package, foreign gets suffix -> " + oapp.child("provider").androidStr("authorities"));
        check(om.children("permission").get(0).androidStr("name").equals("org.demo.player.aa.PRIVATE"), "declared permission renamed");
        check(om.children("uses-permission").get(0).androidStr("name").equals("org.demo.player.aa.PRIVATE"), "own uses-permission renamed");
        check(om.children("uses-permission").get(1).androidStr("name").equals("android.permission.INTERNET"), "system permission untouched");
        check(oapp.children("activity").get(0).androidStr("permission").equals("org.demo.player.aa.PRIVATE"), "component android:permission renamed");
        Axml.Element oact = oapp.children("activity").get(0);
        List<String> cats = new ArrayList<>();
        for (Axml.Element c : oact.child("intent-filter").children) if (c.name.equals("category")) cats.add(c.androidStr("name"));
        check(cats.equals(Arrays.asList(ManifestPatcher.CAT_LAUNCHER, ManifestPatcher.CAT_CAR_LAUNCHER, ManifestPatcher.CAT_DEFAULT)), "launcher filter categories " + cats);
        check(oact.child("meta-data") != null && oact.child("meta-data").android("value").type == Axml.TYPE_BOOLEAN, "distractionOptimized boolean meta-data");
        check(oapp.android("appCategory").data == 0 && oapp.android("appCategory").resId == 0x01010545, "appCategory=game with correct resource id");
        check(oapp.android("resizeableActivity").data == -1, "resizeableActivity=true");

        // idempotent round trip of the written file
        check(Axml.dump(o).equals(Axml.dump(Axml.parse(Axml.write(o)))), "write/parse round trip is lossless");

        // toggles off => nothing injected
        ManifestPatcher.Config off = new ManifestPatcher.Config();
        off.injectCarLauncher = off.injectDistractionOptimized = off.injectGameCategory = off.makeResizeable = false;
        Axml.Element q = Axml.parse(ManifestPatcher.patch(bin, off).manifest).root.child("application");
        check(q.android("appCategory") == null && q.android("resizeableActivity") == null && q.children("activity").get(0).child("meta-data") == null,
                "all injection toggles respected");
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
