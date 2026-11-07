package com.newtube.mobile.ui.playback;

import static org.junit.Assert.*;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

public class WatchLaterActionTest {
    private Observable<List<PlaylistInfo>> info = Observable.just(Arrays.asList(playlist("WL", false)));
    private PublishSubject<Void> edit = PublishSubject.create();
    private int edits;
    private int failures;
    private String operation;
    private String editedPlaylist;
    private final MediaItemService service = (MediaItemService) Proxy.newProxyInstance(
            MediaItemService.class.getClassLoader(), new Class<?>[] {MediaItemService.class}, (proxy, method, args) -> {
                if (method.getName().equals("getPlaylistsInfoObserve")) {
                    return info;
                }
                if (method.getName().equals("addToPlaylistObserve") || method.getName().equals("removeFromPlaylistObserve")) {
                    edits++;
                    operation = method.getName();
                    editedPlaylist = (String) args[0];
                    return edit;
                }
                throw new AssertionError("Unexpected service call: " + method.getName());
            });
    private final WatchLaterAction action = new WatchLaterAction(service, () -> {}, () -> failures++);

    @Test
    public void playlistEditsWaitForSuccessPreserveMembershipAndRecoverFromFailure() {
        info = Observable.just(Arrays.asList(playlist("favorites", true), playlist("WL", false)));
        action.load("video");
        assertTrue(action.isKnown());
        assertFalse(action.isSaved());
        assertTrue(action.isAnyPlaylistSaved());
        assertEquals(0, edits);
        action.toggle("video");
        action.toggle("video");
        assertTrue(action.isBusy());
        assertFalse(action.isSaved());
        assertEquals(1, edits);
        assertEquals("WL", editedPlaylist);
        assertEquals("addToPlaylistObserve", operation);
        edit.onComplete();
        assertFalse(action.isBusy());
        assertTrue(action.isSaved());
        info = Observable.just(Arrays.asList(playlist("WL", true), playlist("other", true)));
        edit = PublishSubject.create();
        action.toggle("video");
        assertEquals("removeFromPlaylistObserve", operation);
        edit.onComplete();
        assertFalse(action.isSaved());
        assertTrue(action.isAnyPlaylistSaved());

        info = Observable.just(Arrays.asList(playlist("WL", false)));
        edit = PublishSubject.create();
        action.toggle("video");
        edit.onError(new IllegalStateException("offline"));
        assertEquals(1, failures);
        assertFalse(action.isBusy());
        assertFalse(action.isSaved());
        edit = PublishSubject.create();
        action.toggle("video");
        edit.onComplete();
        assertTrue(action.isSaved());
    }

    @Test
    public void unavailableMembershipAndStaleCallbacksCannotChangeTheWrongVideo() {
        info = Observable.error(new IllegalStateException("offline"));
        action.load("video");
        assertFalse(action.isBusy());
        assertFalse(action.isKnown());
        assertEquals(0, failures);
        info = Observable.just(Arrays.asList(playlist("WL", true)));
        action.load("video");
        assertTrue(action.isSaved());

        info = Observable.just(Arrays.asList(playlist("other", true)));
        action.toggle("video");
        assertEquals(0, edits);
        assertFalse(action.isBusy());
        info = Observable.empty();
        action.toggle("video");
        assertEquals(2, failures);
        assertFalse(action.isBusy());
        assertEquals(0, edits);

        PublishSubject<List<PlaylistInfo>> pending = PublishSubject.create();
        info = pending;
        action.load("old");
        info = Observable.just(Arrays.asList(playlist("WL", false)));
        action.load("new");
        pending.onNext(Arrays.asList(playlist("WL", true)));
        assertFalse(action.isSaved());
        action.toggle("new");
        action.reset();
        edit.onComplete();
        assertFalse(action.isKnown());
        assertFalse(action.isSaved());
    }

    private static PlaylistInfo playlist(String id, boolean selected) {
        return new PlaylistInfo() {
            public String getPlaylistId() { return id; }
            public String getTitle() { return id; }
            public boolean isSelected() { return selected; }
            public int getSize() { return 0; }
            public int getCurrentIndex() { return 0; }
        };
    }
}
