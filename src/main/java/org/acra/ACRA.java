/*
 *  Copyright 2010 Emmanuel Astier & Kevin Gaudin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.acra;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Build;
import org.acra.annotation.ReportsCrashes;
import org.acra.util.ApplicationStartupProcessor;
import org.acra.config.ACRAConfig;
import org.acra.config.ACRAConfiguration;
import org.acra.config.ACRAConfigurationException;
import org.acra.config.ACRAConfigurationFactory;
import org.acra.legacy.ReportMigrator;
import org.acra.log.ACRALog;
import org.acra.log.AndroidLogDelegate;
import org.acra.prefs.PrefUtils;
import org.acra.prefs.SharedPreferencesFactory;

/**
 * Use this class to initialize the crash reporting feature using
 * {@link #init(Application)} as soon as possible in your {@link Application}
 * subclass {@link Application#onCreate()} method. Configuration items must have
 * been set by using {@link ReportsCrashes} above the declaration of your
 * {@link Application} subclass.
 * 
 * @author Kevin Gaudin
 * 
 */
public class ACRA {

    public static boolean DEV_LOGGING = false; // Should be false for release.

    public static final String LOG_TAG = ACRA.class.getSimpleName();
    
    public static ACRALog log = new AndroidLogDelegate();

    private static final String ACRA_PRIVATE_PROCESS_NAME= ":acra";

    /**
     * The key of the application default SharedPreference where you can put a
     * 'true' Boolean value to disable ACRA.
     */
    public static final String PREF_DISABLE_ACRA = "acra.disable";

    /**
     * Alternatively, you can use this key if you prefer your users to have the
     * checkbox ticked to enable crash reports. If both acra.disable and
     * acra.enable are set, the value of acra.disable takes over the other.
     */
    public static final String PREF_ENABLE_ACRA = "acra.enable";

    /**
     * The key of the SharedPreference allowing the user to disable sending
     * content of logcat/dropbox. System logs collection is also dependent of
     * the READ_LOGS permission.
     */
    public static final String PREF_ENABLE_SYSTEM_LOGS = "acra.syslog.enable";

    /**
     * The key of the SharedPreference allowing the user to disable sending his
     * device id. Device ID collection is also dependent of the READ_PHONE_STATE
     * permission.
     */
    public static final String PREF_ENABLE_DEVICE_ID = "acra.deviceid.enable";

    /**
     * The key of the SharedPreference allowing the user to always include his
     * email address.
     */
    public static final String PREF_USER_EMAIL_ADDRESS = "acra.user.email";

    /**
     * The key of the SharedPreference allowing the user to automatically accept
     * sending reports.
     */
    public static final String PREF_ALWAYS_ACCEPT = "acra.alwaysaccept";

    /**
     * The version number of the application the last time ACRA was started.
     * This is used to determine whether unsent reports should be discarded
     * because they are old and out of date.
     */
    public static final String PREF_LAST_VERSION_NR = "acra.lastVersionNr";

    private static final String PREF__LEGACY_ALREADY_CONVERTED_TO_4_8_0 = "acra.legacyAlreadyConvertedTo4.8.0";

    private static Application mApplication;
    private static ACRAConfiguration configProxy;

    // Accessible via ACRA#getErrorReporter().
    private static ErrorReporter errorReporterSingleton;

    // NB don't convert to a local field because then it could be garbage
    // collected and then we would have no PreferenceListener.
    private static OnSharedPreferenceChangeListener mPrefListener; // TODO consider moving to ErrorReport so it doesn't need to be a static field.

    /**
     * <p>
     * Initialize ACRA for a given Application.
     *
     * The call to this method should be placed as soon as possible in the {@link Application#onCreate()} method.
     *
     * Uses the configuration as configured with the @ReportCrashes annotation.
     * Sends any unsent reports.
     * </p>
     * 
     * @param app   Your Application class.
     * @throws IllegalStateException if it is called more than once.
     */
    public static void init(Application app) {
        final ReportsCrashes reportsCrashes = app.getClass().getAnnotation(ReportsCrashes.class);
        if (reportsCrashes == null) {
            log.e(LOG_TAG, "ACRA#init called but no ReportsCrashes annotation on Application " + app.getPackageName());
            return;
        }
        init(app, new ACRAConfiguration(reportsCrashes));
    }

