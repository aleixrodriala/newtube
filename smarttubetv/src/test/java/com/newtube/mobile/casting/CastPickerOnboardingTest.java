package com.newtube.mobile.casting;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import com.liskovsoft.smartyoutubetv2.tv.R;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Renders the real sheets with discovery and casting replaced by local fakes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
public class CastPickerOnboardingTest {
    private Activity activity;
    private FakeManager manager;
    private CastPickerSheet picker;
    private BottomSheetDialog sheet;

    @Before public void setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("newtube_cast", Context.MODE_PRIVATE)
                .edit().clear().commit();
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setTheme(R.style.Theme_NewTube);
        manager = new FakeManager(activity);
    }

    @After public void tearDown() {
        if (sheet != null) sheet.dismiss();
        activity.finish();
        RxJavaPlugins.reset();
    }

    private void show() {
        picker = new CastPickerSheet(activity, manager) {
            @Override void startDiscovery() {} // Never contacts a phone, TV, or local network.
        };
        picker.show(dialog -> { sheet = dialog; dialog.show(); });
    }

    @Test public void onboardingPairsSmarttubeAndDisappearsOnNextOpen() {
        show();
        assertEquals(View.VISIBLE, sheet.findViewById(R.id.cast_smarttube_prompt).getVisibility());
        sheet.findViewById(R.id.cast_sheet_link_code).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog code = (AlertDialog) ShadowDialog.getLatestDialog();
        RadioGroup apps = code.findViewById(R.id.cast_code_apps);
        assertEquals(-1, apps.getCheckedRadioButtonId());
        assertEquals(R.id.cast_code_app_smarttube, apps.getChildAt(0).getId());
        apps.check(R.id.cast_code_app_smarttube);
        assertEquals(activity.getString(R.string.mobile_cast_code_open_smarttube),
                ((TextView) code.findViewById(R.id.cast_code_instruction)).getText().toString());
        assertEquals(activity.getString(R.string.mobile_cast_code_smarttube_path),
                ((TextView) code.findViewById(R.id.cast_code_path)).getText().toString());
        assertFalse(code.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        ((EditText) code.findViewById(R.id.cast_code_input)).setText("1234-5678-9012");
        assertTrue(code.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        code.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(CastPrefs.getPairedTargets(activity).get(0).isAdFree());
        show();
        assertEquals(View.GONE, sheet.findViewById(R.id.cast_smarttube_prompt).getVisibility());
        // Pairing another TV must still be possible after the first SmartTube pairing.
        assertTrue(sheet.findViewById(R.id.cast_sheet_link_code).isShown());
        sheet.findViewById(R.id.cast_sheet_link_code).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog nextCode = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(View.VISIBLE, nextCode.findViewById(R.id.cast_code_app_choice).getVisibility());
        nextCode.dismiss();
    }

    @Test public void genericPairingRequiresIdentifyingTheApp() {
        show();
        sheet.findViewById(R.id.cast_sheet_link_code).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog code = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(View.VISIBLE, code.findViewById(R.id.cast_code_app_choice).getVisibility());
        assertEquals(View.GONE, code.findViewById(R.id.cast_code_path).getVisibility());
        ((EditText) code.findViewById(R.id.cast_code_input)).setText("123456789012");
        assertFalse(code.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        ((RadioGroup) code.findViewById(R.id.cast_code_apps)).check(R.id.cast_code_app_youtube);
        assertTrue(code.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals(View.VISIBLE, code.findViewById(R.id.cast_code_path).getVisibility());
        assertEquals(activity.getString(R.string.mobile_cast_code_youtube_path),
                ((TextView) code.findViewById(R.id.cast_code_path)).getText().toString());
        ((RadioGroup) code.findViewById(R.id.cast_code_apps)).check(R.id.cast_code_app_smarttube);
        assertEquals(activity.getString(R.string.mobile_cast_code_smarttube_path),
                ((TextView) code.findViewById(R.id.cast_code_path)).getText().toString());
        ((EditText) code.findViewById(R.id.cast_code_input)).setText("123");
        assertFalse(code.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        code.dismiss();
    }

    @Test public void quickSavedYoutubeTapWaitsAndUpgradesToLateCastDiscovery() {
        saveYoutube();
        show();
        firstRow().performClick();
        assertEquals(0, manager.connections);
        picker.onTarget(CastTarget.fromCastDevice("TV", "192.0.2.1", 8009));
        assertEquals(1, manager.connections);
        assertEquals(CastTarget.Route.CAST_V2, manager.chosen.get(0).getRoute());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(1, manager.connections);
    }

    @Test public void dismissalCancelsThePendingSavedRowTap() {
        saveYoutube();
        show();
        firstRow().performClick();
        sheet.dismiss();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        picker.onTarget(CastTarget.fromCastDevice("TV", "192.0.2.1", 8009));
        assertEquals(0, manager.connections);
    }

    @Test public void savedYoutubeRemainsUsableWhenDiscoveryFindsNothing() {
        saveYoutube();
        show();
        firstRow().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CastPickerSheet.DISCOVERY_GRACE_MS));
        assertEquals(1, manager.connections);
        assertEquals(CastTarget.ReceiverApp.YOUTUBE, manager.chosen.get(0).getReceiverApp());
    }

    private void saveYoutube() {
        CastPrefs.addPairedScreen(activity, new CastScreen("yt", "TV"), CastTarget.ReceiverApp.YOUTUBE);
    }

    private View firstRow() {
        return ((LinearLayout) sheet.findViewById(R.id.cast_sheet_targets)).getChildAt(0);
    }

    private static final class FakeManager extends CastSessionManager {
        int connections;
        List<CastTarget> chosen;
        final CastSenderService sender = (CastSenderService) Proxy.newProxyInstance(
                CastSenderService.class.getClassLoader(), new Class<?>[]{CastSenderService.class},
                (proxy, method, args) -> Observable.just(new CastScreen("smart", "TV")));
        FakeManager(Context context) { super(context); }
        @Override public CastSenderService getSender() { return sender; }
        @Override public boolean connect(CastTarget target) {
            return connectWithFallback(Collections.singletonList(target));
        }
        @Override public boolean connectWithFallback(List<CastTarget> routes) {
            chosen = routes;
            connections++;
            return true;
        }
    }
}
