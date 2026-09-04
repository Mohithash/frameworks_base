/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * RECONSTRUCTED 2026-08-19 after the build box was purged. This file lived only
 * in a local commit that died with the box; it is rebuilt, not retyped.
 *
 * Method:
 *   - snap18c/patches/frameworks_base.patch supplies the FINAL text of every
 *     region the 18c uncommitted delta touched, verbatim (context + added
 *     lines), together with that regions exact final line number.
 *   - The 2026-08-16T09:31Z whole-file transcript snapshot (pkrecover/best)
 *     supplies the regions no hunk touches; those are unchanged between the
 *     lost base commit and final, so the snapshot is authoritative there.
 *   - The r49 decompile (system.img -> services.jar, built 2026-08-18T13:44Z,
 *     one minute after the snapshot was taken) was used to cross-check
 *     behaviour, the member inventory and every numeric constant.
 *
 * Confidence: the 16 hunks each landed at the exact final line number the patch
 * declares, and the assembled length (1653) matches the length the patchs own
 * hunk headers imply. Constants were re-derived from the r49 dex and agree
 * (e.g. UNTOUCHABLE_PERMISSION_FLAGS == 32820, POLICY_REJECT_ALL == 0x40000,
 * PHASE_ACTIVITY_MANAGER_READY == 550).
 *
 * TODO(recovery): three collaborators of this file were in the same lost commit
 * and are NOT in the 18c patch, so they must be recovered separately before this
 * compiles - PrivacyKitManagerInternal, PrivacyKitHistoryStore (both
 * com.android.server.privacykit) and com.android.internal.util.voltage.
 * PrivacyKitBooleanListUtils.
 */
