package com.newtube.mobile.auto;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.core.graphics.drawable.IconCompat;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(android-auto): the playing screen. A navigation template, so the car hands over its surface:
 * {@link AutoSurfaceRenderer} draws the video there (parked) or the cover and title (driving).
 */
public class AutoPlayerScreen extends Screen implements AutoPlayer.Listener {
    private final AutoSession mSession;
    private boolean mLastPlaying;
    private boolean mLastHasNext;
    private boolean mLastAudioOnly;
    private boolean mLastVideo;
    /** When the "video only parked" toast was last shown: a second press within seconds forces video on. */
    private long mVideoWarnedAt;

    public AutoPlayerScreen(@NonNull CarContext carContext, AutoSession session) {
        super(carContext);
        mSession = session;
        AutoPlayer player = AutoPlayer.get(carContext);
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                player.addListener(AutoPlayerScreen.this);
                if (!mSession.getDrivingState().hasAnySource()) {
                    mSession.askForDrivingPermissions();
                }
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                player.removeListener(AutoPlayerScreen.this);
            }
        });
    }

    @Override
    public void onAutoPlayerChanged() {
        AutoPlayer player = AutoPlayer.get(getCarContext());
        if (player.isPlaying() != mLastPlaying || player.hasNext() != mLastHasNext
                || player.isAudioOnlyByUser() != mLastAudioOnly || player.shouldShowVideo() != mLastVideo) {
            invalidate();
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        CarContext context = getCarContext();
        AutoPlayer player = AutoPlayer.get(context);
        mLastPlaying = player.isPlaying();
        mLastHasNext = player.hasNext();
        mLastAudioOnly = player.isAudioOnlyByUser();
        mLastVideo = player.shouldShowVideo();

        ActionStrip.Builder transport = new ActionStrip.Builder()
                .addAction(action(context, R.drawable.ic_auto_rewind, () -> player.seekBy(-10_000)))
                .addAction(new Action.Builder()
                        .setIcon(new CarIcon.Builder(IconCompat.createWithResource(context,
                                mLastPlaying ? R.drawable.ic_auto_pause : R.drawable.ic_auto_play))
                                .setTint(AutoHomeScreen.RED).build())
                        .setOnClickListener(() -> {
                            player.togglePlay();
                            mSession.getRenderer().pokeOverlay();
                        })
                        .build())
                .addAction(action(context, R.drawable.ic_auto_forward, () -> player.seekBy(10_000)));
        if (mLastHasNext) {
            transport.addAction(action(context, R.drawable.ic_auto_next, player::next));
        } else {
            transport.addAction(action(context, R.drawable.ic_auto_previous, player::previous));
        }

        ActionStrip side = new ActionStrip.Builder()
                .addAction(action(context, mLastAudioOnly ? R.drawable.ic_auto_video : R.drawable.ic_auto_audio,
                        this::toggleVideo))
                .addAction(action(context, R.drawable.ic_auto_home, () -> getScreenManager().pop()))
                .build();

        return new NavigationTemplate.Builder()
                .setActionStrip(transport.build())
                .setMapActionStrip(side)
                .setBackgroundColor(CarColor.SECONDARY)
                .build();
    }

    private void toggleVideo() {
        CarContext context = getCarContext();
        AutoPlayer player = AutoPlayer.get(context);
        DrivingState driving = mSession.getDrivingState();

        if (AutoPrefs.isVideoAlwaysOn(context)) {
            // Already forced: pressing again restores the parked gate.
            AutoPrefs.setVideoAlwaysOn(context, false);
            player.setAudioOnlyByUser(false);
            driving.start();
            player.setVideoAllowed(driving.getState() == DrivingState.State.PARKED);
            mSession.getRenderer().setParkedHint(mSession.hintFor(driving.getState()));
            CarToast.makeText(context, "Modo normal: vídeo só com o carro parado",
                    CarToast.LENGTH_LONG).show();
            return;
        }

        if (!player.isAudioOnlyByUser()) {
            // Video was showing (or about to): plain switch to audio only.
            player.setAudioOnlyByUser(true);
            return;
        }

        player.setAudioOnlyByUser(false);
        if (driving.getState() == DrivingState.State.PARKED) return;

        if (mVideoWarnedAt != 0 && SystemClock.elapsedRealtime() - mVideoWarnedAt < 6_000) {
            // Second press within seconds: the driver insists — video always on, at their own risk.
            AutoPrefs.setVideoAlwaysOn(context, true);
            driving.stop();
            player.setAudioOnlyByUser(false);
            player.setVideoAllowed(true);
            mSession.getRenderer().setParkedHint("");
            mVideoWarnedAt = 0;
            CarToast.makeText(context, "Vídeo sempre ligado (por tua conta e risco)",
                    CarToast.LENGTH_LONG).show();
            return;
        }

        mVideoWarnedAt = SystemClock.elapsedRealtime();
        if (!driving.hasAnySource()) {
            mSession.askForDrivingPermissions(true);
            CarToast.makeText(context,
                    "Sem acesso à velocidade não sei quando o carro está parado.\n"
                            + "Toca outra vez em Vídeo para ligar sempre (por tua conta e risco).",
                    CarToast.LENGTH_LONG).show();
        } else {
            CarToast.makeText(context,
                    "O vídeo só aparece com o carro parado.\n"
                            + "Toca outra vez em Vídeo para ligar sempre (por tua conta e risco).",
                    CarToast.LENGTH_LONG).show();
        }
    }

    private Action action(CarContext context, int icon, Runnable onClick) {
        return new Action.Builder()
                .setIcon(new CarIcon.Builder(IconCompat.createWithResource(context, icon)).build())
                .setOnClickListener(() -> {
                    onClick.run();
                    // Each press brings back the title and progress bar over the video.
                    if (mSession.getRenderer() != null) mSession.getRenderer().pokeOverlay();
                })
                .build();
    }
}
