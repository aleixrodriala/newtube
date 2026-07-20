package com.newtube.mobile.casting.proxy;

import androidx.annotation.Nullable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Opaque segment tokens for the cast proxy: URL-safe base64 (no padding) of the original absolute
 * googlevideo URL. Self-contained by design - the server decodes a token back to its upstream URL
 * without consulting any per-video state, which is what keeps in-flight relays for a previous
 * video working across a {@code loadVideo} swap (see {@link CastProxyServer}).
 *
 * <p>Hand-rolled base64 on purpose: {@code java.util.Base64} needs API 26 (minSdk is 24) and
 * {@code android.util.Base64} is framework-only (dies in pure-JVM unit tests). Tokens are opaque
 * but NOT secrets - anyone on the LAN already received the same URLs inside the served manifest.</p>
 */
public final class SegmentToken {
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
    }

    private SegmentToken() {
    }

    /** URL-safe base64 (no padding) of the absolute upstream URL. */
    public static String encode(String url) {
        byte[] data = url.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= data.length) {
            int v = (data[i] & 0xFF) << 16 | (data[i + 1] & 0xFF) << 8 | (data[i + 2] & 0xFF);
            sb.append(ALPHABET[v >>> 18]).append(ALPHABET[(v >>> 12) & 0x3F])
                    .append(ALPHABET[(v >>> 6) & 0x3F]).append(ALPHABET[v & 0x3F]);
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int v = (data[i] & 0xFF) << 4;
            sb.append(ALPHABET[v >>> 6]).append(ALPHABET[v & 0x3F]);
        } else if (rem == 2) {
            int v = (data[i] & 0xFF) << 10 | (data[i + 1] & 0xFF) << 2;
            sb.append(ALPHABET[v >>> 12]).append(ALPHABET[(v >>> 6) & 0x3F]).append(ALPHABET[v & 0x3F]);
        }
        return sb.toString();
    }

    /** Decode a token back to the upstream URL. Null on any malformed input (server maps to 404). */
    @Nullable
    public static String decode(@Nullable String token) {
        if (token == null || token.isEmpty() || token.length() % 4 == 1) {
            return null;
        }
        byte[] out = new byte[token.length() * 3 / 4];
        int outLen = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int v = c < 128 ? REVERSE[c] : -1;
            if (v < 0) {
                return null;
            }
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[outLen++] = (byte) (acc >>> bits & 0xFF);
            }
        }
        return new String(out, 0, outLen, StandardCharsets.UTF_8);
    }

    /**
     * Open-relay guard: the proxy only relays hosts YouTube itself handed out - googlevideo media
     * edges and youtube.com (timedtext-style URLs). Anything else is refused even though the token
     * decoded fine (LAN peers could otherwise bounce arbitrary fetches through the phone).
     */
    public static boolean isAllowedUpstream(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String scheme;
        String host;
        try {
            URI uri = URI.create(url);
            scheme = uri.getScheme();
            host = uri.getHost();
        } catch (Exception e) {
            return false;
        }
        if (scheme == null || host == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        return host.equals("googlevideo.com") || host.endsWith(".googlevideo.com")
                || host.equals("youtube.com") || host.endsWith(".youtube.com");
    }
}
