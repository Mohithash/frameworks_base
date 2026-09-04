/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged.
 *
 * Sources, in order of authority:
 *   1. the r49 ROM (2026-08-18 13:44 UTC) - android.privacykit.IPrivacyKitManager
 *      inside system.img /system/framework/framework.jar. The Stub's
 *      TRANSACTION_<name> codes fixed the method ORDER and the arg marshalling
 *      fixed every signature; this file's 31 methods reproduce transaction ids
 *      1..31 exactly, so regenerating it is binder-compatible with r49.
 *   2. snap18c/patches/frameworks_base.patch - supplied the final text of the
 *      two changed regions verbatim, and its hunk headers pin the final file
 *      length at 258 lines, which this file matches.
 *   3. the 2026-08-16 09:31 UTC working-tree snapshot mined out of the Claude
 *      Code transcripts - supplied the prose, which decompilation destroys.
 *
 * Verified: reconstructed base + frameworks_base.patch applies with `git apply`
 * at zero fuzz, and the resulting method order/arity/types are identical to the
 * r49 Stub.
 */
package android.privacykit;

/**
 * PrivacyKit-Native per-app identity spoofing service.
 *
 * Obscura-equivalent per-app controls (Hide App, ADB/DevOptions/etc spoof)
 * are handled separately via the existing Settings.Secure HIDE_APPLIST /
 * HIDE_DEVELOPER_STATUS CSV lists (see HideAppListUtils / HideDeveloperStatusUtils)
 * and do not go through this service.
 *
 * This service covers per-app *identity* spoofing (ANDROID_ID, serial,
 * IMEI/IMSI/ICCID/phone number, Build.* identity, Wi-Fi MAC), each governed
 * by a per-(package, key) rule on that package's *active* Profile, plus
 * management of the Profiles themselves (an app can have several named
 * Profiles - e.g. "Default", "Work" - exactly one of which is active).
 *
 * @hide
 */
interface IPrivacyKitManager {

    /** True if this package's active profile has at least one non-REAL identity rule. */
    boolean hasIdentityProfile(String packageName);

    /** Rule type for (packageName, key) on the active profile. Returns RULE_REAL (0) if unset. */
    int getRuleType(String packageName, String key);

    /** Stored literal value for a CUSTOM rule on the active profile. Null if not applicable/unset. */
    String getRuleValue(String packageName, String key);

    /**
     * Configure a rule on the active profile. ruleType must be one of
     * PrivacyKitRuleResolver.RULE_*. customValue is only used (and required)
     * when ruleType == RULE_CUSTOM. If the package has no profile yet, a
     * "Default" one is created and made active automatically.
     */
    void setIdentifierRule(String packageName, String key, int ruleType, String customValue);

    /** Revert a single (packageName, key) rule on the active profile back to RULE_REAL. */
    void clearIdentifierRule(String packageName, String key);

    /** Revert every rule on the active profile back to RULE_REAL (does not delete the profile). */
    void clearProfile(String packageName);

    /** Packages whose active profile currently has at least one non-REAL rule configured. */
    List<String> getConfiguredPackages();

    /**
     * Main resolution entry point used by framework hook sites (SettingsProvider
     * SSAID, DeviceIdentifiersPolicyService serial, PhoneSubInfoController,
     * ActivityThread Build.* injector). Returns realValue unchanged if no rule
     * is configured, or on any internal error (fail open to the real value,
     * never fail closed to a crash).
     */
    String resolveIdentifier(String packageName, String key, String realValue);

