package com.lunartag.app.ui.camera;

import android.Manifest; 
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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

        updateSlotCounter(); // Update UI if in admin mode
    }

    // --- DEBUG CONSOLE HELPER ---
    private void logToScreen(String message) {
        // Always run on Main Thread so we can update the UI
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (binding != null && binding.textDebugConsole != null) {
                binding.textDebugConsole.append("\n" + message);
                Log.d("LunarTagLive", message); // Also print to system log just in case
            }
        });
    }
    // ----------------------------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(getContext());

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.cameraPreview.getSurfaceProvider());

                // ImageCapture
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                // Select Lens based on current variable
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                // Unbind and Bind
                cameraProvider.unbindAll();

                // Bind and save Camera instance for Zoom control
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

        // Show visual feedback
        Toast.makeText(getContext(), "Capturing...", Toast.LENGTH_SHORT).show();
        logToScreen("System: Requesting image from sensor...");

        // Capture to memory (ImageProxy) to process watermark before saving
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                logToScreen("System: Image sensor capture SUCCESS.");
                // Process in background
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
            // 1. Convert YUV to Bitmap
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
            imageProxy.close(); // Always close the proxy

            if (bitmap == null) {
                logToScreen("ERROR: Failed to convert image to bitmap.");
                return;
            }

            logToScreen("System: Bitmap Ready. Requesting Location...");

            // 2. Get Location (Async)
            // NOTE: We keep the logic robust. If location is null, we still save.
            locationProvider.getCurrentLocation(location -> {
                logToScreen("System: Location Callback Triggered.");

                if (location == null) {
                    logToScreen("WARNING: Location is NULL (GPS issue). Proceeding anyway.");
                } else {
                    logToScreen("System: Location Found (Lat: " + location.getLatitude() + ")");
                }

                try {
                    // 3. Determine Timestamp (Real vs Admin Assigned)
                    long realTime = System.currentTimeMillis();
                    long assignedTime = realTime;

                    // Check Admin Logic
                    SharedPreferences togglePrefs = requireContext().getSharedPreferences(PREFS_TOGGLES, Context.MODE_PRIVATE);
                    if (togglePrefs.getBoolean(KEY_ADMIN_ENABLED, false)) {
                        assignedTime = getNextScheduledTimestamp(realTime);
                    }

                    // 4. Prepare Watermark Data
                    String companyName = "My Company"; // Should load from Settings
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

                    // 5. Apply Watermark
                    logToScreen("System: Applying Watermark...");
                    WatermarkUtils.addWatermark(bitmap, null, watermarkLines);

                    // 6. Save to INTERNAL APP STORAGE first (Guarantees a valid File Path for DB)
                    String filename = "LunarTag_" + realTime;
                    logToScreen("System: Saving to Internal Storage...");
                    String absolutePath = saveImageToInternalStorage(getContext(), bitmap, filename);

                    if (absolutePath != null) {
                        logToScreen("SUCCESS: Saved at " + absolutePath);

                        // 7. Export to Public Gallery (So user sees it in File Manager)
                        logToScreen("System: Exporting to Public Gallery...");
                        exportToPublicGallery(getContext(), absolutePath, filename);

                        // 8. Save to Local Database (Room) using the valid path
                        savePhotoToDatabase(absolutePath, realTime, assignedTime, location);
                        logToScreen("System: Database Updated.");

                        // 9. Update UI (Main Thread)
                        new android.os.Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(getContext(), "Photo Saved & Watermarked!", Toast.LENGTH_SHORT).show();
                            updateSlotCounter(); // Refresh counter if in admin mode
                        });
                    } else {
                        logToScreen("CRITICAL ERROR: Absolute Path is NULL. Save failed.");
                        new android.os.Handler(Looper.getMainLooper()).post(() ->
                                Toast.makeText(getContext(), "Failed to save image!", Toast.LENGTH_SHORT).show());
                    }
                } catch (Exception e) {
                    logToScreen("CRITICAL ERROR inside Callback: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            logToScreen("CRITICAL ERROR in processing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Admin Schedule Logic ---

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
            return fallbackTime; // No slots left, use real time
        }

        // Pop the first one
        long assigned = list.remove(0);

        // Save the updated list back
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

    // --- Storage Logic ---

    /**
     * Saves the bitmap to the app's private external files directory.
     * This works on ALL Android versions without permissions and returns a valid file path.
     */
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
            Log.e(TAG, "Error saving internal image", e);
            return null;
        }
    }

    /**
     * Copies the internally saved file to the public MediaStore so it is visible in the Gallery app.
     */
    private void exportToPublicGallery(Context context, String internalPath, String filename) {
        if (internalPath == null) return;

        try {
            File internalFile = new File(internalPath);
            if (!internalFile.exists()) {
                logToScreen("ERROR: Internal file missing for export.");
                return;
            }

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
            } else {
                logToScreen("Export: Failed to create URI (Permission?).");
            }
        } catch (Exception e) {
            logToScreen("Export EXCEPTION: " + e.getMessage());
            Log.e(TAG, "Failed to export to public gallery", e);
        }
    }

    // --- Database Logic ---

    private void savePhotoToDatabase(String filePath, long realTime, long assignedTime, Location loc) {
        try {
            Photo photo = new Photo();
            photo.setFilePath(filePath); // Store REAL FILE PATH
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
        // We only check Camera/Location here as storage permissions are handled in MainActivity
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