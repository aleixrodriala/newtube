package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.util.Pair;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.misc.TickleManager.TickleListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StreamReminderService implements TickleListener {
    private static final String TAG = StreamReminderService.class.getSimpleName();
    private static StreamReminderService sInstance;
    private final MediaItemService mMediaItemService;
    private final Context mContext;
    private final GeneralData mGeneralData;
    private Disposable mReminderAction;
    /**
     * NEWTUBE(reminder-backoff): the service is ticked every minute for as long as the process
     * lives, and each tick used to cost one /player walk per reminder no matter when the stream
     * was due. Each reminder is now only checked once its {@link LiveStartPollPolicy#REMINDER}
     * delay has run out: minutes apart while the scheduled start is far away or unknown for long,
     * every tick in the last minutes before a known start and for a while after it.
     */
    private final ReminderPollSchedule mSchedule = new ReminderPollSchedule(LiveStartPollPolicy.REMINDER);

    private StreamReminderService(Context context) {
        ServiceManager service = YouTubeServiceManager.instance();
        mMediaItemService = service.getMediaItemService();
        mContext = context.getApplicationContext();
        mGeneralData = GeneralData.instance(context);
    }

    public static StreamReminderService instance(Context context) {
        if (sInstance == null) {
            sInstance = new StreamReminderService(context);
        }

        return sInstance;
    }

    public boolean isReminderSet(Video video) {
        return mGeneralData.containsPendingStream(video);
    }

    public void toggleReminder(Video video) {
        if (video.videoId == null || !video.isUpcoming) {
            return;
        }

        if (mGeneralData.containsPendingStream(video)) {
            mGeneralData.removePendingStream(video);
        } else {
            mGeneralData.addPendingStream(video);
        }

        startStop();
    }

    public void startStop() {
        if (mGeneralData.getPendingStreams().isEmpty()) {
            TickleManager.instance().removeListener(this);
            sInstance = null;
        } else {
            TickleManager.instance().addListener(this);
        }
    }

    @Override
    public void onTickle() {
        List<Video> pending = mGeneralData.getPendingStreams();
        if (pending.isEmpty()) {
            startStop();
            return;
        }

        // NEWTUBE(reminder-backoff): a slow check is still walking the client ring - let it land
        // instead of disposing it (the answer would be lost and the reminder asked again at once).
        if (RxHelper.isAnyActionRunning(mReminderAction)) {
            NetPath.log("reminder-check skip (previous check in flight)");
            return;
        }

        long checkAtMs = System.currentTimeMillis();
        Set<String> pendingIds = new HashSet<>();
        List<Video> due = new ArrayList<>();
        for (Video item : pending) {
            pendingIds.add(item.videoId);
            if (mSchedule.isDue(item.videoId, checkAtMs)) {
                due.add(item);
            }
        }
        mSchedule.retainOnly(pendingIds);

        if (due.isEmpty()) {
            return; // nothing due this minute: no network at all
        }

        NetPath.log("reminder-check due=" + due.size() + "/" + pending.size());

        List<Observable<Pair<Video, MediaItemFormatInfo>>> observables = toObservables(due);

        mReminderAction = Observable.mergeDelayError(observables)
                .subscribe(
                        metadata -> processMetadata(metadata, checkAtMs),
                        error -> Log.e(TAG, "loadMetadata error: %s", error.getMessage())
                );
    }

    private void processMetadata(Pair<Video, MediaItemFormatInfo> metadata, long checkAtMs) {
        Video origin = metadata.first;
        MediaItemFormatInfo formatInfo = metadata.second;
        if (formatInfo == null) {
            // NEWTUBE(reminder-backoff): this reminder's check failed (no network, ring exhausted).
            // Says nothing about the stream, but must not be re-asked every minute either.
            long delayMs = mSchedule.onNotLive(origin.videoId, checkAtMs, 0);
            NetPath.log("reminder-check failed video=" + origin.videoId + " next-in=" + delayMs);
            return;
        }

        if (formatInfo.containsMedia() && formatInfo.getVideoId() != null) {
            Video video = new Video();
            video.title = formatInfo.getTitle();
            video.videoId = formatInfo.getVideoId();
            video.isPending = true;

            Playlist playlist = Playlist.instance();
            Video current = playlist.getCurrent();

            if (current != null && current.isPending && ViewManager.instance(mContext).isPlayerInForeground()) {
                playlist.add(video);
            } else {
                ViewManager.instance(mContext).movePlayerToForeground();
                PlaybackPresenter.instance(mContext).openVideo(video);
                MessageHelpers.showLongMessage(mContext, R.string.starting_stream);
            }

            mGeneralData.removePendingStream(video);
            mSchedule.remove(origin.videoId);
            NetPath.log("reminder-check live video=" + origin.videoId);
            startStop();
        } else if (formatInfo.isUnplayable()) {
            mGeneralData.removePendingStream(origin);
            mSchedule.remove(origin.videoId);
            startStop();
        } else {
            // NEWTUBE(reminder-backoff): not live yet - back off according to the schedule.
            long delayMs = mSchedule.onNotLive(origin.videoId, checkAtMs,
                    LiveStartPollPolicy.scheduledStartMs(formatInfo));
            NetPath.log("reminder-check not-live video=" + origin.videoId
                    + " startIn=" + LiveStartPollPolicy.startInLabel(checkAtMs, mSchedule.getScheduledStartMs(origin.videoId))
                    + " answers=" + mSchedule.getNotLiveAnswers(origin.videoId) + " next-in=" + delayMs);
        }
    }

    /**
     * NOTE: don't use MediaItemMetadata because it has contains isLive and isUpcoming flags
     */
    private List<Observable<Pair<Video, MediaItemFormatInfo>>> toObservables(List<Video> items) {
        List<Observable<Pair<Video, MediaItemFormatInfo>>> result = new ArrayList<>();

        for (Video item : items) {
            // NEWTUBE(reminder-backoff): a failed check becomes a (video, null) answer so it can be
            // backed off per reminder (mergeDelayError alone only reports one error at the end).
            result.add(mMediaItemService.getFormatInfoObserve(item.videoId)
                    .map(info -> new Pair<>(item, info))
                    .onErrorReturn(error -> {
                        Log.e(TAG, "Reminder check failed for %s: %s", item.videoId, error.getMessage());
                        return new Pair<>(item, null);
                    }));
        }

        return result;
    }
}
