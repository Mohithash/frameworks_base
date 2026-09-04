/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
// Reconstructed 2026-08-19 after the build box was purged; this is the final state as
// shipped in the r49 ROM (built 2026-08-18 13:44 UTC). Recovery route: the
// 2026-08-16T04:26 whole-file transcript snapshot (181 lines) is exactly the base this
// file was last committed at, and snap18c/patches/frameworks_base.patch applied to it
// with `git apply` exact, zero fuzz, yielding 399 lines - the length the patch's own
// hunk headers imply. Cross-checked against the r49 services.jar decompile (jadx): the
// media_drm_id / first_install_time / device_name / coherent-build / ABI-radio refusal
// groups, the ":launch:" and daily seed material, sanitize() and seed() all agree.
package com.android.server.privacykit;

import android.privacykit.PrivacyKitIdentifierGenerator;

import java.util.Locale;

/**
 * Pure rule-evaluation logic for PrivacyKit-Native identity spoofing.
 * Stateless aside from the caller-supplied {@link Store} callback used to
 * persist STATIC-rule generated values (so the same package+key gets the
 * same generated value across app launches, not just within one process).
 *
 * <p>The "what does a plausible value for this key look like" half of the job
 * lives in {@link PrivacyKitIdentifierGenerator} (framework.jar) rather than
 * here, because the Settings UI has to mint format-correct values too - for the
 * Randomise controls on the Identifiers screen - and services.jar is not on an
 * app process's classpath. This class keeps the rule semantics; that one keeps
 * the formats; there is exactly one copy of each.
 */
public final class PrivacyKitRuleResolver {

    public static final int RULE_REAL = 0;
    public static final int RULE_STATIC = 1;
    public static final int RULE_PER_LAUNCH = 2;
    public static final int RULE_DAILY = 3;
    public static final int RULE_CUSTOM = 4;
    public static final int RULE_EMPTY = 5;

    public static final String KEY_ANDROID_ID = "android_id";
    public static final String KEY_SERIAL = "serial";
    public static final String KEY_IMEI = "imei";
    public static final String KEY_IMSI = "imsi";
    public static final String KEY_ICCID = "iccid";
    public static final String KEY_PHONE_NUMBER = "phone_number";
    public static final String KEY_BUILD_FINGERPRINT = "build_fingerprint";
    public static final String KEY_BUILD_MODEL = "build_model";
    public static final String KEY_BUILD_MANUFACTURER = "build_manufacturer";
    public static final String KEY_BUILD_BRAND = "build_brand";
    public static final String KEY_BUILD_DEVICE = "build_device";
    public static final String KEY_BUILD_PRODUCT = "build_product";
    public static final String KEY_BUILD_BOARD = "build_board";
    public static final String KEY_BUILD_HARDWARE = "build_hardware";
    public static final String KEY_WIFI_MAC = "wifi_mac";
    public static final String KEY_MEDIA_DRM_ID = "media_drm_id";
    public static final String KEY_BUILD_ID = "build_id";
    public static final String KEY_BUILD_SOC_MODEL = "build_soc_model";
    public static final String KEY_BUILD_SOC_MANUFACTURER = "build_soc_manufacturer";
    public static final String KEY_SUPPORTED_ABIS = "supported_abis";
    public static final String KEY_DEVICE_NAME = "device_name";
    public static final String KEY_SUPPORTED_32_BIT_ABIS = "supported_32_bit_abis";
    public static final String KEY_SUPPORTED_64_BIT_ABIS = "supported_64_bit_abis";
    public static final String KEY_BUILD_RADIO_VERSION = "build_radio_version";
    // Spellings below are the ones in PrivacyKitKeys; compared
    // character-for-character against that file, because a typo here silently
    // matches no rule and enforces nothing.
    public static final String KEY_BUILD_TYPE = "build_type";
    public static final String KEY_BUILD_TAGS = "build_tags";
    public static final String KEY_BUILD_DISPLAY = "build_display";
    public static final String KEY_BUILD_BOOTLOADER = "build_bootloader";
    public static final String KEY_BUILD_HOST = "build_host";
    public static final String KEY_BUILD_USER = "build_user";
    public static final String KEY_FIRST_INSTALL_TIME = "first_install_time";
    // Build.VERSION fields that are printed inside the fingerprint string, or are
    // read straight off the build that produced it. Same coherent identity as the
    // Build.* bundle above - see isCoherentBuildIdentityKey().
    public static final String KEY_OS_VERSION_RELEASE = "os_version_release";
    public static final String KEY_OS_VERSION_INCREMENTAL = "os_version_incremental";
    public static final String KEY_OS_CODENAME = "os_codename";
    public static final String KEY_OS_BASE_OS = "os_base_os";

