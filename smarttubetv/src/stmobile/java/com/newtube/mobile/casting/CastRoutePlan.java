package com.newtube.mobile.casting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Consumes each route once. Explicit choices have one entry and never switch apps. */
final class CastRoutePlan {
    private final List<CastTarget> mRemaining;

    CastRoutePlan(List<CastTarget> routes) {
        mRemaining = new ArrayList<>(routes);
        mRemaining.sort(Comparator.comparingInt(CastDeviceRegistry::priority));
    }

    boolean hasNext() { return !mRemaining.isEmpty(); }
    CastTarget next() { return mRemaining.remove(0); }
}
