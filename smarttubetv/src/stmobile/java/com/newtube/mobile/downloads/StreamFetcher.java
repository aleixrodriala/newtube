package com.newtube.mobile.downloads;

import android.util.Log;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.newtube.mobile.player.MediaHttpClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches one googlevideo stream into a file, resumable, in bounded {@code Range} chunks.
 *
 * <p>Chunks rather than one long GET for the same reason the player reads ranges: googlevideo
 * paces an unbounded transfer to roughly playback speed on most itags, while ranged reads are
 * served at line rate. The {@code Range} header is the only range mechanism used here - the
 * {@code range=} query mirror is a parked experiment with a 416/poisoned-cache post-mortem in
 * HANDOFF, and nothing about downloads changes that verdict.
 *
 * <p>Uses the player's media client ({@link MediaHttpClient}): the shared API client carries
 * the InnerTube interceptors (authorization, visitor headers) that googlevideo answers with
 * 403, plus a 45 s call timeout that would cut a large chunk on a slow link.
 */
final class StreamFetcher {
    private static final String TAG = StreamFetcher.class.getSimpleName();
    static final long CHUNK_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 8;

    interface Progress {
        /** @return false to abort the transfer. */
        boolean onBytes(long fileBytes, long totalBytes);
    }

    /** Thrown when the server refuses the URL (403/410): the link expired or the network changed. */
    static final class UrlRefusedException extends IOException {
        UrlRefusedException(int code) {
            super("stream refused: HTTP " + code);
        }
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private final OkHttpClient mClient;
    private final String mUserAgent;

    StreamFetcher(String userAgent) {
        mClient = MediaHttpClient.create(OkHttpManager.instance().getClient());
        mUserAgent = userAgent;
    }

    /**
     * Appends to {@code target} from its current length until the stream ends.
     *
     * @param expectedLength content length from the format info, or -1 when unknown
     * @return the final file length
     */
    long fetch(String url, File target, long expectedLength, Progress progress) throws IOException {
        long total = expectedLength;
        long position = target.exists() ? target.length() : 0;
        int attempt = 0;

        if (total > 0 && position >= total) {
            return position;
        }

        while (true) {
            if (!progress.onBytes(position, total)) {
                throw new CancelledException();
            }

            long end = total > 0 ? Math.min(position + CHUNK_BYTES, total) - 1 : position + CHUNK_BYTES - 1;
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", mUserAgent)
                    .header("Range", "bytes=" + position + "-" + end)
                    .build();

            try (Response response = mClient.newCall(request).execute()) {
                int code = response.code();
                if (code == 403 || code == 410 || code == 404) {
                    throw new UrlRefusedException(code);
                }
                if (code == 416) {
                    // Past the end: the file already holds everything (length was unknown).
                    return position;
                }
                if (code != 206 && code != 200) {
                    throw new IOException("stream HTTP " + code);
                }
                if (code == 200 && position > 0) {
                    // Server ignored the range: restart from zero rather than corrupt the file.
                    Log.w(TAG, "range ignored, restarting " + target.getName());
                    if (!target.delete()) {
                        throw new IOException("cannot reset part file");
                    }
                    position = 0;
                }

                long announcedTotal = totalFromContentRange(response.header("Content-Range"));
                if (announcedTotal > 0) {
                    total = announcedTotal;
                } else if (code == 200 && response.body() != null && response.body().contentLength() > 0) {
                    total = response.body().contentLength();
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("empty body");
                }

                long chunkBytes = 0;
                try (InputStream in = body.byteStream();
                     OutputStream out = new FileOutputStream(target, position > 0)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long lastReport = System.currentTimeMillis();
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        position += n;
                        chunkBytes += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 250) {
                            lastReport = now;
                            if (!progress.onBytes(position, total)) {
                                throw new CancelledException();
                            }
                        }
                    }
                }

                attempt = 0; // a chunk landed: the link is alive, reset the failure budget

                if (total > 0 && position >= total) {
                    return position;
                }
                if (total <= 0 && chunkBytes < CHUNK_BYTES) {
                    return position; // short read with no known total = end of stream
                }
                if (code == 200) {
                    return position; // whole body delivered in one go
                }
            } catch (UrlRefusedException | CancelledException e) {
                throw e;
            } catch (IOException e) {
                attempt++;
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long backoffMs = Math.min(15_000, 1_000L << Math.min(attempt, 4));
                Log.w(TAG, "chunk failed (" + attempt + "/" + MAX_ATTEMPTS + "), retry in " + backoffMs + "ms: " + e);
                position = target.exists() ? target.length() : 0; // trust the disk, not the counter
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        }
    }

    /** Best-effort single GET to a file (thumbnails); null on any failure. */
    @Nullable
    File fetchSmall(String url, File target) {
        Request request = new Request.Builder().url(url).header("User-Agent", mUserAgent).build();
        try (Response response = mClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                return null;
            }
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return target;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
    }

    /** "bytes 0-999/12345" -> 12345; -1 when absent or "*". */
    static long totalFromContentRange(@Nullable String header) {
        if (header == null) {
            return -1;
        }
        int slash = header.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
