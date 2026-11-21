package com.newtube.mobile.auto;

import android.Manifest;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.ArrayList;
import java.util.List;

/** NEWTUBE(android-auto): one Android Auto connection - surface, parked/moving gate and the screens. */
public class AutoSession extends Session {
    private static int sActive;

    /** A car screen (the navigation-category app) is open right now. */
    static boolean isActive() {
        return sActive > 0;
    }

    private AutoSurfaceRenderer mRenderer;
    private DrivingState mDriving;
    private boolean mAskedPermissions;

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        AutoPlayer player = AutoPlayer.get(getCarContext());
        sActive++;
        mRenderer = new AutoSurfaceRenderer(getCarContext());
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(mRenderer);

        mDriving = new DrivingState(getCarContext(), state -> {
            // "Vídeo sempre ligado" overrides the gate: speed no longer matters.
            player.setVideoAllowed(AutoPrefs.isVideoAlwaysOn(getCarContext())
                    || state == DrivingState.State.PARKED);
            mRenderer.setParkedHint(hintFor(state));
        });
        if (AutoPrefs.isVideoAlwaysOn(getCarContext())) {
            // Gate off: video always, and no speed/location is needed or asked for.
            player.setVideoAllowed(true);
            mRenderer.setParkedHint("");
        } else {
            mDriving.start();
            mRenderer.setParkedHint(hintFor(DrivingState.State.UNKNOWN));
        }

        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner owner) {
                sActive = Math.max(0, sActive - 1);
                mDriving.stop();
                mRenderer.release();
                player.setVideoAllowed(false);
                player.release();
            }
        });
        return new AutoHomeScreen(getCarContext(), this);
    }

    /** Asks once per connection for what tells parked from moving (on the phone). */
    public void askForDrivingPermissions() {
        askForDrivingPermissions(false);
    }

    /** {@code force}: the driver asked for video, so ask again even if once was refused. */
    public void askForDrivingPermissions(boolean force) {
        // With the gate off ("Vídeo sempre ligado") nothing is needed to show video.
        if (AutoPrefs.isVideoAlwaysOn(getCarContext())) return;
        if (mAskedPermissions && !force) return;
        mAskedPermissions = true;
        List<String> missing = new ArrayList<>();
        if (!mDriving.hasCarSpeedPermission()) missing.add(DrivingState.PERMISSION_CAR_SPEED);
        if (!mDriving.hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (missing.isEmpty()) {
            mDriving.start();
            mRenderer.setParkedHint(hintFor(mDriving.getState()));
            askForBackgroundLocation();
            return;
        }
        try {
            getCarContext().requestPermissions(missing, (granted, rejected) -> {
                mDriving.start();
                mRenderer.setParkedHint(hintFor(mDriving.getState()));
                askForBackgroundLocation();
            });
        } catch (Throwable ignored) {
            // Very old hosts: the phone's app settings remain the way to grant them.
        }
    }

    /**
     * With Android Auto on, the phone app is in the background, and from Android 10 a background
     * app gets no GPS without "Allow all the time". Asked separately, as Android requires, and
     * only once the plain location permission is there.
     */
    private void askForBackgroundLocation() {
        if (android.os.Build.VERSION.SDK_INT < 29 || !mDriving.hasLocationPermission()
                || mDriving.hasBackgroundLocationPermission()) {
            return;
        }
        try {
            getCarContext().requestPermissions(
                    java.util.Collections.singletonList(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    (granted, rejected) -> mDriving.start());
        } catch (Throwable ignored) {
        }
    }

    public AutoSurfaceRenderer getRenderer() {
        return mRenderer;
    }

    public DrivingState getDrivingState() {
        return mDriving;
    }

    public String hintFor(DrivingState.State state) {
        switch (state) {
            case PARKED:
                return "";
            case MOVING:
                return "A conduzir: só o som. O vídeo aparece com o carro parado.";
            default:
                return mDriving != null && mDriving.hasAnySource()
                        ? "À espera de saber se o carro está parado…"
                        : "Para ver vídeo parado, dá à app acesso à localização no telemóvel.";
        }
    }
}
