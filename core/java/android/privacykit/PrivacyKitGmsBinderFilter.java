/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */
package android.privacykit;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.BinderWrapper;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

import java.util.Locale;
import java.util.WeakHashMap;

/**
 * PrivacyKit-Native chosen-value spoof for the UUID-shaped identities GmsCore
 * hands an app over a bound service: the Google Advertising ID (GAID) and the
 * App Set ID.
 *
 * <h3>Why a descriptor, and why the reply Parcel</h3>
 *
 * <p>The GMS client library is shipped through R8, so every class, field and
 * method name on the app side (and inside GmsCore) is renamed between releases:
 * {@code AdvertisingIdClient.Info.getId} and {@code AppSetIdInfo.getId} are
 * {@code a()} or {@code zza()} in the APK that actually runs. Two things do
 * <em>not</em> get renamed, because they are wire contract rather than code:
 *
 * <ul>
 *   <li>the <b>interface descriptor</b>, a plain string literal
 *       ({@code "com.google.android.gms.appset.internal.IAppSetIdService"})
 *       that R8 leaves alone because it is data, and that both ends must agree
 *       on for {@code writeInterfaceToken} to validate; and
 *   <li>the <b>shape of the reply Parcel</b>, which is fixed by the AIDL /
 *       SafeParcel encoding, not by any symbol name.
 * </ul>
 *
 * <p>So this class discovers the service by {@link IBinder#getInterfaceDescriptor()}
 * and substitutes by rewriting bytes in the reply {@link Parcel}. Nothing here
 * depends on a class name, a method name, or a field name, which is what makes
 * it survive a GMS update; a hook on a named getter would not.
 *
 * <h3>Where it is installed</h3>
 *
 * <p>{@code LoadedApk.ServiceDispatcher#doConnected}, the one choke point that
 * runs in the app process holding the raw service {@link IBinder} just before
 * it is handed to the app {@code ServiceConnection}. Only the reference
 * delivered to {@code onServiceConnected} is wrapped; the real binder is what
 * the framework {@code linkToDeath}-ed and stored in {@code mActiveConnections},
 * so death and disconnect bookkeeping is untouched.
 *
 * <h3>How the rewrite works</h3>
 *
 * <p>After {@code super.transact} succeeds the reply is read the way the app is
 * about to read it - {@link Parcel#readException()} first - and then scanned,
 * four bytes at a time, for a string that is a canonical 8-4-4-4-12 UUID. That
 * one string is overwritten <em>in place</em> with
 * {@link Parcel#writeStringNoHelper} - the NoHelper variants are used on both
 * sides so that an installed {@link Parcel.ReadWriteHelper}, which is free to
 * encode a string as a pool reference of a different length, can never turn a
 * same-length overwrite into a corrupting one.
 * Because both the real and the substitute value are exactly 36 characters, the
 * encoding occupies the identical byte range (4-byte length + 37 UTF-16 units +
 * padding = 80 bytes), so every other field survives byte for byte - including
 * the App Set ID <b>scope</b> int, and including any SafeParcel field headers
 * and trailing size record. The parcel is never rebuilt and its object offset
 * table is never touched.
 *
 * <p>If the reply is not the expected shape - no UUID anywhere, an exception
 * header, a suspiciously large reply, a read that falls off the end - the
 * original {@code dataPosition} is restored and the untouched real reply is
 * returned. A Parcel is never left half-rewritten.
 *
 * <h3>Gating</h3>
 *
 * <p>Double-gated and fail-open. The master kill switches
 * {@code persist.sys.privacykit.adid_spoof} and
 * {@code persist.sys.privacykit.appsetid_spoof} are read once into static
 * finals and both default false, so the {@code doConnected} hot path is a
 * single boolean test when off. Even with a flag on, the per-package
 * {@code resolveIdentifier(pkg, key, null)} echoes null back unless the user
 * stored a spoof rule, so the default leaves the real identity in place.
 * System callers (uid below {@link Process#FIRST_APPLICATION_UID}) and GmsCore
 * itself are always skipped - GmsCore is the source of these values. Every
 * mismatch (privacykit service down, wrong descriptor, non-UUID value, failed
 * transact, any exception at all) returns the real binder or the real reply.
 *
 * <p>The ORDER of those gates matters as much as the gates themselves, because
 * {@code doConnected} runs on the app MAIN THREAD and
 * {@link IBinder#getInterfaceDescriptor()} is a blocking binder transaction to
 * whatever process just got bound. So the descriptor is the LAST question asked,
 * not the first: two static booleans, then the package of the bound component,
 * then the process uid and the app package, then the per-process spoof memo. An
 * app with no rule, or one binding anything outside the Google namespace, never
 * makes that call at all; an app that does reach it makes it once per binder and
 * reads the answer out of {@link #sDescriptorCache} on every later connection.
 *
 * <h3>Scope</h3>
 *
 * <p>Only leaf identity binders are matched, by descriptor. The shared GMS API
 * broker ({@code IGmsServiceBroker}) is deliberately <em>not</em> in the table:
 * it also carries Auth, Maps, Firebase and Play Integrity, so wrapping it would
 * risk far more than these two keys are worth.
 *
 * <p>PARTIAL, not full. Not covered: the deprecated
 * {@code androidx.ads.identifier} provider route; native or in-GmsCore reads
 * that never cross this process; and any GMS API whose result is delivered to a
 * <em>callback</em> binder passed in the request rather than returned in the
 * reply, since a reply-side rewrite cannot see an inbound transaction.
 *
 * @hide
 */
