/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package android.privacykit;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Random;

/**
 * The one implementation of "what does a plausible value for this identifier
 * look like".
 *
 * <p>This code used to live inside {@code PrivacyKitRuleResolver} (package
 * {@code com.android.server.privacykit}, i.e. services.jar). That is the right
 * place for the rule *engine*, but it is not reachable from an app process:
 * the Settings UI links framework.jar only, and the one binder entry point that
 * would expose a generated value, {@code IPrivacyKitManager#resolveIdentifier},
 * is guarded by {@code enforceCallerIsPackage} so Settings may only resolve its
 * own package. Adding a "generate but do not apply" AIDL transaction would have
 * been the alternative; putting the format rules in framework.jar instead gives
 * the same result with no new binder transaction, no client regeneration and no
 * risk of renumbering an existing one - the same trade the Privacy Restrictions
 * group already made (see IPrivacyKitManager's note on reusing
 * setBooleanControl).
 *
 * <p>The point of having exactly one implementation is not tidiness. A
 * "Randomise" button that filled a Custom field using a second, UI-side copy of
 * these rules would drift from the engine, and a wrongly shaped identifier is
 * strictly worse than no spoof at all: the consumer either rejects it (so the
 * user believes they are hidden when they are not) or, worse, parses it and
 * throws inside a third-party app. {@code PrivacyKitRuleResolver#generate} now
 * delegates here, so the engine and every UI affordance mint values from the
 * same switch.
 *
 * <p>Nothing here persists anything or looks at any rule. It is a pure function
 * of (key, seed, realValue), which is what makes it safe to call from an app
 * process.
 *
 * @hide
 */
public final class PrivacyKitIdentifierGenerator {

    private PrivacyKitIdentifierGenerator() {}

    /**
     * Catalog key for the Bluetooth adapter address. Has no
     * {@link PrivacyKitKeys} constant because no framework hook consumes it
     * yet, but it is a real row in the Settings identifier catalog and its
     * value is a MAC, so it gets a MAC generator rather than the opaque
     * fallback. Same duplication caveat as the rest of the key literals in this
     * tree - see {@link PrivacyKitKeys}' class javadoc.
     */
    public static final String KEY_BLUETOOTH_MAC = "bluetooth_mac";

    /** Catalog key for the associated AP's BSSID; also a MAC. See {@link #KEY_BLUETOOTH_MAC}. */
    public static final String KEY_WIFI_BSSID = "wifi_bssid";

    /**
     * Seeds for {@link #generateFresh}. A {@link SecureRandom} rather than
     * {@code System.nanoTime()} because two identifiers randomised in the same
     * millisecond must not come out correlated.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** A seed no caller can predict or reproduce. */
    public static long freshSeed() {
        return SECURE_RANDOM.nextLong();
    }

    /**
     * Whether {@link #generate} has a dedicated, format-correct case for
     * {@code key}, as opposed to falling through to the opaque hex fallback.
     *
     * <p>This is the honesty gate for every "Randomise" affordance in the UI.
     * The fallback emits 16 hex characters, which is a fine shape for an opaque
     * token and a nonsense shape for a device model, an ABI list, a time zone
     * id, a security patch date or a build type - and the in-process injector
     * validates several of those and keeps the real value when the substitute
     * is not plausible. Offering to "randomise" a key that lands in the
     * fallback would therefore either write visible garbage into the app's view
     * of the device or write a value the backend silently discards. Both are
     * the class of lie this project audits for, so the UI must not offer the
     * button at all for a key this returns false for.
     *
     * <p>Returning false is not a statement that the key is unspoofable. It
     * means the *random* rule types have nothing sensible to emit for it, and
     * that the honest route is a Custom value - for the Build identity, a
     * coherent device template rather than eight independent draws.
     */
    // Curated so every member survives the injector validators
    // (TimeZone.getTimeZone round-trip; parseLocaleTag). These three fields
    // are genuinely cross-field-independent, unlike the Build identity set.
    private static final String[] TIMEZONE_POOL = {
        "America/New_York", "America/Chicago", "America/Denver",
        "America/Los_Angeles", "America/Sao_Paulo", "America/Mexico_City",
        "Europe/London", "Europe/Paris", "Europe/Berlin", "Europe/Madrid",
        "Europe/Moscow", "Africa/Cairo", "Africa/Lagos", "Asia/Dubai",
        "Asia/Kolkata", "Asia/Bangkok", "Asia/Shanghai", "Asia/Singapore",
        "Asia/Tokyo", "Asia/Seoul", "Australia/Sydney", "Pacific/Auckland" };
    private static final String[] LOCALE_POOL = {
        "en-US", "en-GB", "en-CA", "en-AU", "en-IN", "es-ES", "es-MX",
        "pt-BR", "pt-PT", "fr-FR", "fr-CA", "de-DE", "it-IT", "nl-NL",
        "ru-RU", "pl-PL", "tr-TR", "ar-EG", "hi-IN", "id-ID", "th-TH",
        "vi-VN", "ja-JP", "ko-KR", "zh-CN", "zh-TW" };

