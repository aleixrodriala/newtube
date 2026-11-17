package com.newtube.mobile.auto;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
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
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** NEWTUBE(android-auto): a list of videos (a feed or search results). A tap plays from there on. */
public class AutoFeedScreen extends Screen {
    public enum Kind { SUBSCRIPTIONS, HOME, HISTORY, CONTINUE, PLAYLISTS, PLAYLIST, SEARCH }

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final int MAX_ITEMS = 40;
    private static final int THUMB_W = 256;
    private static final int THUMB_H = 144;

    private final AutoSession mSession;
    private final Kind mKind;
    @Nullable private final String mQuery;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Nullable private final MediaItem mPlaylist;
    private final List<Video> mVideos = new ArrayList<>();
    /** PLAYLISTS only: the user's playlists (Watch later included), each one opens as a feed. */
    private final List<MediaItem> mPlaylists = new ArrayList<>();
    private final Map<String, CarIcon> mThumbs = new HashMap<>();
    private boolean mLoading = true;
    @Nullable private String mError;
    private volatile boolean mAlive = true;

    public AutoFeedScreen(@NonNull CarContext carContext, AutoSession session, Kind kind, @Nullable String query) {
        this(carContext, session, kind, query, null);
    }

    /** One playlist's videos; {@code title} is its name. */
    public AutoFeedScreen(@NonNull CarContext carContext, AutoSession session, MediaItem playlist, String title) {
        this(carContext, session, Kind.PLAYLIST, title, playlist);
    }

    private AutoFeedScreen(@NonNull CarContext carContext, AutoSession session, Kind kind,
            @Nullable String query, @Nullable MediaItem playlist) {
        super(carContext);
        mSession = session;
        mKind = kind;
        mQuery = query;
        mPlaylist = playlist;
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                mAlive = false;
            }
        });
        load();
    }

    private String title() {
        switch (mKind) {
            case SUBSCRIPTIONS: return "Subscrições";
            case HOME: return "Início";
            case HISTORY: return "Histórico";
            case CONTINUE: return "Continuar a ver";
            case PLAYLISTS: return "Playlists";
            case PLAYLIST: return mQuery != null ? mQuery : "Playlist";
            default: return mQuery != null ? mQuery : "Pesquisa";
        }
    }

    private void load() {
        IO.execute(() -> {
            List<Video> videos = new ArrayList<>();
            List<MediaItem> playlists = new ArrayList<>();
            String error = null;
            try {
                ContentService content = YouTubeServiceManager.instance().getContentService();
                List<MediaGroup> groups = new ArrayList<>();
                switch (mKind) {
                    case SUBSCRIPTIONS: add(groups, content.getSubscriptions()); break;
                    case HISTORY:
                    case CONTINUE: add(groups, content.getHistory()); break;
                    case PLAYLIST: add(groups, content.getGroup(mPlaylist)); break;
                    case PLAYLISTS: {
                        MediaGroup group = content.getPlaylistsObserve().blockingFirst();
                        if (group != null && group.getMediaItems() != null) {
                            for (MediaItem item : group.getMediaItems()) {
                                if (item != null && item.getTitle() != null
                                        && (item.getVideoId() == null || item.getPlaylistId() != null)) {
                                    playlists.add(item);
                                }
                            }
                        }
                        break;
                    }
                    case HOME: {
                        List<MediaGroup> home = content.getHome();
                        if (home != null) groups.addAll(home);
                        break;
                    }
                    default: {
                        List<MediaGroup> found = content.getSearch(mQuery);
                        if (found != null) groups.addAll(found);
                    }
                }
                Set<String> seen = new HashSet<>();
                for (MediaGroup group : groups) {
                    if (group == null || group.getMediaItems() == null) continue;
                    for (MediaItem item : group.getMediaItems()) {
                        if (videos.size() >= MAX_ITEMS) break;
                        if (item == null || item.getVideoId() == null || item.getVideoId().isEmpty()) continue;
                        if (!seen.add(item.getVideoId())) continue;
                        Video video = Video.from(item);
                        if (video.isShorts) continue;
                        // Continue watching = started but not finished (YouTube's red bar).
                        if (mKind == Kind.CONTINUE
                                && (video.percentWatched < 1 || video.percentWatched > 95)) continue;
                        videos.add(video);
                    }
                }
                if (videos.isEmpty() && playlists.isEmpty()) {
                    error = mKind != Kind.HOME && mKind != Kind.SEARCH
                            ? "Nada aqui. Tens sessão iniciada na app do telemóvel?"
                            : "Sem resultados";
                }
            } catch (Throwable e) {
                error = "Não foi possível carregar: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String finalError = error;
            mMain.post(() -> {
                if (!mAlive) return;
                mVideos.clear();
                mVideos.addAll(videos);
                mPlaylists.clear();
                mPlaylists.addAll(playlists);
                mError = finalError;
                mLoading = false;
                invalidate();
                loadThumbnails(new ArrayList<>(videos));
            });
        });
    }

    private static void add(List<MediaGroup> groups, @Nullable MediaGroup group) {
        if (group != null) groups.add(group);
    }

    private void loadThumbnails(List<Video> videos) {
        IO.execute(() -> {
            int sinceRefresh = 0;
            for (Video video : videos) {
                if (!mAlive) return;
                String url = video.getCardImageUrl();
                if (url == null) continue;
                try {
                    Bitmap bitmap = Glide.with(getCarContext().getApplicationContext())
                            .asBitmap().load(url).centerCrop().submit(THUMB_W, THUMB_H).get();
                    CarIcon icon = new CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build();
                    mMain.post(() -> mThumbs.put(video.videoId, icon));
                    if (++sinceRefresh >= 6) {
                        sinceRefresh = 0;
                        mMain.post(this::refresh);
                    }
                } catch (Throwable ignored) {
                }
            }
            mMain.post(this::refresh);
        });
    }

    private void refresh() {
        if (mAlive) invalidate();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        ListTemplate.Builder template = new ListTemplate.Builder()
                .setTitle(title())
                .setHeaderAction(Action.BACK);
        if (mLoading) {
            return template.setLoading(true).build();
        }
        ItemList.Builder list = new ItemList.Builder();
        if (mError != null) {
            list.setNoItemsMessage(mError);
        }
        for (MediaItem playlist : mPlaylists) {
            String name = playlist.getTitle();
            Row.Builder row = new Row.Builder()
                    .setTitle(name)
                    .setImage(AutoHomeScreen.icon(getCarContext(), com.liskovsoft.smartyoutubetv2.tv.R.drawable.ic_auto_playlist))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(
                            new AutoFeedScreen(getCarContext(), mSession, playlist, name)));
            CharSequence info = playlist.getSecondTitle();
            if (info != null && info.length() > 0) row.addText(info);
            list.addItem(row.build());
        }
        for (int i = 0; i < mVideos.size(); i++) {
            Video video = mVideos.get(i);
            int index = i;
            Row.Builder row = new Row.Builder()
                    .setTitle(video.title != null && !video.title.isEmpty() ? video.title : "Vídeo")
                    .setOnClickListener(() -> {
                        AutoPlayer.get(getCarContext()).play(mVideos, index);
                        getScreenManager().push(new AutoPlayerScreen(getCarContext(), mSession));
                    });
            String author = video.getAuthor();
            if (author != null && !author.isEmpty()) row.addText(author);
            CarIcon thumb = mThumbs.get(video.videoId);
            if (thumb != null) row.setImage(thumb, Row.IMAGE_TYPE_LARGE);
            list.addItem(row.build());
        }
        return template.setSingleList(list.build()).build();
    }
}
