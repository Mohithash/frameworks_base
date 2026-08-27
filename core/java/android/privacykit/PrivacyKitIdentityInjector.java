/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged.
 *
 * Sources, in order of authority:
 *   1. snap18c/patches/frameworks_base.patch - the vanished base commit's copy
 *      of this file was rebuilt from a complete 2026-08-16 08:51 UTC snapshot
 *      recovered from the Claude Code transcripts (923 lines, whole file) by
 *      reverse-applying the seven edits of scratchpad/pk_inj/pkinj_edit.py,
 *      which the same transcripts preserve verbatim. The patch then applied to
 *      that base with `git apply` at ZERO FUZZ - all 11 hunks, every context
 *      line matched - producing this file at 1165 lines (base 839 + 326).
 *   2. the r49 ROM (2026-08-18 13:44 UTC) -
 *      android.privacykit.PrivacyKitIdentityInjector from framework.jar.
 *      Cross-check only: identical field set, identical method set, identical
 *      control flow, and identical string literals modulo source-level line
 *      splitting (the decompiler shows the concatenated form) and three
 *      constants the decompiler resolved to same-valued platform symbols -
 *      "TYPE", ")" and "gsm.version.baseband".
 *   3. the transcript snapshots, for the prose.
 *
 * ONE KNOWN AMBIGUITY, flagged rather than guessed: getManager() here reads
 * ServiceManager.getService("privacykit") with a string literal, which is what
 * the recovered base carried and what the patch left untouched. jadx renders
 * the r49 bytecode as ServiceManager.getService(Context.PRIVACYKIT_SERVICE) -
 * an inlined compile-time String constant it resolved back to a same-valued
 * symbol. Both compile to the same code; the literal is used because
 * Context.PRIVACYKIT_SERVICE is NOT added by frameworks_base.patch and is NOT
 * present in the freshly synced Context.java, i.e. that constant's declaration
 * is itself a casualty of the purge. If Context.PRIVACYKIT_SERVICE is restored,
 * switching this one call site back is cosmetic.
 */
package android.privacykit;

