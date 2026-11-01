package dev.fontmod.app;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Reads the selected file's cmap directly: Android's fallback must not count as coverage. */
final class FontInspector {
    static final class Result {
        String name, postScript, format;
        boolean korean, latin;
        Typeface typeface;
    }
    private static final class Table {
        int start, length;
        Table(int s, int n) { start = s; length = n; }
    }
    private final ByteBuffer b;
    private final Map<String, Table> tables = new HashMap<>();
    private FontInspector(File file) throws Exception {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 12 || f.length() > FontLibrary.MAX_BYTES) throw new Exception("폰트 파일 크기를 확인해 주세요.");
            byte[] bytes = new byte[(int) f.length()]; f.readFully(bytes);
            b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        }
        int magic = b.getInt(0);
        if (magic == 0x74746366) throw new Exception("TTC 모음 파일은 아직 지원하지 않아요. TTF 또는 OTF 파일을 선택해 주세요.");
        if (magic != 0x00010000 && magic != 0x4f54544f) throw new Exception("읽을 수 있는 TTF 또는 OTF 파일이 아니에요.");
        int n = u16(4);
        if (n == 0 || n > 256 || 12L + n * 16L > b.limit()) throw new Exception("손상된 폰트 테이블이에요.");
        for (int i = 0; i < n; i++) {
            int at = 12 + 16 * i;
            String tag = new String(b.array(), at, 4, StandardCharsets.US_ASCII);
            long offset = u32(at + 8), length = u32(at + 12);
            if (offset + length > b.limit()) throw new Exception("손상된 폰트 테이블이에요.");
            tables.put(tag, new Table((int) offset, (int) length));
        }
        if (!tables.containsKey("cmap") || !tables.containsKey("name")) throw new Exception("필수 폰트 정보가 없어요.");
    }
    static Result inspect(File file) throws Exception {
        FontInspector p = new FontInspector(file);
        new Font.Builder(file).build(); // Native font parser must also accept the file.
        Result r = new Result();
        r.name = p.name(16); if (r.name.isEmpty()) r.name = p.name(1);
        r.postScript = p.name(6);
        if (r.name.isEmpty()) r.name = "가져온 폰트";
        r.format = p.b.getInt(0) == 0x4f54544f ? "OTF" : "TTF";
        r.korean = p.glyph(0xac00) && p.glyph(0xd55c) && p.glyph(0xae00);
        r.latin = p.glyph('A') && p.glyph('a') && p.glyph('0');
        r.typeface = new Typeface.Builder(file).build();
        if (r.typeface == null) throw new Exception("폰트를 미리 볼 수 없어요.");
        return r;
    }
    private int u16(int at) { return b.getShort(at) & 0xffff; }
    private long u32(int at) { return b.getInt(at) & 0xffffffffL; }
    private String name(int id) {
        Table t = tables.get("name");
        if (t.length < 6) return "";
        int n = u16(t.start + 2), strings = u16(t.start + 4), score = -1;
        String best = "";
        for (int i = 0; i < n; i++) {
            int at = t.start + 6 + i * 12;
            if (at + 12 > t.start + t.length) break;
            if (u16(at + 6) != id) continue;
            int platform = u16(at), language = u16(at + 4), len = u16(at + 8);
            int offset = t.start + strings + u16(at + 10);
            if (offset < t.start || offset + len > t.start + t.length || (platform != 0 && platform != 3)) continue;
            int s = language == 0x412 ? 3 : language == 0x409 ? 2 : 1;
            if (s > score) {
                best = new String(b.array(), offset, len, StandardCharsets.UTF_16BE).replaceAll("[\\p{Cntrl}]", "").trim();
                if (best.length() > 120) best = best.substring(0, 120);
                score = s;
            }
        }
        return best;
    }
    private boolean glyph(int code) {
        Table t = tables.get("cmap");
        if (t.length < 4) return false;
        int count = u16(t.start + 2);
        for (int i = 0; i < count; i++) {
            int rec = t.start + 4 + i * 8;
            if (rec + 8 > t.start + t.length) break;
            int platform = u16(rec), encoding = u16(rec + 2);
            if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) continue;
            long relative = u32(rec + 4);
            if (relative + 2 > t.length) continue;
            int sub = t.start + (int) relative, format = u16(sub);
            if (format == 12 && sub + 16 <= t.start + t.length) {
                long length = u32(sub + 4), groups = u32(sub + 12);
                if (length < 16 || sub + length > t.start + t.length || 16 + groups * 12 > length) continue;
                for (int j = 0; j < groups; j++) {
                    int at = sub + 16 + j * 12;
                    if (code >= u32(at) && code <= u32(at + 4)) return u32(at + 8) + code - u32(at) != 0;
                }
            } else if (format == 4 && sub + 16 <= t.start + t.length) {
                int length = u16(sub + 2), n = u16(sub + 6) / 2;
                if (sub + length > t.start + t.length || 16L + n * 8L > length) continue;
                for (int j = 0; j < n; j++) {
                    int end = sub + 14 + 2 * j, start = sub + 16 + 2 * n + 2 * j;
                    if (code < u16(start) || code > u16(end)) continue;
                    int delta = b.getShort(sub + 16 + 4 * n + 2 * j);
                    int rangeAt = sub + 16 + 6 * n + 2 * j, range = u16(rangeAt);
                    if (range == 0) return ((code + delta) & 0xffff) != 0;
                    int glyphAt = rangeAt + range + 2 * (code - u16(start));
                    if (glyphAt < sub || glyphAt + 2 > sub + length) continue;
                    int g = u16(glyphAt);
                    return g != 0 && ((g + delta) & 0xffff) != 0;
                }
            }
        }
        return false;
    }
}
