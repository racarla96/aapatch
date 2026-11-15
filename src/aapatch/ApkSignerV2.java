package aapatch;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * APK Signature Scheme v2, implemented from the public spec
 * (source.android.com/docs/security/features/apksigning/v2). RSA PKCS#1 v1.5 + SHA-256 only.
 * Input must be an unsigned ZIP without an existing APK Signing Block.
 */
public final class ApkSignerV2 {
    private ApkSignerV2() {}

    private static final int CHUNK = 1 << 20;
    private static final int ALGO_RSA_PKCS1_SHA256 = 0x0103;
    private static final int V2_ID = 0x7109871a;
    private static final byte[] MAGIC = "APK Sig Block 42".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static void sign(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(in, "r")) {
            long len = raf.length();
            long eocd = findEocd(raf, len);
            ByteBuffer e = ByteBuffer.allocate((int) (len - eocd)).order(ByteOrder.LITTLE_ENDIAN);
            raf.seek(eocd); raf.readFully(e.array());
            long cdSize = e.getInt(12) & 0xffffffffL, cdOffset = e.getInt(16) & 0xffffffffL;
            if (cdOffset >= 16) {
                byte[] m = new byte[16]; raf.seek(cdOffset - 16); raf.readFully(m);
                if (Arrays.equals(m, MAGIC)) throw new IOException("Input already has an APK Signing Block");
            }

            // --- digest over the three sections
            MessageDigest top = MessageDigest.getInstance("SHA-256");
            List<byte[]> chunkDigests = new ArrayList<>();
            digestRange(raf, 0, cdOffset, chunkDigests);
            digestRange(raf, cdOffset, cdOffset + cdSize, chunkDigests);
            byte[] eocdBytes = e.array().clone();
            ByteBuffer.wrap(eocdBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, (int) cdOffset); // = signing block start
            chunkDigests.add(chunkDigest(eocdBytes, 0, eocdBytes.length));
            top.update((byte) 0x5a);
            top.update(le32(chunkDigests.size()));
            for (byte[] d : chunkDigests) top.update(d);
            byte[] apkDigest = top.digest();

            // --- signer structure
            byte[] digests = lp(lp(concat(le32(ALGO_RSA_PKCS1_SHA256), lp(apkDigest))));
            byte[] certs = lp(lp(cert.getEncoded()));
            byte[] signedData = concat(digests, certs, lp(new byte[0]));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(signedData);
            byte[] sig = s.sign();
            byte[] signatures = lp(lp(concat(le32(ALGO_RSA_PKCS1_SHA256), lp(sig))));
            byte[] signer = concat(lp(signedData), signatures, lp(cert.getPublicKey().getEncoded()));
            byte[] v2Value = lp(lp(signer));

            // --- signing block
            byte[] pair = concat(le64(4 + v2Value.length), le32(V2_ID), v2Value);
            long blockSize = pair.length + 8 + 16; // excludes the leading size field
            byte[] block = concat(le64(blockSize), pair, le64(blockSize), MAGIC);

            // --- assemble output: [entries][block][central directory][EOCD with shifted offset]
            ByteBuffer.wrap(e.array()).order(ByteOrder.LITTLE_ENDIAN).putInt(16, (int) (cdOffset + block.length));
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
                copyRange(raf, 0, cdOffset, os);
                os.write(block);
                copyRange(raf, cdOffset, cdOffset + cdSize, os);
                os.write(e.array());
            }
        }
    }

    private static long findEocd(RandomAccessFile raf, long len) throws IOException {
        long min = Math.max(0, len - 22 - 0xffff);
        byte[] b = new byte[4];
        for (long p = len - 22; p >= min; p--) {
            raf.seek(p); raf.readFully(b);
            if (b[0] == 0x50 && b[1] == 0x4b && b[2] == 0x05 && b[3] == 0x06) return p;
        }
        throw new IOException("ZIP end-of-central-directory not found");
    }

    private static void digestRange(RandomAccessFile raf, long start, long end, List<byte[]> out) throws Exception {
        byte[] buf = new byte[CHUNK];
        for (long p = start; p < end; p += CHUNK) {
            int n = (int) Math.min(CHUNK, end - p);
            raf.seek(p); raf.readFully(buf, 0, n);
            out.add(chunkDigest(buf, 0, n));
        }
    }

    private static byte[] chunkDigest(byte[] b, int off, int n) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((byte) 0xa5);
        md.update(le32(n));
        md.update(b, off, n);
        return md.digest();
    }

    private static void copyRange(RandomAccessFile raf, long start, long end, OutputStream os) throws IOException {
        byte[] buf = new byte[1 << 16];
        raf.seek(start);
        for (long left = end - start; left > 0; ) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new EOFException();
            os.write(buf, 0, n); left -= n;
        }
    }

    private static byte[] le32(int v) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(); }
    private static byte[] le64(long v) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array(); }
    private static byte[] lp(byte[] b) { return concat(le32(b.length), b); }
    private static byte[] concat(byte[]... p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] x : p) o.write(x, 0, x.length);
        return o.toByteArray();
    }
}
