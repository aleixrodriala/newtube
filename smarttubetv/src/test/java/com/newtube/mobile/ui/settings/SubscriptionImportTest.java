package com.newtube.mobile.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** {@link SubscriptionImport#readZip} on a backup zip built the way NewPipe's "Export database" is. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SubscriptionImportTest {

    private static final String A = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "UCbbbbbbbbbbbbbbbbbbbbbb";

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void readsTheYouTubeSubscriptionsOfNewPipeDb() throws Exception {
        File db = newPipeDb(
                new Object[] {0, "https://www.youtube.com/channel/" + A, "Alpha"},
                new Object[] {1, "https://soundcloud.com/someone", "SoundCloud"},
                new Object[] {0, "https://www.youtube.com/channel/" + B, "Beta"},
                new Object[] {0, "https://m.youtube.com/channel/" + A, "Alpha again"});
        File zip = zip(db, "newpipe.db");

        List<SubscriptionImportParser.Channel> channels =
                SubscriptionImport.readZip(zip, new File(mFolder.getRoot(), "out.db"));

        assertEquals(2, channels.size());
        assertEquals("https://www.youtube.com/channel/" + A, channels.get(0).url);
        assertEquals("Alpha", channels.get(0).name);
        assertEquals("https://www.youtube.com/channel/" + B, channels.get(1).url);
        assertEquals("Beta", channels.get(1).name);
    }

    @Test
    public void zipWithoutNewPipeDbIsNotNewPipe() throws Exception {
        File other = mFolder.newFile("subscription_groups");
        write(other, "[]");

        assertNotNewPipe(zip(other, "stores/subscription_groups"));
    }

    @Test
    public void newPipeDbThatIsNoDatabaseIsNotNewPipe() throws Exception {
        File junk = mFolder.newFile("junk");
        write(junk, "this is not an SQLite database, just text long enough to have a header");

        assertNotNewPipe(zip(junk, "newpipe.db"));
    }

    private void assertNotNewPipe(File zip) throws IOException {
        try {
            SubscriptionImport.readZip(zip, new File(mFolder.getRoot(), "out.db"));
            fail("read as a NewPipe backup");
        } catch (SubscriptionImport.ImportException e) {
            assertEquals(R.string.mobile_settings_import_not_newpipe, e.message);
        }
    }

    /** A {@code newpipe.db} with NewPipe's {@code subscriptions} table and these (service_id, url, name) rows. */
    private File newPipeDb(Object[]... rows) throws IOException {
        File file = new File(mFolder.newFolder(), "newpipe.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("CREATE TABLE subscriptions (uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + " service_id INTEGER NOT NULL, url TEXT, name TEXT, avatar_url TEXT,"
                    + " subscriber_count INTEGER, description TEXT, notification_mode INTEGER NOT NULL)");
            for (Object[] row : rows) {
                ContentValues values = new ContentValues();
                values.put("service_id", (Integer) row[0]);
                values.put("url", (String) row[1]);
                values.put("name", (String) row[2]);
                values.put("notification_mode", 0);
                db.insertOrThrow("subscriptions", null, values);
            }
        } finally {
            db.close();
        }
        return file;
    }

    /** A zip holding NewPipe's settings file and {@code file} as {@code name}. */
    private File zip(File file, String name) throws IOException {
        File zip = new File(mFolder.newFolder(), "backup.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("preferences.json"));
            out.write("{}".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            out.closeEntry();
        }
        return zip;
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
