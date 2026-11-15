package aapatch;

import java.io.InputStream;
import java.util.zip.ZipFile;

/** Debug helper: prints the parsed AndroidManifest.xml of an APK, and checks a lossless parse->write->parse round trip. */
public final class Dump {
    public static void main(String[] args) throws Exception {
        byte[] m;
        try (ZipFile z = new ZipFile(args[0]); InputStream in = z.getInputStream(z.getEntry("AndroidManifest.xml"))) {
            m = in.readAllBytes();
        }
        Axml.Document d = Axml.parse(m);
        String a = Axml.dump(d);
        String b = Axml.dump(Axml.parse(Axml.write(d)));
        System.out.println(args.length > 1 ? a : a.substring(0, Math.min(a.length(), 2500)));
        System.out.println("ROUND TRIP " + (a.equals(b) ? "OK" : "MISMATCH") + " (" + m.length + " -> " + Axml.write(d).length + " bytes)");
    }
}
