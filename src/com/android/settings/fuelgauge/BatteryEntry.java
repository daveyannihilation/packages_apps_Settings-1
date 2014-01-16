/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.fuelgauge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.BatteryStats;
import android.os.Handler;
import android.os.UserManager;
import com.android.internal.os.BatterySipper;
import com.android.settings.R;
import com.android.settings.users.UserUtils;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Wraps the power usage data of a BatterySipper with information about package name
 * and icon image.
 */
public class BatteryEntry {
    public static final int MSG_UPDATE_NAME_ICON = 1;
    public static final int MSG_REPORT_FULLY_DRAWN = 2;

    static final HashMap<String,UidToDetail> sUidCache = new HashMap<String,UidToDetail>();

    static final ArrayList<BatteryEntry> mRequestQueue = new ArrayList<BatteryEntry>();
    static Handler mHandler;

    static private class NameAndIconLoader extends Thread {
        private boolean mAbort = false;

        public NameAndIconLoader() {
            super("BatteryUsage Icon Loader");
        }

        public void abort() {
            mAbort = true;
        }

        @Override
        public void run() {
            while (true) {
                BatteryEntry be;
                synchronized (mRequestQueue) {
                    if (mRequestQueue.isEmpty() || mAbort) {
                        mHandler.sendEmptyMessage(MSG_REPORT_FULLY_DRAWN);
                        mRequestQueue.clear();
                        return;
                    }
                    be = mRequestQueue.remove(0);
                }
                be.loadNameAndIcon();
            }
        }
    }

    private static NameAndIconLoader mRequestThread;

    public static void startRequestQueue() {
        if (mHandler != null) {
            synchronized (mRequestQueue) {
                if (!mRequestQueue.isEmpty()) {
                    if (mRequestThread != null) {
                        mRequestThread.abort();
                    }
                    mRequestThread = new NameAndIconLoader();
                    mRequestThread.setPriority(Thread.MIN_PRIORITY);
                    mRequestThread.start();
                    mRequestQueue.notify();
                }
            }
        }
    }

    public static void stopRequestQueue() {
        synchronized (mRequestQueue) {
            if (mRequestThread != null) {
                mRequestThread.abort();
                mRequestThread = null;
                mHandler = null;
            }
        }
    }

    public static void clearUidCache() {
        sUidCache.clear();
    }

    public final Context context;
    public final BatterySipper sipper;

    public String name;
    public Drawable icon;
    public int iconId; // For passing to the detail screen.
    public String defaultPackageName;

    static class UidToDetail {
        String name;
        String packageName;
        Drawable icon;
    }

    public BatteryEntry(Context context, Handler handler, UserManager um, BatterySipper sipper) {
        mHandler = handler;
        this.context = context;
        this.sipper = sipper;
        switch (sipper.drainType) {
            case IDLE:
                name = context.getResources().getString(R.string.power_idle);
                iconId = R.drawable.ic_settings_phone_idle;
                break;
            case CELL:
                name = context.getResources().getString(R.string.power_cell);
                iconId = R.drawable.ic_settings_cell_standby;
                break;
            case PHONE:
                name = context.getResources().getString(R.string.power_phone);
                iconId = R.drawable.ic_settings_voice_calls;
                break;
            case WIFI:
                name = context.getResources().getString(R.string.power_wifi);
                iconId = R.drawable.ic_settings_wifi;
                break;
            case BLUETOOTH:
                name = context.getResources().getString(R.string.power_bluetooth);
                iconId = R.drawable.ic_settings_bluetooth;
                break;
            case SCREEN:
                name = context.getResources().getString(R.string.power_screen);
                iconId = R.drawable.ic_settings_display;
                break;
            case APP:
                name = sipper.packageWithHighestDrain;
                break;
            case USER: {
                UserInfo info = um.getUserInfo(sipper.userId);
                if (info != null) {
                    icon = UserUtils.getUserIcon(context, um, info, context.getResources());
                    name = info != null ? info.name : null;
                    if (name == null) {
                        name = Integer.toString(info.id);
                    }
                    name = context.getResources().getString(
                            R.string.running_process_item_user_label, name);
                } else {
                    icon = null;
                    name = context.getResources().getString(
                            R.string.running_process_item_removed_user_label);
                }
            } break;
            case UNACCOUNTED:
                name = context.getResources().getString(R.string.power_unaccounted);
                iconId = R.drawable.ic_power_system;
                break;
            case OVERCOUNTED:
                name = context.getResources().getString(R.string.power_overcounted);
                iconId = R.drawable.ic_power_system;
                break;
        }
        if (iconId > 0) {
            icon = context.getResources().getDrawable(iconId);
        }
        if ((name == null || iconId == 0) && this.sipper.uidObj != null) {
            getQuickNameIconForUid(this.sipper.uidObj);
        }
    }

