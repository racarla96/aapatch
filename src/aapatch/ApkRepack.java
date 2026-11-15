package aapatch;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Copies an APK entry by entry, swapping some entries, dropping old signatures and keeping stored data aligned. */
public final class ApkRepack {
    private ApkRepack() {}

    private static final class Counting extends FilterOutputStream {
        long count;
        Counting(OutputStream o) { super(o); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    static boolean isSignatureEntry(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase(Locale.ROOT);
        return u.equals("META-INF/MANIFEST.MF") || u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA")
                || u.endsWith(".EC") || u.startsWith("META-INF/SIG-");
    }

    public static void repack(File in, File out, Map<String, byte[]> replacements) throws IOException {
        try (ZipFile zf = new ZipFile(in);
             Counting cos = new Counting(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
             ZipOutputStream zos = new ZipOutputStream(cos)) {
            for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry src = en.nextElement();
                String name = src.getName();
                if (isSignatureEntry(name)) continue;
                byte[] repl = replacements.get(name);
                ZipEntry dst = new ZipEntry(name);
                dst.setTime(src.getTime());
                if (src.isDirectory()) { zos.putNextEntry(dst); zos.closeEntry(); continue; }
                if (src.getMethod() == ZipEntry.STORED) {
                    long size = repl != null ? repl.length : src.getSize();
                    long crc;
                    if (repl != null) { CRC32 c = new CRC32(); c.update(repl); crc = c.getValue(); } else crc = src.getCrc();
                    dst.setMethod(ZipEntry.STORED); dst.setSize(size); dst.setCompressedSize(size); dst.setCrc(crc);
                    int align = name.endsWith(".so") ? 16384 : 4; // 16 KB for native libs (16K-page devices), 4 for the rest
                    dst.setExtra(padding(cos.count, name, align));
                } else {
                    dst.setMethod(ZipEntry.DEFLATED);
                }
                zos.putNextEntry(dst);
                if (repl != null) zos.write(repl);
                else try (InputStream is = zf.getInputStream(src)) { Io.copy(is, zos); }
                zos.closeEntry();
            }
        }
    }

    /** Extra field that makes the entry's data start on an {@code align} boundary. */
    static byte[] padding(long pos, String name, int align) {
        int nameLen = name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long dataStart = pos + 30 + nameLen;
        int pad = (int) ((align - dataStart % align) % align);
        if (pad == 0) return new byte[0];
        if (pad < 4) pad += align; // an extra field needs at least a 4-byte header
        byte[] x = new byte[pad];
        x[0] = 0x35; x[1] = (byte) 0xd9;        // header id 0xd935 (same id zipalign uses)
        x[2] = (byte) ((pad - 4) & 0xff); x[3] = (byte) ((pad - 4) >> 8);
        return x;
    }
}
