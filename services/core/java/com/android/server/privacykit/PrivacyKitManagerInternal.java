/*
 * Copyright (C) 2026 BestROM
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * RECOVERED 2026-08-19 after the build box was purged. R8 erased this type
 * entirely from the shipped r49 services.jar (it has exactly one implementation
 * - the anonymous class published from PrivacyKitService.onStart() - so R8
 * merged the two and rewrote every LocalServices key to the concrete
 * PrivacyKitService$1), which means there is NO dex for it to decompile.
 *
 * The body below is the verbatim original file, recovered from a transcript
 * fragment: a `cat frameworks/base/services/core/java/com/android/server/
 * privacykit/PrivacyKitManagerInternal.java` run on 2026-08-15 whose output is
 * preserved in pkrecover/fragments/IPrivacyKitManager.aidl.txt (marker
 * "=====MGRINTERNAL").
 *
 * Independently cross-checked against the shipped ROM: all four r49 call sites
 * (TelephonyRegistry.maybePrivacyKitCellInfo, AccountManagerService.
 * maybePrivacyKitEmptyAccounts, PrivacyKitLocationOverride.resolvePin,
 * ComputerEngine.pkResolveInstallTime) inline exactly one virtual call -
 * resolveIdentifier(String, String, String) returning String - so the one
 * abstract method below is the complete interface, not a subset.
 */
package com.android.server.privacykit;

/**
 * In-process (LocalServices) interface for framework code that already runs
 * inside system_server's own process (SettingsProvider - android:process="system",
 * DeviceIdentifiersPolicyService, PhoneSubInfoController) to resolve PrivacyKit
 * identity rules without a binder round trip.
 *
 * Cross-process callers (the PrivacyKit Settings UI, and ActivityThread running
 * in each app's own process) go through the published IPrivacyKitManager AIDL
 * service instead - see PrivacyKitService.
 */
public abstract class PrivacyKitManagerInternal {

    /**
     * Resolve the value to report for (packageName, key), given the real
     * underlying value. Returns realValue unchanged if no rule is configured.
     * Never throws; any internal failure falls back to realValue.
     */
    public abstract String resolveIdentifier(String packageName, String key, String realValue);
}