    /**
     * <p>
     * Initialize ACRA for a given Application.
     *
     * The call to this method should be placed as soon as possible in the {@link Application#onCreate()} method.
     *
     * Sends any unsent reports.
     * </p>
     *
     * @param app       Your Application class.
     * @param config    ACRAConfiguration to manually set up ACRA configuration.
     * @throws IllegalStateException if it is called more than once.
     */
    public static void init(Application app, ACRAConfiguration config) {
        init(app, config, true);
    }

    /**
     * <p>
     * Initialize ACRA for a given Application. The call to this method should
     * be placed as soon as possible in the {@link Application#onCreate()}
     * method.
     * </p>
     *
     * @param app       Your Application class.
     * @param config    ACRAConfiguration to manually set up ACRA configuration.
     * @param checkReportsOnApplicationStart    Whether to invoke ErrorReporter.checkReportsOnApplicationStart().
     * @throws IllegalStateException if it is called more than once.
     */
    public static void init(Application app, ACRAConfiguration config, boolean checkReportsOnApplicationStart){

        final boolean senderServiceProcess = isACRASenderServiceProcess(app);
        if (senderServiceProcess) {
            if (ACRA.DEV_LOGGING) log.d(LOG_TAG, "Not initialising ACRA to listen for uncaught Exceptions as this is the SendWorker process and we only send reports, we don't capture them to avoid infinite loops");
        }

        boolean supportedAndroidVersion = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO);
        if (!supportedAndroidVersion){
            log.w(LOG_TAG, "ACRA 4.7.0+ requires Froyo or greater. ACRA is disabled and will NOT catch crashes or send messages.");
        }

        if (mApplication != null) {
            log.w(LOG_TAG, "ACRA#init called more than once. Won't do anything more.");
            return;
        }
        mApplication = app;
        
        if (config == null) {
            log.e(LOG_TAG, "ACRA#init called but no ACRAConfiguration provided");
            return;
        }
        configProxy = config;

        final SharedPreferences prefs = new SharedPreferencesFactory(mApplication, configProxy).create();

        try {
            config.checkCrashResources();

            // Check prefs to see if we have converted from legacy (pre 4.8.0) ACRA
            if (!prefs.getBoolean(PREF__LEGACY_ALREADY_CONVERTED_TO_4_8_0, false)) {
                // If not then move reports to approved/unapproved folders and mark as converted.
                new ReportMigrator(app).migrate();

                // Mark as converted.
                final SharedPreferences.Editor editor = prefs.edit().putBoolean(PREF__LEGACY_ALREADY_CONVERTED_TO_4_8_0, true);
                PrefUtils.save(editor);
            }

            // Initialize ErrorReporter with all required data
            final boolean enableAcra = supportedAndroidVersion && !shouldDisableACRA(prefs);
            if (ACRA.DEV_LOGGING) log.d(LOG_TAG, "ACRA is " + (enableAcra ? "enabled" : "disabled") + " for " + mApplication.getPackageName() + ", initializing...");
            errorReporterSingleton = new ErrorReporter(mApplication, configProxy, prefs, enableAcra, supportedAndroidVersion, !senderServiceProcess);

            // Check for approved reports and send them (if enabled).
            // NB don't check if senderServiceProcess as it will gather these reports itself.
            if (checkReportsOnApplicationStart && !senderServiceProcess) {
                final ApplicationStartupProcessor startupProcessor = new ApplicationStartupProcessor(mApplication,  config);
                if (config.deleteOldUnsentReportsOnApplicationStart()) {
                    startupProcessor.deleteUnsentReportsFromOldAppVersion();
                }
                if (config.deleteUnapprovedReportsOnApplicationStart()) {
                    startupProcessor.deleteAllUnapprovedReportsBarOne();
                }
                if (enableAcra) {
                    startupProcessor.sendApprovedReports();
                }
            }

        } catch (ACRAConfigurationException e) {
            log.w(LOG_TAG, "Error : ", e);
        }

