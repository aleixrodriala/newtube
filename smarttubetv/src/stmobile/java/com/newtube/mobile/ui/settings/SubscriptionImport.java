package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *     ones NewTube follows while signed out. Signed in, the import asks to sign out first: the
 *     engine keeps that list per account (MediaServicePrefs profiles), so channels imported then
 *     would land in the account's copy, which signed out never shows.</li>
 *     <li>GrayJay and PocketTube exports: passed through as they are, as SmartTube did, and their
 *     groups are pinned.</li>
 * </ul>
 */
final class SubscriptionImport {
    private static final String TAG = SubscriptionImport.class.getSimpleName();

    /** Larger than any subscriptions file; a NewPipe database with years of history is ~20 MB. */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /**
     * Anything but a zip is read whole into memory, by this class and by the engine, so it stops
     * here: NewPipe writes ~100 bytes a channel, so this is over 100,000 channels.
     */
    private static final long MAX_TEXT_BYTES = 16L * 1024 * 1024;
    /** How much of the file is looked at to tell a zip or JSON from anything else. */
    private static final int HEAD_BYTES = 1024;
    private static final String IMPORT_DIR = "subscription-import";
    private static final String NEWPIPE_DB = "newpipe.db";
    /**
     * The engine's group for the Subscriptions kept on this phone (ChannelGroupServiceImpl's
     * SUBSCRIPTIONS_GROUP_ID in MediaServiceCore, private there).
     */
    private static final String SUBSCRIPTIONS_GROUP_ID = "1000";

    /**
     * One import at a time: they share {@link #IMPORT_DIR} and count what they added by comparing
     * the Subscriptions before and after. Main thread only.
     */
    private static boolean sBusy;

