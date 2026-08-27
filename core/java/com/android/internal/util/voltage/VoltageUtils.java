/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * BestROM A17: slim VoltageUtils. The A16 class is a 1146-line ROM grab-bag
 * (95 imports, com.android.internal.R, IStatusBarService, ...) that does not
 * compile cleanly on the LineageOS-based A17 framework. The PrivacyKit Settings
 * UI uses ONLY launchablePackages(), which is fully self-contained
 * (Intent + PackageManager + ResolveInfo), so this trimmed version keeps the
 * exact package/name/signature the UI imports while dropping the rest.
 */
package com.android.internal.util.voltage;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

public final class VoltageUtils {

    private VoltageUtils() {
    }

    /** Package names of every app that exposes a LAUNCHER activity. */
    public static List<String> launchablePackages(Context context) {
        List<String> list = new ArrayList<>();
        Intent filter = new Intent(Intent.ACTION_MAIN, null);
        filter.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(filter,
                PackageManager.GET_META_DATA);
        int numPackages = apps.size();
        for (int i = 0; i < numPackages; i++) {
            ResolveInfo app = apps.get(i);
            list.add(app.activityInfo.packageName);
        }
        return list;
    }
}