        // We HAVE to keep a reference otherwise the listener could be garbage
        // collected:
        // http://stackoverflow.com/questions/2542938/sharedpreferences-onsharedpreferencechangelistener-not-being-called-consistently/3104265#3104265
        mPrefListener = new OnSharedPreferenceChangeListener() {

            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                if (PREF_DISABLE_ACRA.equals(key) || PREF_ENABLE_ACRA.equals(key)) {
                    final boolean enableAcra = !shouldDisableACRA(sharedPreferences);
                    getErrorReporter().setEnabled(enableAcra);
                }
            }
        };

        // This listener has to be set after initAcra is called to avoid a
        // NPE in ErrorReporter.disable() because
        // the context could be null at this moment.
        prefs.registerOnSharedPreferenceChangeListener(mPrefListener);
    }

    /**
     * @return true is ACRA has been initialised.
     */
    @SuppressWarnings("unused")
    public static boolean isInitialised() {
        return (configProxy != null);
    }

    /**
     * @return true if the current process is the process running the SenderService.
     */
    private static boolean isACRASenderServiceProcess(Application app) {
        final String processName = getCurrentProcessName(app);
        if (ACRA.DEV_LOGGING) log.d(LOG_TAG, "ACRA processName='" + processName + "'");
        return (processName != null) && processName.endsWith(ACRA_PRIVATE_PROCESS_NAME);
    }

    private static String getCurrentProcessName(Application app) {
        final int processId = android.os.Process.myPid();
        final ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        for (final ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()){
            if(processInfo.pid == processId){
                return processInfo.processName;
            }
        }
        return null;
    }

    /**
     * @return the current instance of ErrorReporter.
     * @throws IllegalStateException if {@link ACRA#init(android.app.Application)} has not yet been called.
     */
    public static ErrorReporter getErrorReporter() {
        if (errorReporterSingleton == null) {
            throw new IllegalStateException("Cannot access ErrorReporter before ACRA#init");
        }
        return errorReporterSingleton;
    }

    

    /**
     * Check if the application default shared preferences contains true for the
     * key "acra.disable", do not activate ACRA. Also checks the alternative
     * opposite setting "acra.enable" if "acra.disable" is not found.
     * 
     * @param prefs
     *            SharedPreferences to check to see whether ACRA should be
     *            disabled.
     * @return true if prefs indicate that ACRA should be disabled.
     */
    private static boolean shouldDisableACRA(SharedPreferences prefs) {
        boolean disableAcra = false;
        try {
            final boolean enableAcra = prefs.getBoolean(PREF_ENABLE_ACRA, true);
            disableAcra = prefs.getBoolean(PREF_DISABLE_ACRA, !enableAcra);
        } catch (Exception e) {
            // In case of a ClassCastException
        }
        return disableAcra;
    }

    /**
     * @return The Shared Preferences where ACRA will retrieve its user adjustable setting.
     * @deprecated since 4.8.0 use {@link SharedPreferencesFactory} instead.
     */
    @SuppressWarnings( "unused" )
    public static SharedPreferences getACRASharedPreferences() {
        return new SharedPreferencesFactory(mApplication, configProxy).create();
    }

    /**
     * Provides the current ACRA configuration.
     * 
     * @return Current ACRA {@link ReportsCrashes} configuration instance.
     * @deprecated since 4.8.0 {@link ACRAConfig} should be passed into classes instead of retrieved statically.
     */
    @SuppressWarnings( "unused" )
    public static ACRAConfiguration getConfig() {
        if (mApplication == null) {
            throw new IllegalStateException("Cannot call ACRA.getConfig() before ACRA.init().");
        }
        return configProxy;
    }

    /**
     * @param app       Your Application class.
     * @return new {@link ACRAConfiguration} instance with values initialized from the {@link ReportsCrashes} annotation.
     * @deprecated since 4.8.0 use {@link ACRAConfigurationFactory} instead.
     */
    @SuppressWarnings( "unused" )
    public static ACRAConfiguration getNewDefaultConfig(Application app) {
        return new ACRAConfigurationFactory().create(app);
    }

    public static void setLog(ACRALog log) {
        if (log == null) {
            throw new NullPointerException("ACRALog cannot be null");
        }
        ACRA.log = log;
    }
}
