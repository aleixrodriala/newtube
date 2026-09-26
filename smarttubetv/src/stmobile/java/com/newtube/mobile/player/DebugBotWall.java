package com.newtube.mobile.player;

import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

/**
 * NEWTUBE(botwall): DEBUG-only simulation of YouTube's bot wall, the sibling of
 * {@link DebugHostBlackhole}. The chosen /player answers are replaced, AFTER the real request was
 * made, by the verdict a walled network gets - LOGIN_REQUIRED "Sign in to confirm you're not a
 * bot" with no media - so the wall memory, the account route and the short walled walk can be
 * exercised on demand on a network YouTube is not actually walling.
 *
 * <pre>
 *   adb shell setprop debug.arc.botwall anon        # every answer to a request WITHOUT the account
 *   adb shell setprop debug.arc.botwall all         # every answer, account route included
 *   adb shell setprop debug.arc.botwall VISIONOS,WEB  # exactly these clients
 *   adb shell setprop debug.arc.botwall none        # off (setprop cannot reliably store "")
 * </pre>
 *
 * <p>Read on every /player answer, so a change applies to the next request without a restart.
 * Each injected answer logs {@code debug-botwall client= mode= auth= realStatus=}. Release builds
 * never install it: {@code VideoInfoService} then pays one null volatile read per answer.</p>
 */
public final class DebugBotWall {
    static final String PROP = "debug.arc.botwall";

    private DebugBotWall() {
    }

    /** Call once from the Application, inside a {@code BuildConfig.DEBUG} block. */
    public static void install() {
        VideoInfoService.setDebugBotWallSource(() -> DebugMediaShaper.prop(PROP));
    }
}