import android.app.ILocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Called from ActivityThread.handleBindApplication(), before any app code
 * runs, to apply PrivacyKit-Native per-app identity overrides.
 *
 * Deliberately maximally defensive: this runs on every app cold start, in
 * every app's own process. Any failure here must never prevent the app from
 * launching, so every external call (binder, reflection) is wrapped and
 * failures are silently swallowed after a single debug log line. In
 * particular every single field is applied independently - one failing field
 * never aborts the remaining ones, and the app always keeps the real value
 * for anything that could not be applied (fail open, never fail closed).
 *
 * Scope and known limits (do not over-claim these in the UI):
 *  - This rewrites the java.lang class members of android.os.Build in *this
 *    one app process*. It does not touch the underlying system properties, so
 *    a reader that goes through SystemProperties.get(), __system_property_get()
 *    from native/NDK code, /system/build.prop or WebView's user-agent string
 *    still observes the real device.
 *  - The java.lang.System properties are NOT writable here either. Android
 *    seeds "os.version", "os.arch", "os.name", "user.language", "user.region"
 *    and "user.variant" into System's *unchangeable* property table, and
 *    PropertiesWithNonOverrideableDefaults#put silently discards any later
 *    write to one of them. See the "DELIBERATELY NOT INJECTED" block below;
 *    this is why there is no os.version override in this class any more.
 *  - Build#getRadioVersion() recomputes from TelephonyProperties on every call
 *    rather than reading Build#RADIO, so overriding the RADIO field does not
 *    affect it. Covering it would need a change inside android.os.Build itself.
 *  - Build.VERSION#SDK_INT and the other int version fields are deliberately
 *    NOT overridden. See the "DELIBERATELY NOT SPOOFED" comment block below.
 *  - The locale override has two halves: LocaleList#setDefault, which moves
 *    Locale#getDefault and every formatter derived from it inside this process
 *    immediately, and LocaleManager#setApplicationLocales, which moves the
 *    app's resource Configuration so the two agree. See applyLocale().
 *
 * There are two entry points, because handleBindApplication() installs the
 * system locale and time zone *after* the point where the Build.* overrides
 * have to be written:
 *  - {@link #maybeApply} runs first and covers everything reflective.
 *  - {@link #maybeApplyLocaleAndTimeZone} runs later in the same method, once
 *    the platform has finished setting the real locale and time zone, and is
 *    the only place those two may be overridden.
 * Both still run long before the Application object is constructed.
 *
 * @hide
 */
public final class PrivacyKitIdentityInjector {
    private static final String TAG = "PrivacyKitIdentity";

    // ------------------------------------------------------------------------
    // DELIBERATELY NOT SPOOFED: Build.VERSION#SDK_INT and friends.
    //
    // SDK_INT / SDK_INT_FULL / RESOURCES_SDK_INT / DEVICE_INITIAL_SDK_INT /
    // PREVIEW_SDK_INT / MEDIA_PERFORMANCE_CLASS, and the deprecated String
    // mirror Build.VERSION#SDK, are all reflectively writable, but overriding
    // them here is not safe:
    //
    //  1. Apps and every AndroidX/Jetpack compat shim branch on SDK_INT to
    //     decide which platform APIs *exist* ("if (SDK_INT >= 34) newApi()").
    //     Reporting a higher value makes that code call methods that genuinely
    //     are not present -> NoSuchMethodError/NoClassDefFoundError deep inside
    //     app code. Reporting a lower value pushes apps down legacy paths that
    //     the running platform no longer honours.
    //  2. The framework itself reads SDK_INT from inside the app process (view
    //     inflation, resource compat, WebView), so the override would change
    //     platform behaviour, not just what the app observes.
    //  3. Several derived constants (RESOURCES_SDK_INT, IS_ENG/IS_USER, ...)
    //     are computed at class-init time, much of it in the zygote before the
    //     fork, so a late write leaves a half-spoofed, self-inconsistent state
    //     that is easier to detect than the real value would have been.
    //
    // Net: high crash risk, small privacy gain (the OS major version leaks via
    // the WebView User-Agent and a hundred other channels anyway). If this is
    // ever revisited it needs a per-app opt-in with a loud warning, not an
    // ordinary identifier rule. The same reasoning covers Build#TIME,
    // IS_DEBUGGABLE, IS_EMULATOR, HW_TIMEOUT_MULTIPLIER and the other
    // non-String Build fields: none of them is worth a typed write path today.
    //
    // Known residual incoherence from that choice: Build#IS_ENG, IS_USERDEBUG
    // and IS_USER are booleans computed at class-init time from the REAL
    // Build#TYPE, so overriding build_type does not move them. They are @hide,
    // so an app needs reflection to read them, but a determined detector can
    // compare them against the spoofed TYPE. Fixing that means writing
    // framework-visible debug flags inside the app process, which can change
    // platform behaviour (StrictMode, WebView), so it is left alone.
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // DELIBERATELY NOT INJECTED. Audited 2026-08-16 by reading the platform
    // source for each one; recorded here so the next pass does not re-derive it
    // and, more importantly, does not "helpfully" add a hook that cannot work.
    //
    //  1. kernel_version (System.getProperty("os.version")). THIS USED TO HAVE
    //     AN OVERRIDE HERE AND IT NEVER WORKED. java.lang.System keeps two
    //     property tables: `unchangeableProps`, built by
    //     initUnchangeableSystemProperties(), and `props`, which is a
    //     PropertiesWithNonOverrideableDefaults wrapping it (System.java:1799
    //     and :1800 in the static initialiser, so the order is guaranteed).
    //     "os.version" is written into the unchangeable table at System.java
    //     :1090 (`p.put("os.version", info.release)`), and
    //     PropertiesWithNonOverrideableDefaults#put (System.java:991) does
    //     `if (defaults.containsKey(key)) { logE(...); return defaults.get(key); }`
    //     - i.e. System.setProperty("os.version", x) is a guaranteed silent
    //     no-op that only emits a log line. getProperty() then still resolves
    //     through to the real value in `defaults`. The old code therefore paid a
    //     binder round trip per configured app and changed nothing, so it has
    //     been deleted rather than left to look like enforcement.
    //     There is also no second Java surface worth chasing: os.version is
    //     seeded from Libcore.os.uname().release, and android.system.Os#uname()
    //     is public SDK, needs no permission, and reads the kernel directly - so
    //     even a working property write would be one string compare away from
    //     detection. kernel_version is NOT enforceable from this process.
    //
    //  2. The java.lang.System *locale* properties, for the same reason.
    //     "user.language", "user.region" and "user.variant" are installed with
    //     setUnchangeableSystemProperty() (System.java:1167-1169, :1179-:1183)
    //     and "user.locale" arrives as a -Duser.locale= runtime argument
    //     (AndroidRuntime.cpp:1098) which parsePropertyAssignments() folds into
    //     the same unchangeable table - all four are therefore unwritable.
    //     Note also that Android spells it "user.region"; "user.country" and
    //     "user.script" are simply absent on a stock device (java.util.Locale
    //     reads user.region first at Locale.java:1180 and only falls back to
    //     user.country at :1197). Writing those two would SUCCEED and would
    //     invent properties no real Android device has - a brand new
    //     fingerprinting signal handed to a detector, in a tool whose whole job
    //     is to remove them. The locale override consequently touches
    //     LocaleList/Locale only, and nothing under System.
    //
    //  3. hostname. Nothing identifying is reachable. init.rc:1190 runs
    //     `hostname localhost`, so /proc/sys/kernel/hostname - and therefore
    //     Os.uname().nodename and InetAddress.getLocalHost().getHostName(),
    //     which is literally `Libcore.os.uname().nodename` at
    //     InetAddress.java:1487 - is the constant "localhost" on every unit of
    //     every device running this ROM: zero entropy, nothing to hide. The
    //     legacy net.hostname property does not exist anywhere in this tree.
    //     The only genuinely per-device "hostname" is Settings.Global#DEVICE_NAME
    //     ("Sal's Phone"), which is @Readable by any app with no permission -
    //     but it is served cross-process by SettingsProvider and cannot be
    //     reached from the app process at all, so it is out of scope for this
    //     class. If it is ever implemented it belongs next to the SSAID hook in
    //     SettingsProvider#mascaradeSsaidSetting, and the catalog row should be
    //     relabelled "Device name", because "Hostname" points at the wrong value.
    //
    //  4. bluetooth_mac. Already neutralised by the platform, so a hook would be
    //     pure noise: BluetoothServiceBinder#getAddress
    //     (packages/modules/Bluetooth/service/.../BluetoothServiceBinder.java:170)
    //     returns the constant IBluetoothManager.DEFAULT_MAC_ADDRESS,
    //     "02:00:00:00:00:00", to every caller that does not hold
    //     LOCAL_MAC_ADDRESS (signature|privileged, so no third-party app can
    //     hold it) and is not privileged Android Auto. BluetoothAdapter
    //     #getAddress() is the only public reader and routes there. Spoofing a
    //     value that is already a shared constant would make the user MORE
    //     distinguishable, not less. It is also a mainline module, i.e. the same
    //     API boundary that forced the wifi_mac revert.
    //
    //  5. first_install_time. Reachable only cross-process (ComputerEngine.java
    //     :1741 fills PackageInfo#firstInstallTime inside the PMS snapshot, a
    //     hot path under PMS locking where a synchronous binder out-call to
    //     PrivacyKitService is a deadlock risk), and an app-process hook on
    //     ApplicationPackageManager would be self-defeating: the app can read
    //     the mtime of its own APK (getApplicationInfo().sourceDir, always
    //     readable - it is on its own classpath) and of its data dir, both
    //     written at install time. Spoofing the PackageManager answer while
    //     those stay real manufactures a mismatch that is far easier to detect
    //     than the true install time it was hiding. Same reasoning as the
    //     SDK_INT block above: a half-spoofed, self-inconsistent value is worse
    //     than none.
    //
    //  6. last_boot_time. No safe choke point exists. Apps derive it as
    //     System.currentTimeMillis() - SystemClock.elapsedRealtime(), i.e.
    //     arithmetic in app code, and elapsedRealtime() is the clock the
    //     framework itself runs on inside this very process (Choreographer
    //     frame scheduling, ANR timing, input dispatch). Making it lie - or
    //     making it non-monotonic - breaks the app and the platform, for an
    //     identifier that also leaks through /proc/stat btime and /proc/uptime,
    //     neither of which is hookable from Java. Do not attempt this.
    // ------------------------------------------------------------------------

    /** A plain String-typed static field. */
    private static final int KIND_STRING = 0;
    /** A String[]-typed static field, carried over binder as a comma-separated list. */
    private static final int KIND_STRING_ARRAY = 1;

    /**
     * The complete set of ABI names Android recognises. Used to reject
     * implausible ABI lists before they reach Build.SUPPORTED_ABIS: apps pick
     * which .so to download/dlopen from that list, so a random string there
     * reliably breaks native library loading. This project already has
     * hard-won evidence that spoofing loader/ABI/hardware-adjacent values
     * (ro.hardware, ro.hardware.egl, ro.board.platform) crashes apps'
     * GLThread inside eglGetDisplay, so ABI values are validated, and the
     * hardware-adjacent SOC_* fields are String-only overrides that never
     * touch the underlying properties.
     */
    private static final String[] KNOWN_ABIS = {
            "arm64-v8a", "armeabi-v7a", "armeabi",
            "x86_64", "x86",
            "riscv64",
            "mips64", "mips",
    };

    /** One overridable static field. */
    private static final class FieldSpec {
        final String key;
        final Class<?> owner;
        final String fieldName;
        final int kind;
        /** When true, an explicitly empty resolved value is refused (fail open to real). */
        final boolean rejectEmpty;

        FieldSpec(String key, Class<?> owner, String fieldName, int kind, boolean rejectEmpty) {
            this.key = key;
            this.owner = owner;
            this.fieldName = fieldName;
            this.kind = kind;
            this.rejectEmpty = rejectEmpty;
        }

        String describe() {
            return (owner == Build.VERSION.class ? "Build.VERSION." : "Build.") + fieldName;
        }
    }

    private static final FieldSpec[] SPECS = {
            // The original eight - the coherent set a device template writes.
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_FINGERPRINT,
                    Build.class, "FINGERPRINT", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_MODEL,
                    Build.class, "MODEL", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_MANUFACTURER,
                    Build.class, "MANUFACTURER", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_BRAND,
                    Build.class, "BRAND", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_DEVICE,
                    Build.class, "DEVICE", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_PRODUCT,
                    Build.class, "PRODUCT", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_BOARD,
                    Build.class, "BOARD", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_HARDWARE,
                    Build.class, "HARDWARE", KIND_STRING, false),

            // Remaining String-typed Build.* fields.
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_ID,
                    Build.class, "ID", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_TYPE,
                    Build.class, "TYPE", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_TAGS,
                    Build.class, "TAGS", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_DISPLAY,
                    Build.class, "DISPLAY", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_BOOTLOADER,
                    Build.class, "BOOTLOADER", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_RADIO_VERSION,
                    Build.class, "RADIO", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_HOST,
                    Build.class, "HOST", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_USER,
                    Build.class, "USER", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_SOC_MANUFACTURER,
                    Build.class, "SOC_MANUFACTURER", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_BUILD_SOC_MODEL,
                    Build.class, "SOC_MODEL", KIND_STRING, false),

            // String[] ABI lists. SUPPORTED_ABIS refuses an empty result: no
            // real device has an empty primary ABI list, and an app that finds
            // one has no way to pick a native library at all.
            new FieldSpec(PrivacyKitKeys.KEY_SUPPORTED_ABIS,
                    Build.class, "SUPPORTED_ABIS", KIND_STRING_ARRAY, true),
            new FieldSpec(PrivacyKitKeys.KEY_SUPPORTED_32_BIT_ABIS,
                    Build.class, "SUPPORTED_32_BIT_ABIS", KIND_STRING_ARRAY, false),
            new FieldSpec(PrivacyKitKeys.KEY_SUPPORTED_64_BIT_ABIS,
                    Build.class, "SUPPORTED_64_BIT_ABIS", KIND_STRING_ARRAY, false),

            // Build.VERSION.* String fields. The int fields are intentionally
            // absent - see the "DELIBERATELY NOT SPOOFED" comment block above.
            new FieldSpec(PrivacyKitKeys.KEY_OS_VERSION_RELEASE,
                    Build.VERSION.class, "RELEASE", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_OS_VERSION_INCREMENTAL,
                    Build.VERSION.class, "INCREMENTAL", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_OS_SECURITY_PATCH,
                    Build.VERSION.class, "SECURITY_PATCH", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_OS_CODENAME,
                    Build.VERSION.class, "CODENAME", KIND_STRING, false),
            new FieldSpec(PrivacyKitKeys.KEY_OS_BASE_OS,
                    Build.VERSION.class, "BASE_OS", KIND_STRING, false),
    };

    /** Cached ART "un-final a field" handle; resolved lazily, once per process. */
    private static Field sAccessFlagsField;
    private static boolean sAccessFlagsResolved;

    /**
     * Whether this package has a PrivacyKit identity profile, as answered once
     * by maybeApply(). Lets the second entry point skip its work without paying
     * another binder round trip in the overwhelming majority of processes.
     */
    private static volatile boolean sHasProfile;

    /**
     * The overrides installed by maybeApplyLocaleAndTimeZone(), kept so they can
     * be re-installed after the platform resets the process defaults. Null
     * whenever no rule applied, which makes the re-apply hooks free.
     */
    private static volatile TimeZone sTimeZoneOverride;
    private static volatile Locale sLocaleOverride;
    private static volatile LocaleList sLocaleListOverride;

    /** ISO 639-3 code for "undetermined"; never a legitimate override. */
    private static final String UNDETERMINED_LANGUAGE = "und";

    private PrivacyKitIdentityInjector() {}

    /** Safe to call for every process, including system/platform ones - no-ops for those. */
    public static void maybeApply(String packageName) {
        try {
            applyInternal(packageName);
        } catch (Throwable t) {
            // Never let identity spoofing break app startup.
            Log.w(TAG, "maybeApply failed, continuing with real identity", t);
        }
    }

    private static void applyInternal(String packageName) {
        if (packageName == null) {
            return;
        }
        // Only ever-installed user apps are in scope, matching the same
        // FIRST_APPLICATION_UID boundary android.provider.Settings itself uses
        // for scoping the per-app SSAID. System/platform processes are never
        // touched.
        if (Process.myUid() < Process.FIRST_APPLICATION_UID) {
            return;
        }

        IPrivacyKitManager manager = getManager();
        if (manager == null) {
            return;
        }

        boolean hasProfile;
        try {
            hasProfile = manager.hasIdentityProfile(packageName);
        } catch (RemoteException e) {
            return;
        }
        if (!hasProfile) {
            return; // fast path: no PrivacyKit config for this app, one binder call total.
        }
        // Remembered for maybeApplyLocaleAndTimeZone(), which runs later in the
        // same handleBindApplication() and must not repeat this binder call.
        sHasProfile = true;

        // Only configured apps get here, so the extra binder round-trips (one
        // per field) are paid by a handful of packages, not by every cold start.
        final String[] realValues = new String[SPECS.length];
        final String[] appliedValues = new String[SPECS.length];
        for (int i = 0; i < SPECS.length; i++) {
            if (!applyOneField(manager, packageName, SPECS[i], i, realValues, appliedValues)) {
                return; // service went away - keep the real identity for the rest.
            }
        }

        applyCoherenceCompanions(realValues, appliedValues);

        // Seal the per-process native property table (STEP 4): every Build.*
        // override for this app has been mirrored, so no further pushes are
        // possible and the native mirror can never drift after startup.
        SystemProperties.sealPrivacyKitOverrides();
    }

    private static IPrivacyKitManager getManager() {
        IBinder binder = ServiceManager.getService("privacykit");
        if (binder == null) {
            return null; // PrivacyKitService not up yet (very early boot) - fail open.
        }
        return IPrivacyKitManager.Stub.asInterface(binder);
    }

    // ------------------------------------------------------------------------
    // Locale and time zone.
    //
    // These cannot be applied from maybeApply(), because handleBindApplication()
    // installs the real values *after* that call returns:
    //
    //     PrivacyKitIdentityInjector.maybeApply(...)          <- Build.* overrides
    //     ...
    //     TimeZone.setDefault(null);                          <- re-reads the system zone
    //     LocaleList.setDefault(data.config.getLocales());     <- installs the system locales
    //     ...
    //     mConfigurationController.updateLocaleListFromAppContext(appContext);
    //
    // An override written any earlier is silently wiped before the app can
    // observe it. maybeApplyLocaleAndTimeZone() therefore runs immediately after
    // the last of those, which is still far ahead of any app code: the
    // Application object is not built until makeApplicationInner(), roughly a
    // hundred lines further down the same method.
    //
    // The platform can also reset both defaults *after* bind time, so each
    // override is cached and re-installed by the two reapply* hooks below.
    // ------------------------------------------------------------------------

    /**
     * Second entry point. Applies the per-app locale and time zone overrides,
     * if any are configured. Safe to call for every process, including
     * system/platform ones - it no-ops unless maybeApply() already established
     * that this package has a PrivacyKit identity profile.
     */
    public static void maybeApplyLocaleAndTimeZone(String packageName) {
        try {
            if (!sHasProfile || packageName == null) {
                return;
            }
            IPrivacyKitManager manager = getManager();
            if (manager == null) {
                return;
            }
            applyTimeZone(manager, packageName);
            applyLocale(manager, packageName);
        } catch (Throwable t) {
            // Never let identity spoofing break app startup.
            Log.w(TAG, "maybeApplyLocaleAndTimeZone failed, continuing with the real "
                    + "locale and time zone", t);
        }
    }

    /**
     * Re-installs the spoofed time zone after the platform has cleared it.
     * ActivityThread's updateTimeZone() callback fires whenever the *system*
     * time zone changes and calls TimeZone.setDefault(null), which would
     * otherwise hand a configured app its real zone back mid-session.
     *
     * Allocation-free and lock-free when no override is active, which is the
     * case in every process without a time zone rule.
     */
    public static void reapplyTimeZoneOverride() {
        final TimeZone zone = sTimeZoneOverride;
        if (zone == null) {
            return;
        }
        try {
            TimeZone.setDefault(zone);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to re-apply the PrivacyKit time zone override", t);
        }
    }

    /**
     * Re-installs the spoofed locale after the platform has replaced it.
     * ConfigurationController.updateLocaleListFromAppContext() is the single
     * funnel through which the app process installs the real locale list - it
     * runs at bind time and again on every configuration change (rotation,
     * theme, density, ...) - so without this hook the override would survive
     * only until the app's first config change.
     *
     * Allocation-free and lock-free when no override is active.
     */
    public static void reapplyLocaleOverride() {
        if (sLocaleOverride == null) {
            return;
        }
        try {
            installLocaleOverride();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to re-apply the PrivacyKit locale override", t);
        }
    }

    /**
     * Overrides the process-wide default time zone.
     *
     * libcore's TimeZone#setDefault also clears ICU4J's cached default, so this
     * one write covers java.util.TimeZone#getDefault,
     * android.icu.util.TimeZone#getDefault, java.time's ZoneId#systemDefault
     * and everything derived from them (Calendar, DateFormat, java.time
     * formatting) - which is what an app actually reads when it wants the
     * device's zone. The persist.sys.timezone system property is NOT rewritten,
     * so a reader going through SystemProperties, the TimeManager APIs or
     * native code still sees the real zone.
     *
     * <p>Each link of that chain was re-verified against this tree on
     * 2026-08-16 rather than assumed, because the UI is allowed to call this
     * key enforced only if the whole chain holds:
     * <ul>
     *   <li>java.util.TimeZone#setDefault stores the clone AND calls
     *       ExtendedTimeZone#clearDefaultTimeZone() (TimeZone.java:888-890);
     *   <li>that is com.ibm.icu.util.TimeZone#setICUDefault(null)
     *       (ExtendedTimeZone.java:88), which nulls ICU's cached defaultZone;
     *   <li>ICU's TimeZone#getDefault then lazily recomputes from
     *       java.util.TimeZone.getDefault() (TimeZone.java:969), i.e. from this
     *       override, so android.icu.util.TimeZone#getDefault() agrees;
     *   <li>java.time's ZoneId#systemDefault() reads TimeZone#getDefaultRef()
     *       (ZoneId.java:272), the same field.
     * </ul>
     * Note that "user.timezone" is deliberately NOT set: unlike the JDK,
     * Android never populates it (libcore's setDefaultZone(), the only writer,
     * is inside an Android-removed block), so System#getProperty returns null
     * on a real device and setting it would be a new artifact, not a fix.
     */
    private static void applyTimeZone(IPrivacyKitManager manager, String packageName) {
        try {
            TimeZone realZone = TimeZone.getDefault();
            String realId = realZone == null ? null : realZone.getID();
            if (realId == null) {
                return;
            }
            String resolved = manager.resolveIdentifier(
                    packageName, PrivacyKitKeys.KEY_DEVICE_TIMEZONE, realId);
            if (resolved == null) {
                return;
            }
            String candidate = resolved.trim();
            if (candidate.isEmpty() || candidate.equals(realId)) {
                return; // no rule, a no-op rule, or Empty - there is no empty time zone.
            }
            TimeZone zone = knownTimeZone(candidate);
            if (zone == null) {
                Log.w(TAG, "Ignoring implausible time zone id for "
                        + PrivacyKitKeys.KEY_DEVICE_TIMEZONE + " - keeping the real zone");
                return;
            }
            sTimeZoneOverride = zone;
            TimeZone.setDefault(zone);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply PrivacyKit override for the default time zone", t);
        }
    }

    /**
     * Overrides the process-wide default locale.
     *
     * LocaleList#setDefault feeds Locale#setDefault, which libcore also
     * propagates to ICU, so this covers Locale#getDefault,
     * Locale#getDefault(Category), LocaleList#getDefault,
     * LocaleList#getAdjustedDefault and every locale-sensitive formatter that
     * derives from them (String#format, DateFormat, Collator, NumberFormat).
     *
     * <p>Re-verified against this tree on 2026-08-16, same reason as the time
     * zone chain: LocaleList#setDefault(list, i) sets sLastDefaultLocale,
     * sDefaultLocaleList and sDefaultAdjustedLocaleList and calls
     * Locale#setDefault (LocaleList.java:604-615); Locale#setDefault sets both
     * Category defaults and NoImagePreloadHolder.defaultLocale, then calls
     * ICU#setDefaultLocale(newLocale.toLanguageTag()) (Locale.java:1283-1291),
     * so android.icu's default locale follows too.
     *
     * <p>This write alone used to be the whole override, and it was
     * self-defeating: it moved Locale#getDefault but left the app's resource
     * Configuration on the real locale, so
     * Resources#getConfiguration().getLocales() and Locale#getDefault()
     * disagreed. On a stock device they never disagree unless the app itself
     * caused it, so the disagreement was a cleaner signal than the locale it
     * was hiding. applyAppLocale() below closes that gap.
     */
    private static void applyLocale(IPrivacyKitManager manager, String packageName) {
        try {
            Locale realLocale = Locale.getDefault();
            if (realLocale == null) {
                return;
            }
            String realTag = realLocale.toLanguageTag();
            String resolved = manager.resolveIdentifier(
                    packageName, PrivacyKitKeys.KEY_DEVICE_LOCALE, realTag);
            if (resolved == null) {
                return;
            }
            String candidate = resolved.trim();
            if (candidate.isEmpty() || candidate.equals(realTag)) {
                return; // no rule, a no-op rule, or Empty - there is no empty locale.
            }
            Locale locale = parseLocaleTag(candidate);
            if (locale == null) {
                Log.w(TAG, "Ignoring implausible language tag for "
                        + PrivacyKitKeys.KEY_DEVICE_LOCALE + " - keeping the real locale");
                return;
            }
            sLocaleOverride = locale;
            sLocaleListOverride = new LocaleList(locale);
            installLocaleOverride();
            applyAppLocale(packageName, locale);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply PrivacyKit override for the default locale", t);
        }
    }

    private static void installLocaleOverride() {
        final Locale locale = sLocaleOverride;
        final LocaleList list = sLocaleListOverride;
        if (locale == null || list == null) {
            return;
        }
        if (locale.equals(Locale.getDefault()) && list.equals(LocaleList.getDefault())) {
            return; // already in force - do not churn the ICU default on every config change.
        }
        LocaleList.setDefault(list);
        // Deliberately nothing else. The java.lang.System locale properties are
        // NOT synced here: user.locale / user.language / user.region /
        // user.variant all live in System's unchangeable property table, so the
        // write is a silent no-op, and user.country / user.script do not exist
        // on Android at all, so writing them would invent a property no stock
        // device has - a new fingerprinting signal rather than a fix. See item 2
        // of the "DELIBERATELY NOT INJECTED" block above.
    }

    /**
     * Makes {@code locale} this package's app-specific locale, so the app's
     * resource Configuration reports the same thing installLocaleOverride()
     * just installed as the process default.
     *
     * WHY THIS ROUTE. Android has a first-class per-app locale API - the one
     * behind Settings > Apps > (app) > Language, and behind
     * AppCompatDelegate#setApplicationLocales - and it is the only supported
     * way to move an app's resource Configuration. LocaleManagerService owns
     * that state; it commits the change through
     * ActivityTaskManagerInternal#createPackageConfigurationUpdater, which is
     * the same path a per-app language change from Settings takes. Anything
     * else - intercepting each Configuration the system delivers, or editing
     * the live Configuration object behind Resources - would be re-implementing
     * that plumbing from the outside, would be undone by the next
     * configuration change, and would corrupt resource loading rather than
     * merely leak an identifier if it were even slightly wrong.
     *
     * NO PERMISSION IS NEEDED. LocaleManagerService only demands
     * CHANGE_CONFIGURATION when the caller is setting locales for a package it
     * does not own (isPackageOwnedByCaller in LocaleManagerService); this call
     * names our own package from our own process, so it takes the unprivileged
     * branch, exactly as an app using AppCompat does at startup.
     *
     * WHAT THE APP SEES. The value is persisted per package, so from the NEXT
     * launch onwards the process is created with the spoofed locale already in
     * its Configuration: ActivityThread installs it as the default itself and
     * the override in this class becomes a no-op (installLocaleOverride()
     * returns early once the two agree). On the first launch after the rule is
     * set the Configuration update arrives asynchronously, so that one launch
     * is still the partial case the process-default write covers.
     *
     * COSTS, STATED RATHER THAN HIDDEN.
     *  - The app's UI language really does change. That is not a side effect to
     *    be minimised, it is the point: an app told the device is ja-JP while
     *    rendering its en-US resources contradicts itself.
     *  - The setting outlives the rule. Clearing the PrivacyKit locale rule
     *    does not clear the app-specific locale, because this class cannot tell
     *    a value it set from one the user chose in Settings, and guessing wrong
     *    would silently throw away a real user preference. Undo is Settings >
     *    Apps > (app) > Language, or a service-side clear on rule removal,
     *    which belongs in PrivacyKitService rather than here.
     *  - LocaleManagerService broadcasts ACTION_LOCALE_CHANGED to the app and
     *    ACTION_APPLICATION_LOCALE_CHANGED to its installer whenever the value
     *    actually changes. Reading the current value first and returning when
     *    it already matches keeps that to the launch that changes it - and also
     *    avoids a file write, since LocaleManagerService persists modification
     *    info on every set call, no-op or not.
     *
     * Never throws: an isolated or SDK-sandbox process does not own the
     * package and will get a SecurityException here, which is caught, and the
     * app keeps the real resource locale.
     */
    private static void applyAppLocale(String packageName, Locale locale) {
        try {
            IBinder binder = ServiceManager.getService(Context.LOCALE_SERVICE);
            if (binder == null) {
                return; // LocaleManagerService not up - keep the process-default override only.
            }
            ILocaleManager localeManager = ILocaleManager.Stub.asInterface(binder);
            final int userId = UserHandle.myUserId();
            final String tag = locale.toLanguageTag();
            LocaleList current = localeManager.getApplicationLocales(packageName, userId);
            if (current != null && !current.isEmpty()
                    && tag.equalsIgnoreCase(current.get(0).toLanguageTag())) {
                return; // already in force - no write, no broadcast, no file churn.
            }
            localeManager.setApplicationLocales(
                    packageName, userId, new LocaleList(locale), /* fromDelegate= */ false);
        } catch (Throwable t) {
            Log.w(TAG, "Could not set the per-app locale; the app resource configuration "
                    + "keeps the real locale", t);
        }
    }

    /**
     * Returns the TimeZone for an id, or null when this device does not know it.
     *
     * TimeZone#getTimeZone silently falls back to GMT for anything unparseable,
     * so it cannot be trusted on its own: the rule resolver has no generator
     * for device_timezone and emits a 16-hex-character string for the random
     * rule types, which would otherwise quietly move every configured app to
     * UTC. Only an id that survives the round trip is accepted.
     */
    private static TimeZone knownTimeZone(String id) {
        TimeZone zone = TimeZone.getTimeZone(id);
        if (zone == null) {
            return null;
        }
        String resolvedId = zone.getID();
        if (id.equals(resolvedId)) {
            return zone;
        }
        // Offset ids such as "GMT+5" are legitimate but get normalised
        // ("GMT+05:00"), so the round trip alone would reject them. An
        // unparseable "GMT..." string degrades to plain "GMT" instead, which
        // this check excludes.
        if (id.length() > 3 && id.startsWith("GMT")
                && resolvedId.startsWith("GMT") && !"GMT".equals(resolvedId)) {
            return zone;
        }
        return null;
    }

    /**
     * Parses a locale override, or null when the value is not a plausible
     * language tag. Both the BCP-47 spelling ("pt-BR") and the Locale#toString
     * spelling ("pt_BR") are accepted.
     *
     * Locale#forLanguageTag never throws - it returns an undetermined locale
     * for anything it cannot parse - so the shape has to be checked by hand,
     * for the same reason the time zone id does.
     */
    private static Locale parseLocaleTag(String value) {
        Locale locale = Locale.forLanguageTag(value.replace('_', '-'));
        if (locale == null) {
            return null;
        }
        String language = locale.getLanguage();
        if (!isAsciiAlpha(language, 2, 3)
                || UNDETERMINED_LANGUAGE.equalsIgnoreCase(language)) {
            return null;
        }
        // A region is optional, but when present it is either an ISO 3166-1
        // alpha-2 code or a three-digit UN M.49 area code.
        String country = locale.getCountry();
        if (!country.isEmpty()
                && !isAsciiAlpha(country, 2, 2) && !isAsciiDigits(country, 3)) {
            return null;
        }
        return locale;
    }

    private static boolean isAsciiAlpha(String value, int minLength, int maxLength) {
        if (value == null || value.length() < minLength || value.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigits(String value, int length) {
        if (value == null || value.length() != length) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies a single field override. Returns false only when the PrivacyKit
     * service died, in which case the caller should stop trying; every other
     * failure is logged and swallowed so the remaining fields still get their
     * chance.
     */
    private static boolean applyOneField(IPrivacyKitManager manager, String packageName,
            FieldSpec spec, int index, String[] realValues, String[] appliedValues) {
        try {
            Field field = spec.owner.getDeclaredField(spec.fieldName);
            field.setAccessible(true);

            final String realValue;
            if (spec.kind == KIND_STRING_ARRAY) {
                String[] realArray = (String[]) field.get(null);
                realValue = realArray == null ? "" : TextUtils.join(",", realArray);
            } else {
                realValue = (String) field.get(null);
            }
            realValues[index] = realValue;

            final String resolved;
            try {
                resolved = manager.resolveIdentifier(packageName, spec.key, realValue);
            } catch (RemoteException e) {
                Log.w(TAG, "PrivacyKit service unavailable while resolving " + spec.key
                        + ", keeping the real identity for the remaining fields", e);
                return false;
            }
            if (resolved == null || resolved.equals(realValue)) {
                return true; // no rule configured, or a no-op rule: skip the reflection write.
            }
            if (spec.rejectEmpty && resolved.trim().isEmpty()) {
                Log.w(TAG, "Refusing to blank " + spec.describe() + " - keeping the real value");
                return true;
            }

            final Object newValue;
            if (spec.kind == KIND_STRING_ARRAY) {
                String[] parsed = parseAbiList(resolved, spec.rejectEmpty);
                if (parsed == null) {
                    // A randomised or malformed ABI list is worse than no spoof
                    // at all: apps resolve native libraries from it.
                    Log.w(TAG, "Ignoring implausible ABI list for " + spec.key
                            + " - keeping the real " + spec.describe());
                    return true;
                }
                newValue = parsed;
            } else {
                newValue = resolved;
            }

            clearFinalModifier(field);
            field.set(null, newValue);
            appliedValues[index] = resolved;
            pushNativePropOverrides(spec.key, appliedValues[index]);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply PrivacyKit override for " + spec.describe(), t);
        }
        return true;
    }

    /**
     * Keeps the fields that mirror an overridden one consistent with it. Each
     * mirror is only rewritten when it really did match its source on this
     * device, so a device that legitimately differs is left alone. Without
     * this, a detector that reads e.g. both VERSION.RELEASE and
     * VERSION.RELEASE_OR_CODENAME sees the spoof immediately.
     */
    private static void applyCoherenceCompanions(String[] realValues, String[] appliedValues) {
        // VERSION.RELEASE aliases.
        mirrorField(Build.VERSION.class, "RELEASE_OR_CODENAME",
                PrivacyKitKeys.KEY_OS_VERSION_RELEASE, realValues, appliedValues);
        mirrorField(Build.VERSION.class, "RELEASE_OR_PREVIEW_DISPLAY",
                PrivacyKitKeys.KEY_OS_VERSION_RELEASE, realValues, appliedValues);

        // The @TestApi attestation-ID mirrors of the product identity fields.
        mirrorField(Build.class, "BRAND_FOR_ATTESTATION",
                PrivacyKitKeys.KEY_BUILD_BRAND, realValues, appliedValues);
        mirrorField(Build.class, "PRODUCT_FOR_ATTESTATION",
                PrivacyKitKeys.KEY_BUILD_PRODUCT, realValues, appliedValues);
        mirrorField(Build.class, "DEVICE_FOR_ATTESTATION",
                PrivacyKitKeys.KEY_BUILD_DEVICE, realValues, appliedValues);
        mirrorField(Build.class, "MANUFACTURER_FOR_ATTESTATION",
                PrivacyKitKeys.KEY_BUILD_MANUFACTURER, realValues, appliedValues);
        mirrorField(Build.class, "MODEL_FOR_ATTESTATION",
                PrivacyKitKeys.KEY_BUILD_MODEL, realValues, appliedValues);

        // The deprecated CPU_ABI/CPU_ABI2 pair is just the first two entries of
        // SUPPORTED_ABIS, exactly as Build's own static initialiser builds them.
        applyCpuAbiCompanions(appliedValues);

        // RuntimeInit set http.agent from the REAL model before we ran; recompute it
        // from the spoofed Build fields so the default HTTP/HttpURLConnection UA and
        // System.getProperty("http.agent") stay coherent with Build.MODEL.
        refreshHttpAgentIfSpoofed(appliedValues);
    }

    /**
     * Rebuilds the default HTTP User-Agent from the (already spoofed) Build.MODEL,
     * Build.ID and VERSION.RELEASE_OR_CODENAME, mirroring RuntimeInit.getDefaultUserAgent()
     * byte-for-byte. Only runs when one of those UA inputs was actually overridden, and
     * fails open so a UA-refresh fault can never break app start.
     */
    private static void refreshHttpAgentIfSpoofed(String[] appliedValues) {
        try {
            if (!wasOverridden(appliedValues, PrivacyKitKeys.KEY_BUILD_MODEL)
                    && !wasOverridden(appliedValues, PrivacyKitKeys.KEY_BUILD_ID)
                    && !wasOverridden(appliedValues, PrivacyKitKeys.KEY_OS_VERSION_RELEASE)) {
                return; // no UA input changed - leave RuntimeInit's value alone.
            }
            StringBuilder r = new StringBuilder(64);
            r.append("Dalvik/").append(System.getProperty("java.vm.version"))
                    .append(" (Linux; U; Android ");
            String version = Build.VERSION.RELEASE_OR_CODENAME;
            r.append(version.length() > 0 ? version : "1.0");
            if ("REL".equals(Build.VERSION.CODENAME)) {
                String model = Build.MODEL; // already the spoofed value
                if (model.length() > 0) {
                    r.append("; ").append(model);
                }
            }
            String id = Build.ID; // already the spoofed value
            if (id.length() > 0) {
                r.append(" Build/").append(id);
            }
            r.append(")");
            System.setProperty("http.agent", r.toString());
        } catch (Throwable t) {
            Log.w(TAG, "PrivacyKit: failed to refresh http.agent", t);
        }
    }

    /** True when [appliedValues] carries a non-null override for [key]. */
    private static boolean wasOverridden(String[] appliedValues, String key) {
        int index = indexOfKey(key);
        return index >= 0 && appliedValues[index] != null;
    }

    private static void mirrorField(Class<?> owner, String companionFieldName, String sourceKey,
            String[] realValues, String[] appliedValues) {
        try {
            int index = indexOfKey(sourceKey);
            if (index < 0 || appliedValues[index] == null) {
                return; // the source field was not overridden.
            }
            Field field = owner.getDeclaredField(companionFieldName);
            field.setAccessible(true);
            String companionReal = (String) field.get(null);
            if (companionReal == null || !companionReal.equals(realValues[index])) {
                return; // already differed on the real device - leave it alone.
            }
            clearFinalModifier(field);
            field.set(null, appliedValues[index]);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to mirror PrivacyKit override onto " + companionFieldName, t);
        }
    }

    private static void applyCpuAbiCompanions(String[] appliedValues) {
        try {
            int index = indexOfKey(PrivacyKitKeys.KEY_SUPPORTED_ABIS);
            if (index < 0 || appliedValues[index] == null) {
                return;
            }
            Field abisField = Build.class.getDeclaredField("SUPPORTED_ABIS");
            abisField.setAccessible(true);
            String[] abis = (String[]) abisField.get(null);
            if (abis == null || abis.length == 0) {
                return; // nothing sensible to derive CPU_ABI from.
            }
            setStringField(Build.class, "CPU_ABI", abis[0]);
            setStringField(Build.class, "CPU_ABI2", abis.length > 1 ? abis[1] : "");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to derive Build.CPU_ABI from the overridden ABI list", t);
        }
    }

    private static void setStringField(Class<?> owner, String fieldName, String value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            clearFinalModifier(field);
            field.set(null, value);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to apply PrivacyKit override for " + fieldName, t);
        }
    }

    /**
     * Splits a comma-separated ABI list, rejecting anything that is not a real
     * Android ABI name. Returns null when the value must not be applied, which
     * the caller treats as "keep the real list".
     */
    private static String[] parseAbiList(String value, boolean rejectEmpty) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return rejectEmpty ? null : new String[0];
        }
        String[] raw = trimmed.split(",");
        ArrayList<String> out = new ArrayList<>(raw.length);
        for (String candidate : raw) {
            String abi = canonicalAbi(candidate.trim());
            if (abi == null) {
                return null; // one bad entry poisons the whole list.
            }
            if (!out.contains(abi)) {
                out.add(abi);
            }
        }
        if (out.isEmpty()) {
            return rejectEmpty ? null : new String[0];
        }
        return out.toArray(new String[out.size()]);
    }

    /** The canonical spelling of a known ABI name, or null if it is not one. */
    private static String canonicalAbi(String candidate) {
        if (TextUtils.isEmpty(candidate)) {
            return null;
        }
        for (String known : KNOWN_ABIS) {
            if (known.equalsIgnoreCase(candidate)) {
                return known;
            }
        }
        return null;
    }

    /**
     * STEP 4: static allowlist mapping a PrivacyKit identity key to the
     * read-only system properties that must report the same value as the Java
     * Build.* field it drives. Only safe identity strings are mapped; graphics
     * / loader / HAL / ABI props (ro.board.platform, ro.hardware*, ro.soc.*,
     * ro.product.cpu.abilist*, egl/gralloc/vulkan) are deliberately absent and
     * stay real - libc also hard-denies them as a second line of defence.
     *
     * <p>ro.build.type and ro.build.tags are absent for a different reason,
     * spelled out at the point where they used to be mapped below: this ROM
     * pins them, and a per-app override consulted ahead of that pin can only
     * make the device more detectable, never less.
     */
    private static final HashMap<String, String[]> PK_NATIVE_PROPS = new HashMap<>();
    static {
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_MODEL, new String[] {
                "ro.product.model", "ro.product.system.model", "ro.product.vendor.model",
                "ro.product.odm.model", "ro.product.product.model",
                "ro.product.system_ext.model" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_BRAND, new String[] {
                "ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand",
                "ro.product.odm.brand", "ro.product.product.brand",
                "ro.product.system_ext.brand" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_MANUFACTURER, new String[] {
                "ro.product.manufacturer", "ro.product.system.manufacturer",
                "ro.product.vendor.manufacturer", "ro.product.odm.manufacturer",
                "ro.product.product.manufacturer", "ro.product.system_ext.manufacturer" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_DEVICE, new String[] {
                "ro.product.device", "ro.product.system.device", "ro.product.vendor.device",
                "ro.product.odm.device", "ro.product.product.device",
                "ro.product.system_ext.device" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_PRODUCT, new String[] {
                "ro.product.name", "ro.product.system.name", "ro.product.vendor.name",
                "ro.product.odm.name", "ro.product.product.name",
                "ro.product.system_ext.name" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_BOARD, new String[] {
                "ro.product.board" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_FINGERPRINT, new String[] {
                "ro.build.fingerprint" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_ID, new String[] {
                "ro.build.id" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_DISPLAY, new String[] {
                "ro.build.display.id" });
        // KEY_BUILD_TYPE -> ro.build.type and KEY_BUILD_TAGS -> ro.build.tags
        // are deliberately NOT mapped, and must not be added back.
        //
        // Those two properties are the ROM's own anti-detection posture: the
        // build pins them to the stock "user" / "release-keys" answer precisely
        // because they are the first two strings every root, integrity and
        // environment detector reads. A PrivacyKit native override is consulted
        // BEFORE the pinned static value, so mapping them here let a per-app
        // rule overwrite the best answer the ROM has with a worse one - and,
        // before the allow-list fix in
        // PrivacyKitRuleResolver#isCoherentBuildIdentityKey, with 16 random hex
        // characters that no Android build has ever reported. That is not
        // spoofing; it is a beacon.
        //
        // The Java Build#TYPE and Build#TAGS fields are still overridable from
        // SPECS above under a Custom rule, so a deliberate template can still
        // move them in-process. Leaving the native properties pinned is the
        // safe half of that asymmetry: a detector that goes around Java to
        // __system_property_get sees exactly what the ROM wants it to see.
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_HOST, new String[] {
                "ro.build.host" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_USER, new String[] {
                "ro.build.user" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_BOOTLOADER, new String[] {
                "ro.bootloader" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_OS_VERSION_RELEASE, new String[] {
                "ro.build.version.release", "ro.build.version.release_or_codename" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_OS_VERSION_INCREMENTAL, new String[] {
                "ro.build.version.incremental" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_OS_SECURITY_PATCH, new String[] {
                "ro.build.version.security_patch" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_OS_CODENAME, new String[] {
                "ro.build.version.codename" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_OS_BASE_OS, new String[] {
                "ro.build.version.base_os" });
        PK_NATIVE_PROPS.put(PrivacyKitKeys.KEY_BUILD_RADIO_VERSION, new String[] {
                "gsm.version.baseband" });
    }

    /**
     * Mirrors an already-applied Build.* value onto its read-only system
     * properties so a native reader (SystemProperties.get,
     * __system_property_get) sees the same string as the Java field. Driven only
     * from the applied value - never a fresh resolve - so the two can never
     * disagree. Fails open: any error is logged and the real properties are left
     * untouched.
     */
    private static void pushNativePropOverrides(String key, String value) {
        if (value == null) {
            return;
        }
        try {
            String[] props = PK_NATIVE_PROPS.get(key);
            if (props == null) {
                return; // not an allowlisted identity key - keep the real property.
            }
            for (String prop : props) {
                SystemProperties.setPrivacyKitOverride(prop, value);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to push native property override for " + key, t);
        }
    }

    private static int indexOfKey(String key) {
        for (int i = 0; i < SPECS.length; i++) {
            if (SPECS[i].key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strips the final modifier from a static field so it can be reassigned.
     * ART spells the modifier word "accessFlags" (not OpenJDK's "modifiers").
     * Only ever called from the injector, which runs single-threaded on the
     * main thread during handleBindApplication.
     */
    private static void clearFinalModifier(Field field) throws NoSuchFieldException,
            IllegalAccessException {
        int mods = field.getModifiers();
        if (!Modifier.isFinal(mods)) {
            return;
        }
        if (!sAccessFlagsResolved) {
            sAccessFlagsResolved = true;
            try {
                Field accessFlags = Field.class.getDeclaredField("accessFlags");
                accessFlags.setAccessible(true);
                sAccessFlagsField = accessFlags;
            } catch (Throwable t) {
                sAccessFlagsField = null;
                Log.w(TAG, "Field.accessFlags unavailable, cannot un-final static fields", t);
            }
        }
        if (sAccessFlagsField == null) {
            throw new NoSuchFieldException("Field.accessFlags");
        }
        sAccessFlagsField.setInt(field, mods & ~Modifier.FINAL);
    }
}

