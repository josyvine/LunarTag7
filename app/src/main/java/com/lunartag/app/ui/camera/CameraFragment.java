package com.lunartag.app.ui.camera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.common.util.concurrent.ListenableFuture;
import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.data.PhotoDao;
import com.lunartag.app.databinding.FragmentCameraBinding;
import com.lunartag.app.model.Photo;
import com.lunartag.app.utils.ImageUtils;
import com.lunartag.app.utils.LocationProvider;
import com.lunartag.app.utils.StorageUtils;
import com.lunartag.app.utils.WatermarkUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraFragment extends Fragment {

    private static final String TAG = "CameraFragment";

    // Preferences for Admin/Schedule Mode
    private static final String PREFS_SCHEDULE = "LunarTagSchedule";
    private static final String KEY_TIMESTAMP_LIST = "timestamp_list";
    private static final String PREFS_TOGGLES = "LunarTagFeatureToggles";
    private static final String KEY_ADMIN_ENABLED = "customTimestampEnabled";

    // Preferences for Settings (Company Name)
    private static final String PREFS_SETTINGS = "LunarTagSettings";
    private static final String KEY_COMPANY_NAME = "company_name";

    private FragmentCameraBinding binding;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Camera camera; // Reference to control Zoom
    private int lensFacing = CameraSelector.LENS_FACING_BACK; // Default to Back camera

    // Zoom Handling
    private ScaleGestureDetector scaleGestureDetector;

    // Location
    private LocationProvider locationProvider;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentCameraBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        cameraExecutor = Executors.newSingleThreadExecutor();
        locationProvider = new LocationProvider(getContext());

        // Setup Listener to turn GPS Icon GREEN when locked
        locationProvider.setStatusListener(location -> {
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                if (binding != null) {
                    binding.buttonGpsStatus.setColorFilter(Color.GREEN);
                    // Don't spam the log, just visual indication
                }
            });
        });

        // --- LIVE LOG START ---
        logToScreen("System: Camera View Created.");
        // ----------------------

        // 1. Initialize Zoom Gesture Detector
        scaleGestureDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    float currentZoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                    float delta = detector.getScaleFactor();
                    camera.getCameraControl().setZoomRatio(currentZoomRatio * delta);
                }
                return true;
            }
        });

        // Attach Touch Listener to Preview for Zoom
        binding.cameraPreview.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });

        // 2. Check Permissions and Start
        logToScreen("System: Checking permissions...");
        if (allPermissionsGranted()) {
            logToScreen("System: Permissions OK. Starting CameraX...");
            startCamera();
        } else {
            logToScreen("ERROR: Camera/Location Permissions NOT granted!");
            Toast.makeText(getContext(), "Camera permissions not granted.", Toast.LENGTH_SHORT).show();
        }

        // 3. Capture Button Logic
        binding.buttonCapture.setOnClickListener(v -> {
            logToScreen("Event: Capture Button Clicked.");
            takePhoto();
        });

        // 4. Flip Camera Button Logic
        binding.buttonFlipCamera.setOnClickListener(v -> toggleCamera());

        // 5. NEW: GPS Button Logic (Footer)
        binding.buttonGpsStatus.setOnClickListener(v -> {
            logToScreen("User Command: Force GPS Update.");
            // Logic is handled automatically by LocationProvider being on, 
            // but this gives user confidence
            Toast.makeText(getContext(), "Refining GPS Signal...", Toast.LENGTH_SHORT).show();
        });

        // 6. NEW: Folder Selection Logic (Footer)
        binding.buttonSaveFolder.setOnClickListener(v -> {
            logToScreen("User Command: Opening File Picker...");
            StorageUtils.launchFolderSelector(this);
        });

        updateSlotCounter(); // Update UI if in admin mode
    }

    // --- LIFECYCLE FOR GPS ENGINE (NEW) ---
    @Override
    public void onResume() {
        super.onResume();
        logToScreen("System: Resuming. Starting GPS Engine...");
        // Start tracking immediately so we have data BEFORE capture
        if (locationProvider != null) locationProvider.startLocationUpdates();
    }

    @Override
    public void onPause() {
        super.onPause();
        logToScreen("System: Pausing. Stopping GPS Engine.");
        if (locationProvider != null) locationProvider.stopLocationUpdates();
    }
    // --------------------------------------

    // --- DEBUG CONSOLE HELPER (KEPT ORIGINAL) ---
    private void logToScreen(String message) {
        // Always run on Main Thread so we can update the UI
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (binding != null && binding.textDebugConsole != null) {
                binding.textDebugConsole.append("\n" + message);
                // Auto-scroll to ensure user sees the newest message
                binding.textDebugConsole.post(() -> {
                     if (binding.textDebugConsole.getParent() instanceof View) {
                         View parent = (View) binding.textDebugConsole.getParent();
                         parent.scrollBy(0, 1000); // Force scroll down
                     }
                });
                Log.d("LunarTagLive", message); // Also print to system log just in case
            }
        });
    }
    // --------------------------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(getContext());

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        getViewLifecycleOwner(), cameraSelector, preview, imageCapture);

                logToScreen("System: Camera Started Successfully.");

            } catch (ExecutionException | InterruptedException e) {
                logToScreen("CRITICAL ERROR: Failed to bind camera: " + e.getMessage());
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private void toggleCamera() {
        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            lensFacing = CameraSelector.LENS_FACING_FRONT;
        } else {
            lensFacing = CameraSelector.LENS_FACING_BACK;
        }
        startCamera();
    }

    private void takePhoto() {
        if (imageCapture == null) {
            logToScreen("ERROR: ImageCapture is null (Camera not ready).");
            return;
        }

        Toast.makeText(getContext(), "Capturing...", Toast.LENGTH_SHORT).show();
        logToScreen("System: Requesting image from sensor...");

        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                logToScreen("System: Image sensor capture SUCCESS.");
                processAndSaveImage(image);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                logToScreen("CRITICAL ERROR: Image Sensor Failed: " + exception.getMessage());
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
            }
        });
    }

    private void processAndSaveImage(ImageProxy imageProxy) {
        try {
            logToScreen("System: Converting YUV to Bitmap...");
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
            imageProxy.close();

            if (bitmap == null) {
                logToScreen("ERROR: Failed to convert image to bitmap.");
                return;
            }

            // --- CRITICAL CHANGE: INSTANT GPS ---
            logToScreen("System: Grabbing Location immediately...");
            // We DO NOT wait here. We grab the value from memory instantly.
            Location location = locationProvider.getCurrentLocationFast();

            if (location == null) {
                logToScreen("WARNING: Location is NULL/Waiting. Saving anyway (Safety Mode).");
            } else {
                logToScreen("System: Location Locked (Lat: " + location.getLatitude() + ")");
            }
            // ------------------------------------

            try {
                long realTime = System.currentTimeMillis();
                long assignedTime = realTime;

                SharedPreferences togglePrefs = requireContext().getSharedPreferences(PREFS_TOGGLES, Context.MODE_PRIVATE);
                if (togglePrefs.getBoolean(KEY_ADMIN_ENABLED, false)) {
                    assignedTime = getNextScheduledTimestamp(realTime);
                }

                // --- FIX: LOAD COMPANY NAME FROM SETTINGS ---
                SharedPreferences settingsPrefs = requireContext().getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
                String companyName = settingsPrefs.getString(KEY_COMPANY_NAME, "My Company"); 
                // --------------------------------------------

                String address = getAddressFromLocation(location);
                SimpleDateFormat sdf = new SimpleDateFormat("dd-MMM-yyyy hh:mm:ss a", Locale.US);
                String timeString = sdf.format(new Date(assignedTime));
                String gpsString = "Lat: " + (location != null ? location.getLatitude() : "0.0") +
                        " Lon: " + (location != null ? location.getLongitude() : "0.0");

                String[] watermarkLines = {
                        "GPS Map Camera",
                        companyName,
                        address,
                        gpsString,
                        timeString
                };

                logToScreen("System: Applying Watermark...");
                WatermarkUtils.addWatermark(bitmap, null, watermarkLines);

                // --- CRITICAL CHANGE: STORAGE LOGIC ---
                String absolutePath = null;
                logToScreen("System: Saving File...");

                // 1. Check if user selected a custom folder
                if (StorageUtils.hasCustomFolder(getContext())) {
                    logToScreen("Storage: Using User-Selected Folder (SD/External).");
                    absolutePath = StorageUtils.saveImageToCustomFolder(getContext(), bitmap, "LunarTag_" + realTime);
                } 
                // 2. Fallback to Default Internal
                else {
                    logToScreen("Storage: Using Default Internal Storage.");
                    absolutePath = saveImageToInternalStorage(getContext(), bitmap, "LunarTag_" + realTime);
                    // If Internal, we also export to Gallery for visibility
                    if (absolutePath != null) {
                        logToScreen("Storage: Exporting copy to Public Gallery...");
                        exportToPublicGallery(getContext(), absolutePath, "LunarTag_" + realTime);
                    }
                }

                if (absolutePath != null) {
                    logToScreen("SUCCESS: File Written. (" + absolutePath + ")");
                    savePhotoToDatabase(absolutePath, realTime, assignedTime, location);
                    logToScreen("System: Database Updated.");

                    new android.os.Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(getContext(), "Photo Saved!", Toast.LENGTH_SHORT).show();
                        updateSlotCounter();
                    });
                } else {
                    logToScreen("CRITICAL ERROR: File Write Failed! Check permissions.");
                    new android.os.Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(getContext(), "Save Failed!", Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                logToScreen("CRITICAL ERROR inside Processing: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            logToScreen("CRITICAL ERROR Top Level: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Handle Folder Selection Result (NEW) ---
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == StorageUtils.REQUEST_CODE_PICK_FOLDER) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                logToScreen("User Selected Folder URI: " + treeUri);
                StorageUtils.saveFolderPermission(getContext(), treeUri);
                logToScreen("System: Permission Saved Permanently.");
            } else {
                logToScreen("User Cancelled Folder Selection.");
            }
        }
    }
    // --------------------------------------------

    private long getNextScheduledTimestamp(long fallbackTime) {
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_TIMESTAMP_LIST, "[]");
        List<Long> list = new ArrayList<>();

        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.getLong(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        if (list.isEmpty()) {
            return fallbackTime;
        }
        long assigned = list.remove(0);
        JSONArray updatedArray = new JSONArray();
        for (Long ts : list) {
            updatedArray.put(ts);
        }
        prefs.edit().putString(KEY_TIMESTAMP_LIST, updatedArray.toString()).apply();

        return assigned;
    }

    private void updateSlotCounter() {
        SharedPreferences togglePrefs = requireContext().getSharedPreferences(PREFS_TOGGLES, Context.MODE_PRIVATE);
        if (!togglePrefs.getBoolean(KEY_ADMIN_ENABLED, false)) {
            binding.textSlotCounter.setVisibility(View.GONE);
            return;
        }
        SharedPreferences prefs = requireContext().getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_TIMESTAMP_LIST, "[]");
        try {
            JSONArray jsonArray = new JSONArray(json);
            int count = jsonArray.length();
            binding.textSlotCounter.setText(count + " Slots Left");
            binding.textSlotCounter.setVisibility(View.VISIBLE);
        } catch (JSONException e) {
            binding.textSlotCounter.setVisibility(View.GONE);
        }
    }

    private String saveImageToInternalStorage(Context context, Bitmap bitmap, String filename) {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (directory == null) {
            logToScreen("ERROR: External Files Dir is null!");
            return null;
        }
        File file = new File(directory, filename + ".jpg");
        try (OutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            return file.getAbsolutePath();
        } catch (IOException e) {
            logToScreen("ERROR Saving IO: " + e.getMessage());
            return null;
        }
    }

    private void exportToPublicGallery(Context context, String internalPath, String filename) {
        if (internalPath == null) return;
        try {
            File internalFile = new File(internalPath);
            if (!internalFile.exists()) return;

            ContentResolver resolver = context.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename + ".jpg");
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "LunarTag");

            Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

            if (imageUri != null) {
                try (OutputStream out = resolver.openOutputStream(imageUri);
                     InputStream in = new FileInputStream(internalFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    logToScreen("Export: Copy Success.");
                }
            }
        } catch (Exception e) {
            logToScreen("Export EXCEPTION: " + e.getMessage());
        }
    }

    private void savePhotoToDatabase(String filePath, long realTime, long assignedTime, Location loc) {
        try {
            Photo photo = new Photo();
            photo.setFilePath(filePath); 
            photo.setCaptureTimestampReal(realTime);
            photo.setAssignedTimestamp(assignedTime);
            photo.setCreatedAt(System.currentTimeMillis());
            photo.setStatus("PENDING");
            if (loc != null) {
                photo.setLat(loc.getLatitude());
                photo.setLon(loc.getLongitude());
                photo.setAccuracyMeters(loc.getAccuracy());
            }
            AppDatabase db = AppDatabase.getDatabase(getContext());
            PhotoDao dao = db.photoDao();
            dao.insertPhoto(photo);
        } catch (Exception e) {
            logToScreen("DB ERROR: " + e.getMessage());
        }
    }

    private String getAddressFromLocation(Location location) {
        if (location == null) return "Location Unknown";
        try {
            Geocoder geocoder = new Geocoder(getContext(), Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                return addresses.get(0).getAddressLine(0);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "Address Not Found";
    }

    private boolean allPermissionsGranted() {
        String[] requiredPermissions = {Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(getContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}