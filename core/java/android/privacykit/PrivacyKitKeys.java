/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged.
 *
 * Sources, in order of authority:
 *   1. snap18c/patches/frameworks_base.patch - the vanished base commit's copy
 *      of this file was rebuilt from the 2026-08-16 09:31 UTC transcript
 *      snapshot by reverse-applying the deltas the snapshot already carried;
 *      the patch then applied to it with `git apply` at ZERO FUZZ, which makes
 *      every line the patch touches byte-exact and pins the final length at
 *      457 lines (this file's length).
 *   2. the r49 ROM (2026-08-18 13:44 UTC) - android.privacykit.PrivacyKitKeys
 *      from framework.jar. Cross-check only: all 74 KEY_ and CONTROL_ constant
 *      names AND values, and both BUILD_KEYS (8) and INJECTOR_KEYS (28) arrays,
 *      match this file exactly.
 *   3. the transcript snapshot, for the prose.
 *
 * Nothing in this file is guessed.
 */
package android.privacykit;

/**
 * Identifier key string constants shared between app-process callers (the
 * PrivacyKit Settings UI, which cannot see server-internal classes) and the
 * framework-side PrivacyKitRuleResolver (com.android.server.privacykit),
 * which defines the same literal values as its own internal source of truth.
 * Keep these two definitions in sync if a key is ever renamed.
 *
 * Note that PrivacyKitRuleResolver only needs a matching KEY_* constant for
 * keys whose *generated* value has to follow a specific format (it switches on
 * the key inside its generator). Keys without a case there fall through to the
 * resolver's generic 16-hex-character fallback, which is fine for opaque
 * strings but is not a plausible value for structured fields such as
 * build_type or the ABI lists - those are expected to be driven by
 * RULE_CUSTOM (device templates) rather than by the random rule types.
 *
 * @hide
 */
public final class PrivacyKitKeys {
    private PrivacyKitKeys() {}

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

    /**
     * The Google Advertising ID (GAID). A resettable, device-wide advertising
     * identifier served by GmsCore over the dedicated
     * {@code com.google.android.gms.ads.identifier.service.START} bind; its
     * wire form is a canonical lower-case type-4 UUID (8-4-4-4-12).
     *
     * <p>The literal must stay equal to the private {@code KEY_ADVERTISING_ID}
     * in {@code PrivacyKitService} (a RESTRICTION_KEYS member there): a stored
     * non-Empty rule lifts CONTROL_BLOCK_ADVERTISING_ID and lets
     * {@code PrivacyKitGmsBinderFilter} substitute the getId() reply.
     * PARTIAL, never full - only the GMS bind route is covered.
     */
    public static final String KEY_ADVERTISING_ID = "advertising_id";

    /**
     * The Google App Set ID ({@code com.google.android.gms.appset}). A per-app
     * (or per-developer) identifier, stable until reinstall; wire form is a
     * canonical lower-case type-4 UUID (8-4-4-4-12). Layer-1 plumbing only
     * (stored value + generator); the in-process value-substitution hook is
     * deferred pending on-device pinning of the GMS transaction codes and
     * SafeParcel field ids - see the release notes.
     */
    public static final String KEY_APP_SET_ID = "app_set_id";

    /**
     * MediaDrm's {@code PROPERTY_DEVICE_UNIQUE_ID} ("deviceUniqueId") - the
     * Widevine device unique id. A hardware-provisioned blob that needs no
     * permission and no open session, survives a factory reset, and is
     * identical across every app on the device.
     *
     * <p>Enforced in the calling app's own process by
     * {@code android.media.MediaDrm#getPropertyByteArray}, which reads the
     * real plugin value first and substitutes only on success. If the plugin
     * rejects the property the original exception propagates untouched - a
     * fabricated value on an error path would itself be a detection signal.
     *
     * <p>The value crosses the binder as a lower-case hex string of the real
     * blob. A substitute is applied only if it decodes to hex of exactly the
     * real byte length, because apps size-check the array.
     *
     * <p>PARTIAL, not full. The NDK entry point
     * {@code AMediaDrm_getPropertyByteArray} (frameworks/av, exported from
     * libmediandk) reaches libmediadrm without passing through Java, so a
     * native caller still gets the real blob and could detect the spoof by
     * diffing the two. Do not label this key as fully spoofed in the UI.
     *
     * <p>Only RULE_REAL, RULE_STATIC and RULE_CUSTOM are honoured.
     * PrivacyKitRuleResolver refuses RULE_PER_LAUNCH and RULE_DAILY (streaming
     * services bind this id to a "max N registered devices" entitlement, so a
     * fresh id per launch burns the user's registration slots and can get the
     * account flagged) and RULE_EMPTY (the API is declared @NonNull byte[];
     * app code doing id[0] would crash on a zero-length array). Those
     * refusals live in the resolver, so they hold whatever a UI stores.
     */
    public static final String KEY_MEDIA_DRM_ID = "media_drm_id";

    /**
     * The GSF ID - the {@code android_id} row of the gservices ContentProvider
     * (authority {@code com.google.android.gsf.gservices}, hosted by GmsCore on
     * this build, since GSF's own providers are dropped at parse time). It is
     * device-wide, identical across every app, survives app reinstall, and
     * READ_GSERVICES is protectionLevel="normal" - any app can read it.
     *
     * <p>Enforced in the calling app's own process by
     * {@code ContentProviderProxy} (android/content/ContentProviderNative.java),
     * which rewrites both routes to the value: the {@code query()} result cursor
     * and the {@code call()} result bundle. Hooking only one would give an app
     * that reads it both ways two different answers.
     *
     * <p>The value is a DECIMAL 64-bit string, not hex: the canonical app-side
     * snippet does {@code Long.toHexString(Long.parseLong(value))}. The hook
     * refuses to substitute anything that is not a decimal long and keeps the
     * real value instead, so no app is ever handed something that makes its own
     * parse throw. PrivacyKitIdentifierGenerator#generate now has a dedicated
     * {@code gsf_id} case ({@code genGsfId}) that emits a 19-digit decimal long,
     * so RULE_STATIC / RULE_DAILY / RULE_PER_LAUNCH and a decimal RULE_CUSTOM all
     * produce a value the hook accepts and are enforced on the provider path.
     * RULE_EMPTY stays a no-op ("" is not a decimal long, so the hook keeps the
     * real id); the Settings catalog offers every rule type except Empty.
     *
     * <p>PARTIAL, never full. A provider-layer hook cannot cover the
     * {@code IGmsServiceBroker} transport, which has not been ruled out as an
     * alternative route to the same value, and GmsCore's own in-process reads
     * do not pass through the proxy at all. Do not label this key as fully
     * spoofed in the UI.
     */
    public static final String KEY_GSF_ID = "gsf_id";

    // ---- extended Build.* / Build.VERSION.* identity ---------------------------
    // All of the following are enforced in the app's own process by
    // PrivacyKitIdentityInjector, using the same reflective static-field
    // override as the eight KEY_BUILD_* keys above.

    /** android.os.Build#ID - "ro.build.id", e.g. "BP2A.250605.031". */
    public static final String KEY_BUILD_ID = "build_id";
    /** android.os.Build#TYPE - "ro.build.type", e.g. "user". */
    public static final String KEY_BUILD_TYPE = "build_type";
    /** android.os.Build#TAGS - "ro.build.tags", e.g. "release-keys". */
    public static final String KEY_BUILD_TAGS = "build_tags";
    /** android.os.Build#DISPLAY - "ro.build.display.id". */
    public static final String KEY_BUILD_DISPLAY = "build_display";
    /** android.os.Build#BOOTLOADER - "ro.bootloader". */
    public static final String KEY_BUILD_BOOTLOADER = "build_bootloader";
    /**
     * android.os.Build#RADIO (the deprecated baseband-version field only).
     * Build#getRadioVersion() recomputes from TelephonyProperties on every
     * call and is deliberately NOT covered here - see PrivacyKitIdentityInjector.
     */
    public static final String KEY_BUILD_RADIO_VERSION = "build_radio_version";
    /** android.os.Build#HOST - "ro.build.host" (build machine hostname). */
    public static final String KEY_BUILD_HOST = "build_host";
    /** android.os.Build#USER - "ro.build.user" (build machine user). */
    public static final String KEY_BUILD_USER = "build_user";
    /** android.os.Build#SOC_MANUFACTURER, e.g. "QTI". */
    public static final String KEY_BUILD_SOC_MANUFACTURER = "build_soc_manufacturer";
    /** android.os.Build#SOC_MODEL, e.g. "SM8635". */
    public static final String KEY_BUILD_SOC_MODEL = "build_soc_model";

    /** android.os.Build#SUPPORTED_ABIS - comma-separated ABI list. */
    public static final String KEY_SUPPORTED_ABIS = "supported_abis";
    /** android.os.Build#SUPPORTED_32_BIT_ABIS - comma-separated ABI list. */
    public static final String KEY_SUPPORTED_32_BIT_ABIS = "supported_32_bit_abis";
    /** android.os.Build#SUPPORTED_64_BIT_ABIS - comma-separated ABI list. */
    public static final String KEY_SUPPORTED_64_BIT_ABIS = "supported_64_bit_abis";

    /** android.os.Build.VERSION#RELEASE, e.g. "16". */
    public static final String KEY_OS_VERSION_RELEASE = "os_version_release";
    /** android.os.Build.VERSION#INCREMENTAL - "ro.build.version.incremental". */
    public static final String KEY_OS_VERSION_INCREMENTAL = "os_version_incremental";
    /** android.os.Build.VERSION#SECURITY_PATCH, e.g. "2026-06-05". */
    public static final String KEY_OS_SECURITY_PATCH = "os_security_patch";
    /** android.os.Build.VERSION#CODENAME - "REL" on release builds. */
    public static final String KEY_OS_CODENAME = "os_codename";
    /** android.os.Build.VERSION#BASE_OS - "ro.build.version.base_os" (often empty). */
    public static final String KEY_OS_BASE_OS = "os_base_os";

    /**
     * java.lang.System property "os.version" (the kernel release string).
     *
     * <p>NOT ENFORCED, and not enforceable from the app process. The UI must
     * not claim otherwise. This constant is kept only so the catalog row and
     * any stored rule keep a stable key.
     *
     * <p>The injector used to call System#setProperty("os.version", ...) here.
     * Audited 2026-08-16: that call could never have worked. "os.version" is
     * seeded into java.lang.System's *unchangeable* property table
     * (System.java:1090, inside initUnchangeableSystemProperties()), and the
     * live table is a PropertiesWithNonOverrideableDefaults wrapping it, whose
     * put() discards any write to a key the defaults already hold and merely
     * logs it (System.java:991). So every rule type was a silent no-op and the
     * app always read the real kernel release. The dead write has been removed.
     *
     * <p>It could not be rescued by writing somewhere else either:
     * "os.version" is itself just a copy of Libcore.os.uname().release, and
     * android.system.Os#uname() is public SDK, permission-free, and reads the
     * kernel directly - so the two values must agree or the spoof is one string
     * compare from detection. /proc/version and any native reader are likewise
     * untouched.
     *
     * <p>Deliberately absent from {@link #INJECTOR_KEYS} for that reason.
     * NOTE for the Settings catalog: PrivacyKitIdentifierCatalog correctly treats
     * "kernel_version" as NONE - it is in none of enforcedKeys,
     * partialEnforcementCaveats or notEnforcedReasons, so enforcementOf() returns
     * NONE and no rule type is ever advertised as taking effect for it. (An earlier
     * revision of this note claimed the catalog wrongly listed it as partially
     * enforced; that is no longer the case.)
     */
    public static final String KEY_KERNEL_VERSION = "kernel_version";

    // ---- locale / time zone identity -------------------------------------------
    // Both are applied in the app's own process by PrivacyKitIdentityInjector,
    // from a second entry point that runs *after* handleBindApplication() has
    // finished installing the system values (an override written any earlier is
    // wiped before the app ever observes it).
    //
    // Re-verified end to end on 2026-08-16, because the Settings catalog still
    // lists both of these under "no resolveIdentifier() call site" - that bullet
    // is now STALE and understates coverage. What was actually checked:
    //   * the hooks exist - PrivacyKitIdentityInjector#applyTimeZone and
    //     #applyLocale each call IPrivacyKitManager#resolveIdentifier with these
    //     exact key strings;
    //   * the timing is right - ActivityThread#handleBindApplication calls
    //     maybeApplyLocaleAndTimeZone() at :8083, i.e. after TimeZone
    //     .setDefault(null) (:7968), LocaleList.setDefault(...) (:7973) and
    //     updateLocaleListFromAppContext() have installed the real values, and
    //     ~100 lines before makeApplicationInner() (:8187) builds the
    //     Application - so it genuinely precedes all app code, including static
    //     initializers;
    //   * it survives - ActivityThread:1579 re-applies the zone after the
    //     platform's updateTimeZone() clears it, and ConfigurationController
    //     :296 re-applies the locale after every configuration change;
    //   * it reaches the APIs apps call - the java.util -> ICU -> java.time
    //     propagation chains are cited line by line in the two applyX() javadocs.
    // Nothing here touches system_server: maybeApply() returns early for any uid
    // below Process.FIRST_APPLICATION_UID, so no system state can be corrupted.
    //
    // Both expect a structured value, so - exactly like build_type and the ABI
    // lists - they are only useful under RULE_CUSTOM. The rule resolver has no
    // dedicated generator for either key, so RULE_STATIC / RULE_DAILY /
    // RULE_PER_LAUNCH produce its generic 16-hex-character fallback, which is
    // neither a time zone id nor a language tag; the injector validates the
    // value and keeps the real one rather than applying nonsense. RULE_EMPTY is
    // likewise refused - there is no such thing as an empty locale or zone.

    /**
     * The process-wide default time zone, as an IANA/Olson id ("Europe/Berlin",
     * "UTC", or a "GMT+05:30" offset id). Applied with TimeZone#setDefault,
     * which libcore also propagates to ICU4J, so it covers
     * java.util.TimeZone#getDefault, android.icu.util.TimeZone#getDefault and
     * java.time's ZoneId#systemDefault. The persist.sys.timezone system
     * property is NOT rewritten.
     */
    public static final String KEY_DEVICE_TIMEZONE = "device_timezone";

    /**
     * The process-wide default locale, as a BCP-47 language tag ("pt-BR"); the
     * java "pt_BR" spelling is accepted too. Applied with LocaleList#setDefault,
     * which covers Locale#getDefault, Locale#getDefault(Category),
     * LocaleList#getDefault and the ICU default locale.
     *
     * PARTIAL by construction: the app's *resource* configuration is not
     * touched, so Resources#getConfiguration().getLocales() still reports the
     * real locale. Do not label this key as full locale spoofing in the UI -
     * see PrivacyKitIdentityInjector for why the resource path is left alone.
     */
    public static final String KEY_DEVICE_LOCALE = "device_locale";

    // ---- r35: remaining identifier keys -------------------------------------
    // String values MUST stay equal to the Settings catalog entries: the UI and
    // the backend join on the raw key string, not on this constant.

    /** Settings.Global.adb_enabled, reported to the app as disabled. */
    public static final String KEY_ADB_ENABLED = "adb_enabled";

    /** Settings.Global.development_settings_enabled, reported as disabled. */
    public static final String KEY_DEVELOPER_OPTIONS = "developer_options_enabled";

    /** Settings.Global.adb_wifi_enabled, reported as disabled. */
    public static final String KEY_WIRELESS_DEBUGGING = "wireless_debugging";

    /** Settings.Global.package_verifier_user_consent, reported as denied. */
    public static final String KEY_PACKAGE_VERIFIER = "package_verifier";

    /** Settings.Global.verifier_verify_adb_installs, reported as disabled. */
    public static final String KEY_USB_APP_VERIFICATION = "usb_app_verification";

    /** Settings.Secure.enabled_accessibility_services, reported as an empty list. */
    public static final String KEY_ACCESSIBILITY_SERVICES = "accessibility_services";

    /** CDMA MEID from PhoneSubInfoController. */
    public static final String KEY_MEID = "meid";

    /** SIM MCC+MNC digits. Keep coherent with imsi/country. */
    public static final String KEY_SIM_OPERATOR = "sim_operator";

    /** SIM carrier display name. */
    public static final String KEY_SIM_OPERATOR_NAME = "sim_operator_name";

    /** Android carrier id (decimal int). */
    public static final String KEY_SIM_CARRIER_ID = "sim_carrier_id";

    /** Registered network MCC+MNC digits. */
    public static final String KEY_NETWORK_OPERATOR = "network_operator";

    /** Registered network display name. */
    public static final String KEY_NETWORK_OPERATOR_NAME = "network_operator_name";

    /** SIM country, lower-case ISO 3166-1 alpha-2. */
    public static final String KEY_SIM_COUNTRY_ISO = "sim_country_iso";

    /** Network country, lower-case ISO alpha-2. */
    public static final String KEY_NETWORK_COUNTRY_ISO = "network_country_iso";

    /** Serving-cell info; RULE_EMPTY yields an empty list. */
    public static final String KEY_CELL_INFO = "cell_info";

    /** Bluetooth adapter address. */
    public static final String KEY_BLUETOOTH_MAC = "bluetooth_mac";

    /** Connected AP BSSID. */
    public static final String KEY_WIFI_BSSID = "wifi_bssid";

    /** Connected AP SSID. */
    public static final String KEY_WIFI_SSID = "wifi_ssid";

    /**
     * Settings.Global#DEVICE_NAME and Settings.Secure#BLUETOOTH_NAME - the
     * user-visible device name. Both hold the same string on a stock device
     * and are deliberately spoofed to one value so they cannot disagree.
     *
     * Replaces the former KEY_HOSTNAME: net.hostname is the constant
     * "localhost" on this platform, so there was never a per-device value
     * there to spoof, and the key had no consumers.
     */
    public static final String KEY_DEVICE_NAME = "device_name";

    /** Nearby AP list; RULE_EMPTY yields empty. */
    public static final String KEY_WIFI_SCAN_RESULTS = "wifi_scan_results";

    /** Saved network list; RULE_EMPTY yields empty. */
    public static final String KEY_WIFI_CONFIGURED_NETWORKS = "wifi_configured_networks";

    /** PackageInfo.firstInstallTime. */
    public static final String KEY_FIRST_INSTALL_TIME = "first_install_time";

    /** Boot wall-clock; see catalog for why this is hard. */
    public static final String KEY_LAST_BOOT_TIME = "last_boot_time";

    /** Build.VERSION.SDK_INT. Deliberately never spoofed: it selects app code paths. */
    public static final String KEY_OS_SDK_INT = "os_sdk_int";

    /** AccountManager account list; RULE_EMPTY yields empty. */
    public static final String KEY_ACCOUNTS = "accounts";

    /** Firebase FID - app-process SDK local, needs a companion module. */
    public static final String KEY_FIREBASE_INSTALLATION_ID = "firebase_installation_id";

    /** Firebase App Instance ID - app-process SDK local, needs a companion module. */
    public static final String KEY_FIREBASE_APP_INSTANCE_ID = "firebase_app_instance_id";

    /** Per-app location override, stored as "lat,lon[,accuracy]" in the rule value. */
    public static final String KEY_LOCATION = "location";

    /**
     * The eight coherent Build.* identity fields that a device template covers.
     * Consumers (PrivacyKitBuildIdentityTogglePC, PrivacyKitDeviceProfilePC)
     * write all eight together so a spoofed fingerprint stays self-consistent.
     * Deliberately NOT extended with the keys above: those have no template
     * value, and blanket-randomising e.g. build_type or the ABI lists produces
     * an obviously-fake (and, for ABIs, actively harmful) identity.
     */
    public static final String[] BUILD_KEYS = {
            KEY_BUILD_FINGERPRINT, KEY_BUILD_MODEL, KEY_BUILD_MANUFACTURER, KEY_BUILD_BRAND,
            KEY_BUILD_DEVICE, KEY_BUILD_PRODUCT, KEY_BUILD_BOARD, KEY_BUILD_HARDWARE,
    };

    /**
     * Every key that PrivacyKitIdentityInjector genuinely enforces in the app's
     * own process. This is the authoritative list for the Settings UI's
     * "enforced" catalog for in-process identity; keys outside it are either
     * enforced elsewhere (android_id, serial, imei, imsi, iccid, phone_number)
     * or not enforced at all.
     *
     * "Enforced" here means a real write happens on the API an app reads. It
     * does not mean every rule type produces a usable value: the injector
     * validates structured keys (the ABI lists, device_timezone, device_locale)
     * and keeps the real value when the resolved string is not a plausible one,
     * which in practice restricts those keys to RULE_CUSTOM. It also does not
     * mean the underlying system property moves - none of these overrides is
     * visible to SystemProperties or to native readers, nor to java.lang.System
     * (whose os.* and user.* properties are in an unchangeable table this
     * process cannot write - see {@link #KEY_KERNEL_VERSION}).
     */
    public static final String[] INJECTOR_KEYS = {
            KEY_BUILD_FINGERPRINT, KEY_BUILD_MODEL, KEY_BUILD_MANUFACTURER, KEY_BUILD_BRAND,
            KEY_BUILD_DEVICE, KEY_BUILD_PRODUCT, KEY_BUILD_BOARD, KEY_BUILD_HARDWARE,
            KEY_BUILD_ID, KEY_BUILD_TYPE, KEY_BUILD_TAGS, KEY_BUILD_DISPLAY,
            KEY_BUILD_BOOTLOADER, KEY_BUILD_RADIO_VERSION, KEY_BUILD_HOST, KEY_BUILD_USER,
            KEY_BUILD_SOC_MANUFACTURER, KEY_BUILD_SOC_MODEL,
            KEY_SUPPORTED_ABIS, KEY_SUPPORTED_32_BIT_ABIS, KEY_SUPPORTED_64_BIT_ABIS,
            KEY_OS_VERSION_RELEASE, KEY_OS_VERSION_INCREMENTAL, KEY_OS_SECURITY_PATCH,
            KEY_OS_CODENAME, KEY_OS_BASE_OS,
            // KEY_KERNEL_VERSION is deliberately NOT here - the os.version
            // write was a guaranteed no-op and has been removed. See its doc.
            KEY_DEVICE_TIMEZONE, KEY_DEVICE_LOCALE,
    };

    // Boolean-control names for IPrivacyKitManager#getBooleanControl/setBooleanControl.
    // Must match PrivacyKitService.CONTROL_* exactly (server-side source of truth).
    public static final String CONTROL_RESTRICT_INTERNET = "restrict_internet";
    public static final String CONTROL_RESTRICT_STORAGE = "restrict_storage";
    public static final String CONTROL_FORCE_DATA_ISOLATION = "force_data_isolation";

    /**
     * Block Clipboard Read. When on, the package reads an <em>empty</em>
     * clipboard: ClipboardService hands it null from getPrimaryClip() and
     * getPrimaryClipDescription(), false from hasPrimaryClip() and
     * hasClipboardText(), and stops delivering primary-clip-changed callbacks
     * to it. Clipboard <em>writes</em> are untouched - the app can still copy,
     * it just cannot read what anyone else copied. Nothing is ever thrown at
     * the app; every one of those return values is one the platform already
     * produces for an unfocused app, so no new failure mode is introduced.
     */
    public static final String CONTROL_BLOCK_CLIPBOARD_READ = "block_clipboard_read";

    /**
     * Block Background Start. When on, the package is treated by
     * ActivityManager exactly as if the user had put it in the "Restricted"
     * battery-usage bucket: while none of its processes is in an active state,
     * a startService()/startForegroundService() targeting it is refused. The
     * refusal reuses the platform's own path, so the caller sees the ordinary
     * BackgroundServiceStartNotAllowedException / silent drop it would see
     * under battery restriction, not a novel error.
     *
     * <p>Scope, stated honestly: this covers <em>service</em> starts. Activity
     * background-start (BAL) is decided in WindowManager, not ActivityManager,
     * and is not gated by this control.
     */
    public static final String CONTROL_BLOCK_BACKGROUND_START = "block_background_start";

    /**
     * Auto-revoke On Exit. When on, the package's user-granted dangerous
     * runtime permissions are revoked once the app has actually stopped
     * running - a settle delay after its last process dies or its task is
     * removed, and only if no process of its UID has come back by then.
     *
     * <p>Deliberately conservative: never applies to system, updated-system or
     * persistent packages, and never touches a grant the platform owns
     * (SYSTEM_FIXED, POLICY_FIXED, GRANTED_BY_DEFAULT, GRANTED_BY_ROLE), so it
     * cannot fight device policy or AOSP's own hibernation auto-revoke. The
     * app can re-request each permission on its next launch and the user gets
     * the normal runtime dialog.
     */
    public static final String CONTROL_AUTO_REVOKE_ON_EXIT = "auto_revoke_on_exit";
}
