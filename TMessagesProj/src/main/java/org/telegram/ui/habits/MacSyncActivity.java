package org.telegram.ui.habits;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.nexo.BleTransport;
import org.telegram.messenger.nexo.ClipAccessibilityService;
import org.telegram.messenger.nexo.GroupKey;
import org.telegram.messenger.nexo.LocalFile;
import org.telegram.messenger.nexo.Nexo;
import org.telegram.messenger.nexo.NexoFiles;
import org.telegram.messenger.nexo.NexoPrefs;
import org.telegram.messenger.nexo.SyncEngine;
import org.telegram.messenger.nexo.SyncEvent;
import org.telegram.messenger.nexo.SyncService;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SectionsScrollView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * FORK MOD (Nexo): Mac sync — pairing (QR / code), clipboard & file sending, channels (Wi-Fi, Bluetooth, Internet),
 * auto-send and the permissions the background service needs.
 */
@SuppressLint("SetTextI18n")
public class MacSyncActivity extends BaseFragment {
    private static final int REQ_FILES = 10;
    private SyncEngine engine;
    private NexoPrefs prefs;
    private HabitUi.Form f;
    private LinearLayout content, pairedBox;
    private HabitUi.Card unpairedBox;
    private View macTile;
    private TextView macName, macState, lastEvent;
    private LinearLayout chips;
    private HabitUi.Form.Group perms;
    private HabitUi.Form.SwitchRow lanRow, bleRow, netRow;
    private HabitUi.Form.Row serverRow;
    private final Runnable listener = this::refresh;

