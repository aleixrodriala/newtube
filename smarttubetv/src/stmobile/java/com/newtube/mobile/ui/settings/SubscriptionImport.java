package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.ChannelGroupServiceWrapper;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.common.MobileSnackbar;

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup;

import io.reactivex.rxjava3.core.Observable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * NEWTUBE(import): Settings > Backup > "Import subscriptions". Takes a file picked with Android's
 * own file picker and hands it to the engine's importer ({@code ChannelGroupService}), which until
 * 1.15 sat behind a TV file picker that Android 13+ never let open (docs/settings/AUDIT-app.md).
 *
 * <ul>
 *     <li>NewPipe or PipePipe "Export subscriptions" (JSON) and "Export database" (a zip with
 *     {@code newpipe.db}): their YouTube channels join the Subscriptions kept on this phone, the
 *     ones NewTube follows while signed out.</li>
 *     <li>GrayJay and PocketTube exports: passed through as they are, as SmartTube did, and their
 *     groups are pinned.</li>
 * </ul>
 */
final class SubscriptionImport {
    private static final String TAG = SubscriptionImport.class.getSimpleName();

    /** Larger than any subscriptions file; a NewPipe database with years of history is ~20 MB. */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    private static final String NEWPIPE_DB = "newpipe.db";

    /** What the picked file turned out to be, ready for the engine. */
    private static final class Prepared {
        final File file;
        /** YouTube channels found in a NewPipe/PipePipe file; -1 for a GrayJay/PocketTube file. */
        final int channels;

        Prepared(File file, int channels) {
            this.file = file;
            this.channels = channels;
        }
    }

    private SubscriptionImport() {
    }

    static void importFrom(@NonNull Context context, @NonNull Uri uri) {
        Context app = context.getApplicationContext();
        MobileSnackbar.show(context, R.string.mobile_settings_import_working);

        Prepared[] prepared = new Prepared[1];
        boolean[] imported = new boolean[1];
        RxHelper.execute(
                RxHelper.fromCallable(() -> {
                    prepared[0] = prepare(app, uri);
                    return prepared[0];
                }).flatMap(p -> {
                    if (p.channels == 0) {
                        return Observable.<List<ItemGroup>>empty();
                    }
                    return ChannelGroupServiceWrapper.instance(app).importGroupsObserve(p.file);
                }),
                groups -> {
                    imported[0] = true;
                    // NewPipe's channels went into Subscriptions and come back as no group; any other
                    // app's groups are new ones, pinned the way SmartTube pinned them.
                    if (groups != null && !groups.isEmpty()) {
                        AppDialogUtil.pinGroups(app, groups);
                    } else {
                        done(app, prepared[0]);
                    }
                },
                error -> {
                    Log.e(TAG, "Import failed: %s", error.getMessage());
                    cleanUp(prepared[0]);
                    MobileSnackbar.show(app, error instanceof ImportException
                            ? ((ImportException) error).message : R.string.mobile_settings_import_failed);
                },
                () -> {
                    cleanUp(prepared[0]);
                    if (!imported[0]) {
                        // The engine read nothing from it (or a NewPipe file held no YouTube channel).
                        MobileSnackbar.show(app, R.string.mobile_settings_import_nothing);
                    }
                });
    }

    private static void done(@NonNull Context context, @Nullable Prepared prepared) {
        BrowsePresenter.instance(context).onSubscriptionsEdited();
        int count = prepared != null ? prepared.channels : -1;
        CharSequence text = count > 0
                ? context.getResources().getQuantityString(R.plurals.mobile_settings_import_done, count, count)
                : context.getString(R.string.mobile_settings_import_done_groups);
        if (count > 0 && YouTubeSignInService.instance().isSigned()) {
            // Signed in, Subscriptions shows the account's channels: these wait for signing out.
            text = context.getString(R.string.mobile_settings_import_done_signed_in, text);
        }
        MobileSnackbar.show(context, text, null, null);
    }

    /** Runs off the main thread: copies the picked file and turns it into one the engine reads. */
    @NonNull
    private static Prepared prepare(@NonNull Context context, @NonNull Uri uri) throws IOException, ImportException {
        File dir = new File(context.getCacheDir(), "subscription-import");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No cache dir");
        }

        File picked = new File(dir, "picked");
        copy(context, uri, picked);

        byte[] head = readHead(picked);
        List<SubscriptionImportParser.Channel> channels;
        if (SubscriptionImportParser.isZip(head)) {
            channels = readZip(picked, new File(dir, NEWPIPE_DB));
        } else {
            String text = readText(picked);
            channels = SubscriptionImportParser.parseJson(text);
            if (channels == null) {
                // Not a NewPipe/PipePipe file: GrayJay or PocketTube, read by the engine as is.
                return new Prepared(picked, -1);
            }
        }

        File json = new File(dir, "subscriptions.json");
        writeText(json, SubscriptionImportParser.toNewPipeJson(channels));
        //noinspection ResultOfMethodCallIgnored
        picked.delete();
        return new Prepared(json, channels.size());
    }

    /** The YouTube subscriptions in the {@code newpipe.db} of a NewPipe/PipePipe backup zip. */
    @NonNull
    private static List<SubscriptionImportParser.Channel> readZip(@NonNull File zip, @NonNull File db)
            throws IOException, ImportException {
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new java.io.FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && NEWPIPE_DB.equals(new File(entry.getName()).getName())) {
                    try (OutputStream out = new FileOutputStream(db)) {
                        copyLimited(in, out);
                    }
                    found = true;
                    break;
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        if (!found) {
            throw new ImportException(R.string.mobile_settings_import_not_newpipe);
        }

        List<SubscriptionImportParser.Channel> result = new ArrayList<>();
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor cursor = database.rawQuery("SELECT url, name FROM subscriptions", null)) {
                while (cursor.moveToNext()) {
                    String url = cursor.getString(0);
                    if (url != null) {
                        result.add(new SubscriptionImportParser.Channel(url, cursor.getString(1)));
                    }
                }
            }
        } catch (RuntimeException e) { // SQLiteException: not a database, or no subscriptions table
            Log.e(TAG, "Can't read %s: %s", NEWPIPE_DB, e.getMessage());
            throw new ImportException(R.string.mobile_settings_import_not_newpipe);
        } finally {
            if (database != null) {
                database.close();
            }
            deleteDb(db);
        }
        return SubscriptionImportParser.youTubeOnly(result);
    }

    private static void copy(@NonNull Context context, @NonNull Uri uri, @NonNull File to) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) {
                throw new IOException("Can't open " + uri);
            }
            copyLimited(in, out);
        }
    }

    private static void copyLimited(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new IOException("File too large");
            }
            out.write(buffer, 0, read);
        }
    }

    @NonNull
    private static byte[] readHead(@NonNull File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[4];
            int read = in.read(head);
            if (read < 4) {
                ByteArrayOutputStream partial = new ByteArrayOutputStream();
                partial.write(head, 0, Math.max(read, 0));
                return partial.toByteArray();
            }
            return head;
        }
    }

    @NonNull
    private static String readText(@NonNull File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copyLimited(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteDb(@NonNull File db) {
        for (String suffix : new String[] {"", "-journal", "-wal", "-shm"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(db.getPath() + suffix).delete();
        }
    }

    private static void cleanUp(@Nullable Prepared prepared) {
        if (prepared != null) {
            //noinspection ResultOfMethodCallIgnored
            prepared.file.delete();
        }
    }

    /** A failure with its own message for the user. */
    private static final class ImportException extends Exception {
        final int message;

        ImportException(int message) {
            super("import: " + message);
            this.message = message;
        }
    }
}
