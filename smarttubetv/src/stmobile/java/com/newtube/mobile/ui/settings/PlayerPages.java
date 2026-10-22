package com.newtube.mobile.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import androidx.core.content.ContextCompat;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem.VideoPreset;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.PhoneBackgroundMode;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.DeArrowData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SponsorBlockData;
import com.liskovsoft.smartyoutubetv2.common.proxy.WebProxyDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.newtube.mobile.player.SabrSourcePreference;
import com.newtube.mobile.ui.playback.PlayerClockPrefs;
import com.newtube.mobile.ui.playback.PlayerGesturePrefs;
import com.newtube.mobile.ui.common.MobileSnackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The video-side pages of the phone Settings: Playback, Video quality, Captions, SponsorBlock,
 * DeArrow and Advanced. Only settings the phone player reads are offered (2026-10 audit); the
 * TV-only knobs (remote keys, seek confirmation, pixel ratio, chipset fixes...) are left out with
 * their stored values untouched.
 */
final class PlayerPages {
    /** SponsorBlock's highlight is a point, not a segment: nothing to skip or paint on the phone. */
    private static final String SEGMENT_HIGHLIGHT = "poi_highlight";

    private PlayerPages() {
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page playback(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);
        GeneralData generalData = GeneralData.instance(context);

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_video_ends))
                .option(context.getString(R.string.mobile_settings_video_ends_next), PlayerConstants.PLAYBACK_MODE_ALL)
                .option(context.getString(R.string.mobile_settings_video_ends_repeat), PlayerConstants.PLAYBACK_MODE_ONE)
                .option(context.getString(R.string.mobile_settings_video_ends_shuffle), PlayerConstants.PLAYBACK_MODE_SHUFFLE)
                .option(context.getString(R.string.mobile_settings_video_ends_list), PlayerConstants.PLAYBACK_MODE_LIST)
                .option(context.getString(R.string.mobile_settings_video_ends_reverse), PlayerConstants.PLAYBACK_MODE_REVERSE_LIST)
                .option(context.getString(R.string.mobile_settings_video_ends_pause), PlayerConstants.PLAYBACK_MODE_PAUSE)
                .option(context.getString(R.string.mobile_settings_video_ends_close), PlayerConstants.PLAYBACK_MODE_CLOSE)
                .bind(playerData::getPlaybackMode, playerData::setPlaybackMode));

        boolean pip = Helpers.isPictureInPictureSupported(context);
        SettingsRow.Choice<Integer> background = SettingsRow.choice(context.getString(R.string.mobile_settings_background));
        if (pip) {
            background.option(context.getString(R.string.option_background_playback_pip),
                    context.getString(R.string.mobile_settings_background_pip_desc), PlayerConstants.BACKGROUND_MODE_PIP);
        }
        background.option(context.getString(R.string.option_background_playback_only_audio),
                context.getString(R.string.mobile_settings_background_audio_desc), PlayerConstants.BACKGROUND_MODE_SOUND);
        rows.add(background.bind(
                () -> !pip || PhoneBackgroundMode.isOnlyAudio(playerData.getBackgroundMode())
                        ? PlayerConstants.BACKGROUND_MODE_SOUND : PlayerConstants.BACKGROUND_MODE_PIP,
                mode -> {
                    playerData.setBackgroundMode(mode);
                    generalData.setBackgroundPlaybackShortcut(GeneralData.BACKGROUND_PLAYBACK_SHORTCUT_HOME);
                }));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_remember_speed))
                .option(context.getString(R.string.mobile_settings_remember_speed_channel), SPEED_PER_CHANNEL)
                .option(context.getString(R.string.mobile_settings_remember_speed_video), SPEED_PER_VIDEO)
                .option(context.getString(R.string.mobile_settings_remember_speed_all), SPEED_ALL)
                .option(context.getString(R.string.mobile_settings_remember_speed_none),
                        context.getString(R.string.mobile_settings_remember_speed_none_desc), SPEED_NONE)
                .bind(() -> playerData.isSpeedPerChannelEnabled() ? SPEED_PER_CHANNEL
                                : playerData.isAllSpeedEnabled() ? SPEED_ALL
                                : playerData.isSpeedPerVideoEnabled() ? SPEED_PER_VIDEO : SPEED_NONE,
                        mode -> {
                            switch (mode) {
                                case SPEED_PER_CHANNEL:
                                    playerData.setSpeedPerChannelEnabled(true);
                                    break;
                                case SPEED_PER_VIDEO:
                                    playerData.setSpeedPerVideoEnabled(true);
                                    break;
                                case SPEED_ALL:
                                    playerData.setAllSpeedEnabled(true);
                                    break;
                                default:
                                    playerData.setAllSpeedEnabled(false); // clears all three
                                    break;
                            }
                        }));

        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_speed_list))
                .option(context.getString(R.string.mobile_settings_speed_list_basic), 0)
                .option(context.getString(R.string.mobile_settings_speed_list_fine), 1)
                .option(context.getString(R.string.mobile_settings_speed_list_finest), 2)
                .bind(() -> tweaks.isExtraLongSpeedListEnabled() ? 2 : tweaks.isLongSpeedListEnabled() ? 1 : 0,
                        steps -> {
                            if (steps == 2) {
                                tweaks.setExtraLongSpeedListEnabled(true);
                            } else if (steps == 1) {
                                tweaks.setLongSpeedListEnabled(true);
                            } else {
                                tweaks.setLongSpeedListEnabled(false);
                                tweaks.setExtraLongSpeedListEnabled(false);
                            }
                        }));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_natural_voice),
                context.getString(R.string.mobile_settings_natural_voice_summary),
                tweaks::isAudioTimeStretchingEnabled, tweaks::setAudioTimeStretchingEnabled));

        rows.add(sleepTimer(context, playerData));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_audio_focus),
                context.getString(R.string.mobile_settings_audio_focus_summary),
                tweaks::isAudioFocusEnabled, tweaks::setAudioFocusEnabled));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_resume)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_resume_short),
                context.getString(R.string.mobile_settings_resume_short_summary),
                tweaks::isRememberPositionOfShortVideosEnabled, tweaks::setRememberPositionOfShortVideosEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_resume_live),
                context.getString(R.string.mobile_settings_resume_live_summary),
                tweaks::isRememberPositionOfLiveVideosEnabled, tweaks::setRememberPositionOfLiveVideosEnabled));

        // NEWTUBE(gestures): the optional player swipes (#12), on by default (PlayerGesturePrefs);
        // the fullscreen and minimize swipes are always on.
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_gestures)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_gesture_levels),
                context.getString(R.string.mobile_settings_gesture_levels_summary),
                () -> PlayerGesturePrefs.isLevelSwipesOn(context),
                on -> PlayerGesturePrefs.setLevelSwipesOn(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_gesture_seek),
                context.getString(R.string.mobile_settings_gesture_seek_summary),
                () -> PlayerGesturePrefs.isSeekSwipeOn(context),
                on -> PlayerGesturePrefs.setSeekSwipeOn(context, on)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_watch_page)));
        // NEWTUBE(clock): the time beside the title in fullscreen, where the status bar is hidden.
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_clock),
                context.getString(R.string.mobile_settings_clock_summary),
                () -> PlayerClockPrefs.isClockOn(context),
                on -> PlayerClockPrefs.setClockOn(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_hide_related),
                context.getString(R.string.mobile_settings_hide_related_summary),
                tweaks::isSuggestionsDisabled, tweaks::setSuggestionsDisabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dislikes),
                context.getString(R.string.mobile_settings_dislikes_summary),
                tweaks::isReturnYouTubeDislikeEnabled, tweaks::setReturnYouTubeDislikeEnabled));
        SponsorBlockData sponsorBlock = SponsorBlockData.instance(context);
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_paid_promotion),
                context.getString(R.string.mobile_settings_paid_promotion_summary),
                sponsorBlock::isPaidContentNotificationEnabled, sponsorBlock::setPaidContentNotificationEnabled));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_playback), rows);
    }

    private static final int SPEED_PER_CHANNEL = 0;
    private static final int SPEED_PER_VIDEO = 1;
    private static final int SPEED_ALL = 2;
    private static final int SPEED_NONE = 3;

    /**
     * Pauses playback this long after the player opened (the TV's "no key pressed" half has no
     * phone equivalent). Half-hour steps up to two hours, then whole ones; a stored value from the
     * old 0.5-10 h list stays listed.
     */
    private static SettingsRow sleepTimer(Context context, PlayerData playerData) {
        List<Float> hours = new ArrayList<>();
        for (float value : new float[] {0f, 0.5f, 1f, 1.5f, 2f, 3f, 4f, 6f, 8f}) {
            hours.add(value);
        }
        float stored = playerData.getSleepTimerHours();
        if (!containsFloat(hours, stored)) {
            hours.add(stored);
            Collections.sort(hours);
        }
        SettingsRow.Choice<Float> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_sleep_timer));
        for (float value : hours) {
            choice.option(sleepLabel(context, value), value);
        }
        return choice.bind(() -> nearest(hours, playerData.getSleepTimerHours()), playerData::setSleepTimerHours)
                .summary(() -> {
                    float value = playerData.getSleepTimerHours();
                    return value <= 0 ? context.getString(R.string.mobile_settings_off)
                            : context.getString(R.string.mobile_settings_sleep_timer_summary, sleepLabel(context, value));
                });
    }

    private static String sleepLabel(Context context, float hours) {
        if (hours <= 0) {
            return context.getString(R.string.mobile_settings_off);
        }
        int minutes = Math.round(hours * 60);
        if (minutes < 60) {
            return context.getResources().getQuantityString(R.plurals.mobile_settings_minutes, minutes, minutes);
        }
        if (minutes % 60 == 0) {
            int whole = minutes / 60;
            return context.getResources().getQuantityString(R.plurals.mobile_settings_hours, whole, whole);
        }
        return context.getString(R.string.mobile_settings_hours_minutes, minutes / 60, minutes % 60);
    }

    private static boolean containsFloat(List<Float> values, float value) {
        for (Float candidate : values) {
            if (Math.abs(candidate - value) < 0.001f) {
                return true;
            }
        }
        return false;
    }

    private static Float nearest(List<Float> values, float value) {
        for (Float candidate : values) {
            if (Math.abs(candidate - value) < 0.001f) {
                return candidate;
            }
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------------
    // Video quality (+ audio)
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page quality(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_quality_intro)));
        rows.add(defaultQuality(context));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_audio)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_loudness),
                context.getString(R.string.mobile_settings_loudness_summary),
                tweaks::isPlayerAutoVolumeEnabled, tweaks::setPlayerAutoVolumeEnabled));
        rows.add(playerVolume(context, playerData));

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_quality_data_saver_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_quality), rows);
    }

    /** "Auto (up to 1080p)" or the cap the user picked, for the Video quality row on the top level. */
    static CharSequence qualitySummary(Context context) {
        SettingsRow row = defaultQuality(context);
        CharSequence text = row.summaryText();
        return text != null ? text : "";
    }

    private static final String QUALITY_AUTO = "auto";
    private static final String QUALITY_OTHER = "other";

    /**
     * YouTube-style quality choice: Auto plus one cap per resolution. Each cap is a preset (a
     * ceiling the automatic selection stays under, 60 fps where the phone decodes it); the exact
     * codec presets stay one level down, under Advanced > Video format. A preset picked there shows
     * here under its own name.
     */
    private static SettingsRow defaultQuality(Context context) {
        PlayerData playerData = PlayerData.instance(context);
        FormatItem current = playerData.getFormat(FormatItem.TYPE_VIDEO);
        FormatItem auto = playerData.getDefaultVideoFormat();
        int autoHeight = auto != null && auto.getHeight() > 0 ? auto.getHeight() : 1080;

        List<VideoPreset> caps = new ArrayList<>();
        for (int height : new int[] {2160, 1440, 1080, 720, 480, 360}) {
            if (height == autoHeight) {
                continue; // Auto is that cap already
            }
            VideoPreset preset = bestPreset(height);
            if (preset != null) {
                caps.add(preset);
            }
        }

        SettingsRow.Choice<String> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_default_quality));
        choice.option(context.getString(R.string.mobile_settings_quality_auto, autoHeight),
                context.getString(R.string.mobile_settings_quality_auto_desc), QUALITY_AUTO);
        String currentKey = current == null || !current.isPreset() ? QUALITY_AUTO : null;
        for (VideoPreset preset : caps) {
            int height = preset.getHeight();
            String description = height > autoHeight ? context.getString(R.string.mobile_settings_quality_more_data)
                    : height <= 480 ? context.getString(R.string.mobile_settings_quality_data_saver)
                    : context.getString(R.string.mobile_settings_quality_less_data);
            choice.option(context.getString(R.string.mobile_settings_quality_up_to, resolutionName(height)), description, preset.name);
            if (currentKey == null && preset.format.equals(current)) {
                currentKey = preset.name;
            }
        }
        if (currentKey == null) {
            // A preset chosen under Advanced (another codec or frame rate): show it by name.
            choice.option(presetLabel(presetName(current)), QUALITY_OTHER);
            currentKey = QUALITY_OTHER;
        }
        String selected = currentKey;
        return choice.bind(() -> selected, key -> {
            if (QUALITY_AUTO.equals(key)) {
                setFormat(playerData, playerData.getDefaultVideoFormat());
            } else if (!QUALITY_OTHER.equals(key)) {
                for (VideoPreset preset : caps) {
                    if (preset.name.equals(key)) {
                        setFormat(playerData, preset.format);
                    }
                }
            }
        });
    }

    /** Same as the TV presets list: a pick also clears "Force legacy codecs". */
    private static void setFormat(PlayerData playerData, FormatItem format) {
        if (playerData.isLegacyCodecsForced()) {
            playerData.setLegacyCodecsForced(false);
        }
        playerData.setFormat(format);
    }

    /** The phone's best preset at a height: 60 fps first, then VP9 > AV1 > AVC, SDR only. */
    private static VideoPreset bestPreset(int height) {
        VideoPreset best = null;
        int bestScore = -1;
        for (VideoPreset preset : AppDataSourceManager.instance().getVideoPresets()) {
            if (preset.getHeight() != height || preset.name.contains("hdr") || !Utils.isPresetSupported(preset)) {
                continue;
            }
            int score = (preset.name.contains("60fps") ? 10 : 0)
                    + (preset.isVP9Preset() ? 3 : preset.isAV1Preset() ? 2 : 1);
            if (score > bestScore) {
                bestScore = score;
                best = preset;
            }
        }
        return best;
    }

    private static String resolutionName(int height) {
        return height == 2160 ? "4K (2160p)" : height == 1440 ? "1440p" : height + "p";
    }

    private static String presetName(FormatItem format) {
        if (format != null) {
            for (VideoPreset preset : AppDataSourceManager.instance().getVideoPresets()) {
                if (preset.format.equals(format)) {
                    return preset.name;
                }
            }
        }
        return format != null ? format.getHeight() + "p" : "";
    }

    /** "(4K) 2160p    60fps    vp9+hdr" -> "2160p60 · VP9 HDR". */
    static String presetLabel(String name) {
        String resolution = "";
        String fps = "";
        String codec = "";
        for (String token : name.trim().split("\\s+")) {
            if (token.endsWith("p") && Character.isDigit(token.charAt(0))) {
                resolution = token;
            } else if (token.endsWith("fps")) {
                fps = token.substring(0, token.length() - 3);
            } else if (!token.startsWith("(")) {
                codec = token;
            }
        }
        String codecLabel = codec.replace("av01", "AV1").replace("vp9", "VP9").replace("avc", "H.264").replace("+hdr", " HDR");
        return String.format(Locale.US, "%s%s · %s", resolution, fps, codecLabel);
    }

    /** Fraction of full volume the player plays at, on top of the phone's own volume. */
    private static SettingsRow playerVolume(Context context, PlayerData playerData) {
        List<Float> values = new ArrayList<>();
        for (int percent = 100; percent >= 10; percent -= 10) {
            values.add(percent / 100f);
        }
        float stored = Math.min(1f, playerData.getPlayerVolume());
        if (!containsFloat(values, stored)) {
            values.add(stored);
            Collections.sort(values, Collections.reverseOrder());
        }
        SettingsRow.Choice<Float> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_player_volume));
        for (float value : values) {
            String label = String.format(Locale.US, "%d%%", Math.round(value * 100));
            choice.option(Math.abs(value - 1f) < 0.001f ? context.getString(R.string.mobile_settings_default_value, label) : label, value);
        }
        return choice.bind(() -> nearest(values, Math.min(1f, playerData.getPlayerVolume())), playerData::setPlayerVolume);
    }

    // ---------------------------------------------------------------------------------------------
    // Captions
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page captions(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);

        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitleStylesCategory(context),
                context.getString(R.string.mobile_settings_caption_style)));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitleSizeCategory(context),
                context.getString(R.string.mobile_settings_caption_size)));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createSubtitlePositionCategory(context),
                context.getString(R.string.mobile_settings_caption_position)));

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_caption_per_channel),
                context.getString(R.string.mobile_settings_caption_per_channel_summary),
                playerData::isSubtitlesPerChannelEnabled, playerData::setSubtitlesPerChannelEnabled));

        rows.add(SettingsRow.action(context.getString(R.string.mobile_settings_caption_system),
                context.getString(R.string.mobile_settings_caption_system_summary),
                page -> {
                    try {
                        page.startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
                    } catch (RuntimeException e) {
                        MobileSnackbar.show(context, R.string.mobile_settings_unavailable);
                    }
                }));

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_captions_note)));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_captions), rows);
    }

    // ---------------------------------------------------------------------------------------------
    // SponsorBlock
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page sponsorBlock(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        SponsorBlockData data = SponsorBlockData.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.content_block_provider),
                context.getString(R.string.mobile_settings_sponsorblock_summary),
                data::isSponsorBlockEnabled, data::setSponsorBlockEnabled));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_sponsorblock_segments)));
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            rows.add(SettingsRow.<Integer>choice(segmentTitle(context, data, category))
                    .option(context.getString(R.string.mobile_settings_segment_skip), SponsorBlockData.ACTION_SKIP_ONLY)
                    .option(context.getString(R.string.mobile_settings_segment_skip_notify), SponsorBlockData.ACTION_SKIP_WITH_TOAST)
                    .option(context.getString(R.string.mobile_settings_segment_ask), SponsorBlockData.ACTION_SHOW_DIALOG)
                    .option(context.getString(R.string.mobile_settings_segment_nothing), SponsorBlockData.ACTION_DO_NOTHING)
                    .bind(() -> data.getAction(category), action -> data.setAction(category, action))
                    .enabledWhen(data::isSponsorBlockEnabled));
        }

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_more)));
        rows.add(SettingsRow.page(context.getString(R.string.mobile_settings_segment_marks),
                () -> markSummary(context, data), SettingsPages.SPONSORBLOCK_MARKS).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.fromRadio(AppDialogUtil.createIgnoreShortSegmentsCategory(context),
                context.getString(R.string.mobile_settings_segment_min)).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_segment_once),
                context.getString(R.string.mobile_settings_segment_once_summary),
                data::isDontSkipSegmentAgainEnabled, data::setDontSkipSegmentAgainEnabled).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_segment_alt_server),
                context.getString(R.string.mobile_settings_segment_alt_server_summary),
                data::isAltServerEnabled, data::enableAltServer).enabledWhen(data::isSponsorBlockEnabled));
        rows.add(link(context, R.string.content_block_status, R.string.content_block_status_url));
        rows.add(link(context, R.string.about_sponsorblock, R.string.content_block_provider_url));

        return new SettingsPages.Page(context.getString(R.string.content_block_provider), rows);
    }

    static SettingsPages.Page sponsorBlockMarks(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        SponsorBlockData data = SponsorBlockData.instance(context);
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_segment_marks_note)));
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            rows.add(SettingsRow.toggle(segmentTitle(context, data, category), null,
                    () -> data.isColorMarkerEnabled(category),
                    on -> {
                        if (on) {
                            data.enableColorMarker(category);
                        } else {
                            data.disableColorMarker(category);
                        }
                    }));
        }
        return new SettingsPages.Page(context.getString(R.string.mobile_settings_segment_marks), rows);
    }

    static CharSequence sponsorBlockSummary(Context context) {
        return context.getString(SponsorBlockData.instance(context).isSponsorBlockEnabled()
                ? R.string.mobile_settings_sponsorblock_on : R.string.mobile_settings_off);
    }

    private static CharSequence markSummary(Context context, SponsorBlockData data) {
        int total = 0;
        int marked = 0;
        for (String category : data.getAllCategories()) {
            if (SEGMENT_HIGHLIGHT.equals(category)) {
                continue;
            }
            total++;
            if (data.isColorMarkerEnabled(category)) {
                marked++;
            }
        }
        return context.getString(R.string.mobile_settings_segment_marks_summary, marked, total);
    }

    /** "● Sponsor", the dot in the segment's seek-bar colour. */
    private static CharSequence segmentTitle(Context context, SponsorBlockData data, String category) {
        SpannableStringBuilder title = new SpannableStringBuilder("●  ");
        title.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, data.getColorRes(category))),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.append(context.getString(data.getLocalizedRes(category)));
        return title;
    }

    private static SettingsRow link(Context context, int titleRes, int urlRes) {
        String url = context.getString(urlRes);
        return SettingsRow.action(context.getString(titleRes), url.replace("https://", ""),
                page -> Utils.openLinkExt(page.requireContext(), url));
    }

    // ---------------------------------------------------------------------------------------------
    // DeArrow
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page deArrow(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        DeArrowData data = DeArrowData.instance(context);

        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dearrow_titles),
                context.getString(R.string.mobile_settings_dearrow_titles_summary),
                // NEWTUBE(#45): no longer turns Original titles off - both can be on; DeArrow's
                // title wins and the original title covers videos DeArrow has none for.
                data::isReplaceTitlesEnabled, data::setReplaceTitlesEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_dearrow_thumbnails),
                context.getString(R.string.mobile_settings_dearrow_thumbnails_summary),
                data::isReplaceThumbnailsEnabled, data::setReplaceThumbnailsEnabled));
        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_dearrow_note)));
        rows.add(link(context, R.string.content_block_status, R.string.content_block_status_url));
        rows.add(link(context, R.string.about_dearrow, R.string.dearrow_provider_url));

        return new SettingsPages.Page(context.getString(R.string.dearrow_provider), rows);
    }

    static CharSequence deArrowSummary(Context context) {
        DeArrowData data = DeArrowData.instance(context);
        boolean titles = data.isReplaceTitlesEnabled();
        boolean thumbnails = data.isReplaceThumbnailsEnabled();
        return context.getString(titles && thumbnails ? R.string.mobile_settings_dearrow_both
                : titles ? R.string.mobile_settings_dearrow_titles_only
                : thumbnails ? R.string.mobile_settings_dearrow_thumbnails_only
                : R.string.mobile_settings_off);
    }

    // ---------------------------------------------------------------------------------------------
    // Advanced
    // ---------------------------------------------------------------------------------------------

    static SettingsPages.Page advanced(Context context) {
        List<SettingsRow> rows = new ArrayList<>();
        PlayerData playerData = PlayerData.instance(context);
        PlayerTweaksData tweaks = PlayerTweaksData.instance(context);
        GeneralData generalData = GeneralData.instance(context);
        NetworkData networkData = NetworkData.instance(context);
        MediaServiceData serviceData = MediaServiceData.instance();

        rows.add(SettingsRow.note(context.getString(R.string.mobile_settings_advanced_note)));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_streaming)));
        rows.add(videoFormat(context, playerData, tweaks));
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_buffer))
                .option(context.getString(R.string.video_buffer_size_low), context.getString(R.string.mobile_settings_buffer_low_desc), PlayerData.BUFFER_LOW)
                .option(context.getString(R.string.video_buffer_size_med), context.getString(R.string.mobile_settings_buffer_med_desc), PlayerData.BUFFER_MEDIUM)
                .option(context.getString(R.string.video_buffer_size_high), context.getString(R.string.mobile_settings_buffer_high_desc), PlayerData.BUFFER_HIGH)
                .option(context.getString(R.string.video_buffer_size_highest), context.getString(R.string.mobile_settings_buffer_highest_desc), PlayerData.BUFFER_HIGHEST)
                .bind(playerData::getVideoBufferType, type -> playerData.setVideoBufferTypeByUser(context, type)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_stall_quality),
                context.getString(R.string.mobile_settings_stall_quality_summary),
                () -> !tweaks.isNetworkErrorFixingDisabled(), on -> tweaks.setNetworkErrorFixingDisabled(!on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_high_bitrate),
                context.getString(R.string.mobile_settings_high_bitrate_summary),
                tweaks::isHighBitrateFormatsEnabled, tweaks::setHighBitrateFormatsEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_sabr),
                context.getString(R.string.mobile_settings_sabr_summary),
                () -> SabrSourcePreference.isPreferred(context), on -> SabrSourcePreference.setPreferred(context, on)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_legacy_codecs),
                context.getString(R.string.mobile_settings_legacy_codecs_summary),
                playerData::isLegacyCodecsForced, playerData::setLegacyCodecsForced));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_live)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_live_edge),
                context.getString(R.string.mobile_settings_live_edge_summary),
                tweaks::isBufferOnStreamsDisabled, tweaks::setBufferOnStreamsDisabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_live_hls),
                context.getString(R.string.mobile_settings_live_hls_summary),
                tweaks::isHlsStreamsForced, tweaks::setHlsStreamsForced));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_player)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_controls_on_next),
                context.getString(R.string.mobile_settings_controls_on_next_summary),
                tweaks::isPlayerUiOnNextEnabled, tweaks::setPlayerUiOnNextEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_list_queue),
                context.getString(R.string.mobile_settings_list_queue_summary),
                tweaks::isSectionPlaylistEnabled, tweaks::setSectionPlaylistEnabled));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_queue_mode),
                context.getString(R.string.mobile_settings_queue_mode_summary),
                tweaks::isQueueRespectsPlaybackMode, tweaks::setQueueRespectsPlaybackMode));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_chapter_popup),
                context.getString(R.string.mobile_settings_chapter_popup_summary),
                tweaks::isChapterNotificationEnabled, tweaks::setChapterNotificationEnabled));

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_network)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_proxy),
                context.getString(R.string.mobile_settings_proxy_summary),
                generalData::isProxyEnabled, on -> {
                    // Proxy with authentication is supported only by OkHttp (as in General settings).
                    tweaks.setPlayerDataSource(on ? PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP : PlayerTweaksData.PLAYER_DATA_SOURCE_CRONET);
                    generalData.setProxyEnabled(on);
                    new WebProxyDialog(context).enable(on);
                    OkHttpManager.unhold();
                }));
        rows.add(SettingsRow.<Integer>choice(context.getString(R.string.mobile_settings_dns))
                .option(context.getString(R.string.mobile_settings_dns_ipv4), context.getString(R.string.mobile_settings_dns_ipv4_desc), PlayerTweaksData.DNS_TYPE_IPV4)
                .option(context.getString(R.string.mobile_settings_dns_system), PlayerTweaksData.DNS_TYPE_SYSTEM)
                .option(context.getString(R.string.mobile_settings_dns_google), PlayerTweaksData.DNS_TYPE_GOOGLE)
                .bind(tweaks::getPreferredDnsType, tweaks::setPreferredDnsType).needsRestart());
        // Upstream's "Internet censorship" switch (ByeByeDPI and the like): not a TV leftover.
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_conscrypt),
                context.getString(R.string.mobile_settings_conscrypt_summary),
                networkData::isConscryptEnabled, networkData::setConscryptEnabled).needsRestart());

        // From the TV's Account settings dialog, the one of its three rows the phone has a use for.
        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_accounts)));
        AppPrefs prefs = AppPrefs.instance(context);
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_multi_profiles),
                context.getString(R.string.mobile_settings_multi_profiles_summary),
                prefs::isMultiProfilesEnabled, on -> {
                    prefs.enableMultiProfiles(on);
                    BrowsePresenter.instance(context).updateSections();
                }).needsRestart());

        rows.add(SettingsRow.header(context.getString(R.string.mobile_settings_feeds)));
        rows.add(SettingsRow.toggle(context.getString(R.string.mobile_settings_legacy_feeds),
                context.getString(R.string.mobile_settings_legacy_feeds_summary),
                serviceData::isLegacyUIEnabled, serviceData::setLegacyUIEnabled));

        return new SettingsPages.Page(context.getString(R.string.mobile_settings_advanced), rows);
    }

    /** Every preset, labelled "1080p60 · VP9", as the exact format the automatic quality stays under. */
    private static SettingsRow videoFormat(Context context, PlayerData playerData, PlayerTweaksData tweaks) {
        FormatItem current = playerData.getFormat(FormatItem.TYPE_VIDEO);
        boolean presetSelected = current != null && current.isPreset();
        SettingsRow.Choice<Object> choice = SettingsRow.choice(context.getString(R.string.mobile_settings_video_format));
        Object auto = new Object();
        choice.option(context.getString(R.string.mobile_settings_quality_auto,
                playerData.getDefaultVideoFormat().getHeight()), auto);
        Object selected = auto;
        VideoPreset[] presets = AppDataSourceManager.instance().getVideoPresets();
        for (int i = presets.length - 1; i >= 0; i--) {
            VideoPreset preset = presets[i];
            if (!tweaks.isAllFormatsUnlocked() && !Utils.isPresetSupported(preset)) {
                continue;
            }
            choice.option(presetLabel(preset.name), preset);
            if (presetSelected && preset.format.equals(current)) {
                selected = preset;
            }
        }
        Object currentValue = selected;
        return choice.bind(() -> currentValue, value -> setFormat(playerData,
                value instanceof VideoPreset ? ((VideoPreset) value).format : playerData.getDefaultVideoFormat()));
    }
}
