package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Remaps two keys of the Xiaomi Bluetooth Voice Remote (小米蓝牙语音遥控器) that are dead on
 * Sony sets, and on any firmware whose key layout cannot be fixed system-wide.
 *
 * Home: the remote sends HID usage 0x4a (Home) as Linux key 102, which Generic.kl maps to
 * KEYCODE_MOVE_HOME. That code is only meaningful to a launcher, so with any third-party launcher
 * installed the key does nothing inside other apps. Sony key layouts live on a read-only partition
 * and this firmware ignores key layouts shipped inside an APK, so the only way to fix it without
 * root is to capture the key and perform the platform's own global action - which is exactly what
 * the real HOME key does.
 *
 * TV key: the remote's unlabelled "TV" key sends HID usage 0x35 as Linux key 41, which maps to
 * KEYCODE_GRAVE. Sony's GlobalKeyManager swallows that key, so no app ever sees it. This service
 * gives it a job that matches its label:
 *   - exactly one HDMI port reports connected  -> switch straight to that input;
 *   - otherwise, when not on the home screen   -> go home, where the Inputs pill waits;
 *   - on the home screen with no clear input   -> open the launcher's input picker.
 *
 * Sony quirk worth knowing: the port id and the label disagree (HW5 carries "HDMI 4" here), so the
 * switch is done by input id, not by counting ports. When more than one port reports connected the
 * answer is treated as ambiguous and the key falls back to going home rather than guessing.
 *
 * Verified on a Sony BRAVIA XR-75X90J (BRAVIA_VH2_M_CN, Android 12) with the real remote: Home
 * returns from any app, the TV key lands on the connected HDMI input. ADB cannot be used to test
 * the TV key, because `input keyevent 41` is swallowed by Sony's global-key handling before it
 * reaches anything - press the real key when testing.
 *
 * Enable over adb (build-and-install.sh does this with MI_REMOTE_SERVICE=1):
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.MiRemoteKeyService
 *   settings put secure accessibility_enabled 1
 */
public class MiRemoteKeyService extends AccessibilityService {

    private static final String TAG = "MiRemoteKeys";
    /** Home launchers: pressing the TV key here should offer the input list. */
    private static final String HOME_PKG = "com.gathin.tvhome";

    /** Where the last window-state change put us; refreshed constantly, no polling. */
    private volatile String foregroundPkg = HOME_PKG;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MOVE_HOME:            // 102: what the remote's Home key sends
                performGlobalAction(GLOBAL_ACTION_HOME);
                return true;
            case KeyEvent.KEYCODE_GRAVE:                // 41: the remote's TV key
                return handleTvKey();
            default:
                return false;
        }
    }

    private boolean handleTvKey() {
        String connected = singleConnectedHdmi();
        if (connected != null) {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(connected),
                            "vnd.android.cursor.item/channel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "switch to " + connected + " failed, falling back to home", e);
            }
        }
        if (!HOME_PKG.equals(foregroundPkg)) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            return true;
        }
        Intent i = new Intent();
        i.setClassName(HOME_PKG, "com.gathin.tvhome.InputsActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "input picker failed", e);
        }
        return true;
    }

    /**
     * The one HDMI port that reports connected, or null when the answer is ambiguous. Some Sony
     * firmware reports every port connected whether or not anything is plugged in, which is
     * exactly the ambiguous case.
     */
    private String singleConnectedHdmi() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return null;
        List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
        String found = null;
        int count = 0;
        for (TvInputInfo info : inputs) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
            if (tim.getInputState(info.getId()) != TvInputManager.INPUT_STATE_CONNECTED) continue;
            count++;
            if (found == null || info.getId().compareTo(found) < 0) found = info.getId();
        }
        return count == 1 ? found : null;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p != null) foregroundPkg = p.toString();
    }

    @Override public void onInterrupt() { }
}