    /** A valid recent Android patch level: always day 01, within ~14 months. */
    private static String genSecurityPatch(Random rnd) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.DAY_OF_MONTH, 1);
        c.add(java.util.Calendar.MONTH, -rnd.nextInt(14));
        return String.format(Locale.US, "%1$tY-%1$tm-01", c);
    }

    public static boolean hasGeneratorFor(String key) {
        if (key == null) {
            return false;
        }
        switch (key) {
            case PrivacyKitKeys.KEY_ANDROID_ID:
            case PrivacyKitKeys.KEY_SERIAL:
            case PrivacyKitKeys.KEY_IMEI:
            case PrivacyKitKeys.KEY_IMSI:
            case PrivacyKitKeys.KEY_ICCID:
            case PrivacyKitKeys.KEY_PHONE_NUMBER:
            case PrivacyKitKeys.KEY_WIFI_MAC:
            case PrivacyKitKeys.KEY_MEDIA_DRM_ID:
            case PrivacyKitKeys.KEY_GSF_ID:
            case PrivacyKitKeys.KEY_ADVERTISING_ID:
            case PrivacyKitKeys.KEY_APP_SET_ID:
            case PrivacyKitKeys.KEY_DEVICE_TIMEZONE:
            case PrivacyKitKeys.KEY_DEVICE_LOCALE:
            case PrivacyKitKeys.KEY_OS_SECURITY_PATCH:
            case PrivacyKitKeys.KEY_FIRST_INSTALL_TIME:
            case KEY_BLUETOOTH_MAC:
            case KEY_WIFI_BSSID:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether generating a value for {@code key} requires the device's real
     * value as an input, so it cannot be generated anywhere but system_server.
     *
     * <p>{@link PrivacyKitKeys#KEY_MEDIA_DRM_ID}: the Widevine device unique id
     * is a vendor-length blob and apps size-check it, so the substitute has to
     * have exactly the real one's byte length. An app-process caller has no way
     * to learn that length (its own read of the property goes through the same
     * hook), so a UI must not offer to fill this field itself. The honest route
     * for that key is RULE_STATIC, which generates server-side with the real
     * blob in hand.
     *
     * <p>{@link PrivacyKitKeys#KEY_FIRST_INSTALL_TIME}: a freely invented
     * install date is a worse tell than the true one. A date before the app
     * existed is impossible, a date in the future is impossible, and a date
     * that disagrees with the evidence the app already has about itself (its
     * own data directory, its own logs, its account age) is a contradiction the
     * app can catch on its own. The only defensible substitute is the real
     * timestamp nudged, so the generator needs the real one - see
     * {@link #generate}.
     */
    public static boolean needsRealValue(String key) {
        return PrivacyKitKeys.KEY_MEDIA_DRM_ID.equals(key)
                || PrivacyKitKeys.KEY_FIRST_INSTALL_TIME.equals(key);
    }

    /**
     * A freshly generated value for {@code key}, unrelated to any previous one.
     *
     * <p>Pure CPU - no I/O, no binder, no lock - so it is safe to call from a
     * button handler. Returns {@code realValue} (usually null from an app
     * process) when {@code key} needs the real value and none was supplied; a
     * caller that cannot supply one should gate on {@link #needsRealValue}
     * first rather than shipping the result.
     */
    public static String generateFresh(String key, String realValue) {
        return generate(key, freshSeed(), realValue);
    }

    /**
     * Deterministic, per-identifier-type value generation from a seed.
     *
     * <p>{@code realValue} is passed in for the keys whose generated value has
     * to match the *runtime* shape of the real one (currently only
     * {@link PrivacyKitKeys#KEY_MEDIA_DRM_ID}); most cases ignore it.
     *
     * <p>The draw order from the seeded {@link Random} is load bearing for the
     * keys that already have persisted RULE_STATIC values on real devices: the
     * value is cached the first time it is minted, so an existing one never
     * changes, but keeping the draws identical to the pre-move implementation
     * means nothing about this refactor is observable.
     */
    public static String generate(String key, long seed, String realValue) {
        Random rnd = new Random(seed);
        switch (key) {
            case PrivacyKitKeys.KEY_ANDROID_ID:
                return String.format("%016x", rnd.nextLong());
            case PrivacyKitKeys.KEY_SERIAL:
                return genAlnum(rnd, 12).toUpperCase(Locale.US);
            case PrivacyKitKeys.KEY_IMEI:
                return genLuhnValidImei(rnd);
            case PrivacyKitKeys.KEY_IMSI:
                // 3-digit MCC + 2/3-digit MNC + subscriber digits, 15 digits total.
                return "310" + "260" + genDigits(rnd, 9);
            case PrivacyKitKeys.KEY_ICCID:
                // 19-20 digit ICCID; use a plausible 89-prefixed 20-digit form.
                return "89" + genDigits(rnd, 18);
            case PrivacyKitKeys.KEY_PHONE_NUMBER:
                return "+1" + genDigits(rnd, 10);
            case PrivacyKitKeys.KEY_WIFI_MAC:
            case KEY_BLUETOOTH_MAC:
            case KEY_WIFI_BSSID:
                return genMac(rnd);
            case PrivacyKitKeys.KEY_GSF_ID:
                return genGsfId(rnd);
            case PrivacyKitKeys.KEY_ADVERTISING_ID:
            case PrivacyKitKeys.KEY_APP_SET_ID:
                // Google Advertising ID / App Set ID: a canonical
                // lower-case type-4 UUID (8-4-4-4-12). No realValue needed.
                return new java.util.UUID(rnd.nextLong(), rnd.nextLong())
                        .toString();
            case PrivacyKitKeys.KEY_MEDIA_DRM_ID: {
                // The Widevine device unique id is an opaque blob whose length
                // is vendor-defined (32 bytes on many Widevine builds, but that
                // is not guaranteed) and which apps size-check. realValue is the
                // real blob hex-encoded, so emitting exactly as many hex
                // characters as it has reproduces the real byte length on
                // whatever device this happens to be running.
                //
                // Never bake a fixed length in here, and never let this key fall
                // through to the default branch below: that emits 16 hex
                // characters, i.e. an 8-byte id, which would shrink a 32-byte
                // blob to a quarter of its size.
                if (realValue == null || realValue.isEmpty()
                        || (realValue.length() & 1) != 0) {
                    return realValue; // not a hex blob we can size-match.
                }
                return genHex(rnd, realValue.length());
            }
            case PrivacyKitKeys.KEY_DEVICE_TIMEZONE:
                return TIMEZONE_POOL[rnd.nextInt(TIMEZONE_POOL.length)];
            case PrivacyKitKeys.KEY_DEVICE_LOCALE:
                return LOCALE_POOL[rnd.nextInt(LOCALE_POOL.length)];
            case PrivacyKitKeys.KEY_OS_SECURITY_PATCH:
                return genSecurityPatch(rnd);
            case PrivacyKitKeys.KEY_FIRST_INSTALL_TIME:
                // Derived from the real install time, never invented. See
                // genInstallTime() and needsRealValue().
                return genInstallTime(rnd, realValue);
            // Build.* identity fields (model/brand/fingerprint/soc/build_id/...)
            // are template/Custom-only - see PrivacyKitRuleResolver
            // #isRuleTypeAllowed. No coherent random draw exists, so they are
            // intentionally not listed here and fall through to the real value.
            default:
                return genAlnum(rnd, 16);
        }
    }

    // ------------------------------------------------------------------------
    // Install timestamps.
    //
    // PackageInfo.firstInstallTime and lastUpdateTime are millisecond-precision
    // wall clocks, i.e. roughly forty bits of entropy each and effectively
    // unique per device even without any other signal - which is exactly why
    // they are worth spoofing. They are also the easiest identifier in the
    // catalog to spoof *wrongly*: an app knows things about its own install
    // that a random timestamp contradicts instantly.
    //
    //  - Earlier than the app first shipped: impossible.
    //  - Later than now: impossible.
    //  - Earlier than the files in the app data directory, the account it is
    //    signed into, or the server-side record of its first request: a
    //    contradiction the app can check without any help.
    //
    // So the substitute is always the REAL timestamp moved FORWARD by a
    // deterministic offset bounded by now. Forward-only is what keeps the
    // result inside the plausible window without having to know when the app
    // was published: the real install time is by definition after the app
    // existed, so anything at or after it is too. The offset destroys the
    // millisecond fingerprint - the identifying part - while preserving the
    // "installed roughly N months ago" story that everything else about the app
    // agrees with.
    // ------------------------------------------------------------------------

    /**
     * The furthest a spoofed install date may travel: 30 days. Wide enough that
     * the exact millisecond carries no information, narrow enough that the
     * spoofed date still tells the same story as the app data directory it sits
     * next to.
     */
    private static final long MAX_INSTALL_TIME_JITTER_MILLIS = 30L * 24 * 60 * 60 * 1000L;

    /** 2^64 / golden ratio; the SplitMix64 increment, used to decorrelate two hashes. */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    /**
     * A plausible install timestamp for an app whose real one is
     * {@code realValue} (decimal epoch millis).
     *
     * <p>Returns {@code realValue} unchanged - never a random number - when the
     * real value is missing, unparseable, zero (the platform value for "never
     * installed") or already in the future. Failing open is correct here: the
     * true timestamp is always more plausible than an invented one.
     */
    private static String genInstallTime(Random rnd, String realValue) {
        final long real = parseEpochMillis(realValue);
        if (real <= 0) {
            return realValue;
        }
        final long window = installTimeJitterWindow(real, System.currentTimeMillis());
        if (window <= 0) {
            return realValue; // installed in the future, or this instant - leave it alone.
        }
        return Long.toString(real + ((rnd.nextLong() >>> 1) % (window + 1)));
    }

    /**
     * How far an install timestamp may move forward: at most
     * {@link #MAX_INSTALL_TIME_JITTER_MILLIS}, and never past {@code nowMillis}.
     * Zero means "do not move it at all".
     */
    private static long installTimeJitterWindow(long realMillis, long nowMillis) {
        if (realMillis <= 0 || nowMillis <= realMillis) {
            return 0L;
        }
        return Math.min(nowMillis - realMillis, MAX_INSTALL_TIME_JITTER_MILLIS);
    }

    /**
     * A deterministic forward offset, in {@code [0, window]}, for one
     * (identity, scope) pair - the server-side hook for
     * {@link PrivacyKitKeys#KEY_FIRST_INSTALL_TIME}.
     *
     * <p>{@code seedMaterial} is the value the rule engine already resolved for
     * the reading app, so the offset inherits the rule type for free: frozen by
     * RULE_STATIC, rolling with RULE_DAILY, and different for every profile of
     * the app because the frozen value is minted per profile.
     *
     * <p>{@code scope} is the package the timestamp belongs to. It matters
     * because an app does not only ask about itself: one
     * {@code getInstalledPackages()} call returns a PackageInfo for every
     * package on the device, and if they all moved by the same offset - or
     * worse, all collapsed onto the one value the rule resolved - the result
     * would be a far louder signal than the real timestamps. Mixing the package
     * in gives every package its own offset while keeping all of them stable.
     *
     * <p>Allocation-free and lock-free: this runs inside the PackageManager
     * snapshot path, once per PackageInfo.
     */
    public static long deriveInstallTimeOffsetMillis(String seedMaterial, String scope,
            long realMillis, long nowMillis) {
        final long window = installTimeJitterWindow(realMillis, nowMillis);
        if (window <= 0 || seedMaterial == null || seedMaterial.isEmpty()) {
            return 0L;
        }
        final long mixed = mix64(fnv1a64(seedMaterial) ^ (fnv1a64(scope) * GOLDEN_GAMMA));
        return (mixed >>> 1) % (window + 1);
    }

    /**
     * A RULE_CUSTOM install date read as an explicit timestamp, or -1 when the
     * value is not one the hook may use as-is.
     *
     * <p>A hand-entered date is honoured only for the app that owns the rule
     * and only inside {@code [realMillis, nowMillis]}. Outside that band it is
     * refused rather than clamped: a date before the real install could predate
     * the app itself, and one in the future is impossible, so the caller falls
     * back to a derived offset instead of shipping a value the reading app can
     * disprove.
     */
    public static long explicitInstallTimeMillis(String resolved, long realMillis,
            long nowMillis) {
        final long value = parseEpochMillis(resolved);
        if (value <= 0 || value < realMillis || value > nowMillis) {
            return -1L;
        }
        return value;
    }

    /** Decimal epoch millis, or -1 for anything that is not exactly that. */
    private static long parseEpochMillis(String value) {
        if (value == null) {
            return -1L;
        }
        final String text = value.trim();
        if (text.isEmpty() || text.length() > 18) {
            return -1L; // 18 digits is already the year 10889; longer cannot be a time.
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** FNV-1a, 64-bit. Stable across processes and reboots, unlike String#hashCode mixing. */
    private static long fnv1a64(String value) {
        long h = 0xCBF29CE484222325L;
        if (value == null) {
            return h;
        }
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= 0x100000001B3L;
        }
        return h;
    }

    /** SplitMix64 finaliser: avalanches the low bits so the modulo below is uniform. */
    private static long mix64(long z) {
        z += GOLDEN_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * The GSF ID as a DECIMAL 64-bit string.
     *
     * <p>Not hex, and this is not cosmetic. The canonical app-side snippet for
     * the gservices {@code android_id} row is
     * {@code Long.toHexString(Long.parseLong(cursor.getString(1)))}, so a hex
     * substitute makes somebody else's app throw NumberFormatException. The
     * framework hook (ContentProviderProxy) knows this and refuses to
     * substitute anything that is not a decimal long, which is why every random
     * rule type for this key was a silent no-op before this case existed - the
     * generic fallback emits hex and the hook threw it away.
     *
     * <p>Floored at 10^18 so the result is always 19 digits and always
     * positive: that is the shape real GSF IDs are observed in, a short value
     * would stand out, and a negative one risks an app that parses it as
     * unsigned. That floor keeps roughly 89% of the positive long range, so the
     * value is still effectively unguessable.
     */
    private static String genGsfId(Random rnd) {
        final long min = 1000000000000000000L; // 10^18 -> 19 digits
        final long span = Long.MAX_VALUE - min;
        long v = rnd.nextLong() >>> 1; // 0 .. 2^63-1, always non-negative
        return Long.toString(min + (v % span));
    }

    private static String genDigits(Random rnd, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(rnd.nextInt(10));
        return sb.toString();
    }

    /**
     * n random characters from "0123456789abcdef". Despite the name this is a
     * hex alphabet, not alphanumeric - kept as-is because several existing keys
     * depend on the exact byte stream it draws from the seeded Random, so
     * changing the alphabet would change every already-generated STATIC value.
     * Use {@link #genHex} in new code.
     */
    private static String genAlnum(Random rnd, int n) {
        final String chars = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    /** n random lower-case hex characters, i.e. n/2 bytes' worth. */
    private static String genHex(Random rnd, int n) {
        return genAlnum(rnd, n);
    }

    private static String genMac(Random rnd) {
        byte[] mac = new byte[6];
        rnd.nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // locally-administered, unicast
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02x", mac[i]));
        }
        return sb.toString();
    }

    /** Generates a 15-digit IMEI with a valid Luhn check digit. */
    private static String genLuhnValidImei(Random rnd) {
        int[] digits = new int[15];
        for (int i = 0; i < 14; i++) digits[i] = rnd.nextInt(10);
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = digits[i];
            // Luhn: double every second digit from the right of the check digit;
            // for a 15-digit number with check digit at index 14, double indices
            // 13,11,9,... (i.e. even distance from the check digit, 0-indexed from left: odd i)
            if (i % 2 == 1) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        int check = (10 - (sum % 10)) % 10;
        digits[14] = check;
        StringBuilder sb = new StringBuilder(15);
        for (int d : digits) sb.append(d);
        return sb.toString();
    }
}