    /**
     * Generic per-app boolean control with active enforcement. Unlike the
     * passive HIDE_APPLIST-style CSV lists, setting one of these immediately
     * applies (or reverts) the real enforcement action, not just a stored
     * preference. {@code control} must be one of the PrivacyKitService
     * CONTROL_* names:
     *
     * <ul>
     *   <li>"restrict_internet" - NetworkPolicyManager POLICY_REJECT_ALL.
     *   <li>"restrict_storage", "force_data_isolation" - AppOpsManager
     *       storage op denial.
     *   <li>"restrict_contacts", "restrict_call_log", "restrict_sms",
     *       "restrict_calendar", "restrict_accounts", "restrict_bluetooth" -
     *       the "Privacy Restrictions" group. Each denies exactly one
     *       runtime-permission-backed AppOps op (OP_READ_CONTACTS,
     *       OP_READ_CALL_LOG, OP_READ_SMS, OP_READ_CALENDAR, OP_GET_ACCOUNTS,
     *       OP_BLUETOOTH_CONNECT respectively) for the package's UID, so the
     *       platform's soft-denial path hands the app an empty result instead
     *       of the real data. Note "restrict_bluetooth" is as wide as
     *       BLUETOOTH_CONNECT itself: it empties the bonded-device list, but
     *       it also cuts off the app's other connect-class Bluetooth access.
     * </ul>
     *
     * <p>The Privacy Restrictions group deliberately reuses this pair rather
     * than adding new AIDL methods - it needs no new binder transactions, so
     * no client regeneration and no risk of renumbering existing ones. The
     * matching Identifiers-catalog keys ("contacts", "call_log", "sms",
     * "calendar", "accounts", "bluetooth_bonded_devices") are also bridged
     * automatically by {@link #setIdentifierRule}: RULE_EMPTY on one of them
     * turns the corresponding control on, any other rule type turns it off.
     *
     * <p>Three further controls are enforced outside AppOps entirely, and like
     * the Privacy Restrictions group they add no binder transactions of their
     * own:
     *
     * <ul>
     *   <li>"block_clipboard_read" - ClipboardService answers the package's
     *       OP_READ_CLIPBOARD access check with "not allowed", so it reads an
     *       empty clipboard (null clip, null description, false for
     *       hasPrimaryClip/hasClipboardText, and no primary-clip-changed
     *       callbacks). Clipboard writes are untouched.
     *   <li>"block_background_start" - ActivityManager treats the package as
     *       background-restricted, so a service start targeting it while its
     *       UID is inactive is refused through the platform's own
     *       Restricted-bucket path. Service starts only; activity
     *       background-launch is decided in WindowManager and is not covered.
     *   <li>"auto_revoke_on_exit" - once the package has actually stopped
     *       running, its user-granted dangerous runtime permissions are
     *       revoked. Never applies to system/updated-system/persistent
     *       packages, and never touches a SYSTEM_FIXED, POLICY_FIXED,
     *       GRANTED_BY_DEFAULT or GRANTED_BY_ROLE grant.
     * </ul>
     *
     * <p>See android.privacykit.PrivacyKitKeys for each one's full contract and
     * its stated limits.
     *
     * <p>Unknown control names throw IllegalArgumentException from
     * setBooleanControl, and read back as false from getBooleanControl.
     */
    boolean getBooleanControl(String packageName, String control);
    void setBooleanControl(String packageName, String control, boolean value);

    // ---- profile management -------------------------------------------------

    /** Every package that has been added to PrivacyKit (has at least one Profile). */
    List<String> getManagedPackages();

    /** Profile IDs for packageName, in creation order. Empty if the package isn't managed. */
    List<String> listProfileIds(String packageName);

    /** The currently-active profile ID for packageName, or null if not managed. */
    String getActiveProfileId(String packageName);

    String getProfileName(String packageName, String profileId);

    /** One of PrivacyKitProfileStore.MODE_ISOLATED / MODE_HYBRID / MODE_SHARED. */
    String getProfileMode(String packageName, String profileId);

    void renameProfile(String packageName, String profileId, String newName);

    void setProfileMode(String packageName, String profileId, String mode);

    /**
     * Creates a new named profile for packageName ("adding" the app to
     * PrivacyKit if it has none yet - the new profile becomes active
     * immediately in that case). Returns the new profile's ID.
     */
    String createProfile(String packageName, String name, String mode);

    /** Switches packageName's active profile. No-op if profileId doesn't exist. */
    void setActiveProfile(String packageName, String profileId);

    /**
     * Deletes a profile. If it was active, the next remaining profile (if
     * any) becomes active. Deleting a package's last profile removes it
     * from getManagedPackages().
     */
    void deleteProfile(String packageName, String profileId);

