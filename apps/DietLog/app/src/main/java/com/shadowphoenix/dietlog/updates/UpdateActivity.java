package com.shadowphoenix.dietlog.updates;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.discipline.DisciplineUi;

import java.io.File;
import java.lang.ref.WeakReference;

/** User-controlled downloads and Android's explicit installation confirmation. */
public final class UpdateActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UpdateManager manager;
    private TextView heading, message, version, progressLabel, check, primary, cancel, noticeView;
    private ProgressBar progress;
    private Switch automaticChecks;
    private boolean active, rendering, preparing, permissionRequested;
    private int installGeneration;
    private String notice = "";

    private final Runnable refresh =
            new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    render();
                    handler.postDelayed(this, 1_000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLogger.init(this);
        CrashHandler.install(this);
        manager = UpdateManager.get(getApplicationContext());
        if (state != null) {
            notice = state.getString("notice", "");
            permissionRequested = state.getBoolean("permissionRequested", false);
        }
        LinearLayout body = DisciplineUi.page(this, R.drawable.discipline_hub_background);
        DisciplineUi.add(body, DisciplineUi.button(this, "← Back", this::finish), 0);
        DisciplineUi.add(
                body, DisciplineUi.text(this, "App updates", 30, DisciplineUi.TEXT, true), 22);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Installed version " + BuildConfig.VERSION_NAME,
                        15,
                        DisciplineUi.MUTED,
                        false),
                8);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Get new FuelNote and Discipline releases directly from GitHub. Your saved"
                                + " data stays on this device.",
                        15,
                        DisciplineUi.MUTED,
                        false),
                12);

        LinearLayout card = DisciplineUi.card(this);
        heading = DisciplineUi.text(this, "Check for updates", 21, DisciplineUi.TEXT, true);
        version = DisciplineUi.text(this, "", 15, DisciplineUi.BLUE, true);
        message = DisciplineUi.text(this, "", 15, DisciplineUi.MUTED, false);
        DisciplineUi.add(card, heading, 0);
        DisciplineUi.add(card, version, 10);
        DisciplineUi.add(card, message, 10);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(DisciplineUi.BLUE));
        progress.setIndeterminateTintList(ColorStateList.valueOf(DisciplineUi.BLUE));
        DisciplineUi.add(card, progress, 18);
        progressLabel = DisciplineUi.text(this, "", 13, DisciplineUi.MUTED, false);
        DisciplineUi.add(card, progressLabel, 8);
        primary = DisciplineUi.button(this, "Download update", this::primaryAction);
        DisciplineUi.add(card, primary, 18);
        cancel =
                DisciplineUi.button(
                        this,
                        "Cancel download",
                        () -> {
                            notice = "";
                            manager.cancel();
                            render();
                        });
        DisciplineUi.add(card, cancel, 12);
        check =
                DisciplineUi.button(
                        this,
                        "Check for updates",
                        () -> {
                            notice = "";
                            manager.check(true);
                            render();
                        });
        DisciplineUi.add(card, check, 12);
        noticeView = DisciplineUi.text(this, "", 14, DisciplineUi.MUTED, false);
        DisciplineUi.add(card, noticeView, 14);
        DisciplineUi.add(body, card, 24);

        LinearLayout preferences = DisciplineUi.card(this);
        automaticChecks = new Switch(this);
        automaticChecks.setText("Check automatically when I open the app");
        automaticChecks.setTextColor(DisciplineUi.TEXT);
        automaticChecks.setTextSize(15);
        automaticChecks.setMinHeight(DisciplineUi.dp(this, 56));
        automaticChecks.setThumbTintList(ColorStateList.valueOf(DisciplineUi.BLUE));
        automaticChecks.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!rendering) manager.setAutomaticChecks(checked);
                });
        DisciplineUi.add(preferences, automaticChecks, 0);
        DisciplineUi.add(
                preferences,
                DisciplineUi.text(
                        this,
                        "Checks use an internet connection. Downloads start only when you tap"
                                + " Download update. Android asks you to confirm installation.",
                        14,
                        DisciplineUi.MUTED,
                        false),
                10);
        DisciplineUi.add(body, preferences, 20);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        manager.check(false);
        if (permissionRequested) {
            permissionRequested = false;
            notice =
                    canInstall()
                            ? "Installation is allowed. Tap Install update to continue."
                            : "Installation permission was not enabled. You can try again when you"
                                    + " are ready.";
        }
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refresh);
        // A completed worker must never open another screen after this Activity has left.
        installGeneration++;
        preparing = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("notice", notice);
        state.putBoolean("permissionRequested", permissionRequested);
        super.onSaveInstanceState(state);
    }

    private void render() {
        UpdateManager.State state = manager.snapshot();
        boolean working = state.busy || preparing;
        String title;
        if (preparing || "verifying".equals(state.status)) title = "Verifying update…";
        else if ("downloading".equals(state.status)) title = "Downloading update…";
        else if ("checking".equals(state.status)) title = "Checking GitHub…";
        else if (state.ready) title = "Ready to install";
        else if ("error".equals(state.status)) title = "Update needs attention";
        else if (state.available) title = "Update available";
        else title = "App updates";
        setText(heading, title);
        setText(
                message,
                state.message == null
                        ? "Tap Check for updates to find the latest release."
                        : state.message);
        boolean hasVersion = state.versionName != null && !state.versionName.isEmpty();
        version.setVisibility(hasVersion ? View.VISIBLE : View.GONE);
        if (hasVersion) setText(version, "Release " + state.versionName);
        boolean showProgress = working;
        progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        boolean determinate =
                "downloading".equals(state.status) && state.progress >= 0 && !preparing;
        progress.setIndeterminate(!determinate);
        progress.setProgress(Math.max(0, Math.min(100, state.progress)));
        progressLabel.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        setText(
                progressLabel,
                determinate ? Math.min(100, state.progress) + "% downloaded" : "Please wait…");
        primary.setVisibility(state.ready || state.available ? View.VISIBLE : View.GONE);
        setText(primary, state.ready ? "Install update" : "Download update");
        enable(primary, !working);
        cancel.setVisibility(
                state.busy && "downloading".equals(state.status) ? View.VISIBLE : View.GONE);
        enable(check, !working);
        rendering = true;
        automaticChecks.setChecked(state.automaticChecks);
        rendering = false;
        noticeView.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        setText(noticeView, notice);
    }

    private void primaryAction() {
        UpdateManager.State state = manager.snapshot();
        if (state.busy || preparing) return;
        notice = "";
        if (!state.ready) {
            if (state.available) manager.download();
            render();
            return;
        }
        if (!canInstall()) {
            notice =
                    "Allow this app to install updates on the next screen, then return and tap"
                            + " Install update.";
            permissionRequested = true;
            try {
                startActivity(
                        new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException | SecurityException e) {
                permissionRequested = false;
                notice =
                        "Android could not open installation settings. Allow installation for this"
                                + " app in your device settings, then try again.";
            }
            render();
            return;
        }
        preparing = true;
        int generation = ++installGeneration;
        manager.prepareInstall(new InstallationResult(this, generation));
        render();
    }

    private boolean canInstall() {
        return getPackageManager().canRequestPackageInstalls();
    }

    private void openInstaller(File file, int generation) {
        if (generation != installGeneration || !active || isFinishing() || isDestroyed()) return;
        preparing = false;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("App update", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            notice =
                    "Confirm the update in Android's installation screen. If you cancel, you can"
                            + " install it later.";
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            notice = "No Android package installer is available on this device.";
        } catch (IllegalArgumentException | SecurityException e) {
            notice =
                    "Android could not open this update for installation. Please try downloading it"
                            + " again.";
            manager.invalidateDownload(notice);
        }
        render();
    }

    private void installError(String error, int generation) {
        if (generation != installGeneration || !active || isFinishing() || isDestroyed()) return;
        preparing = false;
        notice =
                error == null || error.isEmpty()
                        ? "The update could not be verified. Download it again and retry."
                        : error;
        render();
    }

    private static void setText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .5f);
    }

    private static final class InstallationResult implements UpdateManager.InstallCallback {
        private final WeakReference<UpdateActivity> activity;
        private final int generation;

        InstallationResult(UpdateActivity activity, int generation) {
            this.activity = new WeakReference<>(activity);
            this.generation = generation;
        }

        @Override
        public void onReady(File file) {
            UpdateActivity target = activity.get();
            if (target != null) target.openInstaller(file, generation);
        }

        @Override
        public void onError(String message) {
            UpdateActivity target = activity.get();
            if (target != null) target.installError(message, generation);
        }
    }
}