    /** Persistence hook for STATIC-rule generated values, implemented by PrivacyKitProfileStore. */
    public interface Store {
        String getGeneratedValue(String packageName, String key);
        void putGeneratedValue(String packageName, String key, String value);
    }

    private PrivacyKitRuleResolver() {}

    public static boolean isValidRuleType(int ruleType) {
        return ruleType >= RULE_REAL && ruleType <= RULE_EMPTY;
    }

    /**
     * Per-key rule-type allow-list. Enforced here, in the backend, rather than
     * in a UI: {@link #resolve} consults it before anything else, so a refused
     * rule type yields the real value no matter what a Settings screen offers,
     * what a restored backup contains, or what any other client stores.
     *
     * <p>{@link #KEY_MEDIA_DRM_ID} (the Widevine device unique id) refuses:
     * <ul>
     *   <li>{@link #RULE_PER_LAUNCH} and {@link #RULE_DAILY} - streaming
     *       services bind this id to a "max N registered devices" entitlement,
     *       so handing out a fresh id on every launch (or every day) burns
     *       through the user's registration slots and can get the account
     *       flagged. A rotating id is actively worse for the user than none.
     *   <li>{@link #RULE_EMPTY} - MediaDrm#getPropertyByteArray is declared
     *       {@code @NonNull byte[]}, and app code does {@code id[0]} or
     *       {@code id.length == 32}; a zero-length array misbehaves or crashes.
     * </ul>
     * {@link #RULE_REAL}, {@link #RULE_STATIC} and {@link #RULE_CUSTOM} are
     * allowed.
     *
     * <p>The families this method refuses for, each with its own reason below:
     * {@link #KEY_ANDROID_ID} (no RULE_EMPTY), {@link #KEY_MEDIA_DRM_ID},
     * {@link #KEY_FIRST_INSTALL_TIME}, {@link #KEY_DEVICE_NAME},
     * {@link #isCoherentBuildIdentityKey} and {@link #isAbiOrRadioKey}. Every
     * other key allows every rule type.
     *
     * <p>PrivacyKitIdentifierCatalog.perKeyRuleTypes in the Settings app is a
     * hand-written mirror of this method (services.jar is not on an app's
     * classpath, so it cannot be queried). Any change here MUST land with the
     * matching change there in the same commit: a rule type offered by the
     * picker and refused here is a spoof the user is told about and never gets,
     * which is the exact defect class that audit exists to prevent.
     */
    public static boolean isRuleTypeAllowed(String key, int ruleType) {
        if (KEY_ANDROID_ID.equals(key)) {
            // RULE_EMPTY is refused. Settings.Secure.ANDROID_ID is documented as
            // a 64-bit hex string and app code treats it as one: it is used as a
            // map key, hashed, substring()-ed and length-checked all over the
            // ecosystem, and a zero-length SSAID makes those callers throw
            // rather than degrade. "No android_id" is also not a state a real
            // device can be in, so an empty one is a louder signal than the real
            // value it was hiding - the same argument that refuses RULE_EMPTY
            // for media_drm_id and first_install_time. Every other rule type is
            // allowed; the SSAID hook shape-checks the result to 16 lower-case
            // hex characters, so a malformed substitute already falls open.
            return ruleType != RULE_EMPTY;
        }
        if (KEY_MEDIA_DRM_ID.equals(key)) {
            return ruleType == RULE_REAL || ruleType == RULE_STATIC
                    || ruleType == RULE_CUSTOM;
        }
        if (KEY_FIRST_INSTALL_TIME.equals(key)) {
            // A spoofed install date must not move once an app has seen it.
            // It is a wall clock the app can write down and re-check, and can
            // cross-check against its own data directory, its signed-in account
            // and its server-side first request; one that jumps at every launch
            // or every midnight is a far louder signal than the true value it
            // was hiding. RULE_PER_LAUNCH and RULE_DAILY are refused for that
            // reason. RULE_EMPTY is refused because "" is not a timestamp - the
            // install-time hook keeps the real value for it anyway, so allowing
            // it only pretended to do something. Real, Static (minted once and
            // frozen per profile) and Custom are all stable.
            return ruleType == RULE_REAL || ruleType == RULE_STATIC
                    || ruleType == RULE_CUSTOM;
        }
        if (KEY_DEVICE_NAME.equals(key)) {
            // The device name is seeded from Build.MODEL (DatabaseHelper ->
            // R.string.def_device_name), so it belongs to the same coherent
            // identity as the Build.* bundle. A random draw would hand an app a
            // 16-hex string where a product name belongs, and a name that
            // disagrees with a spoofed build_model is a one-compare tell.
            // Real, or a template/Custom value a human chose, only.
            return ruleType == RULE_REAL || ruleType == RULE_CUSTOM;
        }
        if (isCoherentBuildIdentityKey(key)) {
            // A fingerprint-linked identity field has no coherent random
            // draw: an independent model/brand/soc/build_id contradicts the
            // fingerprint string and is a one-parse tell. Only Real or a
            // template/Custom bundle. Mirrors media_drm_id.
            return ruleType == RULE_REAL || ruleType == RULE_CUSTOM;
        }
        if (isAbiOrRadioKey(key)) {
            // A random ABI list breaks native-lib loading; a random baseband
            // is a fresh tell. Real/Custom (template) and Empty only.
            return ruleType == RULE_REAL || ruleType == RULE_CUSTOM
                    || ruleType == RULE_EMPTY;
        }
        return true;
    }

