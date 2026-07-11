package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track;

import java.util.HashMap;
import java.util.Locale;
import java.util.MissingResourceException;

/**
 * Engine-neutral track format DTO. Replaces the exoplayer2 {@code Format} INSIDE the app's shared
 * data model ({@link MediaTrack} / {@code ExoFormatItem} / {@code PlayerData} persistence / the
 * quality sheet). Field names mirror exoplayer2 {@code Format} 1:1 so {@code track.format.width}
 * style call sites survive verbatim.
 *
 * <p>SERIALIZATION WARNING: {@code ExoFormatItem.toString()} persists these fields into user prefs
 * (PlayerData VIDEO_PLAYER_DATA fields 9/10/11/47). The {@code NO_VALUE} (-1) defaults, the
 * per-renderer factory semantics and the {@link #normalizeLanguage(String)} behavior below are
 * byte-compatible replicas of the legacy exoplayer2 {@code Format.createXxxSampleFormat} factories
 * they replace - do not change them without a persistence migration.</p>
 */
public final class TrackFormat {
    /** Same sentinel as exoplayer2 {@code Format.NO_VALUE}. */
    public static final int NO_VALUE = -1;

    public String id;
    public String codecs;
    public String sampleMimeType;
    public int width = NO_VALUE;
    public int height = NO_VALUE;
    public float frameRate = NO_VALUE;
    public int bitrate = NO_VALUE;
    public String language;
    public int channelCount = NO_VALUE;
    public int sampleRate = NO_VALUE;
    public boolean isDrc;

    private TrackFormat() {
    }

    /**
     * Mirror of {@code Format.createVideoSampleFormat} as the DTO layer used it:
     * no language (stays null) and no DRC flag on video.
     */
    public static TrackFormat createVideo(String id, String sampleMimeType, String codecs,
                                          int bitrate, int width, int height, float frameRate) {
        TrackFormat format = new TrackFormat();
        format.id = id;
        format.sampleMimeType = sampleMimeType;
        format.codecs = codecs;
        format.bitrate = bitrate;
        format.width = width;
        format.height = height;
        format.frameRate = frameRate;
        return format;
    }

    /**
     * Mirror of {@code Format.createAudioSampleFormat}: width/height/frameRate stay NO_VALUE,
     * language goes through the same normalization the exo2 Format constructor applied.
     */
    public static TrackFormat createAudio(String id, String sampleMimeType, String codecs,
                                          int bitrate, int channelCount, int sampleRate,
                                          String language, boolean isDrc) {
        TrackFormat format = new TrackFormat();
        format.id = id;
        format.sampleMimeType = sampleMimeType;
        format.codecs = codecs;
        format.bitrate = bitrate;
        format.channelCount = channelCount;
        format.sampleRate = sampleRate;
        format.language = normalizeLanguage(language);
        format.isDrc = isDrc;
        return format;
    }

    /**
     * Mirror of {@code Format.createTextSampleFormat}: everything numeric stays NO_VALUE,
     * language normalized like the exo2 Format constructor did.
     */
    public static TrackFormat createText(String id, String sampleMimeType, String language) {
        TrackFormat format = new TrackFormat();
        format.id = id;
        format.sampleMimeType = sampleMimeType;
        format.language = normalizeLanguage(language);
        return format;
    }

    // ---------------------------------------------------------------------------------
    // Language normalization - byte-compatible replica of exo2 Util.normalizeLanguageCode
    // (the exo2 Format constructor ran every language through it, so persisted prefs and
    // the selector's language matching both saw the normalized form).
    // ---------------------------------------------------------------------------------

    private static HashMap<String, String> sIso3ToIso2;

    // ISO 639-2/B codes that Locale can't map back to ISO 639-1 (exo2 Util's table).
    private static final String[] ISO3_BIBLIOGRAPHICAL_TO_ISO2 =
            new String[] {
                "alb", "sq",
                "arm", "hy",
                "baq", "eu",
                "bur", "my",
                "tib", "bo",
                "chi", "zh",
                "cze", "cs",
                "dut", "nl",
                "ger", "de",
                "gre", "el",
                "fre", "fr",
                "geo", "ka",
                "ice", "is",
                "mac", "mk",
                "mao", "mi",
                "may", "ms",
                "per", "fa",
                "rum", "ro",
                "slo", "sk",
                "wel", "cy"
            };

    /**
     * Returns a normalized, all-lowercase IETF BCP 47 language tag; unparseable input (e.g. the
     * human-readable audio-variant labels YouTube puts where a language code would live) is kept
     * but lowercased - exactly what exo2's normalization did.
     */
    public static String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        // Locale data (especially for API < 21) may produce tags with '_' instead of '-'.
        String normalizedTag = language.replace('_', '-');
        // Filters out ill-formed sub-tags, replaces deprecated tags and normalizes valid ones.
        normalizedTag = Locale.forLanguageTag(normalizedTag).toLanguageTag();
        if (normalizedTag.isEmpty() || "und".equals(normalizedTag)) {
            // Tag isn't valid, keep using the original.
            normalizedTag = language;
        }
        normalizedTag = normalizedTag.toLowerCase(Locale.US);
        String mainLanguage = splitAtFirst(normalizedTag, "-");
        if (mainLanguage.length() == 3) {
            // 3-letter ISO 639-2 codes are not converted to 2-letter ISO 639-1 automatically.
            if (sIso3ToIso2 == null) {
                sIso3ToIso2 = createIso3ToIso2Map();
            }
            String iso2Language = sIso3ToIso2.get(mainLanguage);
            if (iso2Language != null) {
                normalizedTag = iso2Language + normalizedTag.substring(/* beginIndex= */ 3);
            }
        }
        return normalizedTag;
    }

    private static String splitAtFirst(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        return index == -1 ? value : value.substring(0, index);
    }

    private static HashMap<String, String> createIso3ToIso2Map() {
        String[] iso2Languages = Locale.getISOLanguages();
        HashMap<String, String> iso3ToIso2 =
                new HashMap<>(iso2Languages.length + ISO3_BIBLIOGRAPHICAL_TO_ISO2.length);
        for (String iso2 : iso2Languages) {
            try {
                // This returns the ISO 639-2/T code for the language.
                String iso3 = new Locale(iso2).getISO3Language();
                if (iso3 != null && !iso3.isEmpty()) {
                    iso3ToIso2.put(iso3, iso2);
                }
            } catch (MissingResourceException e) {
                // Shouldn't happen for the list of known languages; don't throw either.
            }
        }
        // Add the additional ISO 639-2/B codes to the mapping.
        for (int i = 0; i < ISO3_BIBLIOGRAPHICAL_TO_ISO2.length; i += 2) {
            iso3ToIso2.put(ISO3_BIBLIOGRAPHICAL_TO_ISO2[i], ISO3_BIBLIOGRAPHICAL_TO_ISO2[i + 1]);
        }
        return iso3ToIso2;
    }
}
