package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NearbyController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

// FORK MOD (nearby offline chat): simple text chat with one nearby peer over Nearby Connections.
public class NearbyChatActivity extends BaseFragment implements NearbyController.Listener {

    private final String endpointId;
    private RecyclerListView listView;
    private MessagesAdapter adapter;
    private EditText input;

    public NearbyChatActivity(String endpointId) {
        this.endpointId = endpointId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        NearbyController.Peer peer = NearbyController.getInstance().getPeer(endpointId);
        actionBar.setTitle(peer != null ? peer.name : "Nearby");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        listView = new RecyclerListView(context);
        LinearLayoutManager lm = new LinearLayoutManager(context);
        lm.setStackFromEnd(true);
        listView.setLayoutManager(lm);
        listView.setAdapter(adapter = new MessagesAdapter());
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        inputRow.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));

        input = new EditText(context);
        input.setHint("Message");
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setTextSize(16);
        input.setBackground(null);
        input.setMaxLines(4);
        inputRow.addView(input, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView send = new TextView(context);
        send.setText("Send");
        send.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        send.setTextSize(16);
        send.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        send.setOnClickListener(v -> sendCurrent());
        inputRow.addView(send, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private void sendCurrent() {
        if (input == null) {
            return;
        }
        String text = input.getText().toString().trim();
        if (text.length() == 0) {
            return;
        }
        NearbyController.getInstance().sendText(endpointId, text);
        input.setText("");
    }

    @Override
    public void onResume() {
        super.onResume();
        NearbyController.getInstance().addListener(this);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        NearbyController.getInstance().removeListener(this);
    }

    private void refresh() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        NearbyController.Peer peer = NearbyController.getInstance().getPeer(endpointId);
        if (peer != null && listView != null && !peer.messages.isEmpty()) {
            listView.scrollToPosition(peer.messages.size() - 1);
        }
    }

    @Override
    public void onNearbyStateChanged() {
    }

    @Override
    public void onNearbyMessage(String id) {
        if (endpointId.equals(id)) {
            AndroidUtilities.runOnUIThread(this::refresh);
        }
    }

    private class MessagesAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            tv.setTextSize(16);
            tv.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(tv);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            NearbyController.Peer peer = NearbyController.getInstance().getPeer(endpointId);
            if (peer == null || position >= peer.messages.size()) {
                return;
            }
            NearbyController.Message m = peer.messages.get(position);
            TextView tv = (TextView) holder.itemView;
            tv.setText(m.text);
            tv.setGravity(m.outgoing ? Gravity.END : Gravity.START);
        }

        @Override
        public int getItemCount() {
            NearbyController.Peer peer = NearbyController.getInstance().getPeer(endpointId);
            return peer == null ? 0 : peer.messages.size();
        }
    }
}