    public Drawable getIcon() {
        return icon;
    }

    /**
     * Gets the application name
     */
    public String getLabel() {
        return name;
    }

    void getQuickNameIconForUid(BatteryStats.Uid uidObj) {
        final int uid = uidObj.getUid();
        final String uidString = Integer.toString(uid);
        if (sUidCache.containsKey(uidString)) {
            UidToDetail utd = sUidCache.get(uidString);
            defaultPackageName = utd.packageName;
            name = utd.name;
            icon = utd.icon;
            return;
        }
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        icon = pm.getDefaultActivityIcon();
        if (packages == null) {
            //name = Integer.toString(uid);
            if (uid == 0) {
                name = context.getResources().getString(R.string.process_kernel_label);
            } else if ("mediaserver".equals(name)) {
                name = context.getResources().getString(R.string.process_mediaserver_label);
            }
            iconId = R.drawable.ic_power_system;
            icon = context.getResources().getDrawable(iconId);
            return;
        } else {
            //name = packages[0];
        }
        if (mHandler != null) {
            synchronized (mRequestQueue) {
                mRequestQueue.add(this);
            }
        }
    }

    /**
     * Loads the app label and icon image and stores into the cache.
     */
    public void loadNameAndIcon() {
        // Bail out if the current sipper is not an App sipper.
        if (sipper.uidObj == null) {
            return;
        }
        PackageManager pm = context.getPackageManager();
        final int uid = sipper.uidObj.getUid();
        final Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
        sipper.mPackages = pm.getPackagesForUid(uid);
        if (sipper.mPackages == null) {
            name = Integer.toString(uid);
            return;
        }

        String[] packageLabels = new String[sipper.mPackages.length];
        System.arraycopy(sipper.mPackages, 0, packageLabels, 0, sipper.mPackages.length);

        int preferredIndex = -1;
        // Convert package names to user-facing labels where possible
        for (int i = 0; i < packageLabels.length; i++) {
            // Check if package matches preferred package
            if (packageLabels[i].equals(name)) preferredIndex = i;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(packageLabels[i], 0);
                CharSequence label = ai.loadLabel(pm);
                if (label != null) {
                    packageLabels[i] = label.toString();
                }
                if (ai.icon != 0) {
                    defaultPackageName = sipper.mPackages[i];
                    icon = ai.loadIcon(pm);
                    break;
                }
            } catch (PackageManager.NameNotFoundException e) {
            }
        }
        if (icon == null) icon = defaultActivityIcon;

        if (packageLabels.length == 1) {
            name = packageLabels[0];
        } else {
            // Look for an official name for this UID.
            for (String pkgName : sipper.mPackages) {
                try {
                    final PackageInfo pi = pm.getPackageInfo(pkgName, 0);
                    if (pi.sharedUserLabel != 0) {
                        final CharSequence nm = pm.getText(pkgName,
                                pi.sharedUserLabel, pi.applicationInfo);
                        if (nm != null) {
                            name = nm.toString();
                            if (pi.applicationInfo.icon != 0) {
                                defaultPackageName = pkgName;
                                icon = pi.applicationInfo.loadIcon(pm);
                            }
                            break;
                        }
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
        }
        final String uidString = Integer.toString(sipper.uidObj.getUid());
        UidToDetail utd = new UidToDetail();
        utd.name = name;
        utd.icon = icon;
        utd.packageName = defaultPackageName;
        sUidCache.put(uidString, utd);
        if (mHandler != null) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_NAME_ICON, this));
        }
    }
}
