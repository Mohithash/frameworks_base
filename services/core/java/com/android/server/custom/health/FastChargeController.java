/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.custom.health;

import android.content.res.Resources;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
import com.android.internal.R;

import com.android.server.custom.health.LineageHealthFeature;

import vendor.lineage.health.FastChargeMode;
import vendor.lineage.health.IFastCharge;

import java.io.PrintWriter;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;

public class FastChargeController extends LineageHealthFeature {
    private final int[] mChargingSpeedValues;
    private final ContentResolver mContentResolver;
    private IFastCharge mFastCharge;

    // Settings uris
    private final Uri MODE_URI = Settings.System.getUriFor(
            Settings.System.FAST_CHARGE_MODE);

    public FastChargeController(Context context, Handler handler) {
        super(context, handler);

        mContentResolver = mContext.getContentResolver();
        // Do not waitForDeclaredService here: HealthInterfaceService.onStart runs
        // before vendor.lineage.health is guaranteed up, and a null HAL then
        // permanently hides Settings → Battery → Charging speed.
        mFastCharge = IFastCharge.Stub.asInterface(
                ServiceManager.getService(IFastCharge.DESCRIPTOR + "/default"));

        Resources res = mContext.getResources();
        mChargingSpeedValues = Stream.of(res.getStringArray(R.array.charging_speed_values))
                .mapToInt(Integer::parseInt)
                .toArray();

        if (mFastCharge == null) {
            Log.i(TAG, "Lineage Health HAL not ready yet");
        }
    }

    private IFastCharge hal() {
        if (mFastCharge == null) {
            final String name = IFastCharge.DESCRIPTOR + "/default";
            IBinder b = ServiceManager.getService(name);
            // After boot the HAL is declared; wait briefly if getService raced.
            if (b == null && ServiceManager.isDeclared(name)) {
                b = ServiceManager.waitForDeclaredService(name);
            }
            mFastCharge = IFastCharge.Stub.asInterface(b);
        }
        return mFastCharge;
    }

    @Override
    public boolean isSupported() {
        try {
            IFastCharge hal = hal();
            return hal != null && hal.getSupportedFastChargeModes() > 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    public int[] getSupportedFastChargeModes() {
        try {
            IFastCharge hal = hal();
            if (hal == null) return new int[0];
            long supportedFastChargeModes = hal.getSupportedFastChargeModes();

            return IntStream.of(mChargingSpeedValues)
                    .filter(mode -> (supportedFastChargeModes & mode) != 0)
                    .toArray();
        } catch (RemoteException e) {
            return new int[0];
        }
    }

    public int getFastChargeMode() {
        int[] supportedFastChargeModes = getSupportedFastChargeModes();
        if (supportedFastChargeModes.length == 0) {
            return FastChargeMode.NONE;
        }
        // Prefer Fast (smart charge, no sport) over Super. Super is available
        // in Settings but must be an explicit user choice — defaulting to the
        // last array entry used to pin SUPER_FAST and run hotter.
        int defaultMode = FastChargeMode.FAST_CHARGE;
        if (!ArrayUtils.contains(supportedFastChargeModes, defaultMode)) {
            defaultMode = supportedFastChargeModes[0];
        }

        int mode = Settings.System.getInt(mContentResolver,
                Settings.System.FAST_CHARGE_MODE,
                defaultMode);
        if (mode != defaultMode && !ArrayUtils.contains(supportedFastChargeModes, mode)) {
            return defaultMode;
        }

        return mode;
    }

    public boolean setFastChargeMode(int mode) {
        putInt(Settings.System.FAST_CHARGE_MODE, mode);
        return true;
    }

    @Override
    public void onStart() {
        if (hal() == null) {
            return;
        }

        // Register setting observer
        registerSettings(MODE_URI);

        handleSettingChange();
    }

    private void handleSettingChange() {
        try {
            IFastCharge hal = hal();
            if (hal != null) {
                hal.setFastChargeMode(getFastChargeMode());
            }
        } catch (Exception e) {
        }
    }

    @Override
    protected void onSettingsChanged(Uri uri) {
        handleSettingChange();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("FastChargeController Configuration:");
        pw.println("  Mode: " + getFastChargeMode());
        pw.println();
    }
}
