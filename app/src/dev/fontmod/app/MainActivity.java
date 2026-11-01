package dev.fontmod.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free native UI shared by the review APK and the product module. */
public final class MainActivity extends Activity {
    private static final int BLUE = 0xff1c39bb, INK = 0xff17213b, MUTED = 0xff758097;
    private static final int PALE = 0xfff1f4ff, LINE = 0xffe7eaf2, WHITE = Color.WHITE;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FontLibrary library;
    private FontOperations operations;
    private FontState active = new FontState(false, "", 0, false);
    private List<FontLibrary.Entry> entries = new ArrayList<>();
    private FontLibrary.Entry selected;
    private FontLibrary.Candidate candidate;
    private LinearLayout root, body, footer, sampleBox;
    private int page = 0;
    private boolean busy = false;
    private boolean selectionMode = false;
    private final Set<String> checkedIds = new LinkedHashSet<>();
    private static final String DEFAULT_SENTENCE = "오늘의 일상을\n새로운 글씨로.\n\nThe quick brown fox.\n0123456789";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        library = new FontLibrary(this);
        operations = new FontOperations(getFilesDir(), new SystemFontBridge(this));
        if (saved != null) {
            selectionMode = saved.getBoolean("selectionMode");
            ArrayList<String> ids = saved.getStringArrayList("checkedIds");
            if (ids != null) checkedIds.addAll(ids);
        }
        String openId = saved == null ? null : saved.getString("selection");
        reload(openId);
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("selectionMode", selectionMode);
        out.putStringArrayList("checkedIds", new ArrayList<>(checkedIds));
        out.putBoolean("detail", page == 1); if (selected != null) out.putString("selection", selected.id);
    }
    private void ui(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(1); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke); return g;
    }
    private void background(View v, int color, int radius, int border) { v.setBackground(shape(color, radius, border)); }
    private TextView text(String s, float size, int color, boolean strong) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern"); v.setIncludeFontPadding(false);
        v.setTypeface(Typeface.create("sans-serif", strong ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }
    private TextView button(String s, boolean primary, Runnable click) {
        TextView v = text(s, 15, primary ? WHITE : BLUE, true);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(18), dp(15), dp(18), dp(15));
        GradientDrawable bg = shape(primary ? BLUE : PALE, 16, 0);
        v.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x221c39bb), bg, null));
        v.setOnClickListener(w -> { if (!busy) click.run(); }); v.setMinHeight(dp(50)); return v;
    }
    private void gap(LinearLayout parent, int h) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h))); }
    private void pad(View v, int x, int y) { v.setPadding(dp(x), dp(y), dp(x), dp(y)); }
    private void weight(LinearLayout parent, View child) { parent.addView(child, new LinearLayout.LayoutParams(0, -2, 1)); }
    private void base() {
        root = column(); root.setBackgroundColor(WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom); return insets;
        });
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(root);
        LinearLayout top = row(); pad(top, 24, 15);
        TextView brand = text(page == 0 ? (selectionMode ? "폰트 선택" : "FontMod") : "‹", page == 0 ? 23 : 32, BLUE, true);
        if (page == 1) { brand.setContentDescription("보관함으로 돌아가기"); brand.setMinWidth(dp(45)); brand.setOnClickListener(v -> home()); }
        top.addView(brand);
        if (page == 0 && selectionMode) {
            View spacer = new View(this); top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
            boolean allChecked = !entries.isEmpty() && checkedIds.size() == entries.size();
            TextView all = text(allChecked ? "전체 해제" : "전체 선택", 14, BLUE, true);
            pad(all, 12, 13); all.setOnClickListener(v -> {
                checkedIds.clear();
                if (!allChecked) for (FontLibrary.Entry e : entries) checkedIds.add(e.id);
                home();
            }); top.addView(all);
            TextView done = text("×", 28, MUTED, false); done.setContentDescription("선택 모드 종료");
            done.setMinWidth(dp(44)); done.setMinHeight(dp(44)); done.setGravity(Gravity.CENTER);
            done.setOnClickListener(v -> exitSelection()); top.addView(done);
        }
        if (page == 1) {
            TextView label = text("폰트 미리보기", 12, MUTED, false);
            label.setGravity(Gravity.END); weight(top, label);
            TextView more = text("⋮", 23, MUTED, false);
            more.setGravity(Gravity.CENTER); more.setMinWidth(dp(44)); more.setMinHeight(dp(44));
            more.setContentDescription("폰트 메뉴");
            more.setOnClickListener(v -> menu(more)); top.addView(more);
        }
        root.addView(top);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false); scroll.setClipToPadding(false);
        body = column(); pad(body, 24, 8); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column(); pad(footer, 24, 14); root.addView(footer);
    }
    private void reload(String openId) {
        busy = true;
        worker.execute(() -> {
            try {
                List<FontLibrary.Entry> loaded = library.list();
                FontState current = operations.reconcile();
                ui(() -> {
                    busy = false; entries = loaded; active = current;
                    Set<String> existing = new HashSet<>();
                    for (FontLibrary.Entry e : entries) existing.add(e.id);
                    checkedIds.retainAll(existing);
                    if (entries.isEmpty()) selectionMode = false;
                    if (openId != null) {
                        for (FontLibrary.Entry e : entries) if (e.id.equals(openId)) { detail(e); return; }
                    }
                    home();
                });
            } catch (Exception e) { ui(() -> { busy = false; home(); error(e); }); }
        });
    }
    private void home() {
        page = 0; selected = null; base();
        gap(body, 12);
        LinearLayout active = column(); pad(active, 21, 20); background(active, PALE, 22, 0);
        LinearLayout activeTop = row(); weight(activeTop, text("현재 폰트", 14, BLUE, true));
        TextView badge = text(this.active.id.isEmpty() ? "기본값" : (this.active.supported ? "적용됨" : "확인 필요"), 13, BLUE, true); pad(badge, 11, 6); background(badge, 0xffe2e8ff, 20, 0); activeTop.addView(badge);
        active.addView(activeTop); gap(active, 16);
        FontLibrary.Entry currentEntry = null;
        for (FontLibrary.Entry e : entries) if (e.hash.equals(this.active.id)) currentEntry = e;
        final FontLibrary.Entry openCurrent = currentEntry;
        active.addView(text(this.active.id.isEmpty() ? "PixelOS 기본 폰트" : (currentEntry == null ? "사용자 폰트" : currentEntry.name), 21, INK, true));
        gap(active, 15);
        TextView currentSample = text("Aa  가나다  0123", 27, BLUE, false);
        if (currentEntry != null) currentSample.setTypeface(currentEntry.font.typeface);
        active.addView(currentSample);
        active.setOnClickListener(v -> detail(openCurrent)); active.setContentDescription("현재 폰트 미리보기"); body.addView(active);
        if (!this.active.id.isEmpty()) {
            gap(body, 12); body.addView(button("기본값으로 복원", false, () -> detail(null)));
        }
        gap(body, 28);
        body.addView(text("내 폰트", 19, INK, true));
        gap(body, 14);
        if (entries.isEmpty()) {
            LinearLayout empty = column(); pad(empty, 20, 27); background(empty, WHITE, 20, LINE);
            TextView icon = text("Aa +", 31, BLUE, false); icon.setGravity(Gravity.CENTER); empty.addView(icon);
            gap(empty, 17); TextView title = text("첫 번째 폰트를 추가해 보세요", 15, INK, true); title.setGravity(Gravity.CENTER); empty.addView(title);
            gap(empty, 9); TextView desc = text("TTF · OTF 파일을 가져와\n적용 전에 천천히 살펴볼 수 있어요.", 12, MUTED, false);
            desc.setGravity(Gravity.CENTER); desc.setLineSpacing(dp(5), 1); empty.addView(desc); body.addView(empty);
        } else {
            for (FontLibrary.Entry e : entries) renderEntry(e);
        }
        gap(body, 16);
        if (selectionMode) {
            TextView delete = button("삭제", true, this::deleteChecked);
            delete.setEnabled(!checkedIds.isEmpty()); delete.setAlpha(checkedIds.isEmpty() ? .4f : 1f); footer.addView(delete);
        } else footer.addView(button("＋   폰트 추가", true, this::pick));
    }
    private void renderEntry(FontLibrary.Entry e) {
        boolean checked = checkedIds.contains(e.id);
        LinearLayout card = row(); pad(card, 16, 17); background(card, checked ? PALE : WHITE, 18, checked ? BLUE : LINE);
        TextView aa = text("Aa", 28, BLUE, false); aa.setTypeface(e.font.typeface);
        aa.setGravity(Gravity.CENTER); background(aa, PALE, 12, 0); card.addView(aa, new LinearLayout.LayoutParams(dp(58), dp(60)));
        LinearLayout words = column(); words.setPadding(dp(15), 0, dp(8), 0);
        TextView title = text(e.name, 15, INK, true); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); words.addView(title);
        weight(card, words);
        TextView mark = text(selectionMode ? (checked ? "✓" : "○") : (e.hash.equals(this.active.id) ? "✓" : "›"), 24, checked ? BLUE : MUTED, false);
        mark.setMinWidth(dp(32)); mark.setGravity(Gravity.CENTER); card.addView(mark);
        card.setContentDescription(e.name + (selectionMode ? (checked ? ", 선택됨" : ", 선택 안 됨") : ""));
        card.setOnClickListener(v -> {
            if (selectionMode) { toggle(e.id); home(); } else detail(e);
        });
        card.setOnLongClickListener(v -> {
            selectionMode = true; checkedIds.add(e.id); home(); return true;
        });
        body.addView(card); gap(body, 10);
    }
    private void toggle(String id) { if (!checkedIds.remove(id)) checkedIds.add(id); }
    private void exitSelection() { selectionMode = false; checkedIds.clear(); home(); }
    private void deleteChecked() {
        List<FontLibrary.Entry> targets = new ArrayList<>();
        for (FontLibrary.Entry e : entries) if (checkedIds.contains(e.id)) targets.add(e);
        if (targets.isEmpty()) return;
        String message = targets.size() == 1 ? "‘" + targets.get(0).name + "’ 폰트를 보관함에서 삭제할까요?" : "선택한 폰트를 보관함에서 삭제할까요?";
        new AlertDialog.Builder(this).setTitle("폰트 삭제").setMessage(message)
            .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> {
                busy = true;
                worker.execute(() -> {
                    Exception failure = null;
                    try { operations.delete(library, targets); }
                    catch (Exception ex) { failure = ex; }
                    Exception result = failure;
                    ui(() -> {
                        busy = false; checkedIds.clear(); selectionMode = false; reload(null);
                        if (result != null) error(result);
                    });
                });
            }).show();
    }
    private void detail(FontLibrary.Entry entry) {
        page = 1; selected = entry; base();
        String name = entry == null ? "PixelOS 기본 폰트" : entry.name;
        LinearLayout hero = column(); pad(hero, 20, 22); background(hero, PALE, 24, 0);
        TextView title = text(name, 23, INK, true); title.setMaxLines(2); hero.addView(title);
        if (entry != null) { gap(hero, 10); hero.addView(text(entry.font.format, 11, MUTED, false)); }
        body.addView(hero); gap(body, 22);
        body.addView(text("미리보기", 16, INK, true)); gap(body, 16);
        sampleBox = column(); pad(sampleBox, 19, 21); background(sampleBox, WHITE, 18, LINE); body.addView(sampleBox);
        TextView sample = text(DEFAULT_SENTENCE, 24, INK, false);
        sample.setTypeface(face()); sample.setLineSpacing(dp(5), 1.15f);
        sampleBox.addView(sample); gap(body, 22);
        boolean isCurrent = entry == null ? (active.id.isEmpty() && active.storedId.isEmpty())
                : (active.supported && active.id.equals(entry.hash));
        TextView apply = button(isCurrent ? (entry == null ? "기본값 사용 중" : "사용 중")
                : (entry == null ? "기본값 적용" : "이 폰트 적용"), !isCurrent, () -> applySelection(entry));
        apply.setEnabled(!isCurrent); footer.addView(apply);
    }
    private Typeface face() {
        if (selected != null) return selected.font.typeface;
        try { return (Typeface) Typeface.class.getMethod("getFontModDefaultTypeface").invoke(null); }
        catch (Exception ignored) { return Typeface.DEFAULT; }
    }
    private void applySelection(FontLibrary.Entry entry) {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                FontState state = entry == null ? operations.reset() : operations.apply(entry);
                ui(() -> {
                    busy = false; active = state;
                    Toast.makeText(this, "적용했어요.", Toast.LENGTH_SHORT).show(); home();
                });
            } catch (Exception ex) {
                ui(() -> { busy = false; error(ex); reload(entry == null ? null : entry.id); });
            }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        if (operations != null && !busy) reload(page == 1 && selected != null ? selected.id : null);
    }
    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, 10); } catch (ActivityNotFoundException e) { error(new Exception("파일 선택 앱을 찾을 수 없어요.")); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null || busy) return;
        busy = true; Toast.makeText(this, "폰트를 확인하고 있어요…", Toast.LENGTH_SHORT).show();
        android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try { FontLibrary.Candidate c = library.prepare(uri); ui(() -> { busy = false; candidate = c; nameImport(); }); }
            catch (Exception e) { ui(() -> { busy = false; error(e); }); }
        });
    }
    private void nameImport() {
        LinearLayout content = column(); pad(content, 24, 10);
        content.addView(text("보관함에서 사용할 이름을 정해 주세요.\n추가한 뒤에는 이름을 변경할 수 없어요.", 12, MUTED, false)); gap(content, 16);
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(candidate.font.name);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)}); input.setSelectAllOnFocus(true); content.addView(input);
        gap(content, 15); TextView sample = text("가나다 Aa 0123", 29, INK, false); sample.setTypeface(candidate.font.typeface); content.addView(sample);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("폰트 추가").setView(content)
            .setNegativeButton("취소", (d, w) -> discardCandidate()).setPositiveButton("보관함에 추가", null).create();
        dialog.setOnCancelListener(d -> discardCandidate());
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("이름을 입력해 주세요."); return; }
            if (busy) return; busy = true; FontLibrary.Candidate c = candidate;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); dialog.setCancelable(false);
            worker.execute(() -> {
                try { FontLibrary.Entry e = library.save(c, name); ui(() -> { busy = false; candidate = null; dialog.dismiss(); reload(e.id); }); }
                catch (Exception e) { ui(() -> { busy = false; dialog.dismiss(); discardCandidate(); error(e); }); }
            });
        })); dialog.show();
    }
    private void discardCandidate() { if (candidate != null) { candidate.file.delete(); candidate = null; } }
    private void menu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("파일 정보").setOnMenuItemClickListener(i -> { information(); return true; });
        if (selected != null) popup.getMenu().add("보관함에서 삭제").setOnMenuItemClickListener(i -> { deleteSelected(); return true; });
        popup.show();
    }
    private void information() {
        String message = selected == null ? "PixelOS의 원래 시스템 폰트예요. 기본 폰트 파일은 수정하거나 삭제하지 않아요."
            : selected.font.name + "\n" + selected.font.format + "\nPostScript: " + selected.font.postScript
            + "\n\nSHA-256\n" + selected.hash;
        new AlertDialog.Builder(this).setTitle("파일 정보").setMessage(message).setPositiveButton("확인", null).show();
    }
    private void deleteSelected() {
        FontLibrary.Entry e = selected;
        new AlertDialog.Builder(this).setTitle("폰트를 삭제할까요?")
            .setMessage("‘" + e.name + "’ 폰트가 보관함에서 삭제돼요. 다시 사용하려면 파일을 추가해 주세요.")
            .setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> {
                busy = true; worker.execute(() -> {
                    try { operations.delete(library, Collections.singletonList(e)); ui(() -> { busy = false; reload(null); }); }
                    catch (Exception ex) { ui(() -> { busy = false; error(ex); }); }
                });
            }).show();
    }
    private void error(Exception e) {
        String m = e.getMessage();
        new AlertDialog.Builder(this).setTitle("작업을 완료하지 못했어요")
            .setMessage(m == null || m.length() > 200 ? "파일을 읽을 수 없어요. 다른 TTF 또는 OTF 파일로 시도해 주세요." : m)
            .setPositiveButton("확인", null).show();
    }
    @Override public void onBackPressed() { if (page == 1) home(); else if (selectionMode) exitSelection(); else super.onBackPressed(); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdown(); }
}
