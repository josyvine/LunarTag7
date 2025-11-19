package com.lunartag.app.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * A "Pro" architecture Location Provider.
 * It runs in the background, maintaining a constant "Fresh" GPS lock
 * so the Camera never has to wait.
 */
public class LocationProvider {

    private static final String TAG = "LocationProvider";
    private final FusedLocationProviderClient fusedLocationClient;
    private final Context context;
    private LocationCallback locationCallback;
    
    // The "Hot" variable that holds the instant coordinate
    private Location currentBestLocation = null;
    
    // Interfaces for status updates (Optional, used to change GPS Icon color)
    private LocationStatusListener statusListener;

    public interface LocationStatusListener {
        void onLocationUpdated(Location location);
    }

    public void setStatusListener(LocationStatusListener listener) {
        this.statusListener = listener;
    }

    public LocationProvider(Context context) {
        this.context = context;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
    }

    /**
     * STEP 1: Start the Engine.
     * Call this in onResume(). It starts the GPS immediately.
     */
    public void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission missing. Cannot start updates.");
            return;
        }

        // 1. INSTANTLY grab the last known location (Cache)
        // This ensures we have data even if the GPS takes 30 seconds to warm up.
        fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                // Apply "Freshness" logic if needed, but for now, take what we can get.
                Log.d(TAG, "Last Known Location recovered: " + location.toString());
                currentBestLocation = location;
                if (statusListener != null) statusListener.onLocationUpdated(location);
            }
        });

        // 2. Create the Request for FRESH data
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000) // Update every 5s
                .setMinUpdateIntervalMillis(2000) // Fastest every 2s
                .setWaitForAccurateLocation(false) // CRITICAL: Do not wait!
                .build();

        // 3. Define what happens when a NEW satellite signal arrives
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        Log.d(TAG, "Fresh GPS Signal Received: " + location.toString());
                        currentBestLocation = location;
                        
                        // Notify the UI to turn the icon Green
                        if (statusListener != null) statusListener.onLocationUpdated(location);
                    }
                }
            }
        };

        // 4. Start the loop
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        Log.d(TAG, "GPS Engine Started (Background Mode).");
    }

    /**
     * STEP 2: Stop the Engine.
     * Call this in onPause() to save battery.
     */
    public void stopLocationUpdates() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            Log.d(TAG, "GPS Engine Stopped.");
        }
    }

    /**
     * STEP 3: The Instant Getter.
     * Call this when "Capture" is clicked. It returns IMMEDIATELY.
     * No callbacks. No waiting.
     */
    public Location getCurrentLocationFast() {
        if (currentBestLocation != null) {
            // We have a location! Return it.
            return currentBestLocation;
        } else {
            // The engine hasn't found anything yet (e.g. deep underground).
            // Return null, allowing the Camera to print "Location Unknown" instantly
            // rather than crashing or hanging.
            return null;
        }
    }
}