package aapatch;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/** Creates / loads the signing identity (RSA-2048 + self-signed cert) using only the JDK. */
public final class Keys {
    private Keys() {}

    public static final class Identity {
        public final PrivateKey key; public final X509Certificate cert;
        Identity(PrivateKey k, X509Certificate c) { key = k; cert = c; }
    }

    private static final String ALIAS = "patcher";

    public static Identity loadOrCreate(File p12, char[] password, String commonName) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (p12.exists()) {
            try (InputStream in = new FileInputStream(p12)) { ks.load(in, password); }
            return new Identity((PrivateKey) ks.getKey(ALIAS, password), (X509Certificate) ks.getCertificate(ALIAS));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = selfSign(kp, commonName);
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), password, new java.security.cert.Certificate[]{cert});
        try (OutputStream out = new FileOutputStream(p12)) { ks.store(out, password); }
        return new Identity(kp.getPrivate(), cert);
    }

    static X509Certificate selfSign(KeyPair kp, String cn) throws Exception {
        byte[] sigAlg = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.NULL); // sha256WithRSAEncryption
        byte[] name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(cn))));
        long now = System.currentTimeMillis();
        byte[] validity = Der.seq(Der.time(new Date(now - 86_400_000L)), Der.time(new Date(now + 25L * 365 * 86_400_000L)));
        BigInteger serial = new BigInteger(63, new SecureRandom()).add(BigInteger.ONE);
        byte[] tbs = Der.seq(Der.explicit(0, Der.integer(BigInteger.valueOf(2))), Der.integer(serial), sigAlg,
                name, validity, name, kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] cert = Der.seq(tbs, sigAlg, Der.bitString(s.sign()));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(cert));
    }
}