package com.android.server.privacykit;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.net.INetworkPolicyManager;
import android.net.NetworkPolicyManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.privacykit.IPrivacyKitManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PrivacyKit-Native per-app identity spoofing service, plus active
 * enforcement for the Obscura-equivalent controls that need more than a
 * passive on-demand-checked list: Restrict Internet (NetworkPolicyManager
 * POLICY_REJECT_ALL), Restrict Storage / Force Data Isolation
 * (AppOpsManager storage op denial), and the "Privacy Restrictions" group
 * (AppOpsManager denial of a single runtime-permission-backed op, so the
 * platform hands the app an empty result set instead of the real one).
 *
 * Publishes IPrivacyKitManager for cross-process callers (the PrivacyKit
 * Settings UI, and ActivityThread running in each app's own process) and
 * registers PrivacyKitManagerInternal for framework code that already runs
 * inside system_server (SettingsProvider, DeviceIdentifiersPolicyService,
 * PhoneSubInfoController).
 */
public class PrivacyKitService extends SystemService {
    // BestROM: these Settings.Secure keys do not exist in A17's Settings.java.
    // Declared locally with the same string values the A16 tree used.
    private static final String PK_RESTRICT_INTERNET_LIST = "privacykit_restrict_internet_list";
    private static final String PK_RESTRICT_STORAGE_LIST = "privacykit_restrict_storage_list";
    private static final String PK_FORCE_DATA_ISOLATION_LIST = "privacykit_force_data_isolation_list";

    private static final String TAG = "PrivacyKitService";
    public static final String SERVICE_NAME = "privacykit";

    public static final String CONTROL_RESTRICT_INTERNET = "restrict_internet";
    public static final String CONTROL_RESTRICT_STORAGE = "restrict_storage";
    public static final String CONTROL_FORCE_DATA_ISOLATION = "force_data_isolation";

    // "Privacy Restrictions" controls. Each one denies exactly one
    // runtime-permission-backed AppOps op for the target package's UID; the
    // platform's soft-denial path then returns an empty result rather than
    // throwing, which is the "restricted -> empty" behaviour PrivacyKit wants.
    // They ride the existing generic getBooleanControl/setBooleanControl AIDL
    // pair - no new binder transactions were added for them.
    public static final String CONTROL_RESTRICT_CONTACTS = "restrict_contacts";
    public static final String CONTROL_RESTRICT_CALL_LOG = "restrict_call_log";
    public static final String CONTROL_RESTRICT_SMS = "restrict_sms";
    public static final String CONTROL_RESTRICT_CALENDAR = "restrict_calendar";
    public static final String CONTROL_RESTRICT_ACCOUNTS = "restrict_accounts";
    public static final String CONTROL_RESTRICT_BLUETOOTH = "restrict_bluetooth";

    /** Every control in {@link #CONTROL_RESTRICT_CONTACTS} .. {@link
     *  #CONTROL_RESTRICT_BLUETOOTH}, i.e. the AppOps-op-backed subset. */
    private static final String[] APPOP_RESTRICTION_CONTROLS = {
            CONTROL_RESTRICT_CONTACTS, CONTROL_RESTRICT_CALL_LOG, CONTROL_RESTRICT_SMS,
            CONTROL_RESTRICT_CALENDAR, CONTROL_RESTRICT_ACCOUNTS, CONTROL_RESTRICT_BLUETOOTH,
    };

    /**
     * Block Advertising ID. Deliberately <em>not</em> a member of
     * {@link #APPOP_RESTRICTION_CONTROLS} - there is no AppOps op for this, and
     * unlike every control above it this one is enforced by ActivityManager
     * refusing a bind rather than by a soft op denial.
     *
     * <p>It exists because the Google advertising ID (GAID) cannot honestly be
     * spoofed from the platform: the value is minted inside Play services and
     * handed to the app over a closed, proprietary binder contract, so there is
     * no framework API for PrivacyKit to intercept and rewrite. What the
     * platform <em>can</em> do is refuse the bind that fetches it. When this
     * control is on for a package,
     * {@link com.android.server.am.ActiveServices#retrieveServiceLocked}
     * returns null for that package's bind to the dedicated advertising-ID
     * service action, so {@code Context.bindService()} returns false and the
     * caller gets no ad ID. Because the gate sits at ActivityManager rather
     * than inside a client library, it covers Google's own
     * {@code AdvertisingIdClient} and any hand-rolled SDK that binds the same
     * action, equally.
     *
     * <p>This is a restriction, not an identity spoof, and the UI must say so:
     * the app learns that the ad ID is unavailable, it is not fed a fake one.
     * It is therefore also detectable - an app that sees this one bind fail
     * while its other Play services calls keep working can infer the platform
     * is filtering. Functionally the app sees what it would see on a
     * de-Googled device; it just also sees that this device is not one.
     *
     * <p>What it does not cover, stated so nobody assumes more than is true:
     * an ad ID the app fetched and cached before the block was switched on;
     * Google's own use of the ad ID inside Play services and the Play Store,
     * which never crosses this bind; and any non-GMS ad-ID source, such as the
     * deprecated androidx.ads.identifier ContentProvider route intended for
     * Play-less devices, which is a provider query rather than a service bind
     * and so never reaches this gate.
     *
     * <p>Scoped to the dedicated ad-ID bind only, on purpose. The shared GMS
     * broker bind must never be denied - App Set ID, Auth, Maps, Firebase and
     * Play Integrity all multiplex over that single connection, so denying it
     * would break every Google API the app uses instead of just advertising.
     */
    public static final String CONTROL_BLOCK_ADVERTISING_ID = "block_advertising_id";

    /**
     * Block Clipboard Read. The package reads an <em>empty</em> clipboard:
     * {@link com.android.server.clipboard.ClipboardService} answers its
     * OP_READ_CLIPBOARD access check with "not allowed", which the existing
     * read entry points already turn into null (getPrimaryClip,
     * getPrimaryClipDescription, getPrimaryClipSource) or false (hasPrimaryClip,
     * hasClipboardText), and which also stops primary-clip-changed callbacks
     * being dispatched to it.
     *
     * <p>Reads only. The gate is keyed on the AppOps op the access check is
     * being made for, and OP_WRITE_CLIPBOARD never reaches it - an app under
     * this control can still copy, it just cannot read what anyone else copied.
     * Nothing is thrown at the app: every value it now sees is one the platform
     * already produces for an app that is merely unfocused, so this introduces
     * no failure mode the clipboard client code does not already handle.
     *
     * <p>Like {@link #CONTROL_BLOCK_ADVERTISING_ID} this is a restriction, not
     * a spoof, and it overrides the platform's own allowances - if a restricted
     * package happens to be the default IME or holds
     * READ_CLIPBOARD_IN_BACKGROUND, it is still blocked. That is deliberate:
     * the user asked for this package specifically.
     */
    public static final String CONTROL_BLOCK_CLIPBOARD_READ = "block_clipboard_read";

    /**
     * Block Background Start. ActivityManager treats the package exactly as if
     * the user had put it in the "Restricted" battery-usage bucket: while none
     * of its processes is in an active state, a startService() or
     * startForegroundService() targeting it is refused.
     *
     * <p>Implemented in
     * {@link com.android.server.am.ActiveServices#startServiceLocked} by
     * feeding the platform's existing {@code forcedStandby} decision - the same
     * one {@code appRestrictedAnyInBackground()} drives - so the refusal takes
     * the platform's own path: a silent null for a delayed-start-mode app or a
     * forced-standby foreground start, and otherwise the {@code "?"} sentinel
     * ContextImpl converts into BackgroundServiceStartNotAllowedException. Both
     * are outcomes an app is already required to handle, which is why this
     * control adds no novel crash.
     *
     * <p>Two limits, stated rather than implied. First, it gates <em>service</em>
     * starts; background <em>activity</em> launches are decided by
     * WindowManager's BackgroundActivityStartController, which this control
     * does not reach. Second, the gate keys off the <em>target</em> package, so
     * it also refuses another app's attempt to start a service in this one
     * while it is idle - which is the same thing the Restricted bucket does.
     */
    public static final String CONTROL_BLOCK_BACKGROUND_START = "block_background_start";

    /**
     * Auto-revoke On Exit. Once the package has actually stopped running, its
     * user-granted dangerous runtime permissions are revoked, so the next
     * launch has to ask again.
     *
     * <p>The trigger is conservative on purpose. ActiveServices notifies this
     * service when one of the package's processes is cleaned up or its task is
     * removed; that notification only costs a set lookup and a post, and the
     * work itself runs on BackgroundThread after a settle delay. Before
     * revoking anything the deferred pass re-checks that the control is still
     * on and that the UID has no process at all
     * ({@code PROCESS_STATE_NONEXISTENT}), so an app that restarted a service,
     * was relaunched, or still has a second process alive is left alone.
     *
     * <p>What it will never touch: system, updated-system and persistent
     * packages, and any grant the platform owns - SYSTEM_FIXED, POLICY_FIXED,
     * GRANTED_BY_DEFAULT or GRANTED_BY_ROLE. That is what keeps it from
     * fighting device policy or AOSP's own hibernation auto-revoke, which owns
     * its grants through those same flags; a permission this control has
     * already revoked is simply not granted, so hibernation has nothing left to
     * disagree with.
     */
    public static final String CONTROL_AUTO_REVOKE_ON_EXIT = "auto_revoke_on_exit";

    // Settings.Secure CSV membership lists backing the Privacy Restrictions
    // controls, in the same "privacykit_*_list" shape as
    // PK_RESTRICT_INTERNET_LIST and friends. They are spelled out
    // here rather than added to android.provider.Settings because - unlike
    // HIDE_APPLIST / HIDE_LAUNCHER_LIST - nothing outside this service ever
    // reads them: enforcement is applied eagerly through AppOpsManager, and
    // the Settings UI reads the state back through getBooleanControl().
    private static final String RESTRICT_CONTACTS_LIST = "privacykit_restrict_contacts_list";
    private static final String RESTRICT_CALL_LOG_LIST = "privacykit_restrict_call_log_list";
    private static final String RESTRICT_SMS_LIST = "privacykit_restrict_sms_list";
    private static final String RESTRICT_CALENDAR_LIST = "privacykit_restrict_calendar_list";
    private static final String RESTRICT_ACCOUNTS_LIST = "privacykit_restrict_accounts_list";
    private static final String RESTRICT_BLUETOOTH_LIST = "privacykit_restrict_bluetooth_list";
    private static final String BLOCK_ADVERTISING_ID_LIST =
            "privacykit_block_advertising_id_list";
    private static final String BLOCK_CLIPBOARD_READ_LIST =
            "privacykit_block_clipboard_read_list";
    private static final String BLOCK_BACKGROUND_START_LIST =
            "privacykit_block_background_start_list";
    private static final String AUTO_REVOKE_ON_EXIT_LIST =
            "privacykit_auto_revoke_on_exit_list";

    /**
     * How long {@link #CONTROL_AUTO_REVOKE_ON_EXIT} waits after being told the
     * app stopped before it re-checks and revokes.
     *
     * <p>Long enough that an ordinary process restart - a service the platform
     * brings back, a user tapping the app again straight away - lands inside
     * the window and cancels the revoke on the liveness re-check. Short enough
     * that the permissions are gone well before the user could plausibly get
     * back to the app on purpose.
     */
    private static final long AUTO_REVOKE_SETTLE_DELAY_MS = 15_000L;

    /**
     * Permission-state flags that mean "this grant is not the user's to give
     * back" - it belongs to device policy, to a default grant, to a role, or to
     * the system image. {@link #CONTROL_AUTO_REVOKE_ON_EXIT} skips any
     * permission carrying one of them, which is also what keeps it out of
     * AOSP's hibernation auto-revoke lane.
     */
    private static final int UNTOUCHABLE_PERMISSION_FLAGS =
            PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                    | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                    | PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT
                    | PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE;

    // Identifier keys from the Settings-side "Privacy Restrictions" catalog
    // group (PrivacyKitIdentifierCatalog.kt) that map onto one of the controls
    // above. These are access restrictions rather than spoofable values, so the
    // only rule type that means anything for them is RULE_EMPTY ("Empty /
    // Restricted"); selecting it flips the matching boolean control on, and any
    // other rule type flips it off. The literals must stay in sync with the
    // catalog - same duplication caveat as PrivacyKitKeys/PrivacyKitRuleResolver.
    private static final String KEY_CONTACTS = "contacts";
    private static final String KEY_CALL_LOG = "call_log";
    private static final String KEY_SMS = "sms";
    private static final String KEY_CALENDAR = "calendar";
    private static final String KEY_ACCOUNTS = "accounts";
    private static final String KEY_BLUETOOTH_BONDED_DEVICES = "bluetooth_bonded_devices";
    // advertising_id lives in the catalog's Device Identifiers group today, but
    // it behaves like a restriction key, not a spoofable one: RULE_EMPTY blocks
    // the app's access to the ad ID and every other rule type lifts the block.
    // No rule type can make the app see a *different* ad ID - see
    // CONTROL_BLOCK_ADVERTISING_ID.
    private static final String KEY_ADVERTISING_ID = "advertising_id";

    /** Catalog keys handled by {@link #applyRestrictionRule}, in catalog order. */
    private static final String[] RESTRICTION_KEYS = {
            KEY_CONTACTS, KEY_CALL_LOG, KEY_SMS, KEY_CALENDAR, KEY_ACCOUNTS,
            KEY_BLUETOOTH_BONDED_DEVICES, KEY_ADVERTISING_ID,
    };

    /** Bumped whenever the exportBackup()/importBackup() JSON schema changes. */
    private static final int BACKUP_FORMAT_VERSION = 1;

    /**
     * Packages {@link #CONTROL_BLOCK_ADVERTISING_ID} is switched on for, cached
     * in memory because its enforcement point sits on the bindService hot path.
     *
     * <p>That enforcement point is
     * {@link com.android.server.am.ActiveServices#retrieveServiceLocked}, which
     * runs while holding the ActivityManager lock for <em>every</em> bindService
     * and startService on the device. It therefore cannot afford the
     * Settings.Secure read {@link #getBooleanControlInternal} does, cannot make
     * a binder call, and must not take any lock of ours (that would risk an
     * inversion against the AM lock). A volatile snapshot read plus one set
     * lookup is allocation-free, lock-free, and safe to do under someone
     * else's lock.
     *
     * <p>Copy-on-write: a writer publishes a brand-new set and never mutates
     * one that has already been published, so a concurrent reader sees either
     * the whole old snapshot or the whole new one.
     *
     * <p>Static because ActiveServices holds no reference to this service
     * instance, and the usual in-process route - PrivacyKitManagerInternal via
     * LocalServices - would still cost a map lookup per bind. There is exactly
     * one PrivacyKitService per boot, so the static holds exactly one service's
     * state.
     *
     * <p>Starts empty, i.e. "block nothing", so until
     * {@link #primeAdvertisingIdBlockCache()} runs at
     * PHASE_ACTIVITY_MANAGER_READY the gate fails open. That is the intended
     * direction: a PrivacyKit failure must never break an app's bind.
     *
     * <p>Keyed by package name with no user id, exactly like the rest of this
     * service (compare {@link #reapplyRestrictInternet()}, which likewise only
     * repairs USER_SYSTEM at boot). A block toggled on from a secondary user
     * therefore applies to that package in every user until the next reboot,
     * after which it is primed from user 0's list only. Stated rather than
     * hidden: it over-blocks rather than under-blocks, which is the safe
     * direction for a privacy control, but it is not true per-user isolation.
     */
    private static volatile Set<String> sAdvertisingIdBlockedPackages = Collections.emptySet();

    /**
     * Live membership caches for the three controls whose enforcement point is
     * a check made inside another system service, on a path that cannot afford
     * a Settings.Secure read.
     *
     * <p>Same contract as {@link #sAdvertisingIdBlockedPackages} in every
     * respect - copy-on-write, volatile, static, keyed by package name with no
     * user id, empty until {@link #primeEnforcementCaches()} runs at
     * PHASE_ACTIVITY_MANAGER_READY so the gates fail open across early boot.
     * See that field's documentation for why each of those properties is
     * required rather than merely convenient.
     */
    private static volatile Set<String> sClipboardReadBlockedPackages = Collections.emptySet();
    private static volatile Set<String> sBackgroundStartBlockedPackages = Collections.emptySet();
    private static volatile Set<String> sAutoRevokeOnExitPackages = Collections.emptySet();

    /**
     * The one live service instance, published at the end of {@link #onStart()}.
     *
     * <p>Only {@link #notifyPackageProcessGone(String, int)} needs it: unlike
     * the pure predicate gates above, auto-revoke has actual work to do and
     * that work needs mContext. Null until onStart() completes, and every
     * reader treats null as "do nothing".
     */
    private static volatile PrivacyKitService sInstance;

    private final Context mContext;
    private final PrivacyKitProfileStore mStore;
    private final PrivacyKitHistoryStore mHistoryStore;

    /**
     * (userId, package) pairs with an auto-revoke pass already queued. Guards
     * against a crash-looping app queueing an unbounded number of passes; the
     * monitor is private to this object and is never held while taking another
     * lock, so it cannot participate in an inversion with the AM lock the
     * notify path arrives under.
     */
    private final Set<String> mPendingAutoRevoke = new ArraySet<>();

    /** BackgroundThread handler, resolved once in onStart() so the auto-revoke
     *  notify path - which runs under the ActivityManager lock - never triggers
     *  BackgroundThread's lazy thread start while holding it. */
    private volatile Handler mBackgroundHandler;

    public PrivacyKitService(Context context) {
        super(context);
        mContext = context;
        File dir = new File(Environment.getDataSystemDirectory(), "privacykit");
        mStore = new PrivacyKitProfileStore(dir);
        mHistoryStore = new PrivacyKitHistoryStore(dir);
    }

    @Override
    public void onStart() {
        // Both loads are guarded, and separately, because the failure mode is
        // system_server. Every hook in this tree fails open - a store that is
        // empty resolves nothing and every identifier reads real - so a
        // profile store that cannot be parsed costs the user their spoofing
        // rules until they are re-created, while an unguarded throw here costs
        // them a boot loop: onStart() runs inside SystemServer's startup
        // sequence, where an escaping RuntimeException is fatal.
        //
        // Throwable rather than Exception on purpose. The realistic corruption
        // paths through XmlPullParser and the array sizing behind it raise
        // Errors as readily as Exceptions (OutOfMemoryError on a length field
        // read from the file itself, StackOverflowError on a pathologically
        // nested document), and "the file was truncated by a power cut" must
        // not be a different outcome from "the file was truncated in a way that
        // happens to allocate".
        //
        // The two are guarded separately so a corrupt history file cannot cost
        // the user their identity profiles, which are the part that matters.
        try {
            mStore.load();
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to load PrivacyKit profile store; starting empty. "
                    + "Every identifier will read its real value until rules are re-created.", t);
        }
        try {
            mHistoryStore.load();
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to load PrivacyKit history store; starting empty.", t);
        }
        publishBinderService(SERVICE_NAME, new PrivacyKitManagerStub());
        LocalServices.addService(PrivacyKitManagerInternal.class, new PrivacyKitManagerInternal() {
            @Override
            public String resolveIdentifier(String packageName, String key, String realValue) {
                return resolveInternal(packageName, key, realValue);
            }
        });
        mBackgroundHandler = BackgroundThread.getHandler();
        // Published last: a reader that sees a non-null instance is guaranteed
        // to see the fully constructed state above it.
        sInstance = this;
        Slog.i(TAG, "PrivacyKitService started");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            // AppOps-based controls (Restrict Storage / Force Data Isolation)
            // persist natively in AppOpsService's own store and don't need
            // re-applying. Netd firewall rules (Restrict Internet) do not
            // persist across reboots, so re-apply them here.
            reapplyRestrictInternet();
            reapplyAppOpRestrictions();
            primeAdvertisingIdBlockCache();
            primeEnforcementCaches();
        }
    }

    private void reapplyRestrictInternet() {
        Set<String> apps = PrivacyKitBooleanListUtils.getApps(
                mContext.getContentResolver(), PK_RESTRICT_INTERNET_LIST);
        for (String pkg : apps) {
            try {
                int uid = mContext.getPackageManager().getPackageUidAsUser(pkg, UserHandle.USER_SYSTEM);
                setUidNetworkPolicy(uid, true);
            } catch (PackageManager.NameNotFoundException e) {
                // App no longer installed; leave the list entry (harmless, and
                // Settings UI will show it as off next time the app exists again
                // only if reinstalled and re-toggled - acceptable for v1).
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to reapply restrict-internet for " + pkg, e);
            }
        }
    }

    /**
     * Re-asserts the AppOps-backed Privacy Restrictions at boot.
     *
     * <p>Unlike {@link #reapplyRestrictInternet()} this is not needed for bare
     * correctness - AppOps UID modes do survive a reboot in AppOpsService's own
     * store. It exists because PermissionPolicyService re-derives a UID's op
     * modes from that UID's runtime permission state whenever the permission
     * state changes, so a restriction can silently drift back to allowed if the
     * user later grants the underlying permission from the normal Permissions
     * UI. Repairing it once per boot keeps what the PrivacyKit UI shows and
     * what the platform actually enforces in agreement.
     */
    private void reapplyAppOpRestrictions() {
        for (String control : APPOP_RESTRICTION_CONTROLS) {
            String settingsKey = controlSettingsKey(control);
            if (settingsKey == null) {
                continue;
            }
            int op = restrictionOpForControl(control);
            Set<String> apps = PrivacyKitBooleanListUtils.getApps(
                    mContext.getContentResolver(), settingsKey);
            for (String pkg : apps) {
                try {
                    int uid = mContext.getPackageManager()
                            .getPackageUidAsUser(pkg, UserHandle.USER_SYSTEM);
                    setRuntimePermissionOp(op, uid, true);
                } catch (PackageManager.NameNotFoundException e) {
                    // App no longer installed; nothing to enforce.
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Failed to reapply " + control + " for " + pkg, e);
                }
            }
        }
    }

    /**
     * Loads the persisted Block Advertising ID membership list into the
     * enforcement cache at boot.
     *
     * <p>Unlike the other boot-time repairs this pushes nothing out to another
     * subsystem: there is no netd rule or AppOps mode to restore, because the
     * enforcement is a check performed live on each bind. The only thing that
     * has to survive a reboot is the in-memory snapshot that check reads.
     */
    private void primeAdvertisingIdBlockCache() {
        try {
            sAdvertisingIdBlockedPackages = new ArraySet<>(PrivacyKitBooleanListUtils.getApps(
                    mContext.getContentResolver(), BLOCK_ADVERTISING_ID_LIST));
        } catch (RuntimeException e) {
            // Leave the cache alone; empty at boot means "block nothing".
            Slog.w(TAG, "Failed to prime advertising-ID block cache", e);
        }
    }

    /**
     * Whether the dedicated Google advertising-ID service bind must be denied
     * for {@code packageName}. Called from ActiveServices on the bindService
     * path: one volatile read and one set lookup, nothing else.
     *
     * <p>This is a <em>block</em>, not a spoof. PrivacyKit cannot forge a GAID,
     * so the honest enforcement is to stop the app reaching the service that
     * mints it. Never throws; callers must in any case treat any failure as
     * "not blocked" and let the bind through.
     *
     * @see #CONTROL_BLOCK_ADVERTISING_ID
     */
    public static boolean isAdvertisingIdBlocked(String packageName) {
        if (packageName == null) {
            return false;
        }
        return sAdvertisingIdBlockedPackages.contains(packageName);
    }

    /**
     * Publishes a new blocked-package snapshot. Copy-on-write; see
     * {@link #sAdvertisingIdBlockedPackages}.
     */
    private static void setAdvertisingIdBlockCached(String packageName, boolean blocked) {
        if (packageName == null) {
            return;
        }
        final Set<String> current = sAdvertisingIdBlockedPackages;
        if (blocked == current.contains(packageName)) {
            return;
        }
        final ArraySet<String> next = new ArraySet<>(current);
        if (blocked) {
            next.add(packageName);
        } else {
            next.remove(packageName);
        }
        sAdvertisingIdBlockedPackages = next;
    }

    /**
     * Loads the persisted membership of Block Clipboard Read, Block Background
     * Start and Auto-revoke On Exit into their in-memory caches at boot.
     *
     * <p>Like {@link #primeAdvertisingIdBlockCache()} this pushes nothing out to
     * another subsystem: all three are enforced by a check made live at the
     * point of use, so the only thing that has to survive a reboot is the
     * snapshot that check reads.
     *
     * <p>Each cache is primed independently and a failure leaves that one
     * empty, i.e. enforcing nothing - the fail-open direction.
     */
    private void primeEnforcementCaches() {
        sClipboardReadBlockedPackages = primeControlCache(BLOCK_CLIPBOARD_READ_LIST);
        sBackgroundStartBlockedPackages = primeControlCache(BLOCK_BACKGROUND_START_LIST);
        sAutoRevokeOnExitPackages = primeControlCache(AUTO_REVOKE_ON_EXIT_LIST);
    }

    private Set<String> primeControlCache(String settingsKey) {
        try {
            return new ArraySet<>(PrivacyKitBooleanListUtils.getApps(
                    mContext.getContentResolver(), settingsKey));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to prime PrivacyKit cache " + settingsKey, e);
            return Collections.emptySet();
        }
    }

    /**
     * Keeps the live caches in step with a control being toggled. Copy-on-write
     * with exactly the contract of {@link #setAdvertisingIdBlockCached}: a
     * writer publishes a whole new set and never mutates a published one, so a
     * concurrent reader sees either the entire old snapshot or the entire new
     * one. No-op for any control that has no cache.
     */
    private static void updateEnforcementCache(String control, String packageName, boolean value) {
        if (control == null || packageName == null) {
            return;
        }
        switch (control) {
            case CONTROL_BLOCK_CLIPBOARD_READ:
                sClipboardReadBlockedPackages =
                        withMembership(sClipboardReadBlockedPackages, packageName, value);
                break;
            case CONTROL_BLOCK_BACKGROUND_START:
                sBackgroundStartBlockedPackages =
                        withMembership(sBackgroundStartBlockedPackages, packageName, value);
                break;
            case CONTROL_AUTO_REVOKE_ON_EXIT:
                sAutoRevokeOnExitPackages =
                        withMembership(sAutoRevokeOnExitPackages, packageName, value);
                break;
            default:
                break;
        }
    }

    /** Returns {@code current} itself when nothing would change, otherwise a
     *  fresh set with {@code packageName} added or removed. */
    private static Set<String> withMembership(Set<String> current, String packageName,
            boolean present) {
        if (present == current.contains(packageName)) {
            return current;
        }
        final ArraySet<String> next = new ArraySet<>(current);
        if (present) {
            next.add(packageName);
        } else {
            next.remove(packageName);
        }
        return next;
    }

    /**
     * Whether ClipboardService must behave as though {@code packageName} has no
     * clipboard access. Called from that service's OP_READ_CLIPBOARD access
     * check: one volatile read and one small-set lookup, no binder call, no
     * Settings read, no lock.
     *
     * <p>Never throws; callers must in any case treat any failure as "not
     * blocked" and let the read through.
     *
     * @see #CONTROL_BLOCK_CLIPBOARD_READ
     */
    public static boolean isClipboardReadBlocked(String packageName) {
        if (packageName == null) {
            return false;
        }
        return sClipboardReadBlockedPackages.contains(packageName);
    }

    /**
     * Whether ActiveServices must treat {@code packageName} as background-
     * restricted when deciding a service start. Same cost and same fail-open
     * contract as {@link #isAdvertisingIdBlocked(String)}.
     *
     * @see #CONTROL_BLOCK_BACKGROUND_START
     */
    public static boolean isBackgroundStartBlocked(String packageName) {
        if (packageName == null) {
            return false;
        }
        return sBackgroundStartBlockedPackages.contains(packageName);
    }

    /**
     * Trigger for {@link #CONTROL_AUTO_REVOKE_ON_EXIT}: ActiveServices calls
     * this when a process of {@code packageName} has been cleaned up, or when
     * its task has been removed.
     *
     * <p>Runs under the ActivityManager lock, so it does exactly two cheap
     * lock-free things - a volatile read plus one small-set lookup - and then
     * at most a Handler.post. Everything that can block (PackageManager
     * lookups, the revoke itself) happens later on BackgroundThread with no
     * lock held.
     *
     * <p>Never throws. A privacy control must never be the reason process
     * cleanup fails.
     */
    public static void notifyPackageProcessGone(String packageName, int userId) {
        try {
            if (packageName == null || userId < 0) {
                return;
            }
            if (!sAutoRevokeOnExitPackages.contains(packageName)) {
                return;
            }
            final PrivacyKitService service = sInstance;
            if (service != null) {
                service.scheduleAutoRevoke(packageName, userId);
            }
        } catch (Throwable t) {
            Slog.w(TAG, "PrivacyKit auto-revoke trigger failed for " + packageName, t);
        }
    }

    private void scheduleAutoRevoke(String packageName, int userId) {
        final Handler handler = mBackgroundHandler;
        if (handler == null) {
            return;
        }
        final String key = userId + ":" + packageName;
        synchronized (mPendingAutoRevoke) {
            if (!mPendingAutoRevoke.add(key)) {
                // A pass for this (user, package) is already queued; it will
                // re-read the live state when it runs, so a second one would
                // only duplicate work.
                return;
            }
        }
        handler.postDelayed(() -> {
            synchronized (mPendingAutoRevoke) {
                mPendingAutoRevoke.remove(key);
            }
            try {
                autoRevokeIfStopped(packageName, userId);
            } catch (Throwable t) {
                Slog.w(TAG, "PrivacyKit auto-revoke failed for " + packageName, t);
            }
        }, AUTO_REVOKE_SETTLE_DELAY_MS);
    }

    /**
     * The deferred half of {@link #CONTROL_AUTO_REVOKE_ON_EXIT}. Runs on
     * BackgroundThread, one settle delay after the app was seen to stop,
     * holding no lock.
     *
     * <p>Four gates stand between arriving here and revoking anything, in
     * increasing cost order: the control must still be on; the package must
     * still exist; it must not be a system, updated-system or persistent
     * package; and its UID must have no process at all. That last one is the
     * important one - it is what makes a restarted service, a relaunch, or a
     * surviving second process cancel the revoke instead of having permissions
     * pulled out from under it.
     *
     * <p>Per permission it then skips anything not granted, anything that is
     * not a dangerous (runtime) permission, and anything flagged
     * {@link #UNTOUCHABLE_PERMISSION_FLAGS}. A failure on one permission is
     * logged and the loop continues.
     */
    private void autoRevokeIfStopped(String packageName, int userId) {
        if (!PrivacyKitBooleanListUtils.contains(mContext.getContentResolver(),
                AUTO_REVOKE_ON_EXIT_LIST, packageName)) {
            // Toggled off while we were waiting out the settle delay.
            return;
        }
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfoAsUser(packageName, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM
                | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                | ApplicationInfo.FLAG_PERSISTENT)) != 0) {
            Slog.i(TAG, "PrivacyKit auto-revoke: skipping system/persistent package "
                    + packageName);
            return;
        }
        final ActivityManagerInternal ami =
                LocalServices.getService(ActivityManagerInternal.class);
        if (ami == null) {
            return;
        }
        if (ami.getUidProcessState(ai.uid) != ActivityManager.PROCESS_STATE_NONEXISTENT) {
            // The app is back, or never fully left.
            return;
        }
        final PackageInfo pi;
        try {
            pi = pm.getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        final String[] requested = pi.requestedPermissions;
        final int[] requestedFlags = pi.requestedPermissionsFlags;
        if (requested == null || requestedFlags == null
                || requestedFlags.length < requested.length) {
            return;
        }
        final UserHandle user = UserHandle.of(userId);
        int revoked = 0;
        for (int i = 0; i < requested.length; i++) {
            final String permission = requested[i];
            if (permission == null
                    || (requestedFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0) {
                continue;
            }
            try {
                final PermissionInfo info = pm.getPermissionInfo(permission, 0);
                if (info.getProtection() != PermissionInfo.PROTECTION_DANGEROUS) {
                    continue;
                }
                if ((pm.getPermissionFlags(permission, packageName, user)
                        & UNTOUCHABLE_PERMISSION_FLAGS) != 0) {
                    continue;
                }
                pm.revokeRuntimePermission(packageName, permission, user,
                        "PrivacyKit auto-revoke on exit");
                revoked++;
            } catch (Exception e) {
                // One stubborn permission must not abort the rest.
                Slog.w(TAG, "PrivacyKit auto-revoke: could not revoke " + permission
                        + " for " + packageName, e);
            }
        }
        if (revoked > 0) {
            Slog.i(TAG, "PrivacyKit auto-revoke: revoked " + revoked
                    + " runtime permission(s) from " + packageName + " (user " + userId + ")");
        }
    }

    // ---- resolution (shared by AIDL Stub and LocalServices internal) ----------

    private String resolveInternal(String packageName, String key, String realValue) {
        try {
            if (packageName == null || key == null) {
                return realValue;
            }
            PrivacyKitProfileStore.Rule rule = mStore.getRule(packageName, key);
            if (rule == null) {
                return realValue;
            }
            // A method reference, not perLaunchToken(): Java evaluates
            // arguments eagerly, so the call form read and regex-split
            // /proc/<pid>/stat on every resolveIdentifier - about forty times
            // per app launch, on a system_server binder thread - to produce a
            // token only RULE_PER_LAUNCH ever reads. The resolver now pulls it
            // from the supplier inside that one case.
            return PrivacyKitRuleResolver.resolve(rule.type, packageName, key, realValue,
                    rule.value, storeAdapter(), PrivacyKitService::perLaunchToken);
        } catch (RuntimeException e) {
            // Fail open: never let a PrivacyKit bug break the caller's real
            // identifier read.
            Slog.e(TAG, "resolveIdentifier failed for " + packageName + "/" + key, e);
            return realValue;
        }
    }

    /**
     * A token that is stable for the lifetime of the calling app's process and
     * differs across relaunches, used to seed
     * {@link PrivacyKitRuleResolver#RULE_PER_LAUNCH} so it mints one value per
     * launch while every read within that launch agrees.
     *
     * <p>Derived from the caller's pid plus that pid's kernel start time
     * (field 22 of {@code /proc/<pid>/stat}, clock ticks since boot); together
     * they are unique per launch even when the kernel recycles a pid. This runs
     * in system_server while the caller's identity is still on the binder thread
     * - the SSAID and telephony read paths both key off getCallingUid, so
     * identity is never cleared before we reach here.
     *
     * <p>Fails safe to the pid alone, then to 0, in which case PER_LAUNCH is
     * merely stable per (package, key) rather than per launch - still coherent.
     */
    private static long perLaunchToken() {
        final int pid = Binder.getCallingPid();
        if (pid <= 0) {
            return 0L;
        }
        final long start = readProcStartTicks(pid);
        // Pack pid (<= 22 bits on Linux) below the start time so both
        // contribute; either alone still distinguishes most relaunches.
        return (start << 22) ^ (pid & 0x3FFFFFL);
    }

    /**
     * Splitter for the whitespace-separated tail of {@code /proc/<pid>/stat},
     * hoisted so the pattern is compiled once instead of once per parse:
     * {@code String#split} only has a no-regex fast path for a single literal
     * character, which {@code "\\s+"} is not, so the inline form compiled a
     * fresh Pattern on every call.
     */
    private static final java.util.regex.Pattern PROC_STAT_FIELDS =
            java.util.regex.Pattern.compile("\\s+");

    /** How long a parsed start time may be reused for the same pid. */
    private static final long PROC_START_CACHE_MS = 5000L;

    /** An immutable (pid, starttime) parse result. @see #sLastProcStart */
    private static final class ProcStart {
        final int pid;
        final long ticks;
        final long readAtUptimeMillis;

        ProcStart(int pid, long ticks, long readAtUptimeMillis) {
            this.pid = pid;
            this.ticks = ticks;
            this.readAtUptimeMillis = readAtUptimeMillis;
        }
    }

    /**
     * One-entry cache of the last {@link #readProcStartTicks} parse.
     *
     * <p>A launching app resolves its identifiers in a burst from one pid, so a
     * single slot turns roughly forty /proc reads into one. Lock-free: every
     * field of {@link ProcStart} is final, so publishing the object through a
     * volatile is safe, and a lost race only costs one extra read.
     *
     * <p>A pid's start time never changes while the pid lives, so the only
     * staleness risk is pid reuse: the kernel would have to recycle this exact
     * pid AND the new process would have to call resolveIdentifier, both within
     * {@link #PROC_START_CACHE_MS}. Linux hands out pids sequentially up to
     * pid_max, so that means ~32k process creations in five seconds. If it ever
     * did happen the only effect is that one launch reuses the previous
     * launch's PER_LAUNCH value - the same outcome as the documented
     * token == 0 degradation, not a crash and not an incoherent value.
     */
    private static volatile ProcStart sLastProcStart;

    /**
     * Field 22 (starttime) of {@code /proc/<pid>/stat}, or 0 if it cannot be
     * read, answered from {@link #sLastProcStart} when the same pid asked
     * within the last {@link #PROC_START_CACHE_MS}. Failures are never cached,
     * so a transient read error does not stick.
     */
    private static long readProcStartTicks(int pid) {
        final long nowUptime = SystemClock.uptimeMillis();
        final ProcStart cached = sLastProcStart;
        if (cached != null && cached.pid == pid
                && nowUptime - cached.readAtUptimeMillis < PROC_START_CACHE_MS) {
            return cached.ticks;
        }
        final long ticks = readProcStartTicksUncached(pid);
        if (ticks != 0L) {
            sLastProcStart = new ProcStart(pid, ticks, nowUptime);
        }
        return ticks;
    }

    /**
     * The uncached parse. comm (field 2) is parenthesised and may itself
     * contain spaces and parentheses, so parsing starts after the last ')'.
     */
    private static long readProcStartTicksUncached(int pid) {
        try {
            final byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/" + pid + "/stat"));
            final String stat = new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
            final int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 >= stat.length()) {
                return 0L;
            }
            // Everything after ") " is field 3 onward; starttime is field 22,
            // i.e. index (22 - 3) = 19 of that whitespace-split remainder.
            final String[] f = PROC_STAT_FIELDS.split(stat.substring(close + 2).trim());
            if (f.length <= 19) {
                return 0L;
            }
            return Long.parseLong(f[19]);
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---- adb `cmd privacykit` hooks (PrivacyKitShellCommand) -----------------

    boolean isAdbControlEnabledInternal() {
        return mStore.isAdbControlEnabled();
    }

    String resolveForShell(String packageName, String key, String realValue) {
        return resolveInternal(packageName, key, realValue);
    }

    java.util.Map<String, PrivacyKitProfileStore.Rule> activeRulesForShell(
            String packageName) {
        return mStore.getActiveRules(packageName);
    }

    private PrivacyKitRuleResolver.Store storeAdapter() {
        return new PrivacyKitRuleResolver.Store() {
            @Override
            public String getGeneratedValue(String packageName, String key) {
                return mStore.getGeneratedValue(packageName, key);
            }

            @Override
            public void putGeneratedValue(String packageName, String key, String value) {
                mStore.putGeneratedValue(packageName, key, value);
            }
        };
    }

    // ---- boolean control enforcement --------------------------------------------

    private String controlSettingsKey(String control) {
        switch (control) {
            case CONTROL_RESTRICT_INTERNET:
                return PK_RESTRICT_INTERNET_LIST;
            case CONTROL_RESTRICT_STORAGE:
                return PK_RESTRICT_STORAGE_LIST;
            case CONTROL_FORCE_DATA_ISOLATION:
                return PK_FORCE_DATA_ISOLATION_LIST;
            case CONTROL_RESTRICT_CONTACTS:
                return RESTRICT_CONTACTS_LIST;
            case CONTROL_RESTRICT_CALL_LOG:
                return RESTRICT_CALL_LOG_LIST;
            case CONTROL_RESTRICT_SMS:
                return RESTRICT_SMS_LIST;
            case CONTROL_RESTRICT_CALENDAR:
                return RESTRICT_CALENDAR_LIST;
            case CONTROL_RESTRICT_ACCOUNTS:
                return RESTRICT_ACCOUNTS_LIST;
            case CONTROL_RESTRICT_BLUETOOTH:
                return RESTRICT_BLUETOOTH_LIST;
            case CONTROL_BLOCK_ADVERTISING_ID:
                return BLOCK_ADVERTISING_ID_LIST;
            case CONTROL_BLOCK_CLIPBOARD_READ:
                return BLOCK_CLIPBOARD_READ_LIST;
            case CONTROL_BLOCK_BACKGROUND_START:
                return BLOCK_BACKGROUND_START_LIST;
            case CONTROL_AUTO_REVOKE_ON_EXIT:
                return AUTO_REVOKE_ON_EXIT_LIST;
            default:
                return null;
        }
    }

    private boolean getBooleanControlInternal(String packageName, String control) {
        String settingsKey = controlSettingsKey(control);
        if (settingsKey == null) return false;
        return PrivacyKitBooleanListUtils.contains(
                mContext.getContentResolver(), settingsKey, packageName);
    }

    private void setBooleanControlInternal(String packageName, String control, boolean value) {
        String settingsKey = controlSettingsKey(control);
        if (settingsKey == null) {
            throw new IllegalArgumentException("Unknown control " + control);
        }
        int userId = UserHandle.getCallingUserId();
        if (value) {
            PrivacyKitBooleanListUtils.add(mContext, settingsKey, packageName, userId);
        } else {
            PrivacyKitBooleanListUtils.remove(mContext, settingsKey, packageName, userId);
        }
        if (CONTROL_BLOCK_ADVERTISING_ID.equals(control)) {
            // Deliberately before the UID resolution below: the ad-ID gate is
            // keyed by package name, so it must stay in step with the persisted
            // list even for a package whose UID cannot be resolved right now.
            setAdvertisingIdBlockCached(packageName, value);
        }
        // Same reasoning for the clipboard, background-start and auto-revoke
        // caches: all three are keyed by package name and none of them needs a
        // UID, so they are refreshed before the resolution that can fail.
        updateEnforcementCache(control, packageName, value);

        try {
            int uid = mContext.getPackageManager().getPackageUidAsUser(packageName, userId);
            switch (control) {
                case CONTROL_RESTRICT_INTERNET:
                    setUidNetworkPolicy(uid, value);
                    break;
                case CONTROL_RESTRICT_STORAGE:
                    setStorageOps(uid, value);
                    break;
                case CONTROL_FORCE_DATA_ISOLATION:
                    setLegacyStorageOp(uid, value);
                    break;
                case CONTROL_BLOCK_ADVERTISING_ID:
                    // Nothing UID-scoped to push: enforcement is the
                    // ActiveServices bind gate reading the cache refreshed
                    // above. Explicit so it can never fall through to the
                    // AppOps default.
                    break;
                case CONTROL_BLOCK_CLIPBOARD_READ:
                case CONTROL_BLOCK_BACKGROUND_START:
                case CONTROL_AUTO_REVOKE_ON_EXIT:
                    // Likewise nothing to push. Each is enforced by a live
                    // check (ClipboardService's access check, the ActiveServices
                    // start path, the process-gone notification) reading the
                    // cache refreshed above. Listed explicitly so they can
                    // never fall through to the AppOps default.
                    break;
                default:
                    setRuntimePermissionOp(restrictionOpForControl(control), uid, value);
                    break;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Cannot enforce " + control + " for unknown package " + packageName);
        }
    }

    /** Applies the boolean controls implied by a profile's isolation mode
     *  whenever it becomes the active profile for packageName. */
    private void applyModeForActiveProfile(String packageName) {
        String profileId = mStore.getActiveProfileId(packageName);
        if (profileId == null) {
            return;
        }
        String mode = mStore.getProfileMode(packageName, profileId);
        if (mode == null) {
            return;
        }
        boolean restrictStorage;
        boolean forceIsolation;
        switch (mode) {
            case PrivacyKitProfileStore.MODE_ISOLATED:
                restrictStorage = true;
                forceIsolation = true;
                break;
            case PrivacyKitProfileStore.MODE_HYBRID:
                restrictStorage = false;
                forceIsolation = true;
                break;
            default: // MODE_SHARED
                restrictStorage = false;
                forceIsolation = false;
                break;
        }
        setBooleanControlInternal(packageName, CONTROL_RESTRICT_STORAGE, restrictStorage);
        setBooleanControlInternal(packageName, CONTROL_FORCE_DATA_ISOLATION, forceIsolation);
    }

    /** Restrict Internet: block all network access for this UID via the same
     *  per-UID firewall primitive Data Saver's background-restriction uses. */
    private void setUidNetworkPolicy(int uid, boolean restrict) {
        try {
            IBinder b = ServiceManager.getService(Context.NETWORK_POLICY_SERVICE);
            if (b == null) return;
            INetworkPolicyManager policy = INetworkPolicyManager.Stub.asInterface(b);
            policy.setUidPolicy(uid,
                    restrict ? 0 /* BestROM: POLICY_REJECT_ALL absent in A17 (Lineage netpolicy patch not ported) */
                             : NetworkPolicyManager.POLICY_NONE);
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Failed to set network policy for uid " + uid, e);
        }
    }

    /** Restrict Storage: deny all shared/external storage access outright. */
    private void setStorageOps(int uid, boolean restrict) {
        int mode = restrict ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_DEFAULT;
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        if (appOps == null) return;
        appOps.setUidMode(AppOpsManager.OP_READ_EXTERNAL_STORAGE, uid, mode);
        appOps.setUidMode(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, uid, mode);
        appOps.setUidMode(AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE, uid, mode);
    }

    /** Force Data Isolation: force scoped storage even for apps with a legacy
     *  full-storage grandfather grant, without blocking storage entirely. */
    private void setLegacyStorageOp(int uid, boolean force) {
        int mode = force ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_DEFAULT;
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        if (appOps == null) return;
        appOps.setUidMode(AppOpsManager.OP_LEGACY_STORAGE, uid, mode);
    }

    /** The single AppOps op a Privacy Restrictions control denies, or
     *  AppOpsManager.OP_NONE for any control that isn't one of them. */
    private static int restrictionOpForControl(String control) {
        switch (control) {
            case CONTROL_RESTRICT_CONTACTS:
                return AppOpsManager.OP_READ_CONTACTS;
            case CONTROL_RESTRICT_CALL_LOG:
                return AppOpsManager.OP_READ_CALL_LOG;
            case CONTROL_RESTRICT_SMS:
                return AppOpsManager.OP_READ_SMS;
            case CONTROL_RESTRICT_CALENDAR:
                return AppOpsManager.OP_READ_CALENDAR;
            case CONTROL_RESTRICT_ACCOUNTS:
                return AppOpsManager.OP_GET_ACCOUNTS;
            case CONTROL_RESTRICT_BLUETOOTH:
                return AppOpsManager.OP_BLUETOOTH_CONNECT;
            default:
                return AppOpsManager.OP_NONE;
        }
    }

    /**
     * Privacy Restrictions: deny one runtime-permission-backed op for this UID.
     * Every op used here is its own switch op (AppOpInfo.Builder defaults
     * mSwitchCode to the op's own code and none of these six override it), so
     * the denial is exactly as narrow as the control claims - no location-style
     * switch-op aliasing.
     *
     * <p>The platform's soft-denial path then hands the app an *empty* result
     * instead of throwing: ContentProvider.Transport.query() returns an empty
     * MatrixCursor (Contacts / Call Log / SMS / Calendar),
     * AccountManagerService returns EMPTY_ACCOUNT_ARRAY, and the Bluetooth
     * AdapterServiceBinder returns Collections.emptyList().
     *
     * <p>Note the deliberate asymmetry with {@link #setStorageOps}: lifting a
     * restriction uses MODE_ALLOWED, not MODE_DEFAULT. AppOpsService.setUidMode()
     * derives FLAG_PERMISSION_REVOKED_COMPAT from {@code mode != MODE_ALLOWED},
     * and PermissionPolicyService.shouldGrantAppOp() forces the op back to
     * MODE_IGNORED for as long as that flag is set - so clearing a restriction
     * with MODE_DEFAULT would leave the app restricted forever after the next
     * policy sync. MODE_ALLOWED clears the flag; the op is then re-derived from
     * the app's real permission state on the next sync, so an app that never
     * held the permission stays denied on the permission check anyway.
     */
    private void setRuntimePermissionOp(int op, int uid, boolean restrict) {
        if (op == AppOpsManager.OP_NONE) {
            return;
        }
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        if (appOps == null) return;
        appOps.setUidMode(op, uid,
                restrict ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
    }

    /**
     * Bridges a "Privacy Restrictions" catalog rule onto its boolean control, so
     * picking "Empty / Restricted" for e.g. Contacts in the Identifiers screen
     * actually enforces rather than only being stored. RULE_EMPTY means
     * restrict; every other rule type (including the randomising ones, which are
     * meaningless for a content-provider corpus) means unrestricted. No-op for
     * every identifier key that isn't in {@link #RESTRICTION_KEYS}.
     */
    private void applyRestrictionRule(String packageName, String key, int ruleType) {
        String control = restrictionControlForKey(key);
        if (control == null) {
            return;
        }
        try {
            setBooleanControlInternal(packageName, control,
                    ruleType == PrivacyKitRuleResolver.RULE_EMPTY);
        } catch (RuntimeException e) {
            // The rule itself is already stored; an enforcement failure must
            // never propagate back out of the rule write.
            Slog.w(TAG, "Failed to apply restriction " + control + " for " + packageName, e);
        }
    }

    /**
     * Re-applies every Privacy Restrictions rule held by packageName's currently
     * active profile, so switching profiles switches the restrictions with them
     * (and so deleting/clearing a profile lifts them).
     */
    private void applyRestrictionRulesForActiveProfile(String packageName) {
        for (String key : RESTRICTION_KEYS) {
            PrivacyKitProfileStore.Rule rule = mStore.getRule(packageName, key);
            applyRestrictionRule(packageName, key,
                    rule != null ? rule.type : PrivacyKitRuleResolver.RULE_REAL);
        }
    }

    /**
     * Re-derives every eagerly-enforced control for packageName from the profile
     * state the store holds <em>right now</em>: the isolation-mode-backed
     * storage controls and the Privacy Restrictions ops.
     *
     * <p>Safe to call after any mutation that can change which profile is
     * active, including one that removes the package's last profile.
     * {@link #applyModeForActiveProfile} deliberately no-ops when there is no
     * active profile, which would leave a package that just stopped being
     * managed clamped at MODE_IGNORED forever with no UI left to lift it - so
     * that case is handled here by clearing both storage controls explicitly.
     * The Privacy Restrictions ops need no such special case: {@link
     * #applyRestrictionRulesForActiveProfile} already reads a missing rule as
     * RULE_REAL and lifts the op.
     */
    private void applyProfileEnforcement(String packageName) {
        if (mStore.getActiveProfileId(packageName) == null) {
            setBooleanControlInternal(packageName, CONTROL_RESTRICT_STORAGE, false);
            setBooleanControlInternal(packageName, CONTROL_FORCE_DATA_ISOLATION, false);
        } else {
            applyModeForActiveProfile(packageName);
        }
        applyRestrictionRulesForActiveProfile(packageName);
    }

    /** Boolean control enforcing a "Privacy Restrictions" catalog key, or null. */
    private static String restrictionControlForKey(String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case KEY_CONTACTS:
                return CONTROL_RESTRICT_CONTACTS;
            case KEY_CALL_LOG:
                return CONTROL_RESTRICT_CALL_LOG;
            case KEY_SMS:
                return CONTROL_RESTRICT_SMS;
            case KEY_CALENDAR:
                return CONTROL_RESTRICT_CALENDAR;
            case KEY_ACCOUNTS:
                return CONTROL_RESTRICT_ACCOUNTS;
            case KEY_BLUETOOTH_BONDED_DEVICES:
                return CONTROL_RESTRICT_BLUETOOTH;
            case KEY_ADVERTISING_ID:
                return CONTROL_BLOCK_ADVERTISING_ID;
            default:
                return null;
        }
    }

    // ---- backup / restore ---------------------------------------------------------

    /**
     * Combines both stores' export into one top-level backup JSON object:
     * {"formatVersion": 1, "exportedAt": &lt;millis&gt;, "profiles": &lt;profiles
     * export&gt;, "history": &lt;history export&gt;}.
     */
    private String exportBackupInternal() {
        try {
            JSONObject root = new JSONObject();
            root.put("formatVersion", BACKUP_FORMAT_VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("profiles", new JSONObject(mStore.exportToJson()));
            root.put("history", new JSONArray(mHistoryStore.exportToJson()));
            return root.toString();
        } catch (JSONException e) {
            // mStore/mHistoryStore always hand back their own valid JSON, so
            // this is unreachable in practice - surface it as a
            // RuntimeException rather than silently returning a broken or
            // partial backup.
            Slog.e(TAG, "Failed to build PrivacyKit backup JSON", e);
            throw new RuntimeException("Failed to build PrivacyKit backup", e);
        }
    }

    /**
     * Parses the top-level backup object produced by {@link
     * #exportBackupInternal()}, extracts its "profiles" sub-object, and
     * hands it to {@link PrivacyKitProfileStore#importFromJson}. History is
     * never imported - restore only ever touches profiles/rules. Throws on
     * totally malformed top-level JSON; PrivacyKitProfileStore.importFromJson
     * itself already skips individually-invalid profile/rule entries rather
     * than failing the whole import.
     *
     * <p>A restore is a bulk rule mutation, so - exactly like
     * setIdentifierRule / clearIdentifierRule / clearProfile / createProfile /
     * setActiveProfile / deleteProfile - it has to push the restored state out
     * into the platform afterwards. Without that, a restored "Empty /
     * Restricted" rule would read back as RULE_EMPTY through getRuleType() and
     * be drawn as enforced by the Settings UI while no AppOps denial had ever
     * been issued for it. See {@link #reenforceAfterImport}.
     */
    private int importBackupInternal(String json, boolean merge) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Malformed PrivacyKit backup JSON", e);
        }
        JSONObject profilesObj = root.optJSONObject("profiles");
        if (profilesObj == null) {
            throw new IllegalArgumentException(
                    "Malformed PrivacyKit backup JSON: missing 'profiles'");
        }
        // Snapshot before the store mutates: a merge=false restore *replaces*
        // the store, so packages the backup doesn't mention silently stop being
        // managed and need their enforcement lifted just as much as the
        // restored ones need theirs applied.
        List<String> managedBefore = mStore.getManagedPackages();
        int imported = mStore.importFromJson(profilesObj.toString(), merge).packagesImported;
        reenforceAfterImport(managedBefore);
        return imported;
    }

    /**
     * Pushes post-restore profile state out into the platform for every package
     * a backup import could have touched.
     *
     * <p>PrivacyKitProfileStore.ImportResult only carries a count, not the
     * imported package names, so the affected set is reconstructed from two
     * {@link PrivacyKitProfileStore#getManagedPackages()} snapshots instead:
     * everything managed before the import (catching packages a merge=false
     * restore dropped) unioned with everything managed after it (catching the
     * restored ones). That is a superset - packages a merge=true restore never
     * mentioned get re-asserted too - which costs a few redundant AppOps writes
     * but is idempotent, and doubles as a repair for any enforcement that had
     * drifted out of sync with the store.
     *
     * <p>Enforcement runs through {@link #setBooleanControlInternal}, so each
     * restored restriction also (re-)joins its {@code privacykit_restrict_*_list}
     * Settings.Secure membership list. That is what lets the boot-time {@link
     * #reapplyAppOpRestrictions()} repair keep restored restrictions alive
     * across reboots, instead of them surviving only until the next boot.
     *
     * <p>One bad package must never abort the rest of a restore, so each one is
     * guarded individually: log and continue.
     */
    private void reenforceAfterImport(List<String> managedBefore) {
        Set<String> affected = new LinkedHashSet<>(managedBefore);
        affected.addAll(mStore.getManagedPackages());
        for (String pkg : affected) {
            try {
                applyProfileEnforcement(pkg);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to apply restored PrivacyKit enforcement for " + pkg, e);
            }
        }
    }

    // ---- permission helpers -----------------------------------------------------

    /** Only the calling app itself may resolve its own identifiers. */
    private void enforceCallerIsPackage(String packageName) {
        int callingUid = Binder.getCallingUid();
        // Trust any system-range caller (system_server itself, and mainline
        // modules such as Wifi that cannot reach PrivacyKitManagerInternal
        // directly due to the module boundary) to resolve on behalf of an
        // already-permission-verified app, not just literally SYSTEM_UID/
        // ROOT_UID. Matches the FIRST_APPLICATION_UID trust boundary used
        // elsewhere in the platform (e.g. Settings.java's SSAID scoping).
        if (callingUid < android.os.Process.FIRST_APPLICATION_UID) {
            return;
        }
        int callingUserId = UserHandle.getUserId(callingUid);
        try {
            int packageUid = mContext.getPackageManager()
                    .getPackageUidAsUser(packageName, callingUserId);
            if (packageUid != callingUid) {
                throw new SecurityException(
                        "packageName " + packageName + " does not belong to calling uid "
                                + callingUid);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new SecurityException("Unknown package " + packageName);
        }
    }

    /** Rule mutation and cross-app read APIs require the privileged Settings UI. */
    private void enforceWritePermission() {
        mContext.enforceCallingPermission(Manifest.permission.WRITE_SECURE_SETTINGS,
                "PrivacyKit rule changes require WRITE_SECURE_SETTINGS");
    }

    // ---- AIDL implementation -----------------------------------------------------

    private final class PrivacyKitManagerStub extends IPrivacyKitManager.Stub {

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            new PrivacyKitShellCommand(PrivacyKitService.this, this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override
        public boolean isAdbControlEnabled() {
            enforceWritePermission();
            return mStore.isAdbControlEnabled();
        }

        @Override
        public void setAdbControlEnabled(boolean enabled) {
            enforceWritePermission();
            mStore.setAdbControlEnabled(enabled);
        }

        @Override
        public boolean hasIdentityProfile(String packageName) {
            // Called by every app at launch (PrivacyKitIdentityInjector's
            // fast-path check), not just the privileged Settings UI - accept
            // either a WRITE_SECURE_SETTINGS holder checking any package, or
            // an app checking its own package.
            if (mContext.checkCallingPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                enforceCallerIsPackage(packageName);
            }
            return mStore.hasProfile(packageName);
        }

        @Override
        public int getRuleType(String packageName, String key) {
            enforceWritePermission();
            PrivacyKitProfileStore.Rule rule = mStore.getRule(packageName, key);
            return rule != null ? rule.type : PrivacyKitRuleResolver.RULE_REAL;
        }

        @Override
        public String getRuleValue(String packageName, String key) {
            enforceWritePermission();
            PrivacyKitProfileStore.Rule rule = mStore.getRule(packageName, key);
            return rule != null ? rule.value : null;
        }

        @Override
        public void setIdentifierRule(String packageName, String key, int ruleType,
                String customValue) {
            enforceWritePermission();
            if (!PrivacyKitRuleResolver.isValidRuleType(ruleType)) {
                throw new IllegalArgumentException("Invalid ruleType " + ruleType);
            }
            mStore.setRule(packageName, key, ruleType, customValue);
            if (ruleType == PrivacyKitRuleResolver.RULE_REAL) {
                // setRule() special-cases RULE_REAL to remove the rule entirely -
                // log it as a clear, not a set, to match clearIdentifierRule()'s
                // history entry for the same effective outcome.
                mHistoryStore.record(packageName, "RULE_CLEARED", key);
            } else {
                mHistoryStore.record(packageName, "RULE_SET", key + " -> " + ruleType);
            }
            applyRestrictionRule(packageName, key, ruleType);
        }

        @Override
        public void clearIdentifierRule(String packageName, String key) {
            enforceWritePermission();
            mStore.clearRule(packageName, key);
            mHistoryStore.record(packageName, "RULE_CLEARED", key);
            applyRestrictionRule(packageName, key, PrivacyKitRuleResolver.RULE_REAL);
        }

        @Override
        public void clearProfile(String packageName) {
            enforceWritePermission();
            mStore.clearProfile(packageName);
            mHistoryStore.record(packageName, "PROFILE_CLEARED", null);
            applyRestrictionRulesForActiveProfile(packageName);
        }

        @Override
        public List<String> getConfiguredPackages() {
            enforceWritePermission();
            return mStore.getConfiguredPackages();
        }

        @Override
        public String resolveIdentifier(String packageName, String key, String realValue) {
            enforceCallerIsPackage(packageName);
            return resolveInternal(packageName, key, realValue);
        }

        @Override
        public boolean getBooleanControl(String packageName, String control) {
            enforceWritePermission();
            return getBooleanControlInternal(packageName, control);
        }

        @Override
        public void setBooleanControl(String packageName, String control, boolean value) {
            enforceWritePermission();
            setBooleanControlInternal(packageName, control, value);
            mHistoryStore.record(packageName, "CONTROL_SET", control + " -> " + value);
        }

        // ---- profile management ------------------------------------------------

        @Override
        public List<String> getManagedPackages() {
            enforceWritePermission();
            return mStore.getManagedPackages();
        }

        @Override
        public List<String> listProfileIds(String packageName) {
            enforceWritePermission();
            return mStore.listProfileIds(packageName);
        }

        @Override
        public String getActiveProfileId(String packageName) {
            enforceWritePermission();
            return mStore.getActiveProfileId(packageName);
        }

        @Override
        public String getProfileName(String packageName, String profileId) {
            enforceWritePermission();
            return mStore.getProfileName(packageName, profileId);
        }

        @Override
        public String getProfileMode(String packageName, String profileId) {
            enforceWritePermission();
            return mStore.getProfileMode(packageName, profileId);
        }

        @Override
        public void renameProfile(String packageName, String profileId, String newName) {
            enforceWritePermission();
            mStore.renameProfile(packageName, profileId, newName);
        }

        @Override
        public void setProfileMode(String packageName, String profileId, String mode) {
            enforceWritePermission();
            mStore.setProfileMode(packageName, profileId, mode);
        }

        @Override
        public String createProfile(String packageName, String name, String mode) {
            enforceWritePermission();
            String id = mStore.createProfile(packageName, name, mode);
            mHistoryStore.record(packageName, "PROFILE_CREATED", name + " (" + mode + ")");
            applyModeForActiveProfile(packageName);
            applyRestrictionRulesForActiveProfile(packageName);
            return id;
        }

        @Override
        public void setActiveProfile(String packageName, String profileId) {
            enforceWritePermission();
            mStore.setActiveProfile(packageName, profileId);
            mHistoryStore.record(packageName, "PROFILE_ACTIVATED", profileId);
            applyModeForActiveProfile(packageName);
            applyRestrictionRulesForActiveProfile(packageName);
        }

        @Override
        public void deleteProfile(String packageName, String profileId) {
            enforceWritePermission();
            mStore.deleteProfile(packageName, profileId);
            mHistoryStore.record(packageName, "PROFILE_DELETED", profileId);
            // Whichever profile is active *now* - possibly none, if that was the
            // package's last one - decides both the isolation-mode storage
            // controls and the Privacy Restrictions ops. Re-deriving only the
            // latter left Restrict Storage / Force Data Isolation stuck on after
            // an isolated profile was deleted, with no UI left to lift them.
            applyProfileEnforcement(packageName);
        }

        // Colour and note are cosmetic per-profile metadata. Like renameProfile
        // and setProfileMode - the two existing metadata-only mutations - they
        // deliberately record no history entry and trigger no enforcement pass.
        // The log is capped at 200 entries and exists to show what was spoofed
        // and when; a note edited a character at a time would evict real rule
        // changes from it, since PrivacyKitHistoryStore.record() only collapses
        // *identical* consecutive entries and every keystroke yields a different
        // detail string. Neither field changes what the platform enforces, so
        // there is nothing for applyProfileEnforcement() to re-derive.

        @Override
        public String getProfileColor(String packageName, String profileId) {
            enforceWritePermission();
            return mStore.getProfileColor(packageName, profileId);
        }

        @Override
        public void setProfileColor(String packageName, String profileId, String color) {
            enforceWritePermission();
            mStore.setProfileColor(packageName, profileId, color);
        }

        @Override
        public long getProfileLastUsed(String packageName, String profileId) {
            enforceWritePermission();
            return mStore.getProfileLastUsed(packageName, profileId);
        }

        @Override
        public String getProfileNote(String packageName, String profileId) {
            enforceWritePermission();
            return mStore.getProfileNote(packageName, profileId);
        }

        @Override
        public void setProfileNote(String packageName, String profileId, String note) {
            enforceWritePermission();
            mStore.setProfileNote(packageName, profileId, note);
        }

        @Override
        public String cloneProfile(String packageName, String profileId, String newName) {
            enforceWritePermission();
            String id = mStore.cloneProfile(packageName, profileId, newName);
            if (id == null) {
                return null;
            }
            // A clone *is* a profile creation, so it is logged as one - the
            // History tab already has a localized PROFILE_CREATED label, and a
            // new action string would render there as a raw token until the UI
            // shipped one. The name/mode are read back from the store rather
            // than echoed from newName: a null/empty newName falls back to the
            // source profile's name, and the log should say what was actually
            // created.
            mHistoryStore.record(packageName, "PROFILE_CREATED",
                    mStore.getProfileName(packageName, id)
                            + " (" + mStore.getProfileMode(packageName, id) + ")");
            // No enforcement pass: the copy is inactive, so it changes neither
            // the isolation-mode storage controls nor the Privacy Restrictions
            // ops until setActiveProfile() switches to it, which applies both.
            return id;
        }

        @Override
        public List<String> getRecentHistory(int max) {
            enforceWritePermission();
            return mHistoryStore.getRecent(max);
        }

        // ---- backup / restore ---------------------------------------------------

        @Override
        public String exportBackup() {
            enforceWritePermission();
            return exportBackupInternal();
        }

        @Override
        public int importBackup(String json, boolean merge) {
            enforceWritePermission();
            return importBackupInternal(json, merge);
        }
    }
}
