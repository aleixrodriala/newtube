package android.util;

/**
 * Real (non-stub) implementation for local unit tests (see the android.text.TextUtils shim for
 * the rationale). Matches the framework's public surface used by TrackSelectorUtil.
 */
public class Pair<F, S> {
    public final F first;
    public final S second;

    public Pair(F first, S second) {
        this.first = first;
        this.second = second;
    }
}
