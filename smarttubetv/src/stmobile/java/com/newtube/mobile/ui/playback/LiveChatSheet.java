package com.newtube.mobile.ui.playback;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * Live-chat panel for the touch watch page. The hosting Activity owns the actual chat stream (it
 * receives the SmartTube {@code ChatReceiver} pushed by the reused {@code ChatController}); this
 * sheet just seeds itself from the current snapshot and appends new messages as they arrive via the
 * {@link Host} callback, auto-scrolling to the newest. Best-effort: on a non-live video the Activity
 * never shows the chat entry, so this sheet is only ever opened when a chat stream is active.
 */
public class LiveChatSheet extends BottomSheetDialogFragment {

    private static final String TAG_SHEET = "mobile_live_chat_sheet";

    /** Implemented by the hosting Activity to expose the live-chat stream to the sheet. */
    public interface Host {
        List<ChatItem> getChatSnapshot();
        void registerChatObserver(Observer observer);
        void unregisterChatObserver(Observer observer);
    }

    public interface Observer {
        void onChatItem(ChatItem item);
    }

    private LiveChatAdapter mAdapter;
    private RecyclerView mList;
    private TextView mEmpty;
    private Host mHost;

    private final Observer mObserver = new Observer() {
        @Override
        public void onChatItem(ChatItem item) {
            if (mList == null) {
                return;
            }
            mList.post(() -> appendMessage(item));
        }
    };

    public static void show(@NonNull androidx.fragment.app.FragmentManager fm) {
        new LiveChatSheet().show(fm, TAG_SHEET);
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof Host) {
            mHost = (Host) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_mobile_live_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.chat_sheet_close).setOnClickListener(v -> dismissAllowingStateLoss());

        mEmpty = view.findViewById(R.id.chat_sheet_empty);
        mList = view.findViewById(R.id.chat_sheet_list);

        mAdapter = new LiveChatAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true); // newest at the bottom, list starts pinned to the end
        mList.setLayoutManager(lm);
        mList.setAdapter(mAdapter);

        if (mHost != null) {
            List<ChatItem> snapshot = mHost.getChatSnapshot();
            mAdapter.setItems(snapshot);
            if (snapshot != null && !snapshot.isEmpty()) {
                mEmpty.setVisibility(View.GONE);
                mList.scrollToPosition(mAdapter.getItemCount() - 1);
            }
            mHost.registerChatObserver(mObserver);
        }
    }

    private void appendMessage(ChatItem item) {
        if (mAdapter == null) {
            return;
        }
        int count = mAdapter.addItem(item);
        if (count > 0) {
            mEmpty.setVisibility(View.GONE);
            mList.scrollToPosition(count - 1);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog instanceof BottomSheetDialog) {
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT);
                int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.75f);
                sheet.getLayoutParams().height = height;
                sheet.requestLayout();
                BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
                behavior.setPeekHeight(height);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (mHost != null) {
            mHost.unregisterChatObserver(mObserver);
        }
        super.onDestroyView();
    }
}
