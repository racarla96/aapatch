package aapatch;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal read/modify/write support for Android binary XML (compiled AndroidManifest.xml).
 * Strings are held as Java Strings in the tree; the string pool and resource map are rebuilt on write,
 * so adding new attributes never requires manual index fix-ups.
 */
public final class Axml {
    private Axml() {}

    public static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    public static final int TYPE_REFERENCE = 0x01, TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10,
            TYPE_INT_HEX = 0x11, TYPE_BOOLEAN = 0x12;

    private static final int CT_XML = 0x0003, CT_POOL = 0x0001, CT_RESMAP = 0x0180,
            CT_NS_START = 0x0100, CT_NS_END = 0x0101, CT_EL_START = 0x0102, CT_EL_END = 0x0103;

    // ---------------------------------------------------------------- model

    public static final class Attr {
        public String ns, name, str;
        public int resId, type, data;

        public Attr(String ns, String name, int resId) {
            this.ns = ns; this.name = name; this.resId = resId;
        }
        public boolean isString() { return type == TYPE_STRING; }
        @Override public String toString() {
            String v = isString() ? "\"" + str + "\"" : String.format("type=0x%x data=0x%x", type, data);
            return (ns != null ? "android:" : "") + name + (resId != 0 ? String.format("[0x%08x]", resId) : "") + "=" + v;
        }
    }

    public static final class Element {
        public String ns, name;
        public final List<Attr> attrs = new ArrayList<>();
        public final List<Element> children = new ArrayList<>();

        public Element(String ns, String name) { this.ns = ns; this.name = name; }

        public Attr attr(String attrNs, String attrName) {
            for (Attr a : attrs) if (Objects.equals(a.ns, attrNs) && a.name.equals(attrName)) return a;
            return null;
        }
        public Attr android(String attrName) { return attr(ANDROID_NS, attrName); }
        public String androidStr(String attrName) {
            Attr a = android(attrName);
            return a != null && a.isString() ? a.str : null;
        }
        public void setAndroidString(String attrName, int resId, String value) {
            Attr a = android(attrName);
            if (a == null) { a = new Attr(ANDROID_NS, attrName, resId); attrs.add(a); }
            a.type = TYPE_STRING; a.str = value; a.data = 0;
        }
        public void setAndroidBool(String attrName, int resId, boolean value) {
            Attr a = android(attrName);
            if (a == null) { a = new Attr(ANDROID_NS, attrName, resId); attrs.add(a); }
            a.type = TYPE_BOOLEAN; a.str = null; a.data = value ? -1 : 0;
        }
        public void setAndroidInt(String attrName, int resId, int value) {
            Attr a = android(attrName);
            if (a == null) { a = new Attr(ANDROID_NS, attrName, resId); attrs.add(a); }
            a.type = TYPE_INT_DEC; a.str = null; a.data = value;
        }
        public void removeAndroid(String attrName) { attrs.removeIf(a -> ANDROID_NS.equals(a.ns) && a.name.equals(attrName)); }
        public List<Element> children(String childName) {
            List<Element> r = new ArrayList<>();
            for (Element c : children) if (c.name.equals(childName)) r.add(c);
            return r;
        }
        public Element child(String childName) {
            for (Element c : children) if (c.name.equals(childName)) return c;
            return null;
        }
    }

    public static final class Document {
        /** {prefix, uri} pairs, outermost first. */
        public final List<String[]> namespaces = new ArrayList<>();
        public Element root;
    }

    // ---------------------------------------------------------------- parse

