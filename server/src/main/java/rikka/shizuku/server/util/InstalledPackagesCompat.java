package rikka.shizuku.server.util;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ServiceManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Android 17 (API 37) changed the return type of IPackageManager#getInstalledPackages
 * from ParceledListSlice to PackageInfoList. Calls compiled against the stubs from
 * dev.rikka.hidden:stub fail with NoSuchMethodError (method resolution includes the
 * return type), so rikka.hidden.compat.PackageManagerApis#getInstalledPackagesNoThrow
 * silently returns an empty list.
 *
 * Resolve the method on the runtime framework class instead, so that the call always
 * matches the platform.
 */
public class InstalledPackagesCompat {

    private static final Logger LOGGER = new Logger("InstalledPackagesCompat");

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) throws Throwable {
        IBinder binder = ServiceManager.getService("package");
        Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
        Object pm = stubClass.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> pmClass = Class.forName("android.content.pm.IPackageManager");

        Method method;
        Object result;
        try {
            // Android 13+
            method = pmClass.getMethod("getInstalledPackages", long.class, int.class);
            result = method.invoke(pm, flags, userId);
        } catch (NoSuchMethodException e) {
            method = pmClass.getMethod("getInstalledPackages", int.class, int.class);
            result = method.invoke(pm, (int) flags, userId);
        }

        if (result == null) {
            return Collections.emptyList();
        }
        if (result instanceof List) {
            return (List<PackageInfo>) result;
        }
        // ParceledListSlice on Android 16-, PackageInfoList on Android 17+
        Object list = result.getClass().getMethod("getList").invoke(result);
        return list != null ? (List<PackageInfo>) list : Collections.emptyList();
    }

    public static List<PackageInfo> getInstalledPackagesNoThrow(long flags, int userId) {
        try {
            return getInstalledPackages(flags, userId);
        } catch (Throwable tr) {
            LOGGER.w(tr, "getInstalledPackages");
            return Collections.emptyList();
        }
    }
}