    private static boolean isCoherentBuildIdentityKey(String key) {
        switch (key) {
            case KEY_BUILD_FINGERPRINT: case KEY_BUILD_MODEL:
            case KEY_BUILD_MANUFACTURER: case KEY_BUILD_BRAND:
            case KEY_BUILD_DEVICE: case KEY_BUILD_PRODUCT:
            case KEY_BUILD_BOARD: case KEY_BUILD_HARDWARE:
            case KEY_BUILD_ID: case KEY_BUILD_SOC_MODEL:
            case KEY_BUILD_SOC_MANUFACTURER:
            // The six below belong to the same coherent build identity and
            // were missing from this list, with a consequence worse than
            // incoherence: none of them has a case in
            // PrivacyKitIdentifierGenerator, so RULE_STATIC / RULE_DAILY /
            // RULE_PER_LAUNCH all fell through to its generic 16-hex-character
            // fallback. For build_type and build_tags that meant handing an app
            // "3f2ac1..." where every Android build on earth reports "user" and
            // "release-keys" - the two strings a root / integrity detector reads
            // first, and which this ROM deliberately pins to the stock answer.
            // Randomising them did not hide the device, it announced it. Real,
            // or a Custom/template value that a human chose, only.
            case KEY_BUILD_TYPE: case KEY_BUILD_TAGS:
            case KEY_BUILD_DISPLAY: case KEY_BUILD_BOOTLOADER:
            case KEY_BUILD_HOST: case KEY_BUILD_USER:
            // The four Build.VERSION fields below joined this list in the same
            // pass, for the same reason and with the same consequence. Three of
            // them are printed inside the fingerprint verbatim -
            // brand/product/device:RELEASE/id/INCREMENTAL:type/tags - and
            // Build.VERSION.CODENAME / BASE_OS come off the same build, so an
            // independently drawn value contradicts the fingerprint on one
            // parse. And, exactly like build_type and build_tags before them,
            // none of the four has a case in PrivacyKitIdentifierGenerator, so
            // RULE_STATIC / RULE_DAILY / RULE_PER_LAUNCH fell through to its
            // generic 16-hex fallback: Build.VERSION.RELEASE would read
            // "3f2ac1..." where every Android device on earth reports "16", and
            // CODENAME would stop being "REL" - which is the single string a
            // preview/eng build is detected by. Randomising them announced the
            // spoof instead of hiding it. Real, or a coherent Custom/template
            // bundle, only.
            //
            // os_security_patch is deliberately NOT here: it is not part of the
            // fingerprint string, and PrivacyKitIdentifierGenerator does have a
            // case for it that emits a real YYYY-MM-DD patch level, so the
            // random rule types produce a plausible value for it.
            case KEY_OS_VERSION_RELEASE: case KEY_OS_VERSION_INCREMENTAL:
            case KEY_OS_CODENAME: case KEY_OS_BASE_OS:
                return true;
            default: return false;
        }
    }

    private static boolean isAbiOrRadioKey(String key) {
        switch (key) {
            case KEY_SUPPORTED_ABIS: case KEY_SUPPORTED_32_BIT_ABIS:
            case KEY_SUPPORTED_64_BIT_ABIS: case KEY_BUILD_RADIO_VERSION:
                return true;
            default: return false;
        }
    }