    public static Document parse(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != CT_XML) throw new IllegalArgumentException("Not a binary XML file");
        int total = Math.min(b.getInt(4), data.length);
        List<String> pool = null;
        int[] resMap = new int[0];
        Document doc = new Document();
        ArrayDeque<Element> stack = new ArrayDeque<>();
        int pos = 8;
        while (pos + 8 <= total) {
            int type = b.getShort(pos) & 0xffff, hdr = b.getShort(pos + 2) & 0xffff, size = b.getInt(pos + 4);
            if (size < 8) throw new IllegalArgumentException("Corrupt chunk at " + pos);
            switch (type) {
                case CT_POOL: pool = readPool(b, pos); break;
                case CT_RESMAP: {
                    int n = (size - hdr) / 4;
                    resMap = new int[n];
                    for (int i = 0; i < n; i++) resMap[i] = b.getInt(pos + hdr + 4 * i);
                    break;
                }
                case CT_NS_START:
                    doc.namespaces.add(new String[]{str(pool, b.getInt(pos + 16)), str(pool, b.getInt(pos + 20))});
                    break;
                case CT_NS_END: break;
                case CT_EL_START: {
                    Element e = new Element(str(pool, b.getInt(pos + 16)), str(pool, b.getInt(pos + 20)));
                    int attrStart = b.getShort(pos + 24) & 0xffff, attrSize = b.getShort(pos + 26) & 0xffff;
                    int count = b.getShort(pos + 28) & 0xffff;
                    for (int i = 0; i < count; i++) {
                        int o = pos + 16 + attrStart + i * attrSize;
                        int nameIdx = b.getInt(o + 4);
                        Attr a = new Attr(str(pool, b.getInt(o)), str(pool, nameIdx),
                                nameIdx >= 0 && nameIdx < resMap.length ? resMap[nameIdx] : 0);
                        a.type = b.get(o + 15) & 0xff;
                        int v = b.getInt(o + 16);
                        if (a.type == TYPE_STRING) a.str = str(pool, v); else a.data = v;
                        e.attrs.add(a);
                    }
                    Element parent = stack.peek();
                    if (parent == null) doc.root = e; else parent.children.add(e);
                    stack.push(e);
                    break;
                }
                case CT_EL_END: stack.pop(); break;
                default: throw new IllegalArgumentException(String.format("Unsupported chunk 0x%04x", type));
            }
            pos += size;
        }
        if (doc.root == null) throw new IllegalArgumentException("No root element");
        return doc;
    }

    private static String str(List<String> pool, int idx) { return idx < 0 || pool == null ? null : pool.get(idx); }

    private static List<String> readPool(ByteBuffer b, int pos) {
        int hdr = b.getShort(pos + 2) & 0xffff;
        int count = b.getInt(pos + 8), styles = b.getInt(pos + 12), flags = b.getInt(pos + 16), strStart = b.getInt(pos + 20);
        if (styles != 0) throw new IllegalArgumentException("Styled strings not supported in manifests");
        boolean utf8 = (flags & 0x100) != 0;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int p = pos + strStart + b.getInt(pos + hdr + 4 * i);
            if (utf8) {
                int l = b.get(p++) & 0xff;
                if ((l & 0x80) != 0) p++;
                int bl = b.get(p++) & 0xff;
                if ((bl & 0x80) != 0) bl = ((bl & 0x7f) << 8) | (b.get(p++) & 0xff);
                byte[] bytes = new byte[bl];
                for (int k = 0; k < bl; k++) bytes[k] = b.get(p + k);
                out.add(new String(bytes, StandardCharsets.UTF_8));
            } else {
                int l = b.getShort(p) & 0xffff; p += 2;
                if ((l & 0x8000) != 0) { l = ((l & 0x7fff) << 16) | (b.getShort(p) & 0xffff); p += 2; }
                byte[] bytes = new byte[l * 2];
                for (int k = 0; k < bytes.length; k++) bytes[k] = b.get(p + k);
                out.add(new String(bytes, StandardCharsets.UTF_16LE));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- write

    private static final class Out {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        void u8(int v) { bo.write(v); }
        void u16(int v) { bo.write(v); bo.write(v >> 8); }
        void u32(int v) { u16(v & 0xffff); u16(v >>> 16); }
        void bytes(byte[] x) { bo.write(x, 0, x.length); }
        int size() { return bo.size(); }
        byte[] toByteArray() { return bo.toByteArray(); }
    }

    public static byte[] write(Document doc) {
        // 1. string pool: resource-mapped attribute names first (index i <-> resMap[i]), then everything else.
        LinkedHashMap<String, Integer> resNames = new LinkedHashMap<>();
        collectResNames(doc.root, resNames);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(resNames.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).thenComparing(Map.Entry::getKey));
        LinkedHashMap<String, Integer> index = new LinkedHashMap<>();
        int[] resMap = new int[sorted.size()];
        for (Map.Entry<String, Integer> e : sorted) { resMap[index.size()] = e.getValue(); index.put(e.getKey(), index.size()); }
        for (String[] ns : doc.namespaces) { add(index, ns[0]); add(index, ns[1]); }
        collectOthers(doc.root, index);
        List<String> strings = new ArrayList<>(index.keySet());

        // 2. serialise
        Out body = new Out();
        // resource map
        if (resMap.length > 0) {
            body.u16(CT_RESMAP); body.u16(8); body.u32(8 + 4 * resMap.length);
            for (int id : resMap) body.u32(id);
        }
        for (String[] ns : doc.namespaces) nsChunk(body, CT_NS_START, index, ns);
        writeElement(body, doc.root, index);
        for (int i = doc.namespaces.size() - 1; i >= 0; i--) nsChunk(body, CT_NS_END, index, doc.namespaces.get(i));

        byte[] pool = poolChunk(strings);
        Out all = new Out();
        all.u16(CT_XML); all.u16(8); all.u32(8 + pool.length + body.size());
        all.bytes(pool);
        all.bytes(body.toByteArray());
        return all.toByteArray();
    }

    private static void add(Map<String, Integer> index, String s) {
        if (s != null && !index.containsKey(s)) index.put(s, index.size());
    }

    private static void collectResNames(Element e, Map<String, Integer> res) {
        for (Attr a : e.attrs) if (a.resId != 0) res.putIfAbsent(a.name, a.resId);
        for (Element c : e.children) collectResNames(c, res);
    }

    private static void collectOthers(Element e, Map<String, Integer> index) {
        add(index, e.ns); add(index, e.name);
        for (Attr a : e.attrs) { add(index, a.ns); add(index, a.name); if (a.isString()) add(index, a.str); }
        for (Element c : e.children) collectOthers(c, index);
    }

    private static int id(Map<String, Integer> index, String s) { return s == null ? -1 : index.get(s); }

    private static void nsChunk(Out o, int type, Map<String, Integer> index, String[] ns) {
        o.u16(type); o.u16(16); o.u32(24); o.u32(0); o.u32(-1);
        o.u32(id(index, ns[0])); o.u32(id(index, ns[1]));
    }

    private static void writeElement(Out o, Element e, Map<String, Integer> index) {
        List<Attr> attrs = new ArrayList<>(e.attrs);
        // resource-id'd attributes sorted by id first (as aapt does), the rest keep their order
        List<Attr> withId = new ArrayList<>(), without = new ArrayList<>();
        for (Attr a : attrs) (a.resId != 0 ? withId : without).add(a);
        withId.sort(Comparator.comparingInt(a -> a.resId));
        attrs = new ArrayList<>(withId); attrs.addAll(without);

        o.u16(CT_EL_START); o.u16(16); o.u32(36 + 20 * attrs.size()); o.u32(0); o.u32(-1);
        o.u32(id(index, e.ns)); o.u32(id(index, e.name));
        o.u16(20); o.u16(20); o.u16(attrs.size()); o.u16(0); o.u16(0); o.u16(0);
        for (Attr a : attrs) {
            o.u32(id(index, a.ns)); o.u32(id(index, a.name));
            if (a.isString()) o.u32(index.get(a.str)); else o.u32(-1);
            o.u16(8); o.u8(0); o.u8(a.type);
            o.u32(a.isString() ? index.get(a.str) : a.data);
        }
        for (Element c : e.children) writeElement(o, c, index);
        o.u16(CT_EL_END); o.u16(16); o.u32(24); o.u32(0); o.u32(-1);
        o.u32(id(index, e.ns)); o.u32(id(index, e.name));
    }

    private static byte[] poolChunk(List<String> strings) {
        Out data = new Out();
        int[] offs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            offs[i] = data.size();
            String s = strings.get(i);
            int len = s.length();
            if (len > 0x7fff) { data.u16((len >>> 16) | 0x8000); data.u16(len & 0xffff); } else data.u16(len);
            data.bytes(s.getBytes(StandardCharsets.UTF_16LE));
            data.u16(0);
        }
        while (data.size() % 4 != 0) data.u8(0);
        int stringsStart = 28 + 4 * strings.size();
        Out o = new Out();
        o.u16(CT_POOL); o.u16(28); o.u32(stringsStart + data.size());
        o.u32(strings.size()); o.u32(0); o.u32(0); o.u32(stringsStart); o.u32(0);
        for (int off : offs) o.u32(off);
        o.bytes(data.toByteArray());
        return o.toByteArray();
    }

    // ---------------------------------------------------------------- debug

    public static String dump(Document doc) {
        StringBuilder sb = new StringBuilder();
        for (String[] ns : doc.namespaces) sb.append("xmlns:").append(ns[0]).append("=").append(ns[1]).append('\n');
        dump(doc.root, 0, sb);
        return sb.toString();
    }

    private static void dump(Element e, int depth, StringBuilder sb) {
        StringBuilder padb = new StringBuilder();
        for (int i = 0; i < depth; i++) padb.append("  ");
        String pad = padb.toString();
        sb.append(pad).append('<').append(e.name);
        for (Attr a : e.attrs) sb.append("\n").append(pad).append("    ").append(a);
        sb.append(">\n");
        for (Element c : e.children) dump(c, depth + 1, sb);
    }
}
