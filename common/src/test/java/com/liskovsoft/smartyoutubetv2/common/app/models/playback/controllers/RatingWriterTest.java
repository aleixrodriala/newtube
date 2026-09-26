package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.PublishSubject;

/** NEWTUBE(phone): rating writes go out in tap order, and a failure puts back what YouTube took. */
public class RatingWriterTest {
    private static final String VIDEO = "abc";

    private final RatingWriter writer = new RatingWriter();
    private final List<String> events = new ArrayList<>();
    private final RatingWriter.Rollback rollback = (videoId, rating) -> events.add("rollback " + videoId + " " + rating);

    private Observable<Void> call(String name, PublishSubject<Void> result) {
        return Observable.defer(() -> {
            events.add("send " + name);
            return result;
        });
    }

    @Test
    public void aTapIsNotSentUntilTheOneBeforeItIsDone() {
        PublishSubject<Void> like = PublishSubject.create();
        PublishSubject<Void> dislike = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.NONE, RatingWriter.LIKE, call("like", like), rollback);
        writer.write(VIDEO, RatingWriter.LIKE, RatingWriter.DISLIKE, call("dislike", dislike), rollback);
        assertEquals(List.of("send like"), events);

        like.onComplete();
        assertEquals(List.of("send like", "send dislike"), events);
        dislike.onComplete();
        assertEquals(2, events.size());
    }

    @Test
    public void aFailedTapPutsBackTheRatingYouTubeLastTook() {
        PublishSubject<Void> like = PublishSubject.create();
        PublishSubject<Void> dislike = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.NONE, RatingWriter.LIKE, call("like", like), rollback);
        like.onComplete();
        writer.write(VIDEO, RatingWriter.LIKE, RatingWriter.DISLIKE, call("dislike", dislike), rollback);
        dislike.onError(new IllegalStateException("offline"));

        assertEquals(List.of("send like", "send dislike", "rollback abc " + RatingWriter.LIKE), events);
    }

    @Test
    public void aFailureWithALaterTapQueuedLeavesItToThatTap() {
        PublishSubject<Void> like = PublishSubject.create();
        PublishSubject<Void> undo = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.NONE, RatingWriter.LIKE, call("like", like), rollback);
        writer.write(VIDEO, RatingWriter.LIKE, RatingWriter.NONE, call("undo", undo), rollback);

        like.onError(new IllegalStateException("offline"));
        assertEquals(List.of("send like", "send undo"), events);
        undo.onComplete();
        assertEquals(List.of("send like", "send undo"), events);
    }

    @Test
    public void whenEveryTapFailsTheOriginalRatingComesBack() {
        PublishSubject<Void> like = PublishSubject.create();
        PublishSubject<Void> remove = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.DISLIKE, RatingWriter.LIKE, call("like", like), rollback);
        writer.write(VIDEO, RatingWriter.LIKE, RatingWriter.NONE, call("remove", remove), rollback);
        like.onError(new IllegalStateException("offline"));
        remove.onError(new IllegalStateException("offline"));

        assertEquals("rollback abc " + RatingWriter.DISLIKE, events.get(events.size() - 1));
    }

    @Test
    public void theNextRoundReadsItsStartingRatingAfresh() {
        PublishSubject<Void> first = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.NONE, RatingWriter.LIKE, call("like", first), rollback);
        first.onComplete();

        // Later (queue idle) the screen says DISLIKE, e.g. changed elsewhere and reloaded.
        PublishSubject<Void> second = PublishSubject.create();
        writer.write(VIDEO, RatingWriter.DISLIKE, RatingWriter.NONE, call("remove", second), rollback);
        second.onError(new IllegalStateException("offline"));

        assertEquals("rollback abc " + RatingWriter.DISLIKE, events.get(events.size() - 1));
    }
}
