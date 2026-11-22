package com.newtube.mobile.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/**
 * Android Auto entry point.
 *
 * <p>Declared as a {@code NAVIGATION} app in the manifest because that is the only Car App
 * Library category whose template gets a drawing surface (and the only one the host keeps
 * available while the car is moving). The surface is used to render the live NewTube player, not
 * a map - see {@link CarVideoScreen}.</p>
 */
public class NewTubeCarAppService extends CarAppService {
    @NonNull
    @Override
    public HostValidator createHostValidator() {
        // Sideloaded app: Android Auto's host is not in the default allow-list used by Play apps.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new NewTubeCarSession();
    }
}
