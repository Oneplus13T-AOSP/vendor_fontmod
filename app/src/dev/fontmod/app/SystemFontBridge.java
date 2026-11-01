package dev.fontmod.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Same sources build against SDK 35 for preview and platform APIs for the product. */
final class SystemFontBridge implements FontOperations.Backend {
    private Object manager;
    private Method getState, install, clear;
    SystemFontBridge(Context context) {
        try {
            manager = context.getSystemService("font");
            if (manager == null) return;
            getState = manager.getClass().getMethod("getFontModState");
            install = manager.getClass().getMethod("installFontModFont", ParcelFileDescriptor.class, String.class, long.class);
            clear = manager.getClass().getMethod("clearFontModFont", long.class);
        } catch (ReflectiveOperationException e) { getState = null; }
    }
    private String call(Method method, Object... args) throws Exception {
        if (getState == null) throw new Exception("현재 ROM에서는 폰트 적용을 사용할 수 없어요.");
        try { return (String) method.invoke(manager, args); }
        catch (InvocationTargetException e) {
            android.util.Log.e("FontMod", "System font operation failed", e.getCause());
            throw new Exception("폰트를 적용하지 못했어요. 현재 상태를 확인한 후 다시 시도해 주세요.", e.getCause());
        }
    }
    @Override public FontState state() throws Exception {
        return getState == null ? new FontState(false, "", 0, false) : FontState.parse(call(getState));
    }
    @Override public FontState install(File file, String hash, long generation) throws Exception {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return FontState.parse(call(install, fd, hash, generation));
        }
    }
    @Override public FontState clear(long generation) throws Exception { return FontState.parse(call(clear, generation)); }
}
