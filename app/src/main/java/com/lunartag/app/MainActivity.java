package com.lunartag.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.lunartag.app.databinding.ActivityMainBinding;
import com.lunartag.app.firebase.RemoteConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The main screen of the application.
 * UPDATED: Handles navigation and LIVE LOG RECEIVER for the Robot.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;

    private ActivityResultLauncher<String[]> permissionLauncher;

    // We will populate this dynamically based on Android Version to prevent crashes
    private String[] requiredPermissions;

    // --- LIVE LOG RECEIVER ---
    // This listens for messages from LunarTagAccessibilityService
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "com.lunartag.ACTION_LOG_UPDATE".equals(intent.getAction())) {
                String message = intent.getStringExtra("log_msg");
                if (message != null) {
                    // 1. Append the new message to the Green Text View
                    binding.tvLiveLog.append(message + "\n");

                    // 2. Auto-Scroll to the bottom so the latest log is visible
                    binding.logScrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            binding.logScrollView.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Set up the permissions list based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): Needs READ_MEDIA_IMAGES
            requiredPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_MEDIA_IMAGES
            };
        } else {
            // Android 12 and below: Needs WRITE_EXTERNAL_STORAGE
            requiredPermissions = new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }

        // Trigger Remote Config
        RemoteConfigManager.fetchRemoteConfig(this);

        // Setup Navigation Controller
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment_activity_main);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        // --- NEW: CUSTOM NAVIGATION LOGIC FOR 6 ICONS ---
        
        // 1. Dashboard
        binding.navDashboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_dashboard);
                updateIconVisuals(binding.navDashboard);
            }
        });

        // 2. Camera
        binding.navCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_camera);
                updateIconVisuals(binding.navCamera);
            }
        });

        // 3. Gallery
        binding.navGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_gallery);
                updateIconVisuals(binding.navGallery);
            }
        });

        // 4. Robot (Automation Mode)
        binding.navRobot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_robot);
                updateIconVisuals(binding.navRobot);
            }
        });

        // 5. Apps (Clone Selector)
        binding.navApps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_apps);
                updateIconVisuals(binding.navApps);
            }
        });

        // 6. Settings
        binding.navSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                navController.navigate(R.id.navigation_settings);
                updateIconVisuals(binding.navSettings);
            }
        });

        // -------------------------------------------------

        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                new ActivityResultCallback<Map<String, Boolean>>() {
                    @Override
                    public void onActivityResult(Map<String, Boolean> results) {
                        boolean allGranted = true;
                        for (Boolean granted : results.values()) {
                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }

                        if (allGranted) {
                            Toast.makeText(MainActivity.this, "All permissions granted. Lunar Tag is ready.", Toast.LENGTH_SHORT).show();
                            onPermissionsGranted();
                        } else {
                            Toast.makeText(MainActivity.this, "Some permissions were denied. Core features may not work.", Toast.LENGTH_LONG).show();
                        }
                    }
                });

        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // REGISTER RECEIVER: Start listening for robot logs
        // standard registerReceiver works for standard broadcasts from Service
        IntentFilter filter = new IntentFilter("com.lunartag.ACTION_LOG_UPDATE");
        // For Android 14+ compatibility (if targetSDK >= 34), consider specifying flags if needed, 
        // but standard registerReceiver(receiver, filter) is sufficient for internal app use usually.
        // If you crash on Android 14, use ContextCompat.registerReceiver with RECEIVER_NOT_EXPORTED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
             registerReceiver(logReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // UNREGISTER RECEIVER: Stop listening when app is backgrounded (optional, but good practice)
        try {
            unregisterReceiver(logReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }
    }

    /**
     * Optional: Helper to visually highlight the active tab.
     * Resets all icons to default color, then tints the active one.
     */
    private void updateIconVisuals(View activeView) {
        // Get colors
        int activeColor = getAttributeColor(com.google.android.material.R.attr.colorPrimary);
        int inactiveColor = getAttributeColor(com.google.android.material.R.attr.colorOnSurface);

        // Reset all
        binding.navDashboard.setColorFilter(inactiveColor);
        binding.navCamera.setColorFilter(inactiveColor);
        binding.navGallery.setColorFilter(inactiveColor);
        binding.navRobot.setColorFilter(inactiveColor);
        binding.navApps.setColorFilter(inactiveColor);
        binding.navSettings.setColorFilter(inactiveColor);

        // Set Active
        if (activeView instanceof android.widget.ImageView) {
            ((android.widget.ImageView) activeView).setColorFilter(activeColor);
        }
    }

    private int getAttributeColor(int attrId) {
        android.util.TypedValue typedValue = new android.util.TypedValue();
        getTheme().resolveAttribute(attrId, typedValue, true);
        return typedValue.data;
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        boolean allPermissionsAlreadyGranted = true;
        for (String permission : requiredPermissions) {
            if (permission != null && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
                allPermissionsAlreadyGranted = false;
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        }

        if (allPermissionsAlreadyGranted) {
            onPermissionsGranted();
        }
    }

    private void onPermissionsGranted() {
        // Permissions granted logic
    }
}