    /** What the picked file turned out to be, ready for the engine. */
    private static final class Prepared {
        final File file;
        /** YouTube channels found in a NewPipe/PipePipe file; -1 for a GrayJay/PocketTube file. */
        final int channels;
        /** The channel ids in Subscriptions just before the engine's import. */
        @Nullable Set<String> before;

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
        if (sBusy) {
            return;
        }
        sBusy = true;

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
                    p.before = subscribedIds(app);
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
                    sBusy = false;
                    MobileSnackbar.show(app, messageFor(error));
                },
                () -> {
                    cleanUp(prepared[0]);
                    sBusy = false;
                    if (!imported[0]) {
                        // Only a NewPipe/PipePipe file without a YouTube channel ends here: when the
                        // engine reads nothing from a file it returns null, which arrives as onError.
                        MobileSnackbar.show(app, R.string.mobile_settings_import_nothing);
                    }
                });
    }

    private static void done(@NonNull Context context, @Nullable Prepared prepared) {
        BrowsePresenter.instance(context).onSubscriptionsEdited();
        CharSequence text;
        if (prepared != null && prepared.channels > 0) {
            // What the engine really added: it keeps only the channels the YouTube Data API knows
            // (terminated ones drop out) and skips the ones already there.
            Set<String> added = subscribedIds(context);
            if (prepared.before != null) {
                added.removeAll(prepared.before);
            }
            int count = added.size();
            text = count > 0
                    ? context.getResources().getQuantityString(R.plurals.mobile_settings_import_done, count, count)
                    : context.getString(R.string.mobile_settings_import_none_new);
        } else {
            text = context.getString(R.string.mobile_settings_import_done_groups);
        }
        MobileSnackbar.show(context, text, null, null);
    }

    /** The channel ids in the Subscriptions kept on this phone (the current profile's). */
    @NonNull
    private static Set<String> subscribedIds(@NonNull Context context) {
        String[] ids = ChannelGroupServiceWrapper.instance(context).findChannelIdsForGroup(SUBSCRIPTIONS_GROUP_ID);
        return ids != null ? new HashSet<>(Arrays.asList(ids)) : new HashSet<>();
    }

    /** What to tell the user about a failed import. */
    private static int messageFor(@NonNull Throwable error) {
        if (error instanceof ImportException) {
            return ((ImportException) error).message;
        }
        // The engine reports a lost connection as an IllegalStateException around the IOException
        // (RetrofitHelper.getResponse); a file that couldn't be read here is an IOException itself.
        if (error.getCause() instanceof IOException) {
            return R.string.mobile_settings_import_offline;
        }
        return R.string.mobile_settings_import_failed;
    }

    /** Runs off the main thread: copies the picked file and turns it into one the engine reads. */
    @NonNull
    private static Prepared prepare(@NonNull Context context, @NonNull Uri uri) throws IOException, ImportException {
        File dir = new File(context.getCacheDir(), IMPORT_DIR);
        // One import at a time (sBusy): anything here was left by an import the system killed.
        clear(dir, null);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No cache dir");
        }

        Prepared prepared = null;
        try {
            prepared = prepareIn(context, uri, dir);
            return prepared;
        } finally {
            // Only the file for the engine stays, until cleanUp(); the picked copy, the backup's
            // database and anything half written on a failure go now.
            clear(dir, prepared != null ? prepared.file : null);
        }
    }

    @NonNull
    private static Prepared prepareIn(@NonNull Context context, @NonNull Uri uri, @NonNull File dir)
            throws IOException, ImportException {
        File picked = new File(dir, "picked");
        boolean zip = copy(context, uri, picked);

        List<SubscriptionImportParser.Channel> channels;
        if (zip) {
            channels = readZip(picked, new File(dir, NEWPIPE_DB));
        } else {
            String text = readText(picked);
            channels = SubscriptionImportParser.parseJson(text);
            if (channels == null) {
                // Not a NewPipe/PipePipe file: GrayJay or PocketTube, read by the engine as is.
                return new Prepared(picked, -1);
            }
        }

        if (!channels.isEmpty() && YouTubeSignInService.instance().isSigned()) {
            // The engine would add them to this account's copy of Subscriptions, not the one shown
            // signed out (see the class comment).
            throw new ImportException(R.string.mobile_settings_import_sign_out_first);
        }

        File json = new File(dir, "subscriptions.json");
        writeText(json, SubscriptionImportParser.toNewPipeJson(channels));
        return new Prepared(json, channels.size());
    }

    /**
     * The YouTube subscriptions in the {@code newpipe.db} of a NewPipe/PipePipe backup zip. Leaves
     * {@code db} on disk for the caller to delete.
     */
    @VisibleForTesting
    @NonNull
    static List<SubscriptionImportParser.Channel> readZip(@NonNull File zip, @NonNull File db)
            throws IOException, ImportException {
        boolean found = false;
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (!entry.isDirectory() && NEWPIPE_DB.equals(new File(entry.getName()).getName())) {
                    try (OutputStream out = new FileOutputStream(db)) {
                        if (!copyLimited(in, out, MAX_FILE_BYTES)) {
                            throw new IOException(NEWPIPE_DB + " too large");
                        }
                    }
                    found = true;
                    break;
                }
            }
        }
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
        }
        return SubscriptionImportParser.youTubeOnly(result);
    }

    /**
     * Copies the picked file to {@code to}; true for a zip. Anything else has to look like JSON
     * and fit in {@link #MAX_TEXT_BYTES}, so a video picked by mistake is turned away early.
     */
    private static boolean copy(@NonNull Context context, @NonNull Uri uri, @NonNull File to)
            throws IOException, ImportException {
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Can't open " + uri);
            }
            InputStream in = new BufferedInputStream(raw, 64 * 1024);
            in.mark(HEAD_BYTES);
            byte[] head = readHead(in);
            in.reset();

            boolean zip = SubscriptionImportParser.isZip(head);
            if (!zip && !SubscriptionImportParser.looksLikeJson(head)) {
                throw new ImportException(R.string.mobile_settings_import_not_export);
            }
            try (OutputStream out = new FileOutputStream(to)) {
                if (copyLimited(in, out, zip ? MAX_FILE_BYTES : MAX_TEXT_BYTES)) {
                    return zip;
                }
            }
            if (zip) {
                throw new IOException("File too large");
            }
            throw new ImportException(R.string.mobile_settings_import_not_export);
        }
    }

    /** Copies {@code in} to {@code out}; false, with {@code out} cut short, past {@code limit} bytes. */
    private static boolean copyLimited(@NonNull InputStream in, @NonNull OutputStream out, long limit)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                return false;
            }
            out.write(buffer, 0, read);
        }
        return true;
    }

    /** Up to {@link #HEAD_BYTES} from the start of {@code in}; fewer only for a shorter file. */
    @NonNull
    private static byte[] readHead(@NonNull InputStream in) throws IOException {
        byte[] head = new byte[HEAD_BYTES];
        int length = 0;
        int read;
        while (length < head.length && (read = in.read(head, length, head.length - length)) != -1) {
            length += read;
        }
        return length == head.length ? head : Arrays.copyOf(head, length);
    }

    @NonNull
    private static String readText(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!copyLimited(in, out, MAX_TEXT_BYTES)) {
                throw new IOException("File too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(@NonNull File file, @NonNull String text) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Deletes the files in {@code dir} but {@code keep}. */
    private static void clear(@NonNull File dir, @Nullable File keep) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.equals(keep)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private static void cleanUp(@Nullable Prepared prepared) {
        if (prepared != null) {
            //noinspection ResultOfMethodCallIgnored
            prepared.file.delete();
        }
    }

    /** A failure with its own message for the user. */
    @VisibleForTesting
    static final class ImportException extends Exception {
        final int message;

        ImportException(int message) {
            super("import: " + message);
            this.message = message;
        }
    }
}
