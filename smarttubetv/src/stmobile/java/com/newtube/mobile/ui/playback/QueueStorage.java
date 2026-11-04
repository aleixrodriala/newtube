package com.newtube.mobile.ui.playback;

import java.io.IOException;

/** A scope is an opaque local identifier, never an account name or credential. */
interface QueueStorage {
    QueueSnapshot read(String scope);
    void write(String scope, byte[] bytes) throws IOException;
}
