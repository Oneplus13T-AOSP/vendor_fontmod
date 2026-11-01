package dev.fontmod.app;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Serial caller; the service remains the authority after interruption or a lost Binder reply. */
final class FontOperations {
    interface Backend {
        FontState state() throws Exception;
        FontState install(File file, String hash, long generation) throws Exception;
        FontState clear(long generation) throws Exception;
    }
    private final Backend backend;
    private final AtomicFile journal, known;
    FontOperations(File directory, Backend backend) {
        this.backend = backend;
        journal = new AtomicFile(new File(directory, "font-operation.json"));
        known = new AtomicFile(new File(directory, "font-state.json"));
    }
    FontState reconcile() throws Exception {
        FontState actual = backend.state();
        if (actual.supported) {
            record(actual); journal.delete();
            return actual;
        }
        String last = "";
        if (known.getBaseFile().exists() || new File(known.getBaseFile().getPath() + ".bak").exists()) {
            try (InputStream in = known.openRead()) {
                JSONObject saved = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                last = saved.optString("storedId", saved.getString("id"));
            }
        }
        return new FontState(false, last, 0, false);
    }
    FontState apply(FontLibrary.Entry entry) throws Exception {
        FontState before = requireBackend();
        if (before.safeMode) throw new Exception("안전 모드에서는 폰트를 변경할 수 없어요.");
        if (before.id.equals(entry.hash)) return before;
        begin("apply", before, entry.hash);
        try { backend.install(entry.file, entry.hash, before.generation); }
        catch (Exception error) {
            if (!backend.state().id.equals(entry.hash)) throw error;
            // The commit may have succeeded even when the reply was lost.
        }
        return verify(entry.hash);
    }
    FontState reset() throws Exception {
        FontState before = requireBackend();
        if (before.id.isEmpty() && before.storedId.isEmpty()) { record(before); journal.delete(); return before; }
        begin("reset", before, "");
        try { backend.clear(before.generation); }
        catch (Exception error) {
            FontState observed = backend.state();
            if (!observed.id.isEmpty() || !observed.storedId.isEmpty()) throw error;
        }
        return verify("");
    }
    void delete(FontLibrary library, List<FontLibrary.Entry> entries) throws Exception {
        FontState actual = reconcile();
        boolean includesActive = false;
        for (FontLibrary.Entry e : entries) if (e.hash.equals(actual.id) || e.hash.equals(actual.storedId)) includesActive = true;
        if ((!actual.supported && includesActive) || (!actual.supported && journal.getBaseFile().exists())) {
            throw new Exception("현재 폰트 적용 상태를 확인할 수 없어 삭제할 수 없어요.");
        }
        // A clear failure must leave EVERY selected library file intact.
        if (includesActive) reset();
        for (FontLibrary.Entry e : entries) library.delete(e);
    }
    private FontState requireBackend() throws Exception {
        FontState state = backend.state();
        if (!state.supported) throw new Exception("현재 ROM에서는 폰트 적용을 사용할 수 없어요.");
        return state;
    }
    private FontState verify(String expected) throws Exception {
        FontState actual = requireBackend();
        if (!actual.id.equals(expected) || !actual.storedId.equals(expected)) {
            throw new Exception("적용 상태가 변경되었어요. 다시 확인해 주세요.");
        }
        record(actual); journal.delete(); return actual;
    }
    private void begin(String type, FontState before, String target) throws Exception {
        write(journal, new JSONObject().put("operation", type).put("before", before.id)
                .put("generation", before.generation).put("target", target));
    }
    private void record(FontState state) throws Exception {
        write(known, new JSONObject().put("id", state.id).put("storedId", state.storedId).put("generation", state.generation));
    }
    private static void write(AtomicFile file, JSONObject value) throws Exception {
        FileOutputStream out = file.startWrite();
        try { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
    }
}
