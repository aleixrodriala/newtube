package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Chapters bottom sheet for the touch watch page (YouTube's "Chapters" panel). Chapters arrive as
 * a {@link VideoGroup#isChapters()} suggestions group ({@code Video.isChapter} items carrying
 * {@code title} + {@code startTimeMs} + a preformatted timestamp {@code badge}); tapping a row
 * hands the start position to the caller (which seeks the player) and dismisses the sheet.
 *
 * <p>Deliberately a plain {@link BottomSheetDialog} (not a DialogFragment): the chapter list lives
 * in the player Activity's memory and never needs to survive recreation - the player handles
 * rotation via {@code configChanges}, and on a real teardown the sheet should just close.</p>
 */
final class ChaptersSheet {

    interface Listener {
        void onChapterClicked(Video chapter);
    }

    private ChaptersSheet() {
    }

    /** Build + show. {@code currentIndex} highlights the chapter the playhead is inside of. */
    static void show(@NonNull Activity activity, @NonNull List<Video> chapters, int currentIndex, @NonNull Listener listener) {
        if (chapters.isEmpty()) {
            return;
        }

        BottomSheetDialog dialog = new BottomSheetDialog(activity);
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_mobile_chapters, null);
        dialog.setContentView(content);

        View close = content.findViewById(R.id.chapters_sheet_close);
        if (close != null) {
            close.setOnClickListener(v -> dialog.dismiss());
        }

        RecyclerView list = content.findViewById(R.id.chapters_sheet_list);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(new ChapterAdapter(new ArrayList<>(chapters), currentIndex, chapter -> {
            listener.onChapterClicked(chapter);
            dialog.dismiss();
        }));
        if (currentIndex > 0) {
            list.scrollToPosition(currentIndex);
        }

        dialog.show();
    }

    private static class ChapterAdapter extends RecyclerView.Adapter<ChapterHolder> {
        private final List<Video> mChapters;
        private final int mCurrentIndex;
        private final Listener mListener;

        ChapterAdapter(List<Video> chapters, int currentIndex, Listener listener) {
            mChapters = chapters;
            mCurrentIndex = currentIndex;
            mListener = listener;
        }

        @NonNull
        @Override
        public ChapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mobile_chapter, parent, false);
            return new ChapterHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ChapterHolder holder, int position) {
            holder.bind(mChapters.get(position), position == mCurrentIndex, mListener);
        }

        @Override
        public int getItemCount() {
            return mChapters.size();
        }
    }

    private static class ChapterHolder extends RecyclerView.ViewHolder {
        private final TextView mTime;
        private final TextView mTitle;

        ChapterHolder(@NonNull View itemView) {
            super(itemView);
            mTime = itemView.findViewById(R.id.chapter_time);
            mTitle = itemView.findViewById(R.id.chapter_title);
        }

        void bind(Video chapter, boolean isCurrent, Listener listener) {
            mTime.setText(chapter.badge);
            mTitle.setText(chapter.title);
            // Current chapter: accent title, like YouTube's highlighted row.
            mTitle.setTextColor(ContextCompat.getColor(itemView.getContext(),
                    isCurrent ? R.color.mobile_color_link : R.color.mobile_color_on_surface));
            mTitle.setTypeface(null, isCurrent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            itemView.setOnClickListener(v -> listener.onChapterClicked(chapter));
        }
    }
}
