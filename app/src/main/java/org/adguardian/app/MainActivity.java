package org.adguardian.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.EnumMap;
import java.util.Map;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.log.UserEventLog;
import org.adguardian.app.runtime.AccessibilityState;
import org.adguardian.app.runtime.RuntimeHealth;
import org.adguardian.app.service.AdAccessibilityService;
import org.adguardian.app.service.ProtectionService;
import org.adguardian.app.settings.PreferenceStore;

public final class MainActivity extends android.app.Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 41;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<AdType, Switch> typeSwitches = new EnumMap<>(AdType.class);
    private AccessibilityState.Watch statusWatch;
    private UserEventLog.Watch logWatch;
    private TextView permissionStatus;
    private TextView runtimeStatus;
    private TextView backgroundStatus;
    private TextView eventLogView;
    private Switch protectionSwitch;
    private Switch keepAliveSwitch;
    private Switch liTiaotiaoSwitch;
    private Switch gkdSwitch;
    private Switch sdkSwitch;
    private Switch ocrSwitch;
    private boolean changingSwitches;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean taskWasRemoved = PreferenceStore.consumeTaskRemoved(this);
        if (statusWatch == null) {
            statusWatch = new AccessibilityState.Watch(this, () -> {
                updatePermissionStatus();
                updateBackgroundStatus();
                syncSwitches();
            });
        }
        if (logWatch == null) logWatch = new UserEventLog.Watch(this, this::refreshEventLog);
        ProtectionService.sync(this);
        updatePermissionStatus();
        updateBackgroundStatus();
        syncSwitches();
        refreshEventLog();
        if (taskWasRemoved) showTaskRemovedNotice();
    }

    @Override
    protected void onPause() {
        if (statusWatch != null) statusWatch.close();
        statusWatch = null;
        if (logWatch != null) logWatch.close();
        logWatch = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (logWatch != null) logWatch.close();
        logWatch = null;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("去你的广告", 28, true));
        TextView subtitle = text("本地广告拦截", 15, false);
        subtitle.setTextColor(Color.rgb(85, 85, 85));
        root.addView(subtitle, marginTop(dp(4)));

        permissionStatus = text("", 15, true);
        runtimeStatus = text("", 14, false);
        root.addView(permissionStatus, marginTop(dp(22)));
        root.addView(runtimeStatus, marginTop(dp(6)));

        Button enableAll = button("一键开启广告拦截");
        enableAll.setTextSize(17);
        enableAll.setOnClickListener(v -> enableEverything());
        root.addView(enableAll, marginTop(dp(12)));

        Button permission = button("打开辅助功能设置");
        permission.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(permission, marginTop(dp(8)));

        backgroundStatus = text("", 13, false);
        backgroundStatus.setTextColor(Color.rgb(95, 95, 95));
        root.addView(backgroundStatus, marginTop(dp(8)));

        root.addView(sectionTitle("运行方式"), marginTop(dp(20)));
        keepAliveSwitch = optionSwitch("显示保护通知",
                PreferenceStore.isKeepAliveEnabled(this));
        keepAliveSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setKeepAliveEnabled(this, enabled);
            if (enabled) requestNotificationPermission();
            settingsChanged();
        });
        root.addView(keepAliveSwitch, marginTop(dp(2)));

        TextView backgroundHint = text(
                "必须保留最近任务中的后台窗口才可持续运行。划掉后台窗口后，请重新打开本软件并检查辅助功能；未连接时先关闭后重新开启辅助功能。",
                13, false);
        backgroundHint.setTextColor(Color.rgb(95, 95, 95));
        root.addView(backgroundHint, marginTop(dp(4)));

        Button terms = smallButton("使用范围与限制");
        terms.setOnClickListener(v -> showUsageTerms());
        root.addView(terms, marginTop(dp(6)));

        root.addView(sectionTitle("总开关"), marginTop(dp(20)));
        protectionSwitch = optionSwitch("广告拦截", PreferenceStore.isMasterEnabled(this));
        protectionSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setMasterEnabled(this, enabled);
            settingsChanged();
        });
        root.addView(protectionSwitch, marginTop(dp(4)));

        root.addView(sectionTitle("识别方式"), marginTop(dp(20)));
        liTiaotiaoSwitch = optionSwitch("LTT 核心", PreferenceStore.isLiTiaotiaoEnabled(this));
        liTiaotiaoSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setLiTiaotiaoEnabled(this, enabled);
            settingsChanged();
        });
        root.addView(liTiaotiaoSwitch, marginTop(dp(2)));

        gkdSwitch = optionSwitch("GKD 核心", PreferenceStore.isGkdEnabled(this));
        gkdSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setGkdEnabled(this, enabled);
            settingsChanged();
        });
        root.addView(gkdSwitch, marginTop(dp(2)));

        sdkSwitch = optionSwitch("广告组件特征识别", PreferenceStore.isSdkEnabled(this));
        sdkSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setSdkEnabled(this, enabled);
            settingsChanged();
        });
        root.addView(sdkSwitch, marginTop(dp(2)));

        ocrSwitch = optionSwitch("视觉补充识别", PreferenceStore.isOcrEnabled(this));
        ocrSwitch.setOnCheckedChangeListener((view, enabled) -> {
            if (changingSwitches) return;
            PreferenceStore.setOcrEnabled(this, enabled);
            settingsChanged();
        });
        root.addView(ocrSwitch, marginTop(dp(2)));
        TextView ocrHint = text("识别过程在本机完成，不上传截图", 13, false);
        ocrHint.setTextColor(Color.rgb(95, 95, 95));
        root.addView(ocrHint, marginTop(dp(4)));

        root.addView(sectionTitle("广告类型"), marginTop(dp(20)));
        for (AdType type : AdType.values()) {
            Switch typeSwitch = optionSwitch(type.displayName(), PreferenceStore.isTypeEnabled(this, type));
            typeSwitch.setOnCheckedChangeListener((view, enabled) -> {
                if (changingSwitches) return;
                PreferenceStore.setTypeEnabled(this, type, enabled);
                settingsChanged();
            });
            typeSwitches.put(type, typeSwitch);
            root.addView(typeSwitch, marginTop(dp(2)));
            if (type == AdType.JUMP) {
                root.addView(text("识别广告引起的应用跳转，重点覆盖淘宝等购物应用。", 12, false), marginTop(dp(1)));
            } else if (type == AdType.SHAKE) {
                root.addView(text("自动关闭可识别的摇一摇广告，不更改手机传感器设置。", 12, false), marginTop(dp(1)));
            }
        }

        TextView privacy = text("所有识别均在本机完成", 13, false);
        privacy.setTextColor(Color.rgb(95, 95, 95));
        root.addView(privacy, marginTop(dp(12)));

        root.addView(sectionTitle("拦截记录"), marginTop(dp(24)));
        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(logButtons, marginTop(dp(8)));

        Button refresh = smallButton("刷新");
        refresh.setOnClickListener(v -> refreshEventLog());
        logButtons.addView(refresh, weightedButtonParams());

        Button clear = smallButton("清空");
        clear.setOnClickListener(v -> {
            UserEventLog.clear(this);
            refreshEventLog();
        });
        logButtons.addView(clear, weightedButtonParams());

        eventLogView = text("暂无拦截记录", 12, false);
        eventLogView.setTextIsSelectable(true);
        eventLogView.setTextColor(Color.rgb(35, 35, 35));
        eventLogView.setBackgroundColor(Color.rgb(242, 242, 242));
        eventLogView.setPadding(dp(10), dp(10), dp(10), dp(10));
        eventLogView.setMinHeight(dp(130));
        root.addView(eventLogView, marginTop(dp(8)));
        return scroll;
    }

    private void enableEverything() {
        PreferenceStore.enableAllAds(this);
        syncSwitches();
        settingsChanged();
        AccessibilityState.Snapshot state = AccessibilityState.read(this);
        if (state.known && !state.authorized) {
            Toast.makeText(this, "请允许辅助功能权限", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        requestNotificationPermission();
        Toast.makeText(this, state.running() ? "广告拦截已开启" : "请检查辅助功能是否已开启",
                Toast.LENGTH_SHORT).show();
    }

    private void settingsChanged() {
        AdAccessibilityService.notifySettingsChanged();
        ProtectionService.sync(this);
        updatePermissionStatus();
        updateBackgroundStatus();
    }

    private void syncSwitches() {
        changingSwitches = true;
        if (protectionSwitch != null) protectionSwitch.setChecked(PreferenceStore.isMasterEnabled(this));
        if (keepAliveSwitch != null) keepAliveSwitch.setChecked(PreferenceStore.isKeepAliveEnabled(this));
        if (liTiaotiaoSwitch != null) liTiaotiaoSwitch.setChecked(PreferenceStore.isLiTiaotiaoEnabled(this));
        if (gkdSwitch != null) gkdSwitch.setChecked(PreferenceStore.isGkdEnabled(this));
        if (sdkSwitch != null) sdkSwitch.setChecked(PreferenceStore.isSdkEnabled(this));
        if (ocrSwitch != null) ocrSwitch.setChecked(PreferenceStore.isOcrEnabled(this));
        for (Map.Entry<AdType, Switch> entry : typeSwitches.entrySet()) {
            entry.getValue().setChecked(PreferenceStore.isTypeEnabled(this, entry.getKey()));
        }
        changingSwitches = false;
    }

    private void updatePermissionStatus() {
        AccessibilityState.Snapshot state = AccessibilityState.read(this);
        if (permissionStatus != null) permissionStatus.setText(state.permissionText());
        if (runtimeStatus != null) runtimeStatus.setText(state.runtimeText());
    }

    private void updateBackgroundStatus() {
        if (backgroundStatus == null) return;
        if (PreferenceStore.isTaskRemoved(this)) {
            backgroundStatus.setText("后台窗口已被关闭，当前处理已暂停");
        } else if (ProtectionService.isRunning()) {
            backgroundStatus.setText("保护通知已开启，请保留后台窗口");
        } else {
            backgroundStatus.setText("请保留最近任务窗口，并允许状态通知");
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException unavailable) {
            RuntimeHealth.failure(this, "辅助功能设置入口不可用", unavailable);
            Toast.makeText(this, "请从系统设置中打开辅助功能", Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void showUsageTerms() {
        new AlertDialog.Builder(this)
                .setTitle("使用范围与限制")
                .setMessage("本软件用于自动跳过可识别的广告。约 70% 为覆盖预估，并非实测成功率或保证；部分广告可能无法处理。\n\n"
                        + "必须保留最近任务中的后台窗口才可持续运行。划掉后台窗口后，请重新打开本软件并检查辅助功能允许状态；如果服务未连接，请先关闭后重新开启辅助功能。\n\n"
                        + "发现可关闭的广告后立即处理。受广告形式、应用更新和手机系统影响，部分广告仍会短暂显示。\n\n"
                        + "摇一摇广告功能用于关闭广告页面，不能禁止其他应用使用陀螺仪。\n\n"
                        + "拦截记录会区分已执行的关闭操作与未能确认的结果。所有识别与记录均保存在本机。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showTaskRemovedNotice() {
        new AlertDialog.Builder(this)
                .setTitle("请重新检查辅助功能")
                .setMessage("后台窗口已被划掉，持续处理已停止。请保留最近任务窗口，并检查辅助功能是否仍为允许和已连接；未连接时请先关闭后重新开启辅助功能。")
                .setPositiveButton("打开辅助功能设置", (dialog, which) -> openAccessibilitySettings())
                .setNegativeButton("稍后", null)
                .show();
    }

    private void refreshEventLog() {
        if (eventLogView != null) eventLogView.setText(UserEventLog.read(this));
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 19, true);
        view.setTextColor(Color.rgb(25, 25, 25));
        return view;
    }

    private Switch optionSwitch(String title, boolean checked) {
        Switch view = new Switch(this);
        view.setText(title);
        view.setTextSize(16);
        view.setChecked(checked);
        return view;
    }

    private Button button(String title) {
        Button view = new Button(this);
        view.setText(title);
        view.setAllCaps(false);
        return view;
    }

    private Button smallButton(String title) {
        Button view = button(title);
        view.setTextSize(14);
        return view;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = value;
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(3);
        params.rightMargin = dp(3);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
