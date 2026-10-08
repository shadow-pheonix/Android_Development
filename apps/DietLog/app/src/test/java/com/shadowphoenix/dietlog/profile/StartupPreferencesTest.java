package com.shadowphoenix.dietlog.profile;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class StartupPreferencesTest {
    @Test public void freshConnectedInstallationMustCheckTheBackup() {
        assertTrue(StartupPreferences.needsDriveRestore(true, false, false, false));
        assertFalse(StartupPreferences.needsDriveRestore(false, false, false, false));
    }

    @Test public void completedAndGuestProfilesKeepTheirNormalStartup() {
        assertFalse(StartupPreferences.needsDriveRestore(true, true, true, false));
        assertFalse(StartupPreferences.needsDriveRestore(true, true, false, false));
        assertFalse(StartupPreferences.needsDriveRestore(false, true, true, false));
        assertFalse(StartupPreferences.needsDriveRestore(true, false, true, false));
    }

    @Test public void unfinishedRecoveryIsResumedEvenIfProfileWasPartiallyMerged() {
        assertTrue(StartupPreferences.needsDriveRestore(true, true, false, true));
        assertTrue(StartupPreferences.needsDriveRestore(true, false, true, true));
        assertFalse(StartupPreferences.needsDriveRestore(false, true, true, true));
    }

    @Test public void pendingRecoverySurvivesNewControllerAndClearsOnlyOnChoiceCompletion() {
        MemoryContext context = new MemoryContext();
        StartupPreferences first = new StartupPreferences(context);
        first.beginDriveRestore();
        StartupPreferences recreated = new StartupPreferences(context);
        assertTrue(recreated.hasPendingDriveRestore());
        assertFalse(recreated.hasChosen());
        assertTrue(recreated.needsDriveRestore(true, true));
        recreated.markChosen();
        StartupPreferences completed = new StartupPreferences(context);
        assertFalse(completed.hasPendingDriveRestore());
        assertTrue(completed.hasChosen());
        assertFalse(completed.needsDriveRestore(true, false));
    }

    private static final class MemoryContext extends ContextWrapper {
        private final Map<String, Boolean> stored = new HashMap<>();
        private final Map<String, Boolean> edited = new HashMap<>();
        private final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putBoolean")) {
                        edited.put((String) args[0], (Boolean) args[1]);
                        return proxy;
                    }
                    if (method.getName().equals("commit")) {
                        stored.putAll(edited);
                        edited.clear();
                        return true;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        private final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getBoolean")) return stored.getOrDefault((String) args[0], (Boolean) args[1]);
                    if (method.getName().equals("edit")) return editor;
                    throw new UnsupportedOperationException(method.getName());
                });
        MemoryContext() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
    }
}
