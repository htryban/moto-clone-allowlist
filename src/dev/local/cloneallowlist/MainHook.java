package dev.local.cloneallowlist;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.SystemClock;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Widens Motorola's built-in app cloner allowlist.
 *
 * Moto ships a static overlay that sets android:array/cloneable_apps to 8 messengers.
 * Settings > Cloned apps uses that array to decide what can be cloned, and the Moto
 * launcher uses it to decide which clone-profile apps get an icon. This hook appends every
 * user-installed launchable app to that array, in only those two processes (system_server is
 * never touched).
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "CloneAllowlist: ";
    private static final long CACHE_MS = 15_000;

    private static int sCloneableId = 0;
    private static String[] sCache;
    private static long sCacheTime;
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.settings".equals(lpparam.packageName)
                && !"com.motorola.launcher3".equals(lpparam.packageName)) {
            return;
        }
        if (!lpparam.processName.equals(lpparam.packageName)) return;

        sCloneableId = Resources.getSystem().getIdentifier("cloneable_apps", "array", "android");
        if (sCloneableId == 0) {
            XposedBridge.log(TAG + "android:array/cloneable_apps not found, not hooking");
            return;
        }

        XposedHelpers.findAndHookMethod(Resources.class, "getStringArray", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if ((int) param.args[0] != sCloneableId || param.getThrowable() != null) return;
                if (Boolean.TRUE.equals(sBusy.get())) return;
                sBusy.set(true);
                try {
                    String[] merged = widen((String[]) param.getResult());
                    if (merged != null) param.setResult(merged);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "widen failed: " + t);
                } finally {
                    sBusy.set(false);
                }
            }
        });
        XposedBridge.log(TAG + "hooked " + lpparam.packageName + " (id 0x" + Integer.toHexString(sCloneableId) + ")");
    }

    private static String[] widen(String[] original) {
        long now = SystemClock.elapsedRealtime();
        if (sCache != null && now - sCacheTime < CACHE_MS) return sCache;

        Context ctx = (Context) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        if (ctx == null) return null;

        Set<String> pkgs = new LinkedHashSet<>();
        if (original != null) for (String p : original) pkgs.add(p);

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = ctx.getPackageManager().queryIntentActivities(
                launcher, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
        for (ResolveInfo ri : apps) {
            if (ri.activityInfo == null) continue;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            // Only user-installed apps. Preinstalled system apps (Camera, Play Store, ...) are copied into
            // every clone profile automatically; listing them would show them in the launcher as clones
            // that Settings cannot remove.
            if (ai == null || (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            pkgs.add(ri.activityInfo.packageName);
        }

        sCache = pkgs.toArray(new String[0]);
        sCacheTime = now;
        return sCache;
    }
}