public final class PrivacyKitGmsBinderFilter extends BinderWrapper {

    /**
     * Exact descriptor of the dedicated GMS advertising-id leaf binder, reached
     * by the {@code com.google.android.gms.ads.identifier.service.START} bind.
     * {@code AdvertisingIdClient} then calls
     * {@code IAdvertisingIdService.getId()}, whose reply Parcel is
     * {@code [exception header int][String UUID]}.
     */
    static final String DESC_ADVERTISING_ID =
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService";

    /** {@code getId()} is the first method on {@code IAdvertisingIdService}. */
    private static final int TXN_GET_ID = IBinder.FIRST_CALL_TRANSACTION;

    /**
     * Sentinel for "do not restrict the rewrite to one transaction code". Used
     * for App Set ID, whose transaction numbering is not pinned on this device;
     * the UUID scan is itself the guard, since a reply that carries no
     * UUID-shaped string is returned untouched.
     */
    private static final int TXN_ANY = -1;

    private static final String PKG_GMS = "com.google.android.gms";

    /**
     * Namespace every GMS-hosted identity service lives in. Used only to rule a
     * bound component OUT without a binder call - see the ordering note in the
     * class documentation. Never to rule one in: that is the descriptor job.
     */
    private static final String GOOGLE_PKG_PREFIX = "com.google.android.";

    private static final int IDX_AD_ID = 0;
    private static final int IDX_APP_SET_ID = 1;

    /** @see #cachedSpoof(String, String, int) */
    private static final String[] sSpoofCache = new String[2];

    /**
     * Package each {@link #sSpoofCache} slot was resolved for, and the marker
     * for "this slot has been resolved at all" - a resolved slot may legitimately
     * hold null, which is why the null answer cannot double as the marker.
     *
     * <p>One process usually means one package, but not always: a process can
     * hold LoadedApks for more than one, and the package here is the one that
     * owns the ServiceConnection, not the process. Keying on it keeps one app
     * substitute from ever being handed to another.
     */
    private static final String[] sSpoofPkg = new String[2];

    /** @see #cachedDescriptor(IBinder) */
    private static final WeakHashMap<IBinder, String> sDescriptorCache = new WeakHashMap<>();

