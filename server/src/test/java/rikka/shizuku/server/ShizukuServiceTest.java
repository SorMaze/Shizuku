package rikka.shizuku.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;
import android.system.Os;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import rikka.hidden.compat.ActivityManagerApis;
import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.server.util.InstalledPackagesCompat;

public class ShizukuServiceTest {

    private static final int MANAGER_UID = 10000;
    private static final int CLIENT_UID = 1010123;
    private static final int CLIENT_USER = 10;
    private static final String CLIENT_PACKAGE = "test.client";

    private MockedStatic<Binder> binder;
    private MockedStatic<Os> os;
    private MockedStatic<PackageManagerApis> packages;
    private MockedStatic<PermissionManagerApis> permissions;
    private MockedStatic<ActivityManagerApis> activities;
    private MockedStatic<InstalledPackagesCompat> installedPackages;
    private ShizukuService service;
    private ShizukuClientManager clients;
    private ShizukuConfigManager config;
    private ShizukuUserServiceManager userServices;

    @Before
    public void setUp() throws Exception {
        binder = mockStatic(Binder.class);
        os = mockStatic(Os.class);
        packages = mockStatic(PackageManagerApis.class);
        permissions = mockStatic(PermissionManagerApis.class);
        activities = mockStatic(ActivityManagerApis.class);
        installedPackages = mockStatic(InstalledPackagesCompat.class);

        binder.when(Binder::getCallingUid).thenReturn(MANAGER_UID);
        binder.when(Binder::getCallingPid).thenReturn(123);
        os.when(Os::getpid).thenReturn(456);
        packages.when(() -> PackageManagerApis.getPackagesForUidNoThrow(CLIENT_UID))
                .thenReturn(Collections.singletonList(CLIENT_PACKAGE));
        installedPackages.when(() -> InstalledPackagesCompat.getInstalledPackagesNoThrow(anyLong(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Run real entry points without starting the Android server or native rish runtime.
        service = mock(ShizukuService.class, CALLS_REAL_METHODS);
        clients = mock(ShizukuClientManager.class);
        config = mock(ShizukuConfigManager.class);
        userServices = mock(ShizukuUserServiceManager.class);
        setField(ShizukuService.class, "managerAppId", MANAGER_UID);
        setField(ShizukuService.class, "clientManager", clients);
        setField(ShizukuService.class, "configManager", config);
        setField(Service.class, "userServiceManager", userServices);
        when(clients.findClients(CLIENT_UID)).thenReturn(Collections.emptyList());
    }

    @After
    public void tearDown() {
        installedPackages.close();
        activities.close();
        permissions.close();
        packages.close();
        os.close();
        binder.close();
    }

    @Test
    public void revokeCleansDaemonServicesAfterAllClientsHaveExited() throws Exception {
        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);

        verify(userServices).removeUserServicesForPackage(CLIENT_PACKAGE);
        activities.verify(() -> ActivityManagerApis.forceStopPackageNoThrow(CLIENT_PACKAGE, CLIENT_USER));
        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION, 0);
    }

    @Test
    public void revokeAlsoCleansServicesWithoutRuntimePermissionDeclaration() throws Exception {
        PackageInfo info = new PackageInfo();
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(
                CLIENT_PACKAGE, PackageManager.GET_PERMISSIONS, CLIENT_USER)).thenReturn(info);

        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);

        verify(userServices).removeUserServicesForPackage(CLIENT_PACKAGE);
        permissions.verifyNoInteractions();
    }

    @Test
    public void revokeClearsAllLiveClientsAndCleansEachPackageOnce() throws Exception {
        ClientRecord first = new ClientRecord(CLIENT_UID, 1, null, CLIENT_PACKAGE, 13);
        ClientRecord second = new ClientRecord(CLIENT_UID, 2, null, CLIENT_PACKAGE, 13);
        first.allowed = true;
        second.allowed = true;
        when(clients.findClients(CLIENT_UID)).thenReturn(Arrays.asList(first, second));

        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, 0);

        assertFalse(first.allowed);
        assertFalse(second.allowed);
        verify(userServices).removeUserServicesForPackage(CLIENT_PACKAGE);
        activities.verify(() -> ActivityManagerApis.forceStopPackageNoThrow(CLIENT_PACKAGE, CLIENT_USER));
    }

    @Test
    public void grantPreservesServicesAndGrantsRuntimePermission() throws Exception {
        ClientRecord client = new ClientRecord(CLIENT_UID, 1, null, CLIENT_PACKAGE, 13);
        when(clients.findClients(CLIENT_UID)).thenReturn(Collections.singletonList(client));
        PackageInfo info = new PackageInfo();
        info.requestedPermissions = new String[]{ServerConstants.PERMISSION};
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(
                CLIENT_PACKAGE, PackageManager.GET_PERMISSIONS, CLIENT_USER)).thenReturn(info);

        service.updateFlagsForUid(CLIENT_UID, ConfigManager.MASK_PERMISSION, ConfigManager.FLAG_ALLOWED);

        assertTrue(client.allowed);
        verifyNoInteractions(userServices);
        activities.verifyNoInteractions();
        permissions.verify(() -> PermissionManagerApis.grantRuntimePermission(
                CLIENT_PACKAGE, ServerConstants.PERMISSION, CLIENT_USER));
    }

    @Test
    public void revokeKeepsDecisionWhenFrameworkPermissionRevocationFails() throws Exception {
        String sharedPackage = "test.shared.client";
        packages.when(() -> PackageManagerApis.getPackagesForUidNoThrow(CLIENT_UID))
                .thenReturn(Arrays.asList(CLIENT_PACKAGE, sharedPackage));
        PackageInfo info = new PackageInfo();
        info.requestedPermissions = new String[]{ServerConstants.PERMISSION};
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(
                CLIENT_PACKAGE, PackageManager.GET_PERMISSIONS, CLIENT_USER)).thenReturn(info);
        permissions.when(() -> PermissionManagerApis.revokeRuntimePermission(
                CLIENT_PACKAGE, ServerConstants.PERMISSION, CLIENT_USER)).thenThrow(new RemoteException());

        assertThrows(RemoteException.class, () -> service.updateFlagsForUid(
                CLIENT_UID, ConfigManager.MASK_PERMISSION, 0));

        verify(config).update(CLIENT_UID, null, ConfigManager.MASK_PERMISSION, 0);
        verify(userServices).removeUserServicesForPackage(CLIENT_PACKAGE);
        verify(userServices).removeUserServicesForPackage(sharedPackage);
    }

    @Test
    public void applicationListRejectsNonManagerBeforeReadingOrEnumerating() {
        binder.when(Binder::getCallingUid).thenReturn(CLIENT_UID);
        Parcel data = mock(Parcel.class);
        Parcel reply = mock(Parcel.class);

        assertThrows(SecurityException.class, () -> service.onTransact(
                ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0));

        verify(data, never()).readInt();
        installedPackages.verifyNoInteractions();
        verifyNoInteractions(reply);
    }

    @Test
    public void applicationListStillAllowsManagerFromAnotherAndroidUser() throws Exception {
        binder.when(Binder::getCallingUid).thenReturn(1000000 + MANAGER_UID);
        Parcel data = mock(Parcel.class);
        Parcel reply = mock(Parcel.class);
        when(data.readInt()).thenReturn(CLIENT_USER);

        assertTrue(service.onTransact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0));

        installedPackages.verify(() -> InstalledPackagesCompat.getInstalledPackagesNoThrow(
                PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, CLIENT_USER));
        verify(reply).writeNoException();
    }

    private void setField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
