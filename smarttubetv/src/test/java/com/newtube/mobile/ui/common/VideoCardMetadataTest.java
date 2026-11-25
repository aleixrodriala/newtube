package com.newtube.mobile.ui.common;

import android.app.Application;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.BaseMediaItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class VideoCardMetadataTest {
    private static final String CHANNEL = "A very long channel name with a lot of words";
    private static final String DATE = "hace 3 semanas";
    private static final String VIEWS = "1 M de visualizaciones";
    private final Application context = RuntimeEnvironment.getApplication();
    private final AppPrefs prefs = AppPrefs.instance(context);
    private final TextView dateView = new TextView(context);

    @After
    public void resetPreference() {
        prefs.setAlwaysShowUploadDateEnabled(false);
    }

    @Test
    public void defaultsOffAndPreservesOriginalMetadata() {
        Video video = video(DATE, MediaItem.TYPE_VIDEO, false);
        assertSame(video.getSecondTitle(), VideoCardMetadata.bind(video, dateView));
        assertEquals(View.GONE, dateView.getVisibility());
    }

    @Test
    public void enabledSeparatesTheStructuredDateAndClearsRecycledViews() {
        Video video = video(DATE, MediaItem.TYPE_VIDEO, false);
        prefs.setAlwaysShowUploadDateEnabled(true);

        assertEquals(YouTubeHelper.createInfo(CHANNEL, VIEWS).toString(),
                VideoCardMetadata.bind(video, dateView).toString());
        assertEquals(DATE, dateView.getText().toString());
        assertEquals(View.VISIBLE, dateView.getVisibility());

        Video missing = video(null, MediaItem.TYPE_VIDEO, false);
        assertSame(missing.getSecondTitle(), VideoCardMetadata.bind(missing, dateView));
        assertEquals(View.GONE, dateView.getVisibility());
        assertEquals("", dateView.getText().toString());

        VideoCardMetadata.bind(video, dateView);
        prefs.setAlwaysShowUploadDateEnabled(false);
        assertSame(video.getSecondTitle(), VideoCardMetadata.bind(video, dateView));
        assertEquals(View.GONE, dateView.getVisibility());
    }

    @Test
    public void skipsScheduledStreamsPlaylistCardsAndCombinedMusicMetadata() {
        assertNull(video(DATE, MediaItem.TYPE_VIDEO, true).getUploadDate());
        assertNull(video(DATE, MediaItem.TYPE_PLAYLIST, false).getUploadDate());
        assertNull(video(VIEWS + " • " + DATE, MediaItem.TYPE_MUSIC, false).getUploadDate());
    }

    @Test
    public void retainsDateThroughCopyAndSerializationAndReadsLegacyEntries() {
        Video video = video(DATE, MediaItem.TYPE_VIDEO, false);
        assertEquals(DATE, Video.from(video).getUploadDate());
        assertEquals(DATE, video.copy().getUploadDate());
        assertEquals(DATE, Video.from(video.toMediaItem()).getUploadDate());
        assertEquals(DATE, Video.fromString(video.toString()).getUploadDate());
        assertNull(Video.fromString(video(null, MediaItem.TYPE_VIDEO, false).toString()).getUploadDate());
    }

    @Test
    public void anUnmatchedDateDoesNotRemoveUnrelatedMetadata() {
        CharSequence metadata = YouTubeHelper.createInfo(CHANNEL + " " + DATE, VIEWS);
        assertSame(metadata, VideoCardMetadata.withoutDate(metadata, DATE));
    }

    @Test
    public void hiddenDateKeepsTheOriginalCardGeometry() {
        for (int widthDp : new int[]{180, 400}) {
            for (int avatarVisibility : new int[]{View.VISIBLE, View.GONE}) {
                View card = inflateCard(avatarVisibility);
                View oldCard = inflateCard(avatarVisibility);
                View oldDate = oldCard.findViewById(R.id.video_upload_date);
                ((ConstraintLayout) oldDate.getParent()).removeView(oldDate);
                TextView oldMeta = oldCard.findViewById(R.id.video_meta);
                ConstraintLayout.LayoutParams original = (ConstraintLayout.LayoutParams) oldMeta.getLayoutParams();
                original.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
                original.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                original.bottomMargin = original.goneBottomMargin;
                oldMeta.setLayoutParams(original);

                int width = Math.round(widthDp * context.getResources().getDisplayMetrics().density);
                layoutCard(card, width);
                layoutCard(oldCard, width);
                assertEquals(oldCard.getHeight(), card.getHeight());
                for (int id : new int[]{R.id.video_thumbnail_frame, R.id.video_channel_avatar,
                        R.id.video_title, R.id.video_meta, R.id.video_overflow}) {
                    assertEquals(bounds(oldCard.findViewById(id)), bounds(card.findViewById(id)));
                }
            }
        }
    }

    private View inflateCard(int avatarVisibility) {
        View card = LayoutInflater.from(new ContextThemeWrapper(context, R.style.Theme_NewTube))
                .inflate(R.layout.item_video_card, null);
        card.findViewById(R.id.video_channel_avatar).setVisibility(avatarVisibility);
        ((TextView) card.findViewById(R.id.video_title)).setText("A title that can wrap to multiple lines");
        ((TextView) card.findViewById(R.id.video_meta)).setText(YouTubeHelper.createInfo(CHANNEL, DATE, VIEWS));
        return card;
    }

    private static void layoutCard(View card, int width) {
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        card.layout(0, 0, width, card.getMeasuredHeight());
    }

    private static Rect bounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }

    private static Video video(String date, int type, boolean upcoming) {
        return Video.from(new BaseMediaItem() {
            @Override
            public String getVideoId() {
                return "video";
            }

            @Override
            public int getType() {
                return type;
            }

            @Override
            public String getProductionDate() {
                return date;
            }

            @Override
            public boolean isUpcoming() {
                return upcoming;
            }

            @Override
            public CharSequence getSecondTitle() {
                return YouTubeHelper.createInfo(CHANNEL, date, VIEWS);
            }
        });
    }
}