    /** Cache entry meaning "this binder has no usable descriptor". */
    private static final String DESC_UNKNOWN = "";

    /**
     * Bytes a canonical UUID occupies in a Parcel: a 4-byte length, then
     * {@code pad_size((36 + 1) * 2)} = 76 bytes of UTF-16 plus padding.
     */
    private static final int UUID_PARCEL_BYTES = 80;

    /**
     * Replies bigger than this are not identity replies. Bounds the scan so a
     * pathological reply cannot turn into a long loop on a binder thread.
     */
    private static final int MAX_SCAN_BYTES = 8192;

    /** OFF by default; read once so the connect hot path is a single test. */
    private static final boolean sAdIdEnabled =
            SystemProperties.getBoolean("persist.sys.privacykit.adid_spoof", false);

    /** OFF by default; read once so the connect hot path is a single test. */
    private static final boolean sAppSetIdEnabled =
            SystemProperties.getBoolean("persist.sys.privacykit.appsetid_spoof", false);

    private static final boolean sAnyEnabled = sAdIdEnabled || sAppSetIdEnabled;

    /** Canonical 36-char substitute, already shape-validated. */
    private final String mSpoof;

    /** {@link #TXN_ANY} or the single transaction code allowed to be rewritten. */
    private final int mTxn;

    private PrivacyKitGmsBinderFilter(IBinder real, String spoof, int txn) {
        super(real);
        mSpoof = spoof;
        mTxn = txn;
    }

    /**
     * {@code LoadedApk.ServiceDispatcher#doConnected} entry point. Returns a
     * wrapper that rewrites the identity in the reply, or {@code service}
     * untouched on any gate miss or error (fail open).
     *
     * @param name the bound component, used ONLY to rule a connection out
     *             locally (its package must be in the Google namespace) so that
     *             the descriptor round trip below can be skipped on the main
     *             thread. What gets ruled IN is still decided by descriptor
     *             alone, never by component name, because the component name is
     *             exactly the thing a GMS update is free to change. May be null.
     */
    public static IBinder maybeWrap(ComponentName name, IBinder service, String myPkg) {
        // Cheapest test that exists: one static final that is false unless the
        // user turned a spoof on.
        if (!sAnyEnabled) {
            return service;
        }
        // Then a purely local one, before anything can reach a binder. Both
        // identity services are hosted by GmsCore, so a bind to a component
        // outside the Google namespace cannot be one of them. This is a NEGATIVE
        // filter only: a component inside the namespace is still matched by
        // descriptor and never by name, so a GMS release that renames the
        // service, or moves it to another class inside its own package, keeps
        // working - which is the property the descriptor matching exists for.
        if (name != null) {
            final String pkg = name.getPackageName();
            if (pkg == null || !pkg.startsWith(GOOGLE_PKG_PREFIX)) {
                return service;
            }
        }
        return maybeWrap(service, myPkg);
    }

