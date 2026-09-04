/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.location;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.location.util.identity.CallerIdentity;
import android.os.Process;
import android.privacykit.PrivacyKitKeys;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.LocalServices;
import com.android.server.privacykit.PrivacyKitManagerInternal;

/**
 * Per-app location substitution for PrivacyKit-Native.
 *
 * <p>When a package has a {@link PrivacyKitKeys#KEY_LOCATION} rule whose value
 * parses as {@code "lat,lon"} or {@code "lat,lon,accuracyMeters"}, every
 * {@link Location} the platform is about to hand <em>that</em> package is
 * replaced by a fix at the pinned point. Nothing else on the device is
 * affected: this is not the test-provider path, the app needs no
 * {@code MOCK_LOCATION} permission, the global "allow mock locations"
 * developer setting stays off, and the substituted fix is <em>not</em> marked
 * mock ({@link Location#isMock()} stays false), because a fix flagged mock is
 * a louder tell than the real coordinates would have been.
 *
 * <h3>Why the whole object is rebuilt</h3>
 *
 * <p>Rewriting only latitude and longitude on the real {@link Location} is
 * self-defeating. The object carries roughly a dozen other fields, and several
 * of them are derived from where the device actually is:
 *
 * <ul>
 *   <li>{@code speed} / {@code bearing} - a device that reports 14 m/s heading
 *       south-west while its reported coordinates never change has contradicted
 *       itself in a single fix.
 *   <li>{@code altitude} / {@code mslAltitude} - a truthful altitude for a
 *       coordinate we invented does not exist, and the real one is a direct
 *       leak of the terrain the device is actually standing on.
 *   <li>{@code speedAccuracy}, {@code bearingAccuracy}, {@code
 *       verticalAccuracy} - uncertainty figures the GNSS engine computed for a
 *       measurement that no longer exists.
 *   <li>{@code extras} - the worst of the lot. The fused provider stashes a
 *       whole second {@link Location} under {@code "noGPSLocation"}, and GNSS
 *       and network providers add satellite counts and cell/Wi-Fi debug
 *       bundles. Leaving the extras attached re-leaks the real position in full
 *       right next to the fake one.
 * </ul>
 *
 * <p>So the substitution builds a fresh {@link Location} rather than mutating
 * the real one. A fresh object starts with every optional field absent and
 * {@code extras} null, which means the safe state is the default and a field
 * can only survive by being copied across deliberately. Exactly three things
 * are copied:
 *
 * <ul>
 *   <li>the provider name - apps branch on
 *       {@link Location#getProvider()}, and a fix that arrives from the GPS
 *       provider claiming to be from somewhere else is a tell;
 *   <li>{@link Location#getTime()};
 *   <li>{@link Location#getElapsedRealtimeNanos()} (plus its uncertainty when
 *       the real fix had one).
 * </ul>
 *
 * <p>The time fields are copied rather than regenerated on purpose. They are
 * what makes the fake fix <em>fresh</em>, they are what
 * {@link LocationResult#validate()} checks for monotonicity, and they are what
 * {@code LocationProviderManager}'s own fastest-interval filter subtracts to
 * decide whether a delivery is too soon. Minting our own timestamps would drift
 * against {@link android.os.SystemClock#elapsedRealtimeNanos()} and show up as
 * a fix from the future or from the past.
 *
 * <p>What is set: latitude, longitude, horizontal accuracy, and a speed of
 * exactly zero. Zero is not a placeholder here - it is the honest reading for a
 * device pinned to a fixed point, and it agrees with the coordinates never
 * changing. Bearing is left absent rather than set to 0.0, because 0.0 degrees
 * is a claim ("heading due north") while absent is the correct statement that a
 * stationary fix has no heading; the same reasoning leaves speed accuracy,
 * bearing accuracy, altitude and vertical accuracy absent instead of inventing
 * numbers for them.
 *
 * <h3>Failure behaviour</h3>
 *
 * <p>Every entry point fails open to the real value: a null service, a package
 * with no rule, a rule whose value does not parse, coordinates outside their
 * legal range, or any unexpected {@link RuntimeException} all yield the caller's
 * own object back, unchanged and by reference. Callers use that reference
 * identity ({@code result == original}) to tell "no substitution happened"
 * from "substituted", so the no-rule path must never copy.
 *
 * <p>System callers (uid below {@link Process#FIRST_APPLICATION_UID}) are never
 * substituted, matching the trust boundary the rest of PrivacyKit uses.
 *
 * @see com.android.server.location.provider.LocationProviderManager
 */
