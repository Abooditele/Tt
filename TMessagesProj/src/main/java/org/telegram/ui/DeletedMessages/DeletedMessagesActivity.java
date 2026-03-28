/*
 * Deleted Messages Viewer Activity
 * Shows all saved deleted messages organized by chat
 */
package org.telegram.ui.DeletedMessages;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DeletedMessagesManager;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * Activity to view deleted messages
 */
public class DeletedMessagesActivity extends BaseFragment {

    private RecyclerListView listView;
    private ListAdapter adapter;
    private ArrayList<Long> dialogIds = new ArrayList<>();
    private ArrayList<String> dialogNames = new ArrayList<>();
    private ArrayList<Integer> messageCounts = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadData();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.DeletedMessages));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter());
        listView.setVerticalScrollBarEnabled(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        return fragmentView;
    }

    private void loadData() {
        dialogIds.clear();
        dialogNames.clear();
        messageCounts.clear();

        DeletedMessagesManager manager = DeletedMessagesManager.getInstance(currentAccount);
        ArrayList<Long> dialogs = manager.getDialogsWithDeletedMessages();
        MessagesController messagesController = MessagesController.getInstance(currentAccount);

        for (Long dialogId : dialogs) {
            int count = manager.getDeletedMessagesCount(dialogId);
            if (count > 0) {
                dialogIds.add(dialogId);
                messageCounts.add(count);

                String name;
                int lowerId = (int) (long) dialogId;
                if (lowerId > 0) {
                    TLRPC.User user = messagesController.getUser(lowerId);
                    name = UserObject.getUserName(user);
                } else {
                    TLRPC.Chat chat = messagesController.getChat(-lowerId);
                    name = chat != null ? chat.title : "Unknown";
                }
                dialogNames.add(name);
            }
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private static final int VIEW_TYPE_DIALOG = 0;
        private static final int VIEW_TYPE_EMPTY = 1;

        @Override
        public int getItemCount() {
            return dialogIds.isEmpty() ? 1 : dialogIds.size();
        }

        @Override
        public int getItemViewType(int position) {
            return dialogIds.isEmpty() ? VIEW_TYPE_EMPTY : VIEW_TYPE_DIALOG;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_DIALOG;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_EMPTY) {
                TextView emptyView = new TextView(parent.getContext());
                emptyView.setText(LocaleController.getString(R.string.NoDeletedMessages));
                emptyView.setTextSize(16);
                emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                emptyView.setGravity(android.view.Gravity.CENTER);
                view = emptyView;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                view = new DeletedMessageDialogCell(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_DIALOG) {
                DeletedMessageDialogCell cell = (DeletedMessageDialogCell) holder.itemView;
                cell.setDialog(dialogIds.get(position), dialogNames.get(position), messageCounts.get(position));
            }
        }
    }

    /**
     * Cell for displaying a dialog with deleted messages
     */
    private class DeletedMessageDialogCell extends FrameLayout {

        private TextView nameTextView;
        private TextView countTextView;
        private long currentDialogId;

        public DeletedMessageDialogCell(Context context) {
            super(context);

            setWillNotDraw(false);

            nameTextView = new TextView(context);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setTextSize(16);
            nameTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT, 72, 12, 60, 0));

            countTextView = new TextView(context);
            countTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            countTextView.setTextSize(14);
            addView(countTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.LEFT, 72, 38, 60, 0));

            setMinimumHeight(AndroidUtilities.dp(72));
            setBackground(Theme.getSelectorDrawable(false));
        }

        public void setDialog(long dialogId, String name, int count) {
            currentDialogId = dialogId;
            nameTextView.setText(name);
            countTextView.setText(LocaleController.formatPluralString("DeletedMessagesCount", count));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
        }
    }
}
