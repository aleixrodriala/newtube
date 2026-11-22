package com.newtube.mobile.car;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.bumptech.glide.Glide;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A list of videos on the car screen: one of the NewTube sections (Home, Subscriptions, ...),
 * or the contents of a playlist or channel picked from one.
 *
 * <p>Tapping a video starts it in the phone's player (the same player the car screen shows) and
 * goes back to the player screen. Tapping a playlist or channel opens its videos in another list.
 * The number of rows follows the limit Android Auto sets (fewer while driving).</p>
 */
public class CarVideoListScreen extends Screen {
    private static final int FALLBACK_ROW_LIMIT = 6;
    /** Even parked, a car list is for picking, not browsing: enough rows, and Home stops paging. */
    private static final int MAX_ROWS = 30;
    private static final int THUMB_WIDTH = 320;
    private static final int THUMB_HEIGHT = 180;

    /** Where a list comes from. Each emission is one or more groups of items. */
    interface Loader {
        Observable<List<MediaGroup>> load(ContentService service);

        /** Lists of the user's own account: they need the phone app to be signed in. */
        default boolean needsAccount() {
            return false;
        }
    }

    /** The NewTube sections offered in {@link CarMenuScreen}. */
    enum Source implements Loader {
        HOME {
            @Override
            public Observable<List<MediaGroup>> load(ContentService service) {
                return service.getHomeObserve();
            }
        },
        SUBSCRIPTIONS {
            @Override
            public Observable<List<MediaGroup>> load(ContentService service) {
                return service.getSubscriptionsObserve().map(Collections::singletonList);
            }

            @Override
            public boolean needsAccount() {
                return true;
            }
        },
        HISTORY {
            @Override
            public Observable<List<MediaGroup>> load(ContentService service) {
                return service.getHistoryObserve().map(Collections::singletonList);
            }

            @Override
            public boolean needsAccount() {
                return true;
            }
        },
        PLAYLISTS {
            @Override
            public Observable<List<MediaGroup>> load(ContentService service) {
                return service.getPlaylistsObserve().map(Collections::singletonList);
            }

            @Override
            public boolean needsAccount() {
                return true;
            }
        },
        TRENDING {
            @Override
            public Observable<List<MediaGroup>> load(ContentService service) {
                return service.getTrendingObserve();
            }
        }
    }

    private final String mTitle;
    private final int mRowLimit;
    private final CompositeDisposable mDisposables = new CompositeDisposable();
    private final List<Video> mVideos = new ArrayList<>();
    private final Set<String> mSeen = new HashSet<>();
    private final Map<String, Bitmap> mThumbnails = new HashMap<>();
    /** Thumbnails already asked for, so each is fetched once. */
    private final Set<String> mThumbnailsRequested = new HashSet<>();
    @Nullable
    private Disposable mLoad;
    private boolean mLoaded;
    private boolean mFailed;
    private boolean mSignedOut;