public final class PrivacyKitLocationOverride {

    private static final String TAG = "PrivacyKitLocation";

    /**
     * Horizontal accuracy reported when the rule value names none.
     *
     * <p>A fix with no accuracy at all is rejected outright by
     * {@link LocationResult#validate()} and is unusual enough to be noticed, so
     * there is always one. 12 m is an ordinary urban GNSS/fused figure.
     */
    private static final float DEFAULT_ACCURACY_M = 12.0f;

    /**
     * Bounds on a rule-supplied accuracy. Below a metre is not a figure any
     * consumer-grade fix reports; above 10 km stops being a location. Outside
     * this window the whole rule is treated as malformed rather than clamped,
     * so a typo produces the real location instead of a silently-mangled fake.
     */
    private static final float MIN_ACCURACY_M = 1.0f;
    private static final float MAX_ACCURACY_M = 10000.0f;

    private PrivacyKitLocationOverride() {}

    /** A parsed and range-checked per-app pin. Immutable. */
    private static final class Pin {
        final double latitude;
        final double longitude;
        final float accuracyMeters;

        Pin(double latitude, double longitude, float accuracyMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyMeters = accuracyMeters;
        }
    }

    /**
     * Substitutes every location in {@code locationResult} for {@code identity}'s
     * package, or returns {@code locationResult} itself (same reference) when no
     * usable rule applies.
     */
    @Nullable
    public static LocationResult substituteResult(@Nullable CallerIdentity identity,
            @Nullable LocationResult locationResult) {
        if (locationResult == null) {
            return null;
        }
        final Pin pin = getPin(identity);
        if (pin == null) {
            return locationResult;
        }
        try {
            // map() invokes the function once per location, earliest to latest,
            // and each call returns a brand new object - so the result is always
            // a new LocationResult, never the input by reference.
            return locationResult.map(location -> substitute(pin, location));
        } catch (RuntimeException e) {
            Log.w(TAG, "location substitution failed, delivering the real fix", e);
            return locationResult;
        }
    }

    /**
     * Substitutes a single location for {@code identity}'s package, or returns
     * {@code location} itself (same reference) when no usable rule applies.
     */
    @Nullable
    public static Location substituteLocation(@Nullable CallerIdentity identity,
            @Nullable Location location) {
        if (location == null) {
            return null;
        }
        final Pin pin = getPin(identity);
        if (pin == null) {
            return location;
        }
        try {
            return substitute(pin, location);
        } catch (RuntimeException e) {
            Log.w(TAG, "location substitution failed, delivering the real fix", e);
            return location;
        }
    }

    /** The pin configured for this caller, or null - never throws. */
    @Nullable
    /**
     * True when [identity] is receiving a substituted location.
     *
     * Used by the GNSS providers to suppress satellite status and NMEA for that
     * caller: handing an app a fake fix while it can still read the real
     * constellation - or the real lat/lon out of an NMEA sentence - is a
     * self-contradiction, and a contradiction is a stronger fingerprint than the
     * true value would have been.
     */
    public static boolean isPinned(@Nullable CallerIdentity identity) {
        return getPin(identity) != null;
    }

