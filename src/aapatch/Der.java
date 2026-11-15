package aapatch;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Just enough ASN.1 DER to build a self-signed X.509 certificate. */
final class Der {
    private Der() {}
    static final byte[] NULL = {0x05, 0x00};

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        if (b.length < 128) o.write(b.length);
        else {
            byte[] l = BigInteger.valueOf(b.length).toByteArray();
            int skip = l[0] == 0 ? 1 : 0;
            o.write(0x80 | (l.length - skip));
            o.write(l, skip, l.length - skip);
        }
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... p) { return tlv(0x30, p); }
    static byte[] set(byte[]... p) { return tlv(0x31, p); }
    static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }
    static byte[] utf8(String s) { return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8)); }
    static byte[] explicit(int n, byte[] inner) { return tlv(0xA0 | n, inner); }

    static byte[] bitString(byte[] b) {
        byte[] x = new byte[b.length + 1];
        System.arraycopy(b, 0, x, 1, b.length);
        return tlv(0x03, x);
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10]; int n = 0;
            do { tmp[n++] = (byte) (v & 0x7f); v >>= 7; } while (v > 0);
            for (int k = n - 1; k >= 0; k--) o.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    /** UTCTime before 2050, GeneralizedTime from 2050 on (RFC 5280). */
    static byte[] time(Date d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTime(d);
        boolean utc = c.get(Calendar.YEAR) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }
}