    /**
     * Descriptor-driven wrap, independent of how the binder was obtained, so
     * any other site that holds a fresh service {@link IBinder} destined for an
     * app can reuse the same policy.
     */
    public static IBinder maybeWrap(IBinder service, String myPkg) {
        try {
            if (!sAnyEnabled || service == null || myPkg == null) {
                return service;
            }
            // Only ordinary apps; system-uid readers keep the real identity.
            if (Process.myUid() < Process.FIRST_APPLICATION_UID) {
                return service;
            }
            if (PKG_GMS.equals(myPkg)) {
                return service; // GmsCore is the source of these values
            }

            // Ask "does this app have anything to substitute" BEFORE asking the
            // bound service what it is: the first question is answered from a
            // per-process memo, the second is a blocking round trip on the main
            // thread. An app with no rule therefore never touches the binder it
            // was handed, no matter how many services it binds.
            final String adIdSpoof = sAdIdEnabled
                    ? cachedSpoof(myPkg, PrivacyKitKeys.KEY_ADVERTISING_ID, IDX_AD_ID)
                    : null;
            final String appSetIdSpoof = sAppSetIdEnabled
                    ? cachedSpoof(myPkg, PrivacyKitKeys.KEY_APP_SET_ID, IDX_APP_SET_ID)
                    : null;
            if (adIdSpoof == null && appSetIdSpoof == null) {
                return service; // no rule / bad value -> keep the real identity
            }

            final String desc = cachedDescriptor(service);
            if (desc == null) {
                return service;
            }

            final String spoof;
            final int txn;
            if (adIdSpoof != null && DESC_ADVERTISING_ID.equals(desc)) {
                spoof = adIdSpoof;
                txn = TXN_GET_ID;
            } else if (appSetIdSpoof != null && isAppSetDescriptor(desc)) {
                spoof = appSetIdSpoof;
                txn = TXN_ANY;
            } else {
                return service; // not a known identity interface
            }
            return new PrivacyKitGmsBinderFilter(service, spoof, txn);
        } catch (Exception e) {
            return service;
        }
    }

    /**
     * The substitute for one key in this process, resolved at most once.
     *
     * <p>Caching per process is not only a way to avoid the binder call. A
     * process IS the scope RULE_PER_LAUNCH names, so a memo that lives exactly
     * as long as the process is also what keeps two reads inside one launch
     * agreeing - an app that saw two different advertising ids in one run would
     * have learned more than if nothing had been spoofed. The cost, stated
     * rather than hidden: a rule changed while the app is running takes effect
     * on its next launch, not immediately.
     *
     * <p>Both slots are memoised even when the answer is "no rule", because that
     * is the answer worth not asking twice.
     */
    private static String cachedSpoof(String pkg, String key, int idx) {
        synchronized (sSpoofCache) {
            if (!pkg.equals(sSpoofPkg[idx])) {
                sSpoofCache[idx] = resolve(pkg, key);
                sSpoofPkg[idx] = pkg;
            }
            return sSpoofCache[idx];
        }
    }

    /**
     * {@link IBinder#getInterfaceDescriptor()}, asked once per binder.
     *
     * <p>A descriptor never changes for the lifetime of the binder it belongs
     * to, and the process-wide proxy table hands back the same
     * {@code BinderProxy} object for the same remote binder, so a rebind to a
     * service already seen is answered locally. Weak keys, and the values are
     * plain Strings that do not reference their key, so an entry disappears with
     * the binder it describes.
     *
     * <p>{@link #DESC_UNKNOWN} records "asked, and it would not say", so a
     * binder that fails the call is not asked again either.
     */
    private static String cachedDescriptor(IBinder service) {
        synchronized (sDescriptorCache) {
            final String cached = sDescriptorCache.get(service);
            if (cached != null) {
                return cached.isEmpty() ? null : cached;
            }
        }
        String desc;
        try {
            desc = service.getInterfaceDescriptor();
        } catch (RemoteException | RuntimeException e) {
            desc = null;
        }
        synchronized (sDescriptorCache) {
            sDescriptorCache.put(service, desc == null ? DESC_UNKNOWN : desc);
        }
        return desc;
    }

    /**
     * Matches the App Set ID service by descriptor substring rather than by an
     * exact string, because the leaf interface has been renamed across GMS
     * releases ({@code com.google.android.gms.appset.internal.IAppSetIdService}
     * being the current form) while the {@code appset} token has not moved.
     *
     * <p>The second half of the test is not decoration: {@code "appset"} on its
     * own is also a substring of unrelated {@code IAppSettings}-style
     * descriptors, and wrapping one of those would be a pointless risk.
     */
    static boolean isAppSetDescriptor(String desc) {
        final String lower = desc.toLowerCase(Locale.ROOT);
        if (!lower.contains("appset")) {
            return false;
        }
        // "appsetid" is specific enough to stand on its own, so a renamed or
        // rehosted App Set ID interface still matches. Bare "appset" is not -
        // it is equally a substring of "IAppSettings" - so that looser form is
        // honoured only inside the Google namespace.
        return lower.contains("appsetid") || lower.startsWith("com.google.android.");
    }

