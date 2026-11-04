package com.newtube.mobile.ui.playback;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import android.util.AtomicFile;
import java.io.FileOutputStream;
import java.io.FileInputStream;

/** Separate private AtomicFiles; the legacy queue is claimed once by the first selected scope. */
final class QueueAccountStorage implements QueueStorage {
    private final File directory;
    private final QueueSnapshotStore legacy;
    private final String legacyOwner;

    QueueAccountStorage(File directory, String initialScope) throws IOException {
        this.directory = directory;
        legacy = new QueueSnapshotStore(new File(directory, "mobile-queue-v1.json"));
        AtomicFile owner = new AtomicFile(new File(directory, "mobile-queue-owner-v2"));
        String claimed;
        try (FileInputStream in = owner.openRead()) {
            byte[] bytes = new byte[65];
            int count = in.read(bytes);
            claimed = count == 64 && in.read() == -1
                    ? new String(bytes, 0, count, StandardCharsets.US_ASCII) : "";
            if (!claimed.matches("[0-9a-f]{64}")) claimed = ""; // fail closed on corrupt ownership
        } catch (java.io.FileNotFoundException missing) {
            if (!initialScope.matches("[0-9a-f]{64}")) throw new IOException("Invalid queue scope");
            FileOutputStream out = null;
            try {
                out = owner.startWrite();
                out.write(initialScope.getBytes(StandardCharsets.US_ASCII));
                owner.finishWrite(out);
            } catch (IOException | RuntimeException error) {
                if (out != null) owner.failWrite(out);
                throw error;
            }
            claimed = initialScope;
        }
        legacyOwner = claimed;
    }

    private File path(String scope) {
        if (!scope.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid queue scope");
        return new File(directory, "mobile-queue-v2-" + scope + ".json");
    }

    @Override public QueueSnapshot read(String scope) {
        File file = path(scope);
        // A deliberately empty or corrupt scoped file must never resurrect the old shared queue.
        boolean exists = file.exists() || new File(file + ".bak").exists() || new File(file + ".new").exists();
        QueueSnapshot saved = new QueueSnapshotStore(file).read();
        return saved == null && !exists && scope.equals(legacyOwner) ? legacy.read() : saved;
    }

    @Override public void write(String scope, byte[] bytes) throws IOException {
        new QueueSnapshotStore(path(scope)).write(bytes);
        if (scope.equals(legacyOwner)) new AtomicFile(new File(directory, "mobile-queue-v1.json")).delete();
    }
}
