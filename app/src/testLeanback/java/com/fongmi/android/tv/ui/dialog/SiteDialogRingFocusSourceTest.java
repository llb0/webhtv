package com.fongmi.android.tv.ui.dialog;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 电视端站点弹窗的「整页一张表」环形跳转：只在页面外缘回绕，容器之间的移动不动。
 */
public class SiteDialogRingFocusSourceTest {

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve(".git"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("repository root not found");
        return path;
    }

    private static String read(String relative) throws Exception {
        return Files.readString(root().resolve(relative), StandardCharsets.UTF_8);
    }

    private static String body(String source, String declaration) {
        int open = source.indexOf('{', source.indexOf(declaration));
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        return "";
    }

    @Test
    public void dialogInterceptsPageRingBeforeAnythingElse() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        assertTrue(dialog.contains("if (handlePageRing(keyCode, event)) return true;"));

        String ring = body(dialog, "private boolean handlePageRing(");
        assertTrue("环回只处理按下事件", ring.contains("if (event.getAction() != KeyEvent.ACTION_DOWN) return false;"));
        assertTrue("分组栏参与整页环形", ring.contains("if (isGroupChip(focus)) return handleGroupRing(focus, keyCode);"));
        assertTrue("网格参与整页环形", ring.contains("if (isInGrid(focus)) return handleGridRing(focus, keyCode);"));
        assertTrue("管理按钮列参与整页环形", ring.contains("if (isInActionColumn(focus)) return handleActionRing(focus, keyCode);"));
    }

    @Test
    public void menuKeyStillJumpsToTheFirstGroup() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        String key = body(dialog, "private boolean onDialogKey(");
        assertTrue("菜单键仍回分组第一个「全部」", key.contains("View all = findGroupView(\"\");") && key.contains("all.requestFocus()"));
        assertTrue("菜单键不顺手切组", key.contains("groupSwitching = true;") && key.contains("groupSwitching = false;"));
    }

    @Test
    public void groupRowWrapsUpToTheLastGridRowAndAcrossItsOwnEnds() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        String group = body(dialog, "private boolean handleGroupRing(");
        assertTrue("分组栏按上回到网格最后一行", group.contains("KeyEvent.KEYCODE_DPAD_UP) return focusSitePosition(adapter.getItemCount() - 1);"));
        assertTrue("分组栏首尾标签左右互绕", group.contains("index == 0) return focusGroupAt(count - 1);")
                && group.contains("index == count - 1) return focusGroupAt(0);"));
    }

    @Test
    public void gridWrapsInsideItsRowAndDownToTheGroupRow() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        String grid = body(dialog, "private boolean handleGridRing(");
        assertTrue("网格最后一行按下回到分组栏", grid.contains("position / count >= (total - 1) / count) return requestGroupFocus();"));
        assertTrue("行首按左回绕到本行最右", grid.contains("position == rowStart") && grid.contains("return focusSitePosition(rowEnd);"));
        assertTrue("行尾按右回绕到本行最左", grid.contains("position == rowEnd") && grid.contains("return focusSitePosition(rowStart);"));
        assertTrue("管理模式下右键仍让位给右排按钮，不被环形抢走",
                grid.contains("if (hasActionColumn()) return false;"));
    }

    @Test
    public void gridUpOnTheFirstRowStillGoesToTheGroupRowAndOtherRowsStayNative() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        String up = body(dialog, "public boolean onItemKeyUp(");
        assertTrue("首行按上仍回分组栏（原有跨容器移动）", up.contains("if (position >= getCount()) return false;") && up.contains("return requestGroupFocus();"));

        String adapter = read("app/src/leanback/java/com/fongmi/android/tv/ui/adapter/SiteAdapter.java");
        assertFalse("列表不再自己处理左右方向，避免单个容器环形", adapter.contains("onItemKeyHorizontal"));
        assertTrue("列表只把上键交给弹窗，其余方向交给系统焦点搜索",
                adapter.contains("if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return false;"));
    }

    @Test
    public void actionColumnWrapsRightToTheRowStartWithoutTouchingVerticalMoves() throws Exception {
        String dialog = read("app/src/leanback/java/com/fongmi/android/tv/ui/dialog/SiteDialog.java");
        String action = body(dialog, "private boolean handleActionRing(");
        assertTrue("页右缘按右回到该行最左", action.contains("KeyEvent.KEYCODE_DPAD_RIGHT) return focusGridRowStartNear(focus);"));
        assertTrue("左键不拦截，交给原有焦点搜索进网格", !action.contains("KEYCODE_DPAD_LEFT"));
        assertTrue("按钮列上下端仍按整页回绕", action.contains("index == 0) return focusSitePosition(adapter.getItemCount() - 1);")
                && action.contains("index == items.size() - 1) return requestGroupFocus();"));
    }
}
