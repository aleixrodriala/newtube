package com.newtube.mobile.downloads;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.FileHelpers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Where finished downloads live, and how they are opened, shared and deleted.
 *
 * <p>Android 10+: the public media collections ({@code Movies/NewTube}, {@code Music/NewTube})
 * through MediaStore - no storage permission, the files show up in the gallery and any file
 * manager, and the app keeps write access to what it created. The muxer writes straight into
 * the collection's file descriptor, so a finished file is never copied.
 *
 * <p>Android 7-9: {@code Android/media/<package>/NewTube} - the one public location an app can
 * write without a runtime permission there; a media scan makes it visible.
 */
final class DownloadStorage {
    private static final String FOLDER = "NewTube";

    /** An output the muxer can write to, plus what is needed to finish or abandon it. */
    static final class Target {
        final Uri uri;
        @Nullable final File file;
        @Nullable final ParcelFileDescriptor pfd;

        Target(Uri uri, @Nullable File file, @Nullable ParcelFileDescriptor pfd) {
            this.uri = uri;
            this.file = file;
            this.pfd = pfd;
        }
    }

    private final Context mContext;

    DownloadStorage(Context context) {
        mContext = context.getApplicationContext();
    }

    Target create(String fileName, boolean audioOnly) throws IOException {
        String mime = audioOnly ? "audio/mp4" : "video/mp4";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (audioOnly ? "Music" : "Movies") + File.separator + FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = audioOnly
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IOException("media store refused the file");
            }
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "rw");
            if (pfd == null) {
                resolver.delete(uri, null, null);
                throw new IOException("media store file not writable");
            }
            return new Target(uri, null, pfd);
        }

        File dir = legacyDir();
        if (dir == null) {
            throw new IOException("no external storage");
        }
        File file = uniqueFile(dir, fileName);
        return new Target(Uri.fromFile(file), file, null);
    }

    MediaMuxer openMuxer(Target target) throws IOException {
        if (target.pfd != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return new MediaMuxer(target.pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            throw new IOException("descriptor muxing needs Android 8");
        }
        if (target.file == null) {
            throw new IOException("no output path");
        }
        return new MediaMuxer(target.file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** Publishes the finished file. */
    void commit(Target target) throws IOException {
        if (target.pfd != null) {
            target.pfd.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                mContext.getContentResolver().update(target.uri, values, null, null);
            }
        } else if (target.file != null) {
            MediaScannerConnection.scanFile(mContext, new String[]{target.file.getAbsolutePath()}, null, null);
        }
    }

    /** Drops a half-written output. */
    void abandon(Target target) {
        try {
            if (target.pfd != null) {
                target.pfd.close();
            }
        } catch (IOException ignored) {
        }
        delete(target.uri.toString());
    }

    boolean delete(@Nullable String uriString) {
        if (uriString == null) {
            return false;
        }
        Uri uri = Uri.parse(uriString);
        try {
            if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                return mContext.getContentResolver().delete(uri, null, null) > 0;
            }
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
                File file = new File(uri.getPath());
                boolean deleted = file.delete();
                MediaScannerConnection.scanFile(mContext, new String[]{file.getAbsolutePath()}, null, null);
                return deleted;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
        return false;
    }

    /** True when the finished file can still be opened (not removed by the user or a reinstall). */
    boolean exists(@Nullable String uriString) {
        if (uriString == null) {
            return false;
        }
        Uri uri = Uri.parse(uriString);
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return uri.getPath() != null && new File(uri.getPath()).exists();
        }
        try (ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null;
        } catch (FileNotFoundException | SecurityException | IllegalArgumentException e) {
            return false;
        } catch (IOException e) {
            return true; // close() failed after a successful open
        }
    }

    /** Size of the finished file in bytes, or -1. */
    long sizeOf(@Nullable String uriString) {
        if (uriString == null) {
            return -1;
        }
        Uri uri = Uri.parse(uriString);
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return uri.getPath() != null ? new File(uri.getPath()).length() : -1;
        }
        try (ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null ? pfd.getStatSize() : -1;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return -1;
        }
    }

    /** A URI other apps may read (share sheet); MediaStore URIs already are, files go via the provider. */
    @Nullable
    Uri shareUri(@Nullable String uriString) {
        if (uriString == null) {
            return null;
        }
        Uri uri = Uri.parse(uriString);
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            return FileHelpers.getFileUri(mContext, uri.getPath());
        }
        return uri;
    }

    @Nullable
    private File legacyDir() {
        File[] dirs = mContext.getExternalMediaDirs();
        File base = dirs != null && dirs.length > 0 ? dirs[0] : null;
        if (base == null) {
            return null;
        }
        File dir = new File(base, FOLDER);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir.isDirectory() ? dir : null;
    }

    private static File uniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) {
            return file;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + " " + System.currentTimeMillis() + ext);
    }
}
