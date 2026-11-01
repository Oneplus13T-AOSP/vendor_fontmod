package dev.fontmod.app;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class FontLibrary {
    static final long MAX_BYTES = 32L * 1024 * 1024;
    static final class Entry {
        String id, name, hash;
        File file;
        FontInspector.Result font;
    }
    static final class Candidate {
        File file;
        String hash;
        FontInspector.Result font;
    }
    private final Context context;
    private final File root;
    FontLibrary(Context c) {
        context = c;
        root = new File(c.getFilesDir(), "fonts"); root.mkdirs();
        // Interrupted imports are never members of the persistent library.
        File[] abandoned = c.getCacheDir().listFiles((d, n) -> n.startsWith("font-import-"));
        if (abandoned != null) for (File f : abandoned) f.delete();
    }
    List<Entry> list() throws Exception {
        List<Entry> result = new ArrayList<>();
        File[] dirs = root.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            if (dir.getName().startsWith(".deleted-")) {
                new File(dir, "font.bin").delete();
                new AtomicFile(new File(dir, "metadata.json")).delete();
                dir.delete(); continue;
            }
            AtomicFile meta = new AtomicFile(new File(dir, "metadata.json"));
            if (!meta.getBaseFile().exists() && !new File(dir, "metadata.json.bak").exists()) { // Incomplete copy never became an entry.
                new File(dir, "font.bin").delete(); dir.delete(); continue;
            }
            try (FileInputStream in = meta.openRead()) {
                JSONObject obj = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                Entry e = new Entry(); e.id = dir.getName(); e.name = obj.getString("name");
                e.hash = obj.getString("sha256"); e.file = new File(dir, "font.bin");
                e.font = FontInspector.inspect(e.file); result.add(e);
            }
        }
        result.sort(Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    Candidate prepare(Uri uri) throws Exception {
        Candidate c = new Candidate();
        c.file = File.createTempFile("font-import-", ".bin", context.getCacheDir());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(c.file)) {
                if (in == null) throw new IOException("파일을 열 수 없어요.");
                byte[] buf = new byte[65536]; long total = 0; int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("32 MB 이하의 폰트를 선택해 주세요.");
                    digest.update(buf, 0, n); out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            c.hash = hash.toString();
            for (Entry e : list()) if (e.hash.equals(c.hash)) throw new IOException("이미 보관함에 있는 폰트예요: " + e.name);
            c.font = FontInspector.inspect(c.file);
            return c;
        } catch (Exception e) { c.file.delete(); throw e; }
    }
    Entry save(Candidate c, String name) throws Exception {
        name = name.trim();
        if (name.isEmpty() || name.length() > 60 || name.matches("(?s).*[\\p{Cntrl}].*")) throw new IOException("이름은 1~60자의 한 줄로 입력해 주세요.");
        Entry e = new Entry(); e.id = UUID.randomUUID().toString(); e.name = name;
        e.hash = c.hash; e.font = c.font;
        File dir = new File(root, e.id);
        if (!dir.mkdir()) throw new IOException("보관함을 만들 수 없어요.");
        e.file = new File(dir, "font.bin");
        AtomicFile meta = new AtomicFile(new File(dir, "metadata.json"));
        try {
            try (FileInputStream in = new FileInputStream(c.file); FileOutputStream out = new FileOutputStream(e.file)) {
                in.transferTo(out); out.getFD().sync();
            }
            JSONObject o = new JSONObject();
            o.put("name", name); o.put("sha256", e.hash); o.put("postScript", c.font.postScript);
            o.put("format", c.font.format); o.put("addedAt", System.currentTimeMillis());
            FileOutputStream stream = meta.startWrite();
            try { stream.write(o.toString().getBytes(StandardCharsets.UTF_8)); meta.finishWrite(stream); }
            catch (Exception ex) { meta.failWrite(stream); throw ex; }
            c.file.delete();
            return e;
        } catch (Exception ex) { meta.delete(); e.file.delete(); dir.delete(); throw ex; }
    }
    void delete(Entry e) throws IOException {
        // Operations clears an active system selection before reaching this method.
        File directory = e.file.getParentFile();
        File retired = new File(root, ".deleted-" + e.id);
        if (!directory.renameTo(retired)) throw new IOException("폰트를 삭제할 수 없어요.");
        // Atomic retirement prevents a crash between file/metadata removal from breaking the library.
        new File(retired, "font.bin").delete();
        new AtomicFile(new File(retired, "metadata.json")).delete();
        retired.delete();
    }
}
