package com.liskovsoft.smartyoutubetv2.common.app.models.playback;

import java.io.IOException;

/** A source explicitly requires user action; automatic client/source/quality recovery is forbidden. */
public final class TerminalSourceException extends IOException {
    public TerminalSourceException(String userMessage) { super(userMessage); }
}
