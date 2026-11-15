"""Independent APK v2 signature check, written from the spec (not sharing code with the Java signer)."""
import sys, struct, hashlib
from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding

d = open(sys.argv[1], 'rb').read()
# EOCD
e = d.rfind(b'PK\x05\x06')
cd_size, cd_off = struct.unpack_from('<II', d, e + 12)
assert d[cd_off-16:cd_off] == b'APK Sig Block 42', "no signing block"
size2, = struct.unpack_from('<Q', d, cd_off-24)
blk_start = cd_off - size2 - 8
size1, = struct.unpack_from('<Q', d, blk_start)
assert size1 == size2, "block sizes differ"
pairs = d[blk_start+8: cd_off-24]
v2 = None; p = 0
while p < len(pairs):
    ln, = struct.unpack_from('<Q', pairs, p); idv, = struct.unpack_from('<I', pairs, p+8)
    if idv == 0x7109871a: v2 = pairs[p+12:p+8+ln]
    p += 8 + ln
assert v2, "no v2 block"
def lp(b, o):
    n, = struct.unpack_from('<I', b, o); return b[o+4:o+4+n], o+4+n
signers, _ = lp(v2, 0)
signer, _ = lp(signers, 0)
signed, o = lp(signer, 0); sigs, o = lp(signer, o); pub, o = lp(signer, o)
digs, so = lp(signed, 0); certs, so = lp(signed, so); attrs, so = lp(signed, so)
entry, _ = lp(digs, 0); algo, = struct.unpack_from('<I', entry, 0); dig, _ = lp(entry, 4)
cert_der, _ = lp(certs, 0)
sig_entry, _ = lp(sigs, 0); salgo, = struct.unpack_from('<I', sig_entry, 0); sig, _ = lp(sig_entry, 4)
assert algo == salgo == 0x0103, hex(algo)
cert = x509.load_der_x509_certificate(cert_der)
cert.public_key().verify(sig, signed, padding.PKCS1v15(), hashes.SHA256())
print("signature over signed-data: VALID  (subject:", cert.subject.rfc4514_string() + ")")
# recompute the APK digest independently
def chunks(buf):
    for i in range(0, len(buf), 1 << 20):
        c = buf[i:i + (1 << 20)]
        yield hashlib.sha256(b'\xa5' + struct.pack('<I', len(c)) + c).digest()
eocd = bytearray(d[e:]); struct.pack_into('<I', eocd, 16, blk_start)
parts = list(chunks(d[:blk_start])) + list(chunks(d[cd_off:cd_off+cd_size])) + list(chunks(bytes(eocd)))
top = hashlib.sha256(b'\x5a' + struct.pack('<I', len(parts)) + b''.join(parts)).digest()
print("APK digest matches:", top == dig, "(%d chunks)" % len(parts))
assert top == dig
