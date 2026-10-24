package df.R10omxalSU;

import df.D10slice.BuildConfig;
import df.D10slice.R;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements IReporter {

    private static final String TAG = "dfroot";

    private MaterialCardView launchCard;
    private FrameLayout launchIconContainer;
    private ImageView launchIcon;
    private CircularProgressIndicator launchProgress;
    private TextView launchTitle;
    private TextView launchSubtitle;
    private LinearLayout suManagerRow;
    private TextView suManagerSubtitle;
    private ScrollView outputScroll;
    private TextView outputView;
    private TextView logEmpty;
    private MaterialSwitch switchBootStart;
    private MaterialSwitch switchSoftReboot;
    private MaterialSwitch switchDisableModules;
    private Context mDeCtx;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Executor mExec = Executors.newSingleThreadExecutor();
    private final List<SuManagerEntry> suManagers = new ArrayList<>();
    private String mSelectedSuManagerPackage;
    private SharedPreferences suManagerPrefs;
    private boolean isLaunching;
    private boolean launchFailed;
    private String launchError;
    private boolean isLoadingManagers;
    private PopupWindow suManagerPopup;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        mMain.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            logEmpty.setVisibility(View.GONE);
            outputView.append(msg);
            outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        mDeCtx = createDeviceProtectedStorageContext();
        setContentView(R.layout.activity_main);

        launchCard = findViewById(R.id.launchCard);
        launchIconContainer = findViewById(R.id.launchIconContainer);
        launchIcon = findViewById(R.id.launchIcon);
        launchProgress = findViewById(R.id.launchProgress);
        launchTitle = findViewById(R.id.launchTitle);
        launchSubtitle = findViewById(R.id.launchSubtitle);
        suManagerRow = findViewById(R.id.suManagerRow);
        suManagerSubtitle = findViewById(R.id.suManagerSubtitle);
        outputScroll = findViewById(R.id.outputScroll);
        outputView = findViewById(R.id.outputView);
        logEmpty = findViewById(R.id.logEmpty);
        switchBootStart = findViewById(R.id.switchBootStart);
        switchSoftReboot = findViewById(R.id.switchSoftReboot);
        switchDisableModules = findViewById(R.id.switchDisableModules);
        ((TextView) findViewById(R.id.versionView)).setText(BuildConfig.VERSION_NAME);

        suManagerPrefs = mDeCtx.getSharedPreferences(ExploitRunner.PREFS_NAME, Context.MODE_PRIVATE);
        refreshSuManagers();
        refreshSuManagerSelection();
        configureSettings(suManagerPrefs);

        suManagerRow.setOnClickListener(v -> {
            if (isSuManagerLocked()) {
                updateRunButton();
                return;
            }
            openSuManagerPicker();
        });
        launchCard.setOnClickListener(v -> onLaunchCardClicked());

        updateRunButton();
    }

    private void onLaunchCardClicked() {
        if (isLaunching) return;
        if (isRootActive()) {
            openSelectedSuManager();
            return;
        }
        if (launchFailed || hasIncompleteAttempt()) {
            if (hasIncompleteAttempt()) {
                showRebootRequiredDialog();
                return;
            }
            if (mSelectedSuManagerPackage == null) {
                Toast.makeText(this, "Select an SU Manager first", Toast.LENGTH_SHORT).show();
                return;
            }
            launchRoot();
            return;
        }
        if (mSelectedSuManagerPackage == null) {
            Toast.makeText(this, "Select an SU Manager first", Toast.LENGTH_SHORT).show();
            return;
        }
        launchRoot();
    }

    private void openSelectedSuManager() {
        if (mSelectedSuManagerPackage == null) {
            Toast.makeText(this, "No SU Manager selected", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = getPackageManager().getLaunchIntentForPackage(mSelectedSuManagerPackage);
        if (intent == null) {
            Toast.makeText(this, "Could not open the SU Manager", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "failed to open SU manager", e);
            Toast.makeText(this, "Could not open the SU Manager", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshSuManagers() {
        suManagers.clear();
        suManagers.addAll(scanSuManagers());
    }

    private List<SuManagerEntry> scanSuManagers() {
        PackageManager pm = getPackageManager();
        List<SuManagerEntry> entries = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (!ExploitRunner.hasKsud(ai)) continue;
            Drawable icon = pm.getApplicationIcon(ai);
            entries.add(new SuManagerEntry(ai.packageName, pm.getApplicationLabel(ai), icon));
        }
        entries.sort((a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
        return entries;
    }

    private void openSuManagerPicker() {
        if (isSuManagerLocked() || isLoadingManagers) return;
        isLoadingManagers = true;
        mExec.execute(() -> {
            List<SuManagerEntry> detected;
            try {
                detected = scanSuManagers();
            } catch (Exception e) {
                Log.e(TAG, "failed to scan SU managers", e);
                detected = new ArrayList<>();
            }
            final List<SuManagerEntry> result = detected;
            mMain.post(() -> {
                isLoadingManagers = false;
                if (isFinishing() || isDestroyed()) return;
                suManagers.clear();
                suManagers.addAll(result);
                refreshSuManagerSelection();
                updateRunButton();
                if (isSuManagerLocked()) {
                    return;
                }
                if (suManagers.isEmpty()) {
                    Toast.makeText(this, "No compatible SU managers found", Toast.LENGTH_LONG).show();
                    return;
                }
                showSuManagerPicker();
            });
        });
    }

    private void showSuManagerPicker() {
        if (isSuManagerLocked() || suManagers.isEmpty()) return;
        if (suManagerPopup != null) {
            suManagerPopup.dismiss();
            suManagerPopup = null;
        }

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(8), dp(8), dp(8), dp(8));

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.addView(options);

        MaterialCardView surface = new MaterialCardView(this);
        surface.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh));
        surface.setRadius(dp(24));
        surface.setCardElevation(0);
        surface.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        surface.setStrokeWidth(dp(1));
        surface.addView(scroll, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (SuManagerEntry entry : suManagers) {
            View item = inflater.inflate(R.layout.item_su_manager, options, false);
            ImageView icon = item.findViewById(R.id.iconApp);
            TextView label = item.findViewById(R.id.labelApp);
            TextView packageName = item.findViewById(R.id.packageApp);
            ImageView selected = item.findViewById(R.id.checkSelected);

            if (entry.icon != null) {
                icon.setImageDrawable(entry.icon);
            } else {
                icon.setVisibility(View.GONE);
            }
            label.setText(entry.label);
            packageName.setText(entry.packageName);
            selected.setVisibility(entry.packageName.equals(mSelectedSuManagerPackage)
                    ? View.VISIBLE : View.GONE);

            item.setOnClickListener(v -> {
                popupDismiss();
                if (isSuManagerLocked()) {
                    updateRunButton();
                    return;
                }
                selectSuManager(entry, suManagerPrefs);
            });
            options.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int popupHeight = Math.min(dp(360), suManagers.size() * dp(64) + dp(16));
        PopupWindow popup = new PopupWindow(surface, suManagerRow.getWidth(), popupHeight, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        popup.setClippingEnabled(true);
        suManagerPopup = popup;
        popup.setOnDismissListener(() -> {
            if (suManagerPopup == popup) suManagerPopup = null;
        });

        suManagerRow.post(() -> {
            if (!isFinishing() && !isDestroyed() && suManagerRow.isShown() && !isSuManagerLocked()) {
                popup.showAsDropDown(suManagerRow, 0, dp(6));
            }
        });
    }

    private void popupDismiss() {
        if (suManagerPopup != null) {
            suManagerPopup.dismiss();
            suManagerPopup = null;
        }
    }

    private void selectSuManager(SuManagerEntry entry, SharedPreferences prefs) {
        if (isSuManagerLocked()) return;
        if (!prefs.edit().putString(ExploitRunner.PREF_SU_MANAGER, entry.packageName).commit()) {
            Log.e(TAG, "failed to save selected SU manager");
            Toast.makeText(this, "Could not save SU manager selection", Toast.LENGTH_LONG).show();
            return;
        }
        mSelectedSuManagerPackage = entry.packageName;
        setSuManagerUi(entry);
        updateRunButton();
    }

    private void setSuManagerUi(SuManagerEntry entry) {
        mSelectedSuManagerPackage = entry.packageName;
        suManagerSubtitle.setText(entry.label + " (" + entry.packageName + ")");
    }

    private void refreshSuManagerSelection() {
        String saved = suManagerPrefs.getString(ExploitRunner.PREF_SU_MANAGER, null);
        for (SuManagerEntry entry : suManagers) {
            if (entry.packageName.equals(saved)) {
                setSuManagerUi(entry);
                return;
            }
        }
        if (saved != null && !suManagerPrefs.edit()
                .remove(ExploitRunner.PREF_SU_MANAGER).commit()) {
            Log.e(TAG, "failed to clear invalid SU manager preference");
        }
        mSelectedSuManagerPackage = null;
        suManagerSubtitle.setText("Not selected - required");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int color(int attr) {
        return MaterialColors.getColor(this, attr, Color.GRAY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSuManagerSelection();
        updateRunButton();
    }

    private void configureSettings(SharedPreferences prefs) {
        ComponentName bootReceiver = new ComponentName(this, BootReceiver.class);
        switchBootStart.setChecked(getPackageManager().getComponentEnabledSetting(bootReceiver)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        switchBootStart.setOnCheckedChangeListener((button, enabled) ->
                getPackageManager().setComponentEnabledSetting(bootReceiver,
                        enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP));

        switchSoftReboot.setChecked(prefs.getBoolean(ExploitRunner.PREF_SOFT_REBOOT, false));
        switchSoftReboot.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean(ExploitRunner.PREF_SOFT_REBOOT, enabled).apply());

        switchDisableModules.setChecked(prefs.getBoolean("disable_modules", false));
        switchDisableModules.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean("disable_modules", enabled).apply());
    }

    private void updateRunButton() {
        boolean suManagerLocked = isSuManagerLocked();
        suManagerRow.setClickable(!suManagerLocked);
        suManagerRow.setFocusable(!suManagerLocked);
        suManagerRow.setAlpha(suManagerLocked ? 0.55f : 1f);
        if (suManagerLocked && suManagerPopup != null) suManagerPopup.dismiss();
        updateLaunchCard();
    }

    private void updateLaunchCard() {
        boolean active = isRootActive();
        boolean incomplete = hasIncompleteAttempt();
        int cardColor;
        int titleColor;
        int subtitleColor;
        int iconBackgroundColor;
        int iconColor;

        if (isLaunching) {
            cardColor = color(com.google.android.material.R.attr.colorSecondaryContainer);
            titleColor = color(com.google.android.material.R.attr.colorOnSecondaryContainer);
            subtitleColor = titleColor;
            iconBackgroundColor = color(com.google.android.material.R.attr.colorSecondary);
            iconColor = color(com.google.android.material.R.attr.colorOnSecondary);
            launchTitle.setText("Launching root…");
            launchSubtitle.setText("Running the exploit, please wait");
            launchIcon.setVisibility(View.INVISIBLE);
            launchProgress.setVisibility(View.VISIBLE);
            launchProgress.setIndicatorColor(iconColor);
            launchCard.setClickable(false);
            launchCard.setFocusable(false);
        } else if (active) {
            cardColor = color(com.google.android.material.R.attr.colorPrimaryContainer);
            titleColor = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
            subtitleColor = titleColor;
            iconBackgroundColor = color(androidx.appcompat.R.attr.colorPrimary);
            iconColor = color(com.google.android.material.R.attr.colorOnPrimary);
            launchTitle.setText("Root active");
            launchSubtitle.setText("Root access is ready - tap to open");
            launchIcon.setImageResource(R.drawable.ic_check);
            launchIcon.setVisibility(View.VISIBLE);
            launchProgress.setVisibility(View.GONE);
            launchCard.setClickable(true);
            launchCard.setFocusable(true);
        } else if (launchFailed || incomplete) {
            cardColor = color(com.google.android.material.R.attr.colorErrorContainer);
            titleColor = color(com.google.android.material.R.attr.colorOnErrorContainer);
            subtitleColor = titleColor;
            iconBackgroundColor = color(android.R.attr.colorError);
            iconColor = color(com.google.android.material.R.attr.colorOnError);
            launchTitle.setText(incomplete ? "Root incomplete" : "Launch failed");
            String message = launchError;
            if (message == null) {
                message = incomplete
                        ? "Previous attempt did not finish. Reboot required."
                        : "The exploit failed. Tap to retry.";
            }
            launchSubtitle.setText(message);
            launchIcon.setImageResource(R.drawable.ic_error);
            launchIcon.setVisibility(View.VISIBLE);
            launchProgress.setVisibility(View.GONE);
            launchCard.setClickable(true);
            launchCard.setFocusable(true);
        } else {
            cardColor = color(com.google.android.material.R.attr.colorSurfaceContainerHighest);
            titleColor = color(com.google.android.material.R.attr.colorOnSurface);
            subtitleColor = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
            iconBackgroundColor = color(com.google.android.material.R.attr.colorPrimaryContainer);
            iconColor = color(com.google.android.material.R.attr.colorOnPrimaryContainer);
            launchTitle.setText("Launch Root");
            launchSubtitle.setText(mSelectedSuManagerPackage == null
                    ? "Select an SU Manager to launch"
                    : "Tap to run the exploit");
            launchIcon.setImageResource(R.drawable.ic_power);
            launchIcon.setVisibility(View.VISIBLE);
            launchProgress.setVisibility(View.GONE);
            launchCard.setClickable(true);
            launchCard.setFocusable(true);
        }

        launchCard.setCardBackgroundColor(cardColor);
        launchTitle.setTextColor(titleColor);
        launchSubtitle.setTextColor(subtitleColor);
        launchIconContainer.setBackgroundTintList(ColorStateList.valueOf(iconBackgroundColor));
        launchIcon.setImageTintList(ColorStateList.valueOf(iconColor));
    }

    private boolean isRootActive() {
        return ExploitRunner.isRootActive();
    }

    private boolean hasIncompleteAttempt() {
        return ExploitRunner.hasIncompleteAttempt();
    }

    private boolean isSuManagerLocked() {
        return isLaunching || isRootActive();
    }

    private void launchRoot() {
        if (isLaunching || isRootActive() || mSelectedSuManagerPackage == null) return;
        launchFailed = false;
        launchError = null;
        isLaunching = true;
        updateRunButton();
        outputView.setText("");
        logEmpty.setVisibility(View.GONE);
        mExec.execute(this::runExploit);
    }

    private void runExploit() {
        try {
            int rc = ExploitRunner.run(mDeCtx, this);
            if (rc == ExploitRunner.RESULT_OK) {
                mMain.post(() -> {
                    launchFailed = false;
                    launchError = null;
                    updateRunButton();
                });
                return;
            }

            String reason = describeFailure(rc);
            reason += hasIncompleteAttempt()
                    ? " Reboot required before retrying."
                    : " Tap to retry.";
            String finalReason = reason;
            report("\nDslice: " + reason + "\n");
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchFailed = true;
                launchError = finalReason;
                updateRunButton();
            });
        } catch (Exception e) {
            Log.e(TAG, "exploit exception", e);
            report("\nexception: " + e + "\n");
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                launchFailed = true;
                launchError = "Unexpected error. Tap to retry.";
                updateRunButton();
            });
        } finally {
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                isLaunching = false;
                updateRunButton();
            });
        }
    }

    private String describeFailure(int rc) {
        switch (rc) {
            case ExploitRunner.RESULT_KSUD_ERROR:
                return "KernelSU failed to start.";
            case ExploitRunner.RESULT_TIMEOUT:
                return "Timed out while starting KernelSU.";
            case ExploitRunner.RESULT_PATCH_FAILED:
                return "Could not patch the required files.";
            case ExploitRunner.RESULT_INCOMPLETE:
                return "A previous attempt did not finish.";
            case ExploitRunner.RESULT_NO_MANAGER:
                return "Select an SU Manager first.";
            case ExploitRunner.RESULT_MANAGER_UNAVAILABLE:
                return "The selected SU Manager is unavailable.";
            default:
                return "The exploit failed (code " + rc + ").";
        }
    }

    private void showRebootRequiredDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Reboot required")
                .setMessage("The previous attempt did not finish loading KernelSU. "
                        + "Reboot the device to clear the partial hook before trying again.")
                .setPositiveButton("OK", null)
                .show();
    }

    private static class SuManagerEntry {
        final String packageName;
        final CharSequence label;
        final Drawable icon;

        SuManagerEntry(String pkg, CharSequence label, Drawable icon) {
            this.packageName = pkg;
            this.label = label;
            this.icon = icon;
        }
    }
}
