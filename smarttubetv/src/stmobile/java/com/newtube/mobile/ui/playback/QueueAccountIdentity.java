package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.util.AtomicFile;
import com.liskovsoft.googlecommon.service.oauth.YouTubeAccount;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Stable local identity: YouTube page ID, otherwise email; never display name or auth tokens. */
final class QueueAccountIdentity {
    private final byte[] salt;
    private final File directory;
    private boolean durable = true;
    private final java.util.Map<String, String> aliases = new java.util.HashMap<>();
    private final java.util.Map<Account, String> unresolved = new java.util.IdentityHashMap<>();

    QueueAccountIdentity(File directory) {
        this.directory = directory;
        byte[] value = new byte[32];
        try {
            if (directory == null) throw new IOException("Private storage unavailable");
            AtomicFile file = new AtomicFile(new File(directory, "mobile-queue-salt-v2"));
            try (FileInputStream in = file.openRead()) {
                int offset = 0, count;
                while (offset < value.length && (count = in.read(value, offset, value.length - offset)) != -1) offset += count;
                if (offset != value.length || in.read() != -1) throw new IOException("Invalid queue identity salt");
            } catch (java.io.FileNotFoundException missing) {
                new SecureRandom().nextBytes(value);
                FileOutputStream out = null;
                try { out = file.startWrite(); out.write(value); file.finishWrite(out); }
                catch (IOException | RuntimeException error) { if (out != null) file.failWrite(out); throw error; }
            }
        } catch (IOException | RuntimeException unavailable) {
            // Storage failure must not disable account boundaries or turn them into a shared queue.
            durable = false;
            new SecureRandom().nextBytes(value);
            android.util.Log.w("QueuePersistence", "Queue identity storage unavailable; using session isolation");
        }
        salt = value;
    }

    String initialScope(Context context) {
        // The account manager restores asynchronously and does not replay a startup notification.
        // Read the same persisted selection locally, without waiting for auth/network initialization.
        String data = GlobalPreferences.instance(context).getMediaServiceAccountData();
        if (data != null) {
            String[] specs = Helpers.splitArray(data);
            if (specs != null) for (String spec : specs) {
                Account account = YouTubeAccount.from(spec);
                if (account != null && account.isSelected()) return scope(account);
            }
        }
        return scope(null);
    }

    String scope(Account account) {
        if (account == null) return hash("signed-out");
        String page = account instanceof YouTubeAccount ? ((YouTubeAccount) account).getPageIdToken() : null;
        String email = account.getEmail();
        String channel = account instanceof YouTubeAccount ? ((YouTubeAccount) account).getChannelName() : null;
        // A fallback identifies one channel, so another channel under the same email cannot claim it.
        String fallback = email != null && !email.trim().isEmpty()
                ? hash("email:" + email.trim().toLowerCase(Locale.ROOT) + "\nchannel:"
                        + (channel != null ? channel : account.getName())) : null;
        String pageKey = page != null && !page.trim().isEmpty() ? hash("page:" + page.trim()) : null;
        try {
            if (pageKey != null) {
                String established = alias(pageKey);
                if (established != null) return established;
                // A persisted email-only identity may gain a page ID during background account sync.
                // Bind both opaque aliases to the original scope rather than stranding its queue.
                String previous = fallback != null ? alias(fallback) : null;
                String previousKey = fallback;
                // Older persisted accounts may lack BOTH page ID and handle. Hydration may add
                // both together, so also recognize the original email/name fallback.
                if (previous == null && email != null && account.getName() != null) {
                    String oldFallback = hash("email:" + email.trim().toLowerCase(Locale.ROOT)
                            + "\nchannel:" + account.getName());
                    String oldScope = alias(oldFallback);
                    if (oldScope != null) { previous = oldScope; previousKey = oldFallback; }
                }
                String claim = previousKey != null ? hash("claim:" + previousKey) : null;
                String claimedPage = claim != null ? alias(claim) : null;
                if (claimedPage != null && !claimedPage.equals(pageKey)) previous = null;
                if (claim != null && claimedPage == null) bind(claim, pageKey);
                String canonical = previous != null ? previous : pageKey;
                bind(pageKey, canonical);
                if (fallback != null && alias(fallback) == null) {
                    bind(hash("claim:" + fallback), pageKey);
                    bind(fallback, canonical);
                }
                return canonical;
            }
            if (fallback != null) {
                String established = alias(fallback);
                if (established != null) return established;
                bind(fallback, fallback);
                return fallback;
            }
        } catch (IOException error) {
            durable = false;
            android.util.Log.w("QueuePersistence", "Queue aliases unavailable; using session isolation");
            return pageKey != null ? pageKey : fallback;
        }
        // Incomplete identities never borrow signed-out state or another user's queue.
        return unresolved.computeIfAbsent(account, ignored -> hash("unresolved:" + java.util.UUID.randomUUID()));
    }

    private String alias(String key) throws IOException {
        if (aliases.containsKey(key)) return aliases.get(key);
        if (!durable) return null;
        AtomicFile file = new AtomicFile(new File(directory, "mobile-queue-alias-v2-" + key));
        try (FileInputStream in = file.openRead()) {
            byte[] bytes = new byte[64];
            int offset = 0, count;
            while (offset < bytes.length && (count = in.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
            if (offset != bytes.length || in.read() != -1) throw new IOException("Invalid queue alias");
            String value = new String(bytes, StandardCharsets.US_ASCII);
            if (!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid queue alias");
            aliases.put(key, value);
            return value;
        } catch (java.io.FileNotFoundException missing) { return null; }
    }

    private void bind(String key, String scope) throws IOException {
        aliases.put(key, scope);
        if (!durable) return;
        AtomicFile file = new AtomicFile(new File(directory, "mobile-queue-alias-v2-" + key));
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(scope.getBytes(StandardCharsets.US_ASCII)); file.finishWrite(out); }
        catch (IOException | RuntimeException error) {
            if (out != null) file.failWrite(out);
            durable = false;
            android.util.Log.w("QueuePersistence", "Queue aliases unavailable; using session isolation");
        }
    }

    private String hash(String identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            byte[] bytes = digest.digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(64);
            for (byte value : bytes) key.append(String.format(Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