    CarVideoListScreen(@NonNull CarContext carContext, @NonNull String title,
            @NonNull Loader loader) {
        super(carContext);
        mTitle = title;
        mRowLimit = Math.min(rowLimit(carContext), MAX_ROWS);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                mDisposables.dispose();
            }
        });
        load(loader);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ListTemplate.Builder template = new ListTemplate.Builder()
                .setTitle(mTitle)
                .setHeaderAction(Action.BACK);

        if (!mLoaded && mVideos.isEmpty()) {
            return template.setLoading(true).build();
        }

        CarContext context = getCarContext();
        ItemList.Builder list = new ItemList.Builder();
        if (mVideos.isEmpty()) {
            list.setNoItemsMessage(context.getString(mSignedOut ? R.string.car_list_sign_in
                    : mFailed ? R.string.car_list_error : R.string.car_list_empty));
        }
        for (Video video : mVideos) {
            list.addItem(row(video));
        }
        return template.setSingleList(list.build()).build();
    }

    private Row row(Video video) {
        Row.Builder row = new Row.Builder()
                .setTitle(TextUtils.isEmpty(video.title) ? "-" : video.title)
                .setOnClickListener(() -> open(video));

        String author = video.getAuthor();
        if (!TextUtils.isEmpty(author)) {
            row.addText(author);
        }

        Bitmap thumbnail = video.cardImageUrl != null ? mThumbnails.get(video.cardImageUrl) : null;
        if (thumbnail != null) {
            row.setImage(new CarIcon.Builder(IconCompat.createWithBitmap(thumbnail)).build(),
                    Row.IMAGE_TYPE_LARGE);
        }

        if (isFolder(video)) {
            row.setBrowsable(true);
        }
        return row.build();
    }

    private void open(Video video) {
        CarContext context = getCarContext();
        if (!isFolder(video)) {
            // The car screen follows the phone's player, so starting the video there is enough.
            PlaybackPresenter.instance(context.getApplicationContext()).openVideo(video);
            getScreenManager().popToRoot();
            return;
        }

        MediaItem item = video.mediaItem;
        String title = TextUtils.isEmpty(video.title) ? mTitle : video.title;
        Loader loader = video.isChannel()
                ? service -> service.getChannelObserve(item)
                : service -> service.getGroupObserve(item).map(Collections::singletonList);
        getScreenManager().push(new CarVideoListScreen(context, title, loader));
    }

    /** A playlist or channel: opens a list instead of playing. */
    private static boolean isFolder(Video video) {
        return video.videoId == null && video.mediaItem != null;
    }

    private void load(Loader loader) {
        if (loader.needsAccount() && !isSignedIn()) {
            // Without an account YouTube answers these with an error, and "check the internet"
            // would send people the wrong way.
            mSignedOut = true;
            onLoadDone();
            return;
        }

        Observable<List<MediaGroup>> source;
        try {
            source = loader.load(YouTubeServiceManager.instance().getContentService());
        } catch (RuntimeException e) {
            onLoadFailed();
            return;
        }

        mLoad = source
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::onGroups, error -> onLoadFailed(), this::onLoadDone);
        mDisposables.add(mLoad);
    }

    private void onGroups(@Nullable List<MediaGroup> groups) {
        if (groups == null || mVideos.size() >= mRowLimit) {
            return;
        }
        boolean wasEmpty = mVideos.isEmpty();
        for (MediaGroup group : groups) {
            if (group == null || group.getMediaItems() == null) {
                continue;
            }
            for (MediaItem item : group.getMediaItems()) {
                if (mVideos.size() >= mRowLimit) {
                    break;
                }
                if (item == null) {
                    continue;
                }
                Video video = Video.from(item);
                if (video.videoId == null && video.mediaItem == null) {
                    continue;
                }
                if (TextUtils.isEmpty(video.title) || video.isEmpty()) {
                    // Section headers, and cards that open nothing here: paid movies and the
                    // "Watch ... with Premium" promotions YouTube mixes into Home.
                    continue;
                }
                String key = video.videoId != null ? video.videoId
                        : String.valueOf(video.playlistId) + '/' + video.channelId + '/' + video.title;
                if (mSeen.add(key)) {
                    mVideos.add(video);
                }
            }
        }
        if (mVideos.size() >= mRowLimit) {
            // Enough to fill the screen: stop paging further rows (Home keeps emitting).
            if (mLoad != null) {
                mLoad.dispose();
            }
            onLoadDone();
        } else if (wasEmpty && !mVideos.isEmpty()) {
            // First rows on screen: show them now and start their thumbnails. Home keeps paging
            // for a while, so waiting for the end would leave the list without pictures.
            invalidate();
            loadThumbnails();
        }
    }

    private void onLoadFailed() {
        if (mVideos.isEmpty()) {
            mFailed = true;
        }
        onLoadDone();
    }

    private void onLoadDone() {
        if (mLoaded) {
            return;
        }
        mLoaded = true;
        invalidate();
        loadThumbnails();
    }

    /** Fetches the thumbnails of the rows not asked for yet in one go, then redraws the list once. */
    private void loadThumbnails() {
        List<String> urls = new ArrayList<>();
        for (Video video : mVideos) {
            if (!TextUtils.isEmpty(video.cardImageUrl) && mThumbnailsRequested.add(video.cardImageUrl)) {
                urls.add(video.cardImageUrl);
            }
        }
        if (urls.isEmpty()) {
            return;
        }
        Context appContext = getCarContext().getApplicationContext();
        Disposable disposable = Observable.fromCallable(() -> {
                    Map<String, Bitmap> result = new HashMap<>();
                    for (String url : urls) {
                        try {
                            result.put(url, Glide.with(appContext).asBitmap().load(url)
                                    .centerCrop()
                                    .submit(THUMB_WIDTH, THUMB_HEIGHT)
                                    .get(10, TimeUnit.SECONDS));
                        } catch (Exception e) {
                            // A missing thumbnail only leaves that row without a picture.
                        }
                    }
                    return result;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    mThumbnails.putAll(result);
                    invalidate();
                }, error -> { });
        mDisposables.add(disposable);
    }

    private static boolean isSignedIn() {
        try {
            return YouTubeServiceManager.instance().getSignInService().isSigned();
        } catch (RuntimeException e) {
            return true; // unknown: try the request and report what happens
        }
    }

    private static int rowLimit(CarContext context) {
        try {
            if (context.getCarAppApiLevel() >= 2) {
                return context.getCarService(ConstraintManager.class)
                        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
            }
        } catch (RuntimeException e) {
            // Older hosts: use the common in-motion limit.
        }
        return FALLBACK_ROW_LIMIT;
    }
}
