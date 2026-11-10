package com.trydent.motocloneallowlist;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Widens Motorola's built-in app cloner allowlist (libxposed API 101+).
 *
 * Moto ships a static overlay that sets android:array/cloneable_apps to 8 messengers.
 * Settings > Cloned apps uses that array to decide what can be cloned, and the Moto
 * launcher uses it to decide which clone-profile apps get an icon. This hook appends every
 * user-installed launchable app to that array, in only those two processes (system_server is
 * never touched).
 */
public class MainHook extends XposedModule {
    private static final String TAG = "CloneAllowlist";
    private static final String HOOK_ID = "cloneable_apps";
    private static final long CACHE_MS = 15_000;

    private static int sCloneableId = 0;
    private static String[] sCache;
    private static long sCacheTime;
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<>();

    private static boolean isTargetProcess(String process) {
        return "com.android.settings".equals(process) || "com.motorola.launcher3".equals(process);
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (!isTargetProcess(param.getProcessName())) {
            detach();
            return;
        }
        Method target = resolveTarget(param.getProcessName());
        if (target == null) return;
        hook(target).setId(HOOK_ID).intercept(this::intercept);
        log(Log.INFO, TAG, "hooked " + param.getProcessName() + " (id 0x" + Integer.toHexString(sCloneableId) + ")");
    }

    // A new APK generation can take over without restarting Settings or the launcher.
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        return true; // no threads, receivers or native state to tear down
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        if (!isTargetProcess(param.getProcessName())) return;
        boolean replaced = false;
        for (HookHandle old : param.getOldHookHandles()) {
            if (!replaced && HOOK_ID.equals(old.getId())) {
                resolveTarget(param.getProcessName()); // refresh sCloneableId in this generation
                old.replaceHook(this::intercept);
                replaced = true;
            } else {
                old.unhook();
            }
        }
        if (!replaced) {
            Method target = resolveTarget(param.getProcessName());
            if (target != null) hook(target).setId(HOOK_ID).intercept(this::intercept);
        }
        log(Log.INFO, TAG, "hot reloaded in " + param.getProcessName());
    }

    private Method resolveTarget(String process) {
        sCloneableId = Resources.getSystem().getIdentifier("cloneable_apps", "array", "android");
        if (sCloneableId == 0) {
            log(Log.WARN, TAG, "android:array/cloneable_apps not found in " + process + ", not hooking");
            return null;
        }
        try {
            return Resources.class.getDeclaredMethod("getStringArray", int.class);
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "Resources.getStringArray(int) not found", e);
            return null;
        }
    }

    private Object intercept(Chain chain) throws Throwable {
        Object result = chain.proceed();
        if ((int) chain.getArg(0) != sCloneableId || Boolean.TRUE.equals(sBusy.get())) return result;
        sBusy.set(true);
        try {
            String[] merged = widen((String[]) result);
            return merged != null ? merged : result;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "widen failed", t);
            return result;
        } finally {
            sBusy.set(false);
        }
    }

    private static String[] widen(String[] original) throws ReflectiveOperationException {
        long now = SystemClock.elapsedRealtime();
        if (sCache != null && now - sCacheTime < CACHE_MS) return sCache;

        Context ctx = (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
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