    /**
     * Supplies the per-launch token, and is called only when the rule actually
     * needs one.
     *
     * <p>It exists because the token is expensive and almost never used:
     * {@code PrivacyKitService#perLaunchToken} reads and parses
     * {@code /proc/<pid>/stat}, while only {@link #RULE_PER_LAUNCH} - one of
     * six rule types, and not the common one - ever looks at the result. Java
     * evaluates arguments eagerly, so passing the token as a {@code long} paid
     * that read on every single resolveIdentifier call, roughly forty times per
     * app launch, on a system_server binder thread.
     */
    public interface LaunchToken {
        long get();
    }

    /**
     * @deprecated pass a {@link LaunchToken} instead; a {@code long} argument
     *     is computed eagerly even for the five rule types that never read it.
     *     Kept so an out-of-tree caller still compiles.
     */
    @Deprecated
    public static String resolve(int ruleType, String packageName, String key,
            String realValue, String customValue, Store store, long launchToken) {
        return resolve(ruleType, packageName, key, realValue, customValue, store,
                () -> launchToken);
    }

    /**
     * Resolve the value to hand back to the caller for (packageName, key),
     * given the device's real value and the configured rule.
     *
     * <p>{@code launchToken} is consulted only by {@link #RULE_PER_LAUNCH}; a
     * null supplier is treated as token 0, the same "caller unidentifiable"
     * degradation that supplier is documented to return on failure.
     */
    public static String resolve(int ruleType, String packageName, String key,
            String realValue, String customValue, Store store, LaunchToken launchToken) {
        if (!isRuleTypeAllowed(key, ruleType)) {
            // Backend refusal - see isRuleTypeAllowed(). Fail open to the real
            // value rather than producing a substitute this key cannot carry.
            return realValue;
        }
        switch (ruleType) {
            case RULE_EMPTY:
                return "";
            case RULE_CUSTOM: {
                String custom = customValue != null && !customValue.isEmpty()
                        ? customValue : realValue;
                return sanitize(key, custom, realValue);
            }
            case RULE_STATIC: {
                if (store == null) return realValue;
                String cached = store.getGeneratedValue(packageName, key);
                if (cached == null) {
                    // Fresh entropy, NOT seed(packageName, key).
                    //
                    // The value is persisted on the next line and every later
                    // read comes from that cache, so a STATIC value is exactly
                    // as stable as it was before; the seed only ever decides
                    // what the *first* read mints. Deriving it from
                    // (packageName, key) made that first read a pure function
                    // of the package name, with two consequences that were both
                    // wrong:
                    //
                    //  - PrivacyKitProfileStore#cloneProfile documents that a
                    //    copied profile "mints a fresh value on first read: two
                    //    profiles of the same app are two different identities,
                    //    which is the entire point of having two". The cache is
                    //    keyed per profile, but the seed was not, so both
                    //    profiles minted the *same* identity and were silently
                    //    linked - the exact failure that comment says it avoids.
                    //  - setRule() drops a key's cache entry on every write, so
                    //    re-applying a STATIC rule is the natural way to ask for
                    //    a new value ("Randomise all" on the Identifiers screen
                    //    does exactly that, and it is the only way to re-roll
                    //    media_drm_id, whose generator needs the real blob's
                    //    byte length). With a deterministic seed that re-roll
                    //    silently returned the identical value.
                    //
                    // Cost of the change, stated rather than hidden: a backup
                    // does not carry generated.xml, so before this a restore
                    // happened to reproduce the same STATIC values and now mints
                    // new ones. That matches what importFromJson already
                    // intends - it deliberately drops cache entries "so they
                    // regenerate from the new rule" - and a per-app identity
                    // that survives a restore was never a promise the UI made.
                    cached = PrivacyKitIdentifierGenerator.generate(
                            key, PrivacyKitIdentifierGenerator.freshSeed(), realValue);
                    store.putGeneratedValue(packageName, key, cached);
                }
                return sanitize(key, cached, realValue);
            }
            case RULE_DAILY: {
                // Deterministic on purpose: there is no cache behind this rule,
                // so every read within the same day has to re-derive the same
                // value. The date is part of the seed material, which is what
                // rolls it over at midnight.
                String today = String.format(Locale.US, "%1$tY%1$tm%1$td", new java.util.Date());
                return generate(key, seed(packageName, key + ":" + today), realValue);
            }
            case RULE_PER_LAUNCH:
                // One value per app *launch*, and every read within that
                // launch must agree: an app that saw two different
                // android_ids in a single process would know it was being
                // spoofed - a worse tell than not spoofing at all.
                // launchToken (PrivacyKitService#perLaunchToken) is stable
                // for the lifetime of the reading process and changes only
                // when the app is relaunched. The old seed used
                // elapsedRealtimeNanos(), which changed on every call and
                // so minted a fresh value per read - incoherent within one
                // launch. launchToken == 0 (caller unidentifiable) degrades
                // to a value stable per (package, key): still coherent,
                // just not re-rolled across launches.
                // This is the one branch that pays for the token, which is
                // why it is pulled here and not in the argument list.
                final long token = launchToken != null ? launchToken.get() : 0L;
                return generate(key, seed(packageName, key + ":launch:" + token),
                        realValue);
            case RULE_REAL:
            default:
                return realValue;
        }
    }