    private static Pin getPin(@Nullable CallerIdentity identity) {
        if (identity == null) {
            return null;
        }
        try {
            if (identity.getUid() < Process.FIRST_APPLICATION_UID) {
                // Same trust boundary as PrivacyKitService: never lie to the
                // platform's own components about where the device is.
                return null;
            }
            final String packageName = identity.getPackageName();
            if (TextUtils.isEmpty(packageName)) {
                return null;
            }
            final PrivacyKitManagerInternal pk =
                    LocalServices.getService(PrivacyKitManagerInternal.class);
            if (pk == null) {
                return null;
            }
            // realValue is null on purpose: there is no "real" string form of a
            // location, and passing null means every rule type that mints a
            // generic identifier (STATIC/DAILY/PER_LAUNCH all fall through to a
            // random 16-char token for this key) fails the parse below and
            // leaves the real fix alone. Only a CUSTOM rule holding real
            // coordinates gets through.
            return parse(pk.resolveIdentifier(packageName, PrivacyKitKeys.KEY_LOCATION, null));
        } catch (RuntimeException e) {
            Log.w(TAG, "location rule lookup failed, delivering the real fix", e);
            return null;
        }
    }

    /**
     * Parses {@code "lat,lon"} or {@code "lat,lon,accuracy"}. Returns null for
     * anything else - a malformed rule means no override, never a partial one.
     */
    @Nullable
    private static Pin parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        final String[] parts = trimmed.split(",");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        try {
            final double latitude = Double.parseDouble(parts[0].trim());
            final double longitude = Double.parseDouble(parts[1].trim());
            // parseDouble happily accepts "NaN" and "Infinity", and NaN loses
            // every range comparison below, so both are rejected explicitly.
            if (!isFinite(latitude) || !isFinite(longitude)) {
                return null;
            }
            if (latitude < -90.0 || latitude > 90.0
                    || longitude < -180.0 || longitude > 180.0) {
                return null;
            }
            if (latitude == 0.0 && longitude == 0.0) {
                // Null Island. LocationResult#validate() rejects 0,0 for any
                // non-mock fix, so delivering it would be both an obvious tell
                // and a location the platform itself calls invalid.
                return null;
            }

            float accuracyMeters = DEFAULT_ACCURACY_M;
            if (parts.length == 3) {
                final String rawAccuracy = parts[2].trim();
                if (!rawAccuracy.isEmpty()) {
                    accuracyMeters = Float.parseFloat(rawAccuracy);
                    if (Float.isNaN(accuracyMeters) || Float.isInfinite(accuracyMeters)
                            || accuracyMeters < MIN_ACCURACY_M
                            || accuracyMeters > MAX_ACCURACY_M) {
                        return null;
                    }
                }
            }
            return new Pin(latitude, longitude, accuracyMeters);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * Builds the fix the app will see. Always a fresh object; see the class
     * javadoc for why each field is copied, set or left absent.
     */
    private static Location substitute(Pin pin, Location real) {
        final Location fake = new Location(real.getProvider());

        fake.setLatitude(pin.latitude);
        fake.setLongitude(pin.longitude);
        fake.setAccuracy(pin.accuracyMeters);

        // The only real-world values carried across. Without them the fix has
        // no age, breaks LocationResult#validate()'s monotonicity check, and
        // confuses every interval filter downstream.
        fake.setTime(real.getTime());
        fake.setElapsedRealtimeNanos(real.getElapsedRealtimeNanos());
        if (real.hasElapsedRealtimeUncertaintyNanos()) {
            fake.setElapsedRealtimeUncertaintyNanos(real.getElapsedRealtimeUncertaintyNanos());
        }

        // Pinned means stationary; this is the reading that agrees with
        // coordinates that never change.
        fake.setSpeed(0.0f);

        // Deliberately left absent: altitude, mslAltitude and their accuracies
        // (no truthful value exists for coordinates we invented, and the real
        // ones describe the real terrain), bearing and bearingAccuracy (a
        // stationary fix has no heading), speedAccuracy (nothing to be
        // uncertain about at a fixed point), and extras (they carry the real
        // position - see the class javadoc). All of these are already absent on
        // a newly constructed Location, so nothing is done here to keep them
        // that way; that is the point of building fresh instead of copying.
        return fake;
    }
}
