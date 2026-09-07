package com.newtube.mobile.sabrproof.ump;

/** Relevant identifiers retained from tv-legacy's UMPPartId (see ../PROVENANCE.md). */
public final class UMPPartId {
    public static final int MEDIA_HEADER = 20;
    public static final int MEDIA = 21;
    public static final int MEDIA_END = 22;
    public static final int NEXT_REQUEST_POLICY = 35;
    public static final int FORMAT_INITIALIZATION_METADATA = 42;
    public static final int SABR_REDIRECT = 43;
    public static final int SABR_ERROR = 44;
    public static final int RELOAD_PLAYER_RESPONSE = 46;
    public static final int PLAYBACK_START_POLICY = 47;
    public static final int ALLOWED_CACHED_FORMATS = 48;
    public static final int START_BW_SAMPLING_HINT = 49;
    public static final int PAUSE_BW_SAMPLING_HINT = 50;
    public static final int SELECTABLE_FORMATS = 51;
    public static final int REQUEST_IDENTIFIER = 52;
    public static final int REQUEST_CANCELLATION_POLICY = 53;
    public static final int REQUEST_PIPELINING = 56;
    public static final int SABR_CONTEXT_UPDATE = 57;
    public static final int STREAM_PROTECTION_STATUS = 58;
    public static final int SABR_CONTEXT_SENDING_POLICY = 59;
    public static final int END_OF_TRACK = 62;
    public static final int PREWARM_CONNECTION = 65;

    private UMPPartId() { }
}
