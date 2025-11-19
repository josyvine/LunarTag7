package com.lunartag.app.firebase;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages fetching and activating configuration values from Firebase Remote Config.
 * This replaces the old FCM push notification system.
 */
public class RemoteConfigManager {

    private static final String TAG = "RemoteConfigManager";

    // The key defined in the Firebase Console
    private static final String REMOTE_KEY_ADMIN_ENABLED = "admin_ui_enabled";

    // The local SharedPreferences mapping (Legacy keys to maintain compatibility with existing UI)
    private static final String PREFS_NAME = "LunarTagFeatureToggles";
    private static final String KEY_CUSTOM_TIMESTAMP_ENABLED = "customTimestampEnabled";

    /**
     * Initializes Remote Config, sets defaults, and fetches the latest values.
     *
     * @param context The application context used for saving SharedPreferences.
     */
    public static void fetchRemoteConfig(final Context context) {
        // 1. Get the Remote Config instance
        FirebaseRemoteConfig mFirebaseRemoteConfig = FirebaseRemoteConfig.getInstance();

        // 2. Create a config settings object
        // We set the minimum fetch interval to 3600 seconds (1 hour) to prevent
        // throttling by the Firebase server while on the free plan.
        // For development/testing, you might reduce this to 0, but 3600 is safe for prod.
        FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(3600)
                .build();
        mFirebaseRemoteConfig.setConfigSettingsAsync(configSettings);

        // 3. Set default values
        // These are used if the device is offline or the fetch fails.
        // Default: Admin UI is HIDDEN (false).
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(REMOTE_KEY_ADMIN_ENABLED, false);
        mFirebaseRemoteConfig.setDefaultsAsync(defaults);

        // 4. Fetch and Activate
        // This retrieves values from the cloud and makes them available to the app.
        mFirebaseRemoteConfig.fetchAndActivate()
                .addOnCompleteListener(new OnCompleteListener<Boolean>() {
                    @Override
                    public void onComplete(@NonNull Task<Boolean> task) {
                        if (task.isSuccessful()) {
                            boolean updated = task.getResult();
                            Log.d(TAG, "Remote Config fetch succeeded. Config params updated: " + updated);

                            // 5. Apply the logic
                            // Get the value from Remote Config
                            boolean isAdminEnabled = mFirebaseRemoteConfig.getBoolean(REMOTE_KEY_ADMIN_ENABLED);
                            Log.d(TAG, "Remote Config 'admin_ui_enabled' value is: " + isAdminEnabled);

                            // Save this value to the local SharedPreferences.
                            // This ensures the SettingsFragment and AdminConsoleFragment work exactly as before.
                            updateLocalPreferences(context, isAdminEnabled);

                        } else {
                            Log.e(TAG, "Remote Config fetch failed.");
                            // In case of failure, the app will continue using the cached
                            // or default values automatically.
                        }
                    }
                });
    }

    /**
     * Writes the boolean value to the specific SharedPreferences file
     * that the rest of the app (SettingsFragment) is listening to.
     */
    private static void updateLocalPreferences(Context context, boolean isEnabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        // We map the new Remote Config key to the old existing key
        editor.putBoolean(KEY_CUSTOM_TIMESTAMP_ENABLED, isEnabled);
        editor.apply();
        
        Log.d(TAG, "Updated local preference 'customTimestampEnabled' to: " + isEnabled);
    }
}