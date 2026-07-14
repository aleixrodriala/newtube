package com.newtube.mobile;

import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

/** Enables annotation-discovered Glide library modules, including the animated-WebP decoder. */
@GlideModule
public final class NewTubeGlideModule extends AppGlideModule {
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
