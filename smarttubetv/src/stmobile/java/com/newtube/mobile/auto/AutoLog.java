package com.newtube.mobile.auto;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NEWTUBE(android-auto): the car log. Logcat drops this app's lines first (the player is chatty), so every
 * NewTubeAuto line also goes to Android/data/<package>/files/newtube-auto.log (readable with adb,
 * capped at ~512 KB, the older half kept on rollover).
 */
final class AutoLog {
    private static final String TAG = "NewTubeAuto";
    private static final long MAX_BYTES = 512 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static File sFile;

    private AutoLog() {
    }

    static void init(Context context) {
        if (sFile != null) return;
        File dir = context.getApplicationContext().getExternalFilesDir(null);
        if (dir != null) sFile = new File(dir, "newtube-auto.log");
    }

    static void i(String message) {
        Log.i(TAG, message);
        write("I " + message);
    }

    static void w(String message) {
        Log.w(TAG, message);
        write("W " + message);
    }

    private static void write(String line) {
        File file = sFile;
        if (file == null) return;
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        IO.execute(() -> {
            try {
                if (file.length() > MAX_BYTES) {
                    File old = new File(file.getParentFile(), "newtube-auto.old.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(old);
                }
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(stamp + " " + line + "\n");
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
