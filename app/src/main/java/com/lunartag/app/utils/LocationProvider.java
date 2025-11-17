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
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

/**
 * A helper class to simplify getting the device's current location.
 * It uses the FusedLocationProviderClient for efficient location fetching.
 */
public class LocationProvider {

    private static final String TAG = "LocationProvider";
    private final FusedLocationProviderClient fusedLocationClient;
    private final Context context;

    /**
     * Interface to provide the location result asynchronously.
     */
    public interface LocationResultCallback {
        void onLocationResult(Location location);
    }

    public LocationProvider(Context context) {
        this.context = context;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
    }

    /**
     * Fetches the current location of the device.
     * This method requests a single, high-accuracy update.
     * @param callback The callback to be invoked with the location result.
     */
    public void getCurrentLocation(final LocationResultCallback callback) {
        // First, check if location permissions have been granted.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted. Cannot fetch location.");
            callback.onLocationResult(null);
            return;
        }

        // Use the modern getCurrentLocation API for a one-time location request.
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        if (location != null) {
                            Log.d(TAG, "Successfully retrieved location.");
                            callback.onLocationResult(location);
                        } else {
                            Log.w(TAG, "getCurrentLocation returned null. This can happen if location is off.");
                            requestLocationUpdate(callback); // Fallback to a legacy request
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "getCurrentLocation failed.", e);
                        requestLocationUpdate(callback); // Fallback to a legacy request
                    }
                });
    }

    /**
     * A fallback method to request location updates if getCurrentLocation fails.
     */
    private void requestLocationUpdate(final LocationResultCallback callback) {
        final LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
                .setMinUpdateIntervalMillis(5000)
                .setMaxUpdates(1)
                .build();

        final LocationCallback locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                fusedLocationClient.removeLocationUpdates(this);
                if (locationResult.getLastLocation() != null) {
                    Log.d(TAG, "Successfully retrieved location via fallback request.");
                    callback.onLocationResult(locationResult.getLastLocation());
                } else {
                    Log.e(TAG, "LocationResult was null after fallback request.");
                    callback.onLocationResult(null);
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Permission check failed before requesting location updates.", e);
            callback.onLocationResult(null);
        }
    }
              }
