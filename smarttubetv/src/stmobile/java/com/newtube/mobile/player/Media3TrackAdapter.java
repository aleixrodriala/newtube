package com.newtube.mobile.player;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Media3 replacement for {@code TrackSelectorManager}: translates the app's
 * {@link FormatItem} vocabulary (persisted presets, explicit picks, the quality sheet) into media3
 * {@code DefaultTrackSelector} parameters, and the current {@link Tracks} back into
 * {@link FormatItem} lists for the pickers.
 *
 * <p>Where the legacy manager had to hand-build adaptive {@code SelectionOverride}s to get ABR,
 * media3 does the right thing natively: a preset ("Auto up to 1080p60 vp9") maps to plain
 * constraints ({@code setMaxVideoSize/...FrameRate} + a soft codec preference) under which the
 * player's own {@code AdaptiveTrackSelection} adapts; an explicit rung maps to a
 * {@link TrackSelectionOverride}. Overrides bind to a concrete {@code TrackGroup} of the CURRENT
 * source, so explicit picks are re-resolved on every tracks change; the shared
 * {@code VideoStateController.restoreFormats} keeps calling {@code setFormat} on each new video,
 * exactly like on the legacy path.</p>
 */
class Media3TrackAdapter {

    private static final String TAG = Media3TrackAdapter.class.getSimpleName();

    /** Marks YouTube's untranslated audio variant in the generated MPD's language label. */
    private static final String ORIGINAL_AUDIO_MARK = "original";

    private final DefaultTrackSelector mTrackSelector;
    /** What the app asked for, per renderer index. Never the momentary ABR rung. */
    private final FormatItem[] mTargets = new FormatItem[3];
    private Tracks mTracks = Tracks.EMPTY;
    /** NEWTUBE(mobile): default multi-audio videos to the untranslated ("original") track. */
    private boolean mPreferOriginalAudio;

    Media3TrackAdapter(DefaultTrackSelector trackSelector) {
        mTrackSelector = trackSelector;
    }

    void setPreferOriginalAudio(boolean prefer) {
        mPreferOriginalAudio = prefer;
    }