    @Override
    public boolean onFragmentCreate() {
        engine = Nexo.engine();
        prefs = engine.prefs;
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        f = new HabitUi.Form(context);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.MacClipboard));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(HabitUi.bg());
        LinearLayout[] c = new LinearLayout[1];
        SectionsScrollView scroll = HabitUi.page(context, c);
        content = c[0];
        content.setPadding(dp(12), dp(4), dp(12), dp(32));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(scroll);
        buildUi(context);
        fragmentView = root;
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        engine.addListener(listener);
        if (getContext() != null) SyncService.start(getContext());
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        engine.removeListener(listener);
    }

    // region UI

    private void buildUi(Context ctx) {
        // Not paired: one clear step — the QR code
        // One island: icon, title, hint and the buttons
        unpairedBox = HabitUi.card(ctx);
        unpairedBox.setGravity(Gravity.CENTER_HORIZONTAL);
        unpairedBox.setPadding(dp(16), dp(40), dp(16), dp(16));
        FrameLayout iconBox = new FrameLayout(ctx);
        iconBox.setBackground(HabitUi.roundRect(HabitUi.withAlpha(HabitUi.sub(), 0x26), HabitUi.dpf(24)));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.msg_qrcode);
        icon.setColorFilter(HabitUi.sub());
        iconBox.addView(icon, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        unpairedBox.addView(iconBox, new LinearLayout.LayoutParams(dp(88), dp(88)));
        TextView t1 = HabitUi.text(ctx, "Not paired with a Mac", 20, HabitUi.text(), true);
        t1.setGravity(Gravity.CENTER);
        t1.setPadding(0, dp(20), 0, dp(6));
        unpairedBox.addView(t1);
        TextView t2 = HabitUi.text(ctx, "Scan the QR code from the Nexo menu on your Mac", 15, HabitUi.sub());
        t2.setGravity(Gravity.CENTER);
        unpairedBox.addView(t2);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(32);
        View scan = f.primaryButton("Scan QR code", this::startScan);
        scan.setTag(null);
        unpairedBox.addView(scan, blp);
        TextView manual = HabitUi.text(ctx, "Enter the code manually", 15, HabitUi.accent(), true);
        manual.setGravity(Gravity.CENTER);
        manual.setPadding(dp(16), dp(16), dp(16), dp(12));
        manual.setOnClickListener(v -> manualCodeDialog());
        manual.setTag(null);
        unpairedBox.addView(manual);
        content.addView(HabitUi.spacer(ctx, 12));
        content.addView(unpairedBox);

        pairedBox = new LinearLayout(ctx);
        pairedBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(pairedBox);

        // Status: Mac name, connection and the last transfer
        macName = HabitUi.text(ctx, "", 18, HabitUi.text(), true);
        macState = HabitUi.text(ctx, "", 14, HabitUi.sub());
        chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        lastEvent = HabitUi.text(ctx, "", 14, HabitUi.sub());
        lastEvent.setMaxLines(2);
        lastEvent.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pairedBox.addView(HabitUi.spacer(ctx, 12));
        HabitUi.Card card = HabitUi.card(ctx);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        pairedBox.addView(card);
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        macTile = new FrameLayout(ctx);
        ImageView laptop = new ImageView(ctx);
        laptop.setImageResource(R.drawable.msg_openin);
        laptop.setColorFilter(HabitUi.white());
        ((FrameLayout) macTile).addView(laptop, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        tlp.rightMargin = dp(14);
        head.addView(macTile, tlp);
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(macName);
        names.addView(macState);
        head.addView(names);
        card.addView(head);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        card.addView(chips, clp);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(12);
        card.addView(lastEvent, elp);

        // Permissions — shown only when something is missing
        perms = f.new Group("Permission needed", pairedBox);

        HabitUi.Form.Group send = f.new Group("Send", pairedBox);
        send.add(f.new Row("Send clipboard", this::sendClipboardNow).view);
        send.add(f.new Row("Send files", this::pickFiles).sub("Up to " + NexoFiles.sizeLabel(NexoFiles.MAX_BYTES)).view);

        HabitUi.Form.Group sync = f.new Group("Sync", pairedBox);
        sync.add(f.new SwitchRow("Sync", prefs.isSyncEnabled(), on -> {
            prefs.setSyncEnabled(on);
            if (on) SyncService.start(getContext()); else SyncService.stop(getContext());
        }).view);
        sync.add(f.new SwitchRow("Images too", prefs.isImagesEnabled(), on -> prefs.setImagesEnabled(on)).view);
        sync.add(f.new SwitchRow("Auto-send", prefs.isAutoModeEnabled(), on -> {
            prefs.setAutoModeEnabled(on);
            if (on && !ClipAccessibilityService.isEnabled(getContext())) accessibilityDialog();
            refresh();
        }).sub("Copied text is sent automatically").view);

        HabitUi.Form.Group channels = f.new Group("Channels", pairedBox);
        lanRow = f.new SwitchRow("Wi-Fi", prefs.isLanEnabled(), on -> { prefs.setLanEnabled(on); engine.postApplySettings(); });
        bleRow = f.new SwitchRow("Bluetooth", prefs.isBleEnabled(), on -> {
            prefs.setBleEnabled(on);
            if (on) requestBlePermissions();
            engine.postApplySettings();
        });
        netRow = f.new SwitchRow("Internet", prefs.isInternetEnabled(), on -> { prefs.setInternetEnabled(on); engine.postApplySettings(); });
        serverRow = f.new Row("Server", this::serverDialog);
        channels.add(lanRow.view);
        channels.add(bleRow.view);
        channels.add(netRow.view);
        channels.add(serverRow.view);

        pairedBox.addView(f.spacer(24));
        HabitUi.Form.Group other = f.new Group(null, pairedBox);
        other.add(f.new Row("Pair with another Mac", this::startScan).view);
        other.add(f.new Row("Unpair", this::unpairDialog).titleColor(HabitUi.skip()).view);
    }

    private TextView chip(String label, boolean on) {
        TextView t = HabitUi.text(getContext(), label, 13, on ? HabitUi.done() : HabitUi.sub(), on);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setBackground(HabitUi.roundRect(on ? HabitUi.withAlpha(HabitUi.done(), 0x26) : HabitUi.withAlpha(HabitUi.sub(), 0x20), HabitUi.dpf(12)));
        return t;
    }

    private View permRow(String title, String sub, Runnable onClick) {
        HabitUi.Form.Row r = f.new Row(title, onClick).value("Enable").valueColor(HabitUi.fire());
        if (sub != null) r.sub(sub);
        return r.view;
    }

    private void refresh() {
        if (fragmentView == null || getContext() == null) return;
        boolean paired = engine.isPaired();
        unpairedBox.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairedBox.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (!paired) return;
        boolean lan = !engine.lan.connectedPeers().isEmpty();
        boolean ble = !engine.ble.connectedPeers().isEmpty();
        boolean net = engine.internet.isConnected();
        ArrayList<String> via = new ArrayList<>();
        if (lan) via.add("Wi-Fi");
        if (ble) via.add("Bluetooth");
        if (net) via.add("Internet");
        macName.setText(prefs.getMacName() == null ? "Mac" : prefs.getMacName());
        macState.setText(!prefs.isSyncEnabled() ? "Sync is off" : !via.isEmpty() ? "Connected · " + via.get(0) : "Connecting…");
        macTile.setBackground(HabitUi.roundRect(!via.isEmpty() && prefs.isSyncEnabled() ? HabitUi.done() : HabitUi.sub(), HabitUi.dpf(9)));
        chips.removeAllViews();
        String[] names = {"Wi-Fi", "Bluetooth", "Internet"};
        boolean[] ons = {lan, ble, net};
        for (int i = 0; i < 3; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chips.addView(chip(names[i], ons[i]), lp);
        }
        SyncEvent e = engine.getLastEvent();
        lastEvent.setVisibility(e == null ? View.GONE : View.VISIBLE);
        if (e != null) {
            String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(e.time));
            lastEvent.setText((e.incoming ? "↓" : "↑") + " \"" + e.preview + "\" · " + time);
        }
        lanRow.sub(prefs.isLanEnabled() ? engine.lan.status() : "");
        bleRow.sub(prefs.isBleEnabled() ? engine.ble.status() : "");
        netRow.sub(prefs.isInternetEnabled() ? engine.internet.status() : "");
        String host = Uri.parse(prefs.getRelayServer()).getHost();
        serverRow.value(host == null ? prefs.getRelayServer() : host);

        perms.card.removeAllViews();
        Activity a = getParentActivity();
        boolean notifOk = Build.VERSION.SDK_INT < 33 || a == null || androidx.core.content.ContextCompat.checkSelfPermission(a, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!notifOk) perms.add(permRow("Notifications", null, this::requestNotificationPermission));
        if (prefs.isBleEnabled() && !BleTransport.hasPermissions(getContext())) perms.add(permRow("Bluetooth", null, this::requestBlePermissions));
        PowerManager pm = androidx.core.content.ContextCompat.getSystemService(getContext(), PowerManager.class);
        if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getContext().getPackageName())) {
            perms.add(permRow("Battery restriction", "So the background sync is not killed", this::requestBatteryExemption));
        }
        if (prefs.isAutoModeEnabled() && !ClipAccessibilityService.isEnabled(getContext())) {
            perms.add(permRow("Auto-send service", "In Accessibility settings", this::accessibilityDialog));
        }
        perms.setVisible(perms.card.getChildCount() > 0);
    }

    private void accessibilityDialog() {
        showDialog(new AlertDialog.Builder(getContext())
            .setTitle("Auto-send")
            .setMessage("Accessibility → enable \"Telegram auto-send and app blocking\".\n\nIf it can't be enabled: App info → ⋮ → \"Allow restricted settings\".")
            .setPositiveButton("Open", (d, w) -> {
                try { getParentActivity().startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignore) {}
            })
            .setNegativeButton("Later", null)
            .create());
    }

    private void serverDialog() {
        Context ctx = getContext();
        EditTextBoldCursor input = HabitUi.input(ctx, "https://ntfy.sh");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getRelayServer());
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        showDialog(new AlertDialog.Builder(ctx)
            .setTitle("Internet server (ntfy)")
            .setView(box)
            .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                String url = input.getText().toString().trim();
                if (url.startsWith("http")) {
                    prefs.setRelayServer(url);
                    engine.restartInternet();
                    refresh();
                } else {
                    HabitUi.toast(this, "The address must start with http");
                }
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create());
    }

    private void unpairDialog() {
        HabitUi.confirm(this, "Unpair?", "To pair again you will need to scan the QR code.", "Unpair", true, () -> {
            engine.unpair();
            SyncService.stop(getContext());
            content.postDelayed(this::refresh, 300);
        });
    }

    // endregion

    // region Actions

    private void sendClipboardNow() {
        ClipboardManager cm = androidx.core.content.ContextCompat.getSystemService(getContext(), ClipboardManager.class);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(getContext()) : null;
        if (text == null || text.length() == 0) {
            HabitUi.toast(this, "Clipboard is empty");
            return;
        }
        engine.sendLocalText(text.toString());
        HabitUi.toastSuccess(this, "Sent to Mac");
    }

    private void pickFiles() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_FILES);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILES || resultCode != Activity.RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        NexoFiles.readAsync(getContext(), uris, (files, skipped) -> {
            for (LocalFile file : files) engine.sendLocalFile(file, false, null);
            if (!skipped.isEmpty()) HabitUi.toast(this, "Not sent (larger than " + NexoFiles.sizeLabel(NexoFiles.MAX_BYTES) + " or unreadable): " + android.text.TextUtils.join(", ", skipped));
            else HabitUi.toastSuccess(this, files.size() == 1 ? "Sent to Mac" : files.size() + " files sent to Mac");
        });
    }

    private void startScan() {
        if (getParentActivity() == null) return;
        CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                handlePairingText(text);
            }
        });
    }

    private void manualCodeDialog() {
        Context ctx = getContext();
        EditTextBoldCursor input = HabitUi.input(ctx, "nexo://pair?k=…");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        showDialog(new AlertDialog.Builder(ctx)
            .setTitle("Pairing code")
            .setMessage("On the Mac tap \"Copy code\" and paste it here.")
            .setView(box)
            .setPositiveButton("Pair", (d, w) -> handlePairingText(input.getText().toString()))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create());
        HabitUi.focusWithKeyboard(input);
    }

    private void handlePairingText(String text) {
        Uri uri = Uri.parse(text.trim());
        GroupKey key = null;
        if ("nexo".equals(uri.getScheme()) && "pair".equals(uri.getHost())) {
            String k = uri.getQueryParameter("k");
            if (k != null) key = GroupKey.fromBase64url(k);
        }
        if (key == null) {
            HabitUi.toast(this, "This is not a Nexo pairing code");
            return;
        }
        if (engine.getKey() != null && key.base64url().equals(engine.getKey().base64url())) {
            HabitUi.toast(this, "Already paired with this Mac");
            return;
        }
        String mac = uri.getQueryParameter("n");
        final String macLabel = mac == null ? "Mac" : mac;
        ArrayList<String> hints = new ArrayList<>();
        String h = uri.getQueryParameter("h");
        if (h != null) for (String s : h.split(",")) if (!s.trim().isEmpty()) hints.add(s.trim());
        String relay = uri.getQueryParameter("r");
        final GroupKey finalKey = key;
        showDialog(new AlertDialog.Builder(getContext())
            .setTitle("Pair with \"" + macLabel + "\"?")
            .setPositiveButton("Pair", (d, w) -> {
                engine.pair(finalKey, macLabel, hints, relay);
                content.postDelayed(() -> {
                    SyncService.start(getContext());
                    requestInitialPermissions();
                    refresh();
                }, 300);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create());
    }

    private void requestInitialPermissions() {
        Activity a = getParentActivity();
        if (a == null) return;
        ArrayList<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (prefs.isBleEnabled()) for (String p : BleTransport.permissions()) needed.add(p);
        ArrayList<String> missing = new ArrayList<>();
        for (String p : needed) if (androidx.core.content.ContextCompat.checkSelfPermission(a, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (!missing.isEmpty()) a.requestPermissions(missing.toArray(new String[0]), 1);
        else requestBatteryExemption();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && getParentActivity() != null) getParentActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }

    private void requestBlePermissions() {
        if (getParentActivity() != null && !BleTransport.hasPermissions(getContext())) getParentActivity().requestPermissions(BleTransport.permissions(), 3);
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        Context ctx = getContext();
        PowerManager pm = androidx.core.content.ContextCompat.getSystemService(ctx, PowerManager.class);
        if (Build.VERSION.SDK_INT < 23 || pm == null || pm.isIgnoringBatteryOptimizations(ctx.getPackageName())) return;
        try {
            getParentActivity().startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception e) {
            try { getParentActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignore) {}
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        engine.postApplySettings();
        refresh();
        if (requestCode == 1) requestBatteryExemption();
    }

    // endregion
}
