package com.liskovsoft.smartyoutubetv2.tv.ui.main;

/**
 * External-intent trampoline: identical to {@link SplashActivity} (same presenter routing chain)
 * but declared in the manifest with its own ephemeral task ({@code taskAffinity=":router"},
 * {@code excludeFromRecents}, {@code noHistory}) so a relaunch of the same URL can never be
 * absorbed by Android's task root-intent dedupe. See the manifest comment for the full story.
 */
public class IntentRouterActivity extends SplashActivity {
}
