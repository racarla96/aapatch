import sys, zipfile, struct
from loguru import logger
logger.remove()
from androguard.core.apk import APK
a = APK(sys.argv[1])
print("package      :", a.get_package())
print("v2 signed    :", a.is_signed_v2(), "| v1:", a.is_signed_v1())
print("label        :", a.get_app_name())
print("sharedUserId :", a.get_attribute_value("manifest", "sharedUserId"))
print("appCategory  :", a.get_attribute_value("application", "appCategory"), "| resizeable:", a.get_attribute_value("application", "resizeableActivity"))
print("main activity:", a.get_main_activity())
x = a.get_android_manifest_xml()
ns = "{http://schemas.android.com/apk/res/android}"
for act in x.iter("activity"):
    cats = [c.get(ns+"name") for c in act.iter("category")]
    metas = [(m.get(ns+"name"), m.get(ns+"value")) for m in act.iter("meta-data")]
    if "android.intent.category.LAUNCHER" in cats:
        print("launcher act :", act.get(ns+"name")); print("  categories :", cats); print("  meta-data  :", metas)
print("providers    :", [(p.get(ns+"name").split('.')[-1], p.get(ns+"authorities")) for p in x.iter("provider")])
print("own perms    :", [p.get(ns+"name") for p in x.iter("permission")])
print("receivers    :", [r.get(ns+"name") for r in x.iter("receiver")])
# alignment of stored entries
z = zipfile.ZipFile(sys.argv[1]); raw = open(sys.argv[1],'rb').read(); bad = 0; n = 0
for i in z.infolist():
    if i.compress_type == 0:
        sig, = struct.unpack_from('<I', raw, i.header_offset); assert sig == 0x04034b50
        nl, el = struct.unpack_from('<HH', raw, i.header_offset + 26)
        start = i.header_offset + 30 + nl + el; n += 1
        need = 16384 if i.filename.endswith('.so') else 4
        if start % need: bad += 1; print("MISALIGNED", i.filename, start % need)
print("stored entries: %d, misaligned: %d" % (n, bad))
print("stale v1 sig files:", [i.filename for i in z.infolist() if i.filename.upper().endswith(('.SF','.RSA')) or i.filename=='META-INF/MANIFEST.MF'])
