package com.newtube.mobile.casting;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups physical TVs as discovery arrives, retaining each app's receiver identity. */
final class CastDeviceRegistry {
    private final Map<String, CastTarget> mTargets = new LinkedHashMap<>();

    void add(CastTarget target) {
        if (target.getRoute() == CastTarget.Route.LOUNGE_DIAL) {
            String host = target.getDeviceHost();
            mTargets.values().removeIf(old -> old.getRoute() == CastTarget.Route.LOUNGE_DIAL
                    && host != null && host.equals(old.getDeviceHost()));
        }
        CastTarget old = mTargets.get(target.getDedupeKey());
        if (old != null && target.getReceiverApp() == CastTarget.ReceiverApp.UNKNOWN) {
            target = target.withReceiverApp(old.getReceiverApp());
        }
        mTargets.put(target.getDedupeKey(), target);
    }

    List<Device> devices() {
        Map<String, Device> groups = new LinkedHashMap<>();
        // Different physical endpoints never merge just because their names happen to match.
        for (CastTarget target : mTargets.values()) {
            String host = target.getDeviceHost();
            if (host != null) groups.computeIfAbsent("host:" + host, Device::new).add(target);
        }
        for (CastTarget target : mTargets.values()) {
            if (target.getDeviceHost() != null) continue;
            Device match = null;
            int matches = 0;
            for (Device device : groups.values()) {
                if (device.contains(target)) { match = device; matches = 1; break; }
                if (device.matchesName(target.getName())) { match = device; matches++; }
            }
            if (matches != 1) {
                String name = normalizedName(target.getName());
                String key = matches > 1 || name.isEmpty() ? target.getDedupeKey() : "name:" + name;
                match = groups.computeIfAbsent(key, Device::new);
            }
            match.add(target);
        }
        List<Device> result = new ArrayList<>(groups.values());
        result.sort(Comparator.comparingInt((Device d) -> priority(d.routes().get(0)))
                .thenComparing(d -> normalizedName(d.name())));
        return result;
    }

    @Nullable Device deviceFor(CastTarget target) {
        for (Device device : devices()) {
            if (device.contains(target)) return device;
        }
        return null;
    }

    static int priority(CastTarget target) {
        if (target.getReceiverApp() == CastTarget.ReceiverApp.SMARTTUBE) return 0;
        if (target.getRoute() == CastTarget.Route.CAST_V2) return 1;
        if (target.getReceiverApp() == CastTarget.ReceiverApp.UNKNOWN) return 2;
        return 3;
    }

    private static String normalizedName(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    static final class Device {
        final String key;
        private final Map<String, CastTarget> targets = new LinkedHashMap<>();

        Device(String key) { this.key = key; }

        private void add(CastTarget target) {
            CastTarget old = targets.get(target.getDedupeKey());
            if (old != null && old.getDeviceHost() != null) {
                target = target.getReceiverApp() == CastTarget.ReceiverApp.UNKNOWN
                        ? old : old.withReceiverApp(target.getReceiverApp());
            }
            targets.put(target.getDedupeKey(), target);
        }

        boolean contains(CastTarget target) { return targets.containsKey(target.getDedupeKey()); }

        private boolean matchesName(String name) {
            String wanted = normalizedName(name);
            if (wanted.isEmpty()) return false;
            for (CastTarget target : targets.values()) {
                if (wanted.equals(normalizedName(target.getName()))) return true;
            }
            return false;
        }

        String name() {
            for (CastTarget target : targets.values()) {
                if (target.getRoute() == CastTarget.Route.CAST_V2) return target.getName();
            }
            return targets.values().iterator().next().getName();
        }

        /** SmartTube, direct cast, unidentified saved receivers, and stock YouTube last. */
        List<CastTarget> routes() {
            List<CastTarget> result = new ArrayList<>();
            CastTarget cast = null;
            for (CastTarget target : targets.values()) {
                if (target.getRoute() == CastTarget.Route.CAST_V2) cast = target;
            }
            for (CastTarget target : targets.values()) {
                // A Cast TV needs its YouTube app launched before a saved Lounge ID can work.
                if (cast == null || target.getReceiverApp() != CastTarget.ReceiverApp.YOUTUBE) {
                    result.add(target);
                }
            }
            if (cast != null) result.add(CastTarget.fromCastDeviceYouTubeApp(
                    cast.getName(), cast.getCastHost(), cast.getCastPort()));
            result.sort(Comparator.comparingInt(CastDeviceRegistry::priority));
            return result;
        }
    }
}