    /** New source opened: stale (group-bound) overrides can't apply to the next video. */
    void onSourceChanged() {
        mTracks = Tracks.EMPTY;
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT));
    }

    /**
     * NEWTUBE(bg-audio): drop the whole video renderer for true background audio-only playback
     * (no PiP, no in-app mini-player) so VIDEO stops both downloading and decoding; re-enable on
     * foreground return. Single owner for the flag: composes with the CURRENT parameters (never a
     * snapshot), so the quality/audio/subtitle writes elsewhere neither clear it nor are cleared by
     * it, and {@link #onSourceChanged} only drops group-bound overrides - so the flag survives an
     * autoplay-advance on the reused selector.
     */
    void setVideoTrackDisabled(boolean disabled) {
        mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled));
    }

    /** Fresh tracks from the player: re-resolve every explicit target against the new groups. */
    void onTracksChanged(Tracks tracks) {
        mTracks = tracks;

        for (FormatItem target : mTargets) {
            if (target != null) {
                applyTarget(target);
            }
        }

        // No explicit audio pick anywhere: steer media3's default to the untranslated track
        // (YouTube auto-dubs put the dubbed language first otherwise).
        if (mPreferOriginalAudio && mTargets[FormatItem.TYPE_AUDIO] == null) {
            applyOriginalAudioDefault();
        }
    }

    void selectFormat(FormatItem formatItem) {
        if (formatItem == null) {
            return;
        }

        int type = formatItem.getType();
        if (type < 0 || type >= mTargets.length) {
            return;
        }

        mTargets[type] = formatItem;
        applyTarget(formatItem);
    }

    @Nullable
    FormatItem getSelectedFormat(int rendererIndex) {
        FormatItem target = rendererIndex >= 0 && rendererIndex < mTargets.length ? mTargets[rendererIndex] : null;
        if (target != null) {
            return target;
        }

        // Fall back to what is actually playing.
        for (Tracks.Group group : mTracks.getGroups()) {
            if (Media3FormatConverter.rendererIndexForTrackType(group.getType()) != rendererIndex) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    return toFormatItem(rendererIndex, group, i, true);
                }
            }
        }

        return null;
    }

    List<FormatItem> getFormats(int rendererIndex) {
        List<FormatItem> result = new ArrayList<>();

        for (Tracks.Group group : mTracks.getGroups()) {
            if (Media3FormatConverter.rendererIndexForTrackType(group.getType()) != rendererIndex) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                result.add(toFormatItem(rendererIndex, group, i, group.isTrackSelected(i)));
            }
        }

        if (rendererIndex == TrackSelectorManager.RENDERER_INDEX_VIDEO) {
            // The pickers expect quality-descending order ("first of each rung is its best variant").
            // Rungs by class, not height: a vertical video's 608x1080 is a 480p stream.
            Collections.sort(result, (item1, item2) -> {
                int rung1 = VideoRung.classHeight(item1.getWidth(), item1.getHeight());
                int rung2 = VideoRung.classHeight(item2.getWidth(), item2.getHeight());
                if (rung2 != rung1) {
                    return rung2 - rung1;
                }
                if (item2.getFrameRate() != item1.getFrameRate()) {
                    return Float.compare(item2.getFrameRate(), item1.getFrameRate());
                }
                MediaTrack track1 = item1.getTrack();
                MediaTrack track2 = item2.getTrack();
                int codec = MediaTrack.getCodecWeight(track2) - MediaTrack.getCodecWeight(track1);
                if (codec != 0) {
                    return codec;
                }
                int bitrate1 = track1 != null && track1.format != null ? track1.format.bitrate : -1;
                int bitrate2 = track2 != null && track2.format != null ? track2.format.bitrate : -1;
                return bitrate2 - bitrate1;
            });
        }

        return result;
    }

    // ---------------------------------------------------------------------------------
    // FormatItem -> selector parameters
    // ---------------------------------------------------------------------------------

    private void applyTarget(FormatItem item) {
        switch (item.getType()) {
            case FormatItem.TYPE_VIDEO:
                applyVideoTarget(item);
                break;
            case FormatItem.TYPE_AUDIO:
                applyAudioTarget(item);
                break;
            case FormatItem.TYPE_SUBTITLE:
                applySubtitleTarget(item);
                break;
        }
    }

    /**
     * "Auto" = a ceiling preset, not a concrete stream. Same rule as the legacy selector
     * (which detected presets by a missing format id).
     */
    private static boolean isAutoTarget(FormatItem item) {
        if (item.isPreset()) {
            return true;
        }
        MediaTrack track = item.getTrack();
        return track == null || track.format == null || track.format.id == null;
    }

    private void applyVideoTarget(FormatItem item) {
        DefaultTrackSelector.Parameters.Builder builder = mTrackSelector.buildUponParameters();

        if (isAutoTarget(item)) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            // A square ceiling: the preset's class in either orientation (see VideoRung).
            int ceiling = VideoRung.ceilingSide(VideoRung.classHeight(item.getWidth(), item.getHeight()));
            builder.setMaxVideoSize(ceiling, ceiling);
            builder.setMaxVideoFrameRate(item.getFrameRate() > 0 ? Math.round(item.getFrameRate()) + 1 : Integer.MAX_VALUE);
            // Soft preference; media3 falls back to whatever the stream actually has.
            MediaTrack track = item.getTrack();
            String codec = track != null && track.format != null ? track.format.codecs : null;
            builder.setPreferredVideoMimeTypes(mimeTypesForCodec(codec));
        } else {
            TrackLocation location = findTrack(TrackSelectorManager.RENDERER_INDEX_VIDEO, item);
            if (location != null) {
                // Lift the Auto ceiling out of the way: the override itself pins the track.
                builder.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
                builder.setMaxVideoFrameRate(Integer.MAX_VALUE);
                builder.setPreferredVideoMimeTypes();
                builder.setOverrideForType(
                        new TrackSelectionOverride(location.group.getMediaTrackGroup(), location.trackIndex));
            } else if (!mTracks.getGroups().isEmpty()) {
                Log.d(TAG, "applyVideoTarget: no track matches %s, leaving Auto in place", item);
            } // else: tracks not known yet - re-resolved in onTracksChanged
        }

        mTrackSelector.setParameters(builder);
    }

    private void applyAudioTarget(FormatItem item) {
        DefaultTrackSelector.Parameters.Builder builder = mTrackSelector.buildUponParameters();

        if (isAutoTarget(item)) {
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
            mTrackSelector.setParameters(builder);
            if (mPreferOriginalAudio) {
                applyOriginalAudioDefault();
            }
            return;
        }

        TrackLocation location = findTrack(TrackSelectorManager.RENDERER_INDEX_AUDIO, item);
        if (location != null) {
            builder.setOverrideForType(
                    new TrackSelectionOverride(location.group.getMediaTrackGroup(), location.trackIndex));
            mTrackSelector.setParameters(builder);
        } else {
            mTrackSelector.setParameters(builder);
            // The persisted variant doesn't exist on this video at all: fall back to the
            // untranslated track rather than whatever adaptation set the manifest lists first.
            if (mPreferOriginalAudio) {
                applyOriginalAudioDefault();
            }
        }
    }

    private void applySubtitleTarget(FormatItem item) {
        DefaultTrackSelector.Parameters.Builder builder = mTrackSelector.buildUponParameters();

        if (item.isDefault() || item.getLanguage() == null) {
            // The "None" entry.
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT);
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false);
            TrackLocation location = findTrack(TrackSelectorManager.RENDERER_INDEX_SUBTITLE, item);
            if (location != null) {
                builder.setOverrideForType(
                        new TrackSelectionOverride(location.group.getMediaTrackGroup(), location.trackIndex));
            } else {
                builder.setPreferredTextLanguage(item.getLanguage());
            }
        }

        mTrackSelector.setParameters(builder);
    }

    /** No pick at all: default the audio to YouTube's untranslated ("original") variant. */
    private void applyOriginalAudioDefault() {
        TrackLocation best = null;
        int bestBitrate = -1;
        boolean multipleLanguages = false;
        boolean seenAny = false;
        String firstLanguage = null;

        for (Tracks.Group group : mTracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                String language = Media3FormatConverter.pickLanguage(group.getTrackFormat(i));
                if (!seenAny) {
                    // A plain null-check can't track the first language: the original often has
                    // no xtags (language == null), and it must still count as a distinct variant.
                    seenAny = true;
                    firstLanguage = language;
                } else if (!TextUtils.equals(firstLanguage, language)) {
                    multipleLanguages = true;
                }
                if (isOriginalAudio(group.getTrackFormat(i))) {
                    int bitrate = group.getTrackFormat(i).bitrate;
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate;
                        best = new TrackLocation(group, i);
                    }
                }
            }
        }

        // Single-language videos: media3's own default is already right; don't pin anything.
        if (best != null && multipleLanguages) {
            mTrackSelector.setParameters(mTrackSelector.buildUponParameters()
                    .setOverrideForType(
                            new TrackSelectionOverride(best.group.getMediaTrackGroup(), best.trackIndex)));
        }
    }

    /** Preset codec ("vp9"/"avc"...) -> soft media3 mime preference; unknown -> no preference. */
    private static String[] mimeTypesForCodec(@Nullable String codec) {
        if (codec == null) {
            return new String[0];
        }
        String lower = codec.toLowerCase();
        if (lower.contains("vp9") || lower.contains("vp09")) {
            return new String[]{androidx.media3.common.MimeTypes.VIDEO_VP9};
        }
        if (lower.contains("avc")) {
            return new String[]{androidx.media3.common.MimeTypes.VIDEO_H264};
        }
        if (lower.contains("av01")) {
            return new String[]{androidx.media3.common.MimeTypes.VIDEO_AV1};
        }
        return new String[0];
    }

    // ---------------------------------------------------------------------------------
    // Matching
    // ---------------------------------------------------------------------------------

    private static class TrackLocation {
        final Tracks.Group group;
        final int trackIndex;

        TrackLocation(Tracks.Group group, int trackIndex) {
            this.group = group;
            this.trackIndex = trackIndex;
        }
    }

    /**
     * Locates the media3 track a {@link FormatItem} means. Exact format-id (itag) match first -
     * ids are stable across sessions - then the legacy fallback chain (same rung + codec family
     * for video, same language for subtitles). Audio itags are NOT unique on multi-language
     * videos (every dub carries its own "251"; a persisted "en-us (original)" 251 once pinned a
     * pt-br auto-dub on a Spanish video), so audio ranks by language first:
     * {@link #findAudioTrack}.
     */
    @Nullable
    private TrackLocation findTrack(int rendererIndex, FormatItem item) {
        if (rendererIndex == TrackSelectorManager.RENDERER_INDEX_AUDIO) {
            return findAudioTrack(item);
        }
        String targetId = item.getFormatId();
        TrackLocation fallback = null;

        for (Tracks.Group group : mTracks.getGroups()) {
            if (Media3FormatConverter.rendererIndexForTrackType(group.getType()) != rendererIndex) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                androidx.media3.common.Format format = group.getTrackFormat(i);

                if (targetId != null && targetId.equals(format.id)) {
                    return new TrackLocation(group, i);
                }

                if (fallback == null && matchesLoosely(rendererIndex, item, format)) {
                    fallback = new TrackLocation(group, i);
                }
            }
        }

        return fallback;
    }

    /**
     * NEWTUBE(audio-track): the track a stored audio pick means on the current video - the
     * language variant first, the itag last, because every variant repeats the same itags.
     * <ol>
     *   <li>the same variant and itag (exact);</li>
     *   <li>the same variant (identical label) in the same codec family, then in any codec (a
     *       Spanish Opus 251 pick plays Spanish AAC where the video has no Spanish Opus - not
     *       English 251);</li>
     *   <li>the same language (primary subtag: "en" = "en-US", never "en" = "ben") with
     *       another variant tag, same codec family first;</li>
     *   <li>otherwise the original: its track with the same itag, then the same codec family,
     *       then its best one (a stored untagged 251 plays an AAC-only original - not a dub's
     *       251);</li>
     *   <li>only on a video with no original marked, the same itag in any variant.</li>
     * </ol>
     * An item without a language (a single-language video's track, the sheet's "Default" row)
     * is its own variant: it matches untagged tracks only.
     */
    @Nullable
    private TrackLocation findAudioTrack(FormatItem item) {
        String targetId = item.getFormatId();
        String language = item.getLanguage();
        String codec = codecOf(item);

        TrackLocation exactSameCodec = null;
        TrackLocation exactBest = null;
        int exactBitrate = Integer.MIN_VALUE;
        TrackLocation sameLanguageSameCodec = null;
        TrackLocation sameLanguage = null;
        int sameLanguageBitrate = Integer.MIN_VALUE;
        TrackLocation originalSameId = null;
        TrackLocation originalSameCodec = null;
        TrackLocation originalBest = null;
        int originalBestBitrate = Integer.MIN_VALUE;
        TrackLocation sameId = null;

        for (Tracks.Group group : mTracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) {
                    continue;
                }
                androidx.media3.common.Format format = group.getTrackFormat(i);
                boolean idEquals = targetId != null && targetId.equals(format.id);
                String trackLanguage = Media3FormatConverter.pickLanguage(format);

                if (sameVariant(language, trackLanguage)) {
                    if (idEquals) {
                        return new TrackLocation(group, i);
                    }
                    if (exactSameCodec == null && codecFamilyEquals(codec, format.codecs)) {
                        exactSameCodec = new TrackLocation(group, i);
                    }
                    if (format.bitrate > exactBitrate) {
                        exactBitrate = format.bitrate;
                        exactBest = new TrackLocation(group, i);
                    }
                    continue;
                }

                if (audioLanguageEquals(language, trackLanguage)) {
                    if (sameLanguageSameCodec == null && codecFamilyEquals(codec, format.codecs)) {
                        sameLanguageSameCodec = new TrackLocation(group, i);
                    }
                    if (format.bitrate > sameLanguageBitrate) {
                        sameLanguageBitrate = format.bitrate;
                        sameLanguage = new TrackLocation(group, i);
                    }
                    continue;
                }

                if (isOriginalAudio(format)) {
                    if (idEquals && originalSameId == null) {
                        originalSameId = new TrackLocation(group, i);
                    }
                    if (originalSameCodec == null && codecFamilyEquals(codec, format.codecs)) {
                        originalSameCodec = new TrackLocation(group, i);
                    }
                    if (format.bitrate > originalBestBitrate) {
                        originalBestBitrate = format.bitrate;
                        originalBest = new TrackLocation(group, i);
                    }
                }
                if (idEquals && sameId == null) {
                    sameId = new TrackLocation(group, i);
                }
            }
        }

        TrackLocation[] ranked = {exactSameCodec, exactBest, sameLanguageSameCodec, sameLanguage,
                originalSameId, originalSameCodec, originalBest, sameId};
        for (TrackLocation location : ranked) {
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    /** The untranslated variant: Role=main in our generated MPDs, "(original)" in the label. */
    private static boolean isOriginalAudio(androidx.media3.common.Format format) {
        return (format.roleFlags & C.ROLE_FLAG_MAIN) != 0
                || containsIgnoreCase(Media3FormatConverter.pickLanguage(format), ORIGINAL_AUDIO_MARK);
    }

    private static boolean matchesLoosely(int rendererIndex, FormatItem item, androidx.media3.common.Format format) {
        switch (rendererIndex) {
            case TrackSelectorManager.RENDERER_INDEX_VIDEO:
                // Same class, not same height: a 1080p pick carried from a landscape video to a
                // vertical one means 1080x1920, not the 608x1080 (480p) that shares its height.
                // A pick stored without its width can only be matched by height, as before.
                return (item.getWidth() > 0
                                ? VideoRung.classHeight(item.getWidth(), item.getHeight())
                                        == VideoRung.classHeight(format.width, format.height)
                                : item.getHeight() == format.height)
                        && Math.abs(item.getFrameRate() - format.frameRate) <= 2
                        && codecFamilyEquals(codecOf(item), format.codecs);
            case TrackSelectorManager.RENDERER_INDEX_AUDIO:
                return audioLanguageEquals(item.getLanguage(), Media3FormatConverter.pickLanguage(format))
                        && codecFamilyEquals(codecOf(item), format.codecs);
            case TrackSelectorManager.RENDERER_INDEX_SUBTITLE:
                return languageEquals(item.getLanguage(), Media3FormatConverter.pickLanguage(format));
            default:
                return false;
        }
    }

    @Nullable
    private static String codecOf(FormatItem item) {
        MediaTrack track = item.getTrack();
        return track != null && track.format != null ? track.format.codecs : null;
    }

    private static boolean codecFamilyEquals(@Nullable String codecs1, @Nullable String codecs2) {
        if (codecs1 == null || codecs2 == null) {
            return codecs1 == null && codecs2 == null;
        }
        return Helpers.equals(TrackSelectorUtil.codecNameShort(codecs1), TrackSelectorUtil.codecNameShort(codecs2));
    }

    /** The same audio variant: the identical label ("es (dubbed)"), or both untagged. */
    private static boolean sameVariant(@Nullable String language1, @Nullable String language2) {
        if (language1 == null || language2 == null) {
            return language1 == null && language2 == null;
        }
        return language1.equalsIgnoreCase(language2);
    }

    /**
     * NEWTUBE(issues #52/#53): audio languages are equal when their primary language subtags are
     * ("en" = "en-US" = "en (original)", "es" = "es-419"; never "en" = "ben").
     * The old substring test matched "en" inside "ben (dubbed)" or "de" inside "(dubbed)".
     */
    static boolean audioLanguageEquals(@Nullable String language1, @Nullable String language2) {
        if (language1 == null || language2 == null) {
            return language1 == null && language2 == null;
        }
        String primary1 = primaryLanguage(language1);
        return !primary1.isEmpty() && primary1.equals(primaryLanguage(language2));
    }

    /** "en-US (original)" -> "en": the leading letters, lower-cased. */
    static String primaryLanguage(String language) {
        int end = 0;
        String trimmed = language.trim();
        while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

    /** Subtitles keep the legacy loose comparison (stored names vs codes; not part of #52). */
    private static boolean languageEquals(@Nullable String language1, @Nullable String language2) {
        if (language1 == null || language2 == null) {
            return language1 == null && language2 == null;
        }
        return language1.equalsIgnoreCase(language2)
                || containsIgnoreCase(language1, language2)
                || containsIgnoreCase(language2, language1);
    }

    private static boolean containsIgnoreCase(@Nullable String haystack, @Nullable String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase().contains(needle.toLowerCase());
    }

    // ---------------------------------------------------------------------------------
    // media3 track -> FormatItem
    // ---------------------------------------------------------------------------------

    private static FormatItem toFormatItem(int rendererIndex, Tracks.Group group, int trackIndex, boolean selected) {
        MediaTrack mediaTrack = Media3FormatConverter.toMediaTrack(rendererIndex, group.getTrackFormat(trackIndex));
        if (mediaTrack != null) {
            mediaTrack.isSelected = selected;
        }
        return ExoFormatItem.from(mediaTrack);
    }
}