    private static String resolve(String pkg, String key) {
        try {
            final IBinder b = ServiceManager.getService("privacykit");
            if (b == null) {
                return null;
            }
            // null realValue is the sentinel: resolveIdentifier echoes null back
            // unless a spoof rule is stored for this package.
            return sanitizeUuid(IPrivacyKitManager.Stub.asInterface(b)
                    .resolveIdentifier(pkg, key, null));
        } catch (Exception e) {
            return null;
        }
    }

    /** Accept only a canonical lower-case 8-4-4-4-12 UUID; else null. */
    static String sanitizeUuid(String v) {
        return isCanonicalUuid(v, false) ? v : null;
    }

    /**
     * Same shape test, but tolerant of upper-case hex, used only to recognise
     * the real value already sitting in the reply. Recognising it in either
     * case cannot corrupt anything: the substitute written over it is always a
     * canonical 36-character UUID, so the byte length is identical either way.
     */
    static boolean isUuidShaped(String v) {
        return isCanonicalUuid(v, true);
    }

    private static boolean isCanonicalUuid(String v, boolean allowUpper) {
        if (v == null || v.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            final char c = v.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
                continue;
            }
            final boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (allowUpper && c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    @Override
    public IInterface queryLocalInterface(@NonNull String descriptor) {
        // Return null so the app Stub.asInterface builds a Proxy that routes
        // through transact(), where the reply rewrite lives.
        return null;
    }

    @Override
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
            throws RemoteException {
        final boolean ok = base.transact(code, data, reply, flags);
        if (!ok || reply == null || mSpoof == null) {
            return ok; // one-way call, failed call, or nothing to substitute
        }
        if (mTxn != TXN_ANY && code != mTxn) {
            return ok;
        }
        final int pos = reply.dataPosition();
        try {
            rewriteUuidInPlace(reply);
        } catch (Exception e) {
            // Fail open. This also covers the reply carrying a real remote
            // exception: we swallow it here and rewind, so the app still reads
            // and throws its own exception exactly as GmsCore sent it.
        } finally {
            try {
                reply.setDataPosition(pos);
            } catch (RuntimeException ignored) {
                // Nothing sane left to do; leave the parcel as it is.
            }
        }
        return ok;
    }

    /**
     * Overwrites the first canonical-UUID string in {@code reply} with the
     * substitute, leaving every other byte alone. Callers restore
     * {@code dataPosition}; this method deliberately does not, so a partial
     * scan is indistinguishable from a no-op.
     */
    private void rewriteUuidInPlace(Parcel reply) {
        final int size = reply.dataSize();
        if (size <= 0 || size > MAX_SCAN_BYTES) {
            return; // empty, or far too big to be an identity reply
        }
        reply.setDataPosition(0);
        // Consume the header exactly as the app is about to. Throws if the call
        // failed remotely, which the caller turns into "leave it untouched".
        reply.readException();

        for (int p = reply.dataPosition(); p + UUID_PARCEL_BYTES <= size; p += 4) {
            final String candidate;
            try {
                reply.setDataPosition(p);
                candidate = reply.readStringNoHelper();
            } catch (Exception e) {
                continue; // not a string at this offset; keep looking
            }
            if (!isUuidShaped(candidate)) {
                continue;
            }
            // Both values are canonical 36-character UUIDs, so the write lays
            // down the identical byte count over the identical range: the
            // App Set ID scope int, any SafeParcel field headers and any
            // trailing fields are left exactly as GmsCore wrote them.
            reply.setDataPosition(p);
            reply.writeStringNoHelper(mSpoof);
            return;
        }
    }
}
