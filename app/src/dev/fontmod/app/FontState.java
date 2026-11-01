package dev.fontmod.app;

import org.json.JSONObject;

final class FontState {
    final boolean supported, safeMode;
    final String id, storedId;
    final long generation;
    FontState(boolean supported, String id, long generation, boolean safeMode) {
        this(supported, id, id, generation, safeMode);
    }
    FontState(boolean supported, String id, String storedId, long generation, boolean safeMode) {
        this.supported = supported; this.id = id; this.generation = generation; this.safeMode = safeMode;
        this.storedId = storedId;
    }
    static FontState parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (o.getInt("version") != 1) throw new Exception("지원하지 않는 폰트 서비스 버전이에요.");
        String id = o.getString("id");
        if (!id.isEmpty() && !id.matches("[a-f0-9]{64}")) throw new Exception("폰트 적용 상태가 올바르지 않아요.");
        String stored = o.optString("storedId", id);
        if (!stored.isEmpty() && !stored.matches("[a-f0-9]{64}")) throw new Exception("저장된 폰트 상태가 올바르지 않아요.");
        return new FontState(true, id, stored, o.getLong("generation"), o.optBoolean("safeMode"));
    }
}
