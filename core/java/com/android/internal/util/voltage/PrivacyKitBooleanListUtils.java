/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged. No transcript
 * snapshot of this file survives, but it does not need one: it lives in
 * framework.jar, which ships unobfuscated, so the r49 decompile
 * (pkrecover/jadxout/framework/sources/com/android/internal/util/voltage/
 * PrivacyKitBooleanListUtils.java) preserves the real class name, the real
 * method names, the exact signatures and every branch. The body below is that
 * decompile restored to source form - locals renamed, generics restored,
 * javadoc written - with no behavioural change: same null/negative-user guards,
 * same lone `,` sentinel check, same IllegalStateException catch, same
 * String.join(",") write through putStringForUser.
 *
 * Style follows the sibling CSV-list helper in this package,
 * HideAppListUtils, which this class was clearly derived from; the difference
 * is that this one takes the Settings.Secure key as a parameter instead of
 * hardcoding one, so PrivacyKitService can drive all of its boolean per-app
 * control lists (restrict_internet, clipboard_read, background_start,
 * auto_revoke_on_exit, ...) through a single helper.
 *
 * Callers in the shipped ROM: PrivacyKitService only - getApps() for the boot
 * re-apply and cache-priming paths, contains() for the live gates, and
 * add()/remove() for setBooleanControlInternal().
 */
package com.android.internal.util.voltage;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Membership helper for the comma-separated per-app "boolean control" lists
 * PrivacyKit-Native keeps in Settings.Secure - one setting per control, each
 * holding the package names the control is switched on for.
 *
 * Unlike {@link HideAppListUtils} the setting key is a parameter, so one
 * helper serves every control. Reads are cheap and total: an unset, empty or
 * degenerate ("," only) value all mean "no packages", and a Settings read that
 * throws (which happens when the provider is not up yet, early in boot) is
 * treated the same way rather than propagating - these are privacy gates, and
 * failing them open is the only safe direction.
 *
 * All methods are static; the class is never instantiated.
 */
public final class PrivacyKitBooleanListUtils {

    private PrivacyKitBooleanListUtils() {
    }

    /**
     * True when {@code packageName} is a member of the list held in the
     * Settings.Secure setting {@code settingsKey}. False for any null
     * argument, and false rather than throwing if the setting cannot be read.
     */
    public static boolean contains(ContentResolver cr, String settingsKey, String packageName) {
        if (cr == null || settingsKey == null || packageName == null) {
            return false;
        }

        return getApps(cr, settingsKey).contains(packageName);
    }

    /**
     * The package names listed in the Settings.Secure setting
     * {@code settingsKey}, or an empty (always non-null, always mutable) set
     * if it is unset, empty, degenerate, or unreadable.
     */
    public static Set<String> getApps(ContentResolver cr, String settingsKey) {
        if (cr == null || settingsKey == null) {
            return new HashSet<>();
        }

        try {
            String apps = Settings.Secure.getString(cr, settingsKey);
            if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
                return new HashSet<>(Arrays.asList(apps.split(",")));
            }
        } catch (IllegalStateException e) {
            // Settings provider not available (early boot); treat as empty.
            return new HashSet<>();
        }

        return new HashSet<>();
    }

    /** Adds {@code packageName} to {@code settingsKey}'s list for {@code userId}. */
    public static void add(Context context, String settingsKey, String packageName, int userId) {
        setMembership(context, settingsKey, packageName, userId, true);
    }

    /** Removes {@code packageName} from {@code settingsKey}'s list for {@code userId}. */
    public static void remove(Context context, String settingsKey, String packageName, int userId) {
        setMembership(context, settingsKey, packageName, userId, false);
    }

    /**
     * Read-modify-write of one list. A no-op for a null context/key/package or
     * a negative user id. The set is rebuilt and written whole, so the stored
     * order is unspecified and duplicates cannot accumulate.
     */
    private static void setMembership(Context context, String settingsKey, String packageName,
            int userId, boolean member) {
        if (context == null || settingsKey == null || packageName == null || userId < 0) {
            return;
        }

        final Set<String> apps = new HashSet<>(getApps(context.getContentResolver(), settingsKey));
        if (member) {
            apps.add(packageName);
        } else {
            apps.remove(packageName);
        }

        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                settingsKey,
                String.join(",", apps),
                userId);
    }
}
