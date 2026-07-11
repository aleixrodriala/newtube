package android.text;

/**
 * Real (non-stub) implementation for local unit tests: the AGP mockable android.jar stubs every
 * method to throw, and Robolectric 4.6 (the pinned version) predates JDK 17 support. Test sources
 * precede the mockable jar on the unit-test classpath, so this shim wins. Only the methods the
 * DTO layer under test actually calls are implemented.
 */
public class TextUtils {
    public static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}
