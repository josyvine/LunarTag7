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
import android.view.MotionEvent;
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
import com.lunartag.app.R;
import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.data.PhotoDao;
import com.lunartag.app.databinding.FragmentCameraBinding;
import com.lunartag.app.model.Photo;
import com.lunartag.app.utils.ExifUtils;
import com.lunartag.app.utils.ImageUtils;
import com.lunartag.app.utils.LocationProvider;
import com.lunartag.app.utils.Scheduler;
import com.lunartag.app.utils.WatermarkUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            Toast.makeText(getContext(), "Camera permissions not granted.", Toast.LENGTH_SHORT).show();
        }

        // 3. Capture Button Logic
        binding.buttonCapture.setOnClickListener(v -> takePhoto());

        // 4. Flip Camera Button Logic
        binding.buttonFlipCamera.setOnClickListener(v -> toggleCamera());

        updateSlotCounter(); // Update UI if in admin mode
    }

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

            } catch (ExecutionException | InterruptedException e) {
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
        if (imageCapture == null) return;

        // Show visual feedback
        Toast.makeText(getContext(), "Capturing...", Toast.LENGTH_SHORT).show();

        // Capture to memory (ImageProxy) to process watermark before saving
        imageCapture.takePicture(cameraExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                // Process in background
                processAndSaveImage(image);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
            }
        });
    }

    private void processAndSaveImage(ImageProxy imageProxy) {
        // 1. Convert YUV to Bitmap
        Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
        imageProxy.close(); // Always close the proxy

        if (bitmap == null) {
            Log.e(TAG, "Failed to convert image to bitmap.");
            return;
        }

        // 2. Get Location (Async)
        locationProvider.getCurrentLocation(location -> {
            
            // 3. Determine Timestamp (Real vs Admin Assigned)
            long realTime = System.currentTimeMillis();
            long assignedTime = realTime;
            boolean isAdminMode = false;

            // Check Admin Logic
            SharedPreferences togglePrefs = requireContext().getSharedPreferences(PREFS_TOGGLES, Context.MODE_PRIVATE);
            if (togglePrefs.getBoolean(KEY_ADMIN_ENABLED, false)) {
                isAdminMode = true;
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
            WatermarkUtils.addWatermark(bitmap, null, watermarkLines);

            // 6. Save to Public Gallery (MediaStore)
            Uri savedUri = saveImageToGallery(getContext(), bitmap, "LunarTag_" + realTime);

            if (savedUri != null) {
                Log.d(TAG, "Image saved to Gallery: " + savedUri.toString());
                
                // 7. Save to Local Database (Room)
                savePhotoToDatabase(savedUri.toString(), realTime, assignedTime, location);
                
                // 8. Update UI (Main Thread)
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                     Toast.makeText(getContext(), "Photo Saved & Watermarked!", Toast.LENGTH_SHORT).show();
                     updateSlotCounter(); // Refresh counter if in admin mode
                });
            }
        });
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

    // --- Storage Logic (MediaStore) ---

    private Uri saveImageToGallery(Context context, Bitmap bitmap, String filename) {
        OutputStream fos;
        Uri imageUri = null;

        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, filename + ".jpg");
            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            // Save to "Pictures/LunarTag"
            contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "LunarTag");

            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

            if (imageUri == null) return null;

            fos = resolver.openOutputStream(imageUri);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
            if (fos != null) fos.close();

            return imageUri;
        } catch (IOException e) {
            Log.e(TAG, "Error saving to gallery", e);
            return null;
        }
    }

    // --- Database Logic ---

    private void savePhotoToDatabase(String uriString, long realTime, long assignedTime, Location loc) {
        Photo photo = new Photo();
        photo.setFilePath(uriString); // Store URI as path
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
        long id = dao.insertPhoto(photo);
        
        // Schedule the send
        // Scheduler.schedulePhotoSend(getContext(), id, uriString, assignedTime); 
        // Note: We might need to adjust Scheduler to handle URIs instead of File paths later.
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