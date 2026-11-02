package com.newtube.mobile.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NEWTUBE(menu-icons): the shared menu code names its rows by string ({@code OptionCategory.iconKey})
 * and a name MenuIcons doesn't list draws no icon, silently. So every name the menu presenters use
 * must resolve here. They are read from the presenters' sources: the keys live in common/, which
 * knows nothing of the phone's drawables.
 */
public class MenuIconsTest {
    private static final String MENU_DIR =
            "../common/src/main/java/com/liskovsoft/smartyoutubetv2/common/app/presenters/dialogs/menu";
    // MENU_ICONS.put(MainUIData.MENU_ITEM_X, "key"), setIconKey("key"), runWithIcon("key", ...)
    private static final Pattern KEY = Pattern.compile(
            "MENU_ICONS\\.put\\([^,]+,\\s*\"([a-z_]+)\"\\)|(?:setIconKey|runWithIcon)\\(\\s*\"([a-z_]+)\"");

    @Test
    public void everyKeyThePresentersUseHasAnIcon() throws IOException {
        File dir = new File(MENU_DIR);
        assertTrue("run from the module directory: " + dir.getAbsolutePath(), dir.isDirectory());

        Set<String> keys = new TreeSet<>();
        for (String name : new String[] {"VideoMenuPresenter.java", "BaseMenuPresenter.java"}) {
            String source = new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
            Matcher matcher = KEY.matcher(source);
            while (matcher.find()) {
                keys.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
        }
        // The menu items alone are over thirty: far fewer means the pattern stopped matching.
        assertTrue("found only " + keys, keys.size() > 30);

        Set<String> missing = new TreeSet<>();
        for (String key : keys) {
            if (MenuIcons.forKey(key) == 0) {
                missing.add(key);
            }
        }
        assertEquals("keys without an icon in MenuIcons", "[]", missing.toString());
    }

    @Test
    public void noKeyMeansNoIcon() {
        assertEquals(0, MenuIcons.forKey(null));
        assertEquals(0, MenuIcons.forKey("no_such_row"));
    }
}
