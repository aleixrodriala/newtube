package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoMetadataSyncTest {
    @Test
    public void ordinaryVideoGetsRefreshedTitle() {
        Video video = new Video();
        ReflectionHelpers.setField(video, "metadataTitle", "Old title");
        video.sync(metadata("New title"));
        assertEquals("New title", video.getTitleFull());
    }

    @Test
    public void upcomingVideoKeepsScheduledTitle() {
        Video video = new Video();
        video.isUpcoming = true;
        ReflectionHelpers.setField(video, "metadataTitle", "Starts tomorrow at 20:00");
        video.sync(metadata("Live stream"));
        assertEquals("Starts tomorrow at 20:00", video.getTitleFull());
    }

    @Test
    public void upcomingVideoWithoutTitleCanBeEnriched() {
        Video video = new Video();
        video.isUpcoming = true;
        video.sync(metadata("Premiere"));
        assertEquals("Premiere", video.getTitleFull());
    }

    @Test
    public void titleRefreshResumesWhenUpcomingVideoStarts() {
        Video video = new Video();
        video.isUpcoming = true;
        ReflectionHelpers.setField(video, "metadataTitle", "Starts tomorrow");
        video.sync(metadata("Broadcast"));
        video.isUpcoming = false;
        video.sync(metadata("Broadcast in progress"));
        assertEquals("Broadcast in progress", video.getTitleFull());
    }

    @Test
    public void absentMetadataIsIgnored() {
        Video video = new Video();
        video.sync((MediaItemMetadata) null);
        assertNull(video.getTitleFull());
    }

    private static MediaItemMetadata metadata(String title) {
        return (MediaItemMetadata) Proxy.newProxyInstance(MediaItemMetadata.class.getClassLoader(),
                new Class<?>[] {MediaItemMetadata.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getTitle")) return title;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    return null;
                });
    }
}