    /**
     * Generate a plausible value for {@code key} without configuring, applying
     * or persisting anything - the "generate but do not apply" entry point the
     * Randomise controls in Settings need.
     *
     * <p>Note that the Settings UI does <em>not</em> reach this method: this
     * class lives in services.jar, which is not on an app process's classpath,
     * and the one binder call that could expose a generated value
     * ({@code IPrivacyKitManager#resolveIdentifier}) is scoped by
     * {@code enforceCallerIsPackage} to the caller's own package. The UI calls
     * {@link PrivacyKitIdentifierGenerator} directly instead, which is the same
     * code this delegates to, so no new AIDL transaction was needed and there
     * is still exactly one implementation of every format rule. This wrapper
     * exists for server-side callers and so that a future AIDL surface has an
     * obvious thing to call.
     *
     * <p>Callers must gate on {@link PrivacyKitIdentifierGenerator#hasGeneratorFor}
     * - a key without a dedicated case falls through to an opaque hex string,
     * which is not a plausible model name, ABI list or time zone id - and on
     * {@link PrivacyKitIdentifierGenerator#needsRealValue} when they cannot
     * supply {@code realValue}.
     */
    public static String generateWithoutApplying(String key, String realValue) {
        return PrivacyKitIdentifierGenerator.generateFresh(key, realValue);
    }

    /**
     * Shape check for keys whose consumer needs a specific format. Returns
     * {@code realValue} instead of a malformed substitute.
     *
     * <p>Only {@link #KEY_MEDIA_DRM_ID} is constrained today: its consumer
     * decodes the string as hex and hands the bytes to an app that size-checks
     * them, so a value that is not hex of exactly the real byte length is worse
     * than no spoof at all. Common human spellings of a hex blob (a "0x" prefix,
     * ":"/"-"/"_"/space separators, upper case) are normalised rather than
     * rejected, so a hand-typed RULE_CUSTOM value still works.
     *
     * <p>No-op for every other key.
     */
    private static String sanitize(String key, String value, String realValue) {
        if (!KEY_MEDIA_DRM_ID.equals(key)) {
            return value;
        }
        if (value == null || realValue == null) {
            return realValue;
        }
        String hex = value.trim();
        if (hex.length() > 2 && (hex.startsWith("0x") || hex.startsWith("0X"))) {
            hex = hex.substring(2);
        }
        StringBuilder sb = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (c == ':' || c == '-' || c == '_' || c == ' ') {
                continue;
            }
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            } else if (c >= 'A' && c <= 'F') {
                sb.append((char) (c - 'A' + 'a'));
            } else {
                return realValue; // not a hex blob at all
            }
        }
        String normalized = sb.toString();
        if (normalized.length() != realValue.length()) {
            return realValue; // wrong byte length
        }
        return normalized;
    }

    private static long seed(String packageName, String seedMaterial) {
        String combined = packageName + "|" + seedMaterial;
        long h = 1125899906842597L; // prime
        for (int i = 0; i < combined.length(); i++) {
            h = 31 * h + combined.charAt(i);
        }
        return h;
    }

    /**
     * Deterministic, per-identifier-type value generation from a seed.
     *
     * <p>The per-key format rules moved to
     * {@link PrivacyKitIdentifierGenerator} so that the Settings UI - which
     * cannot see this package - mints values from the same switch instead of
     * from a second, drifting copy. Nothing about the output changed: the draw
     * order from the seeded Random is byte-for-byte the same as before the move,
     * and a value already persisted in the STATIC cache is never regenerated
     * anyway.
     */
    private static String generate(String key, long seed, String realValue) {
        return PrivacyKitIdentifierGenerator.generate(key, seed, realValue);
    }
}
