package org.telegram.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NearbyController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

// FORK MOD (nearby offline chat): list of nearby people running this fork, over Nearby Connections.
public class NearbyActivity extends BaseFragment implements NearbyController.Listener {

    private static final int REQUEST_CODE = 5501;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private ArrayList<NearbyController.Peer> peers = new ArrayList<>();
    private boolean permissionRequested;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Nearby (offline)");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        emptyView = new TextView(context);
        emptyView.setText("Searching for nearby people…\n\nBoth devices need this app, with Bluetooth and/or Wi-Fi on. No internet required.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(15);
        emptyView.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= peers.size()) {
                return;
            }
            NearbyController.Peer peer = peers.get(position);
            if (peer.connected) {
                presentFragment(new NearbyChatActivity(peer.endpointId));
            } else {
                NearbyController.getInstance().connect(peer.endpointId);
            }
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        NearbyController.getInstance().addListener(this);
        ensurePermissionsAndStart();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        NearbyController.getInstance().removeListener(this);
    }

    private String localName() {
        try {
            TLRPC.User u = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (u != null && u.first_name != null && u.first_name.length() > 0) {
                return u.first_name;
            }
        } catch (Exception ignore) {
        }
        return "Telegram user";
    }

    private void ensurePermissionsAndStart() {
        if (getParentActivity() == null) {
            return;
        }
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(need, Manifest.permission.BLUETOOTH_SCAN);
            addIfMissing(need, Manifest.permission.BLUETOOTH_ADVERTISE);
            addIfMissing(need, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addIfMissing(need, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(need, Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!need.isEmpty()) {
            if (!permissionRequested && Build.VERSION.SDK_INT >= 23) {
                permissionRequested = true;
                getParentActivity().requestPermissions(need.toArray(new String[0]), REQUEST_CODE);
            }
            return;
        }
        NearbyController.getInstance().start(getParentActivity(), localName());
    }

    private void addIfMissing(ArrayList<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(getParentActivity(), perm) != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE) {
            boolean allGranted = grantResults.length > 0;
            for (int g : grantResults) {
                if (g != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                }
            }
            if (allGranted) {
                NearbyController.getInstance().start(getParentActivity(), localName());
            }
        }
    }

    private void refresh() {
        peers = NearbyController.getInstance().getPeers();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null) {
            emptyView.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onNearbyStateChanged() {
        AndroidUtilities.runOnUIThread(this::refresh);
    }

    @Override
    public void onNearbyMessage(String endpointId) {
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
            tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            tv.setTextSize(16);
            tv.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            tv.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(tv);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            NearbyController.Peer peer = peers.get(position);
            String status = peer.connected ? "Connected · tap to chat" : (peer.connecting ? "Connecting…" : "Tap to connect");
            ((TextView) holder.itemView).setText(peer.name + "\n" + status);
        }

        @Override
        public int getItemCount() {
            return peers.size();
        }
    }
}
