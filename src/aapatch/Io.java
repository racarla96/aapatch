package aapatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Tiny stream helpers so the core also runs on Android below API 33 (no readAllBytes/transferTo there). */
final class Io {
    private Io() {}

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        copy(in, o);
        return o.toByteArray();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        for (int n; (n = in.read(buf)) >= 0; ) out.write(buf, 0, n);
    }
}
