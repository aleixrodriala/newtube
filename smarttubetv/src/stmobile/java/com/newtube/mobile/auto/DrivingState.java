package com.newtube.mobile.auto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.common.OnCarDataAvailableListener;
import androidx.car.app.hardware.info.CarInfo;
import androidx.car.app.hardware.info.Speed;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

/**
 * NEWTUBE(android-auto): tells "parked" from "moving" for the video gate. Two sources, either is enough:
 * the car's own speed (Android Auto, permission CAR_SPEED) and the phone's GPS speed (location
 * permission). With no source reporting, the state is UNKNOWN and the car gets sound only.
 *
 * <p>Parked = every live source says under {@link #PARKED_MPS} for {@link #PARKED_HOLD_MS}.
 * Moving = any source over {@link #MOVING_MPS}; that cuts the video at once.
 */
public final class DrivingState {
    public static final String PERMISSION_CAR_SPEED = "com.google.android.gms.permission.CAR_SPEED";

    public enum State { UNKNOWN, PARKED, MOVING }

    public interface Listener {
        void onDrivingStateChanged(State state);
    }

    private static final float PARKED_MPS = 0.6f;   // ~2 km/h
    private static final float MOVING_MPS = 1.5f;   // ~5 km/h
    private static final long PARKED_HOLD_MS = 2_000;
    // The car reports speed on change: parked, it may say "0" once and then nothing. So the car's
    // last value holds for as long as its listener is registered. GPS fixes come every second
    // when they come at all, so a silent GPS is treated as no source after a while.
    private static final long GPS_STALE_MS = 30_000;

    private final CarContext mCarContext;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mMainExecutor;

    private State mState = State.UNKNOWN;
    private float mCarSpeed = -1;
    private long mCarSpeedAt;
    private float mGpsSpeed = -1;
    private long mGpsSpeedAt;
    private long mSlowSince = -1;

    private OnCarDataAvailableListener<Speed> mSpeedListener;
    private LocationListener mLocationListener;
    private boolean mStarted;

    public DrivingState(CarContext carContext, Listener listener) {
        mCarContext = carContext;
        mListener = listener;
        mMainExecutor = ContextCompat.getMainExecutor(carContext);
    }

    public State getState() {
        return mState;
    }

    public boolean hasAnySource() {
        return hasCarSpeedPermission() || hasLocationPermission();
    }

    public boolean hasCarSpeedPermission() {
        return granted(PERMISSION_CAR_SPEED);
    }

    public boolean hasLocationPermission() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    public boolean hasBackgroundLocationPermission() {
        return android.os.Build.VERSION.SDK_INT < 29
                || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(mCarContext, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        stop();
        mStarted = true;
        mCarSpeed = -1;
        mGpsSpeed = -1;
        startCarSpeed();
        startGps();
        mHandler.postDelayed(mTick, 1_000);
    }

    public void stop() {
        mStarted = false;
        mHandler.removeCallbacks(mTick);
        if (mSpeedListener != null) {
            try {
                carInfo().removeSpeedListener(mSpeedListener);
            } catch (Throwable ignored) {
            }
            mSpeedListener = null;
        }
        if (mLocationListener != null) {
            LocationManager lm = (LocationManager) mCarContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(mLocationListener);
            mLocationListener = null;
        }
    }

    private CarInfo carInfo() {
        return mCarContext.getCarService(CarHardwareManager.class).getCarInfo();
    }

    private void startCarSpeed() {
        if (!hasCarSpeedPermission()) return;
        try {
            mSpeedListener = speed -> {
                float value = read(speed.getRawSpeedMetersPerSecond());
                if (value < 0) value = read(speed.getDisplaySpeedMetersPerSecond());
                if (value >= 0) {
                    mCarSpeed = Math.abs(value);
                    mCarSpeedAt = SystemClock.elapsedRealtime();
                    evaluate();
                }
            };
            carInfo().addSpeedListener(mMainExecutor, mSpeedListener);
            android.util.Log.i("NewTubeAuto", "car speed listener on");
        } catch (Throwable error) {
            // Older Android Auto hosts or cars without the speed property: GPS covers it.
            android.util.Log.w("NewTubeAuto", "car speed unavailable: " + error);
            mSpeedListener = null;
        }
    }

    private static float read(CarValue<Float> value) {
        if (value == null || value.getStatus() != CarValue.STATUS_SUCCESS || value.getValue() == null) {
            return -1;
        }
        return value.getValue();
    }

    @SuppressLint("MissingPermission")
    private void startGps() {
        if (!hasLocationPermission()) return;
        LocationManager lm = (LocationManager) mCarContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return;
        mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (!location.hasSpeed()) return;
                mGpsSpeed = location.getSpeed();
                mGpsSpeedAt = SystemClock.elapsedRealtime();
                evaluate();
            }

            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(@NonNull String provider) { }
            @Override public void onProviderDisabled(@NonNull String provider) { }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000, 0, mLocationListener,
                    Looper.getMainLooper());
        } catch (Throwable error) {
            mLocationListener = null;
        }
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            evaluate();
            if (mStarted) mHandler.postDelayed(this, 1_000);
        }
    };

    private void evaluate() {
        long now = SystemClock.elapsedRealtime();
        boolean carLive = mCarSpeed >= 0 && mSpeedListener != null;
        boolean gpsLive = mGpsSpeed >= 0 && now - mGpsSpeedAt < GPS_STALE_MS;

        State next;
        if (!carLive && !gpsLive) {
            next = State.UNKNOWN;
            mSlowSince = -1;
        } else if ((carLive && mCarSpeed > MOVING_MPS) || (gpsLive && mGpsSpeed > MOVING_MPS)) {
            next = State.MOVING;
            mSlowSince = -1;
        } else if ((!carLive || mCarSpeed < PARKED_MPS) && (!gpsLive || mGpsSpeed < PARKED_MPS)) {
            if (mSlowSince < 0) mSlowSince = now;
            next = now - mSlowSince >= PARKED_HOLD_MS ? State.PARKED
                    : (mState == State.PARKED ? State.PARKED : State.MOVING);
        } else {
            // Between the two thresholds: creeping. Keep what we had, never upgrade to parked.
            next = mState == State.PARKED ? State.PARKED : State.MOVING;
            mSlowSince = -1;
        }
        if (next != mState) {
            android.util.Log.i("NewTubeAuto", "driving " + mState + " -> " + next
                    + " car=" + (carLive ? mCarSpeed : "-") + " gps=" + (gpsLive ? mGpsSpeed : "-"));
            mState = next;
            mListener.onDrivingStateChanged(next);
        }
    }
}