    /**
     * Up to {@code max} most-recent PrivacyKit activity entries (rule
     * changes, profile management), newest-first, pre-formatted as
     * human-readable display strings for the Settings History tab.
     */
    List<String> getRecentHistory(int max);

    // ---- backup / restore -----------------------------------------------------

    /**
     * Exports all PrivacyKit-Native data (profiles, rules, and history) as a
     * single JSON string, for the Settings Backup & Restore "Export backup"
     * action. The caller is responsible for writing the returned string
     * wherever it wants (e.g. a user-picked file via Storage Access
     * Framework) - this call performs no I/O of its own.
     */
    String exportBackup();

    /**
     * Restores profiles/rules (not history - restoring a historical log
     * doesn't make operational sense) from a JSON string previously produced
     * by {@link #exportBackup()}. When {@code merge} is true, packages and
     * profiles found in {@code json} are added or updated, leaving anything
     * not mentioned in {@code json} untouched; when false, all existing
     * profile data is replaced entirely by {@code json}. Returns the number
     * of packages imported. Throws on malformed JSON.
     */
    int importBackup(String json, boolean merge);

    // ---- profile appearance: colour + note -------------------------------------
    //
    // Appended at the END of the interface on purpose. AIDL assigns each method
    // a transaction ID by declaration order, so inserting anything above this
    // point would renumber every method after it and silently break any caller
    // built against the old ordering. New methods only ever go here.

    /**
     * A profile's colour token, or null if it has none - which is also what
     * every profile created before colours existed reads back as.
     *
     * <p>The value is one of PrivacyKitProfileStore's COLOR_* tokens - "blue",
     * "green", "amber", "red", "purple", "teal", "grey" - and deliberately not
     * a packed ARGB int: the UI is expected to map the token onto a
     * theme-resolved Material colour so the same profile stays legible in light
     * mode, in dark mode and under any dynamic-colour palette. The token set is
     * documented here and enumerated in PrivacyKitProfileStore rather than
     * fetched over binder, exactly like the MODE_* strings above.
     */
    String getProfileColor(String packageName, String profileId);

    /**
     * Sets a profile's colour token. Null or "" clears it back to "no colour";
     * an unrecognised token is ignored and the current colour left alone,
     * matching setProfileMode's handling of an unknown mode.
     */
    void setProfileColor(String packageName, String profileId, String color);

    /** A profile's free-text note, or null if it has none. */
    String getProfileNote(String packageName, String profileId);

    /**
     * Sets a profile's free-text note. Null, empty or whitespace-only clears
     * it. Anything longer than PrivacyKitProfileStore.MAX_NOTE_LENGTH (256)
     * characters is truncated to that length rather than rejected, so a caller
     * that wants to display exactly what was kept should read the value back
     * after writing it.
     */
    void setProfileNote(String packageName, String profileId, String note);

    /**
     * Copies an existing profile under a new id and name, keeping its mode,
     * colour, note and every rule (including each CUSTOM rule's literal value).
     * Returns the new profile's ID, or null if profileId doesn't exist. The
     * copy does not become active.
     *
     * <p>It does not inherit the source's generated values either: RULE_STATIC
     * keys mint fresh ones on first read, so a cloned profile is a *different*
     * identity for the same app rather than a second name for the same one.
     */
    String cloneProfile(String packageName, String profileId, String newName);

    /**
     * Whether the developer "ADB control" gate is on. When true, the
     * {@code adb shell cmd privacykit ...} interface may *mutate* profiles
     * and rules (its read-only subcommands work regardless). Off by
     * default; this gate can only be flipped here, by a privileged caller
     * (the Settings developer toggle) - never from the shell itself, so an
     * adb session cannot grant itself write access.
     */
    boolean isAdbControlEnabled();

    /** Sets the ADB-control gate. See {@link #isAdbControlEnabled()}. */
    void setAdbControlEnabled(boolean enabled);

    /**
     * Wall-clock millis when this profile was last made active, or 0 if it has
     * never been selected. Used by the profile list to mark one "Last opened".
     */
    long getProfileLastUsed(String packageName, String profileId);
}
