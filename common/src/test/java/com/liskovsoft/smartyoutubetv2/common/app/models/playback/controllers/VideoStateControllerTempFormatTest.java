package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * NEWTUBE(quality-pin): the per-session quality override (an explicit rung picked while the Auto
 * default is persisted) must survive a reload of the SAME video (error-fix url remint, live poll
 * - each re-enters onNewVideo) and end only when a genuinely new video opens, exactly like the
 * official app's "720p stays 720p until the next video".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoStateControllerTempFormatTest {
    private Context context;
    private PlayerData playerData;
    private Video currentVideo;
    private VideoStateController controller;

    private final FormatItem pinned720p = ExoFormatItem.fromVideoSpec("1280,720,30,avc", false);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        playerData = PlayerData.instance(context);
        VideoStateService.instance(context).getStates().clear();

        PlaybackView player = (PlaybackView) java.lang.reflect.Proxy.newProxyInstance(
                PlaybackView.class.getClassLoader(), new Class<?>[] {PlaybackView.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        // A reload dispatch is not a saveState trigger; keep the harness minimal.
                        case "containsMedia": return false;
                        default: throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                });
        controller = new VideoStateController() {
            @Override public PlaybackView getPlayer() { return player; }
            @Override public Video getVideo() { return currentVideo; }
            @Override public Context getContext() { return context; }
        };
    }

    @Test
    public void sameVideoReloadKeepsSessionQualityOverride() {
        Video video = video("same-video");
        currentVideo = video;
        playerData.setTempVideoFormat(pinned720p);

        controller.onNewVideo(video); // error-fix url remint / live poll re-entry

        assertSame(pinned720p, playerData.getTempVideoFormat());
    }

    @Test
    public void newVideoClearsSessionQualityOverride() {
        currentVideo = video("previous-video");
        playerData.setTempVideoFormat(pinned720p);

        controller.onNewVideo(video("next-video"));

        assertNull(playerData.getTempVideoFormat());
    }

    @Test
    public void sameVideoReloadKeepsSessionAudioOverride() {
        Video video = video("same-video");
        currentVideo = video;
        FormatItem audioPick = ExoFormatItem.fromVideoSpec("1280,720,30,avc", false); // shape only
        playerData.setTempAudioFormat(audioPick);

        controller.onNewVideo(video);

        assertSame(audioPick, playerData.getTempAudioFormat());
    }

    @Test
    public void newVideoClearsSessionAudioOverride() {
        currentVideo = video("previous-video");
        FormatItem audioPick = ExoFormatItem.fromVideoSpec("1280,720,30,avc", false); // shape only
        playerData.setTempAudioFormat(audioPick);

        controller.onNewVideo(video("next-video"));

        assertNull(playerData.getTempAudioFormat());
    }

    /** Two Videos with the same id are equal (content hash), which is what a reload dispatch sends. */
    private static Video video(String id) {
        Video video = new Video();
        video.videoId = id;
        return video;
    }
}
