package com.newtube.mobile.ui.playback;

import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** One private file. AtomicFile retains the last complete version on interrupted writes. */
final class QueueSnapshotStore {
    private final AtomicFile file;
    QueueSnapshotStore(File path) { file = new AtomicFile(path); }

    QueueSnapshot read() {
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > QueueSnapshot.MAX_BYTES) return null;
                out.write(buffer, 0, count);
            }
            return QueueSnapshot.decode(out.toByteArray());
        } catch (IOException ignored) { return null; }
    }

    void write(byte[] bytes) throws IOException {
        if (bytes.length > QueueSnapshot.MAX_BYTES) throw new IOException("Queue too large");
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(bytes); file.finishWrite(out);
        } catch (IOException | RuntimeException error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
    }
}
