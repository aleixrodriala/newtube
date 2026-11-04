package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;
import android.app.Application;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 35}, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueueAccountIdentityTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private YouTubeAccount account(String name, String email, String page, String token, boolean selected) {
        return YouTubeAccount.from(Helpers.mergeData(0, name, null, selected, token, email, true, page, "@handle"));
    }
    @After public void clearPrefs() {
        GlobalPreferences.instance(RuntimeEnvironment.getApplication()).setMediaServiceAccountData(null);
    }
    @Test public void pageIdentitySurvivesDisplayNameEmailAndCredentialChanges() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        String original = keys.scope(account("Same name", "first@example.invalid", "page-1", "fixture-one", true));
        assertEquals(original, keys.scope(account("Renamed", "changed@example.invalid", "page-1", "fixture-two", true)));
        assertNotEquals(original, keys.scope(account("Same name", "first@example.invalid", "page-2", "fixture-one", true)));
        assertNotEquals(original, keys.scope(null));
    }
    @Test public void emailFallbackIsNormalizedAndIndependentOfNameOrToken() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        assertEquals(keys.scope(account("A", "A@Example.invalid", null, "fixture-one", true)),
                keys.scope(account("B", " a@example.invalid ", null, "fixture-two", true)));
        assertNotEquals(keys.scope(account("A", "a@example.invalid", null, "fixture-one", true)),
                keys.scope(account("A", "b@example.invalid", null, "fixture-one", true)));
    }
    @Test public void opaqueKeysSurviveRestartButDifferBetweenInstallations() throws Exception {
        YouTubeAccount account = account("Name", "a@example.invalid", "page-1", "fixture-token", true);
        String scope = new QueueAccountIdentity(folder.getRoot()).scope(account);
        assertTrue(scope.matches("[0-9a-f]{64}"));
        assertEquals(scope, new QueueAccountIdentity(folder.getRoot()).scope(account));
        assertNotEquals(scope, new QueueAccountIdentity(folder.newFolder()).scope(account));
        byte[] salt = Files.readAllBytes(new java.io.File(folder.getRoot(), "mobile-queue-salt-v2").toPath());
        assertEquals(32, salt.length);
    }
    @Test public void startupUsesPersistedSelectionBeforeAccountManagerHasRestored() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        YouTubeAccount a = account("Same name", "a@example.invalid", "page-a", "fixture-one", false);
        YouTubeAccount b = account("Same name", "b@example.invalid", "page-b", "fixture-two", true);
        GlobalPreferences prefs = GlobalPreferences.instance(RuntimeEnvironment.getApplication());
        prefs.setMediaServiceAccountData(Helpers.mergeArray(a, b));
        assertEquals(keys.scope(b), keys.initialScope(RuntimeEnvironment.getApplication()));
        b.setSelected(false); prefs.setMediaServiceAccountData(Helpers.mergeArray(a, b));
        assertEquals(keys.scope(null), keys.initialScope(RuntimeEnvironment.getApplication()));
    }
    @Test public void emailIdentityKeepsQueueWhenBackgroundSyncAddsPageId() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        String original = keys.scope(account("Name", "a@example.invalid", null, "fixture-one", true));
        assertEquals(original, keys.scope(account("Name", "a@example.invalid", "page-1", "fixture-two", true)));
        assertEquals(original, new QueueAccountIdentity(folder.getRoot()).scope(
                account("Renamed", "new@example.invalid", "page-1", "fixture-three", true)));
    }

    @Test public void hydrationCanAddPageAndHandleTogether() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        YouTubeAccount old = YouTubeAccount.from(Helpers.mergeData(0, "Name", null, true,
                "fixture-one", "a@example.invalid", true, null, null));
        String original = keys.scope(old);
        assertEquals(original, keys.scope(account("Name", "a@example.invalid", "page-1", "fixture-two", true)));
        assertNotEquals(original, keys.scope(account("Name", "a@example.invalid", "page-2", "fixture-two", true)));
    }
    @Test public void corruptSaltKeepsStableSessionIsolationWithoutReplacingIt() throws Exception {
        java.io.File file = new java.io.File(folder.getRoot(), "mobile-queue-salt-v2");
        Files.write(file.toPath(), new byte[] {1});
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        YouTubeAccount a = account("A", "a@example.invalid", "page-a", "fixture-one", true);
        YouTubeAccount b = account("B", "b@example.invalid", "page-b", "fixture-two", true);
        assertEquals(keys.scope(a), keys.scope(a));
        assertNotEquals(keys.scope(a), keys.scope(b)); assertNotEquals(keys.scope(a), keys.scope(null));
        assertEquals(1, Files.size(file.toPath()));
    }
    @Test public void missingPrivateDirectoryAndUnwritableAliasesCannotCrashIdentityResolution() throws Exception {
        QueueAccountIdentity session = new QueueAccountIdentity(null);
        YouTubeAccount a = account("A", "a@example.invalid", "page-a", "fixture-one", true);
        assertEquals(session.scope(a), session.scope(a));
        assertNotEquals(session.scope(a), session.scope(null));
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        // Turn the storage directory into an ordinary file, a deterministic write failure.
        for (java.io.File child : folder.getRoot().listFiles()) assertTrue(child.delete());
        assertTrue(folder.getRoot().delete()); Files.write(folder.getRoot().toPath(), new byte[] {0});
        assertNotEquals(keys.scope(a), keys.scope(null));
        assertEquals(keys.scope(a), keys.scope(a));
        assertTrue(folder.getRoot().delete()); assertTrue(folder.getRoot().mkdir());
    }

    @Test public void incompleteAccountCannotBorrowAnonymousQueue() throws Exception {
        QueueAccountIdentity keys = new QueueAccountIdentity(folder.getRoot());
        assertNotEquals(keys.scope(null), keys.scope(YouTubeAccount.fromToken("fixture-token")));
    }
}
