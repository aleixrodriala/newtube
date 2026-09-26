package com.newtube.mobile.ui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** NEWTUBE(history-thumbs): local history entries (no image URL) get the CDN thumbnail. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LocalThumbnailsTest {

    @Test
    public void videoWithoutImageGetsTheHqThumbnail() {
        Video video = new Video();
        video.videoId = "aqz-KE-bpKQ";
        LocalThumbnails.fill(video);
        assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg", video.cardImageUrl);
    }

    @Test
    public void existingImageIsKept() {
        Video video = new Video();
        video.videoId = "aqz-KE-bpKQ";
        video.cardImageUrl = "https://i.ytimg.com/vi/aqz-KE-bpKQ/sddefault.jpg?sqp=x";
        LocalThumbnails.fill(video);
        assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/sddefault.jpg?sqp=x", video.cardImageUrl);
    }

    @Test
    public void itemWithoutVideoIdIsLeftAlone() {
        Video playlist = new Video();
        playlist.playlistId = "PL123";
        LocalThumbnails.fill(playlist);
        assertNull(playlist.cardImageUrl);
    }
}
