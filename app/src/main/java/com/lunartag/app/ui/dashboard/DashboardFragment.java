package com.lunartag.app.ui.dashboard; 

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.databinding.FragmentDashboardBinding;
import com.lunartag.app.model.Photo;
import com.lunartag.app.ui.gallery.GalleryAdapter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;

    // SharedPreferences to store the Shift State permanently
    private static final String PREFS_SHIFT = "LunarTagShiftPrefs";
    private static final String KEY_IS_SHIFT_ACTIVE = "is_shift_active";
    private static final String KEY_LAST_ACTION_TIME = "last_action_time";

    // --- DB Components ---
    private ExecutorService databaseExecutor;
    
    // Two separate adapters for the two boxes
    private GalleryAdapter scheduledAdapter;
    private GalleryAdapter recentAdapter;
    
    // Data lists
    private List<Photo> scheduledPhotoList;
    private List<Photo> recentPhotoList;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Initialize Executor for DB operations
        databaseExecutor = Executors.newSingleThreadExecutor();
        scheduledPhotoList = new ArrayList<>();
        recentPhotoList = new ArrayList<>();

        // --- 1. Setup Top Box (Scheduled Sends) ---
        LinearLayoutManager scheduledManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        // Using 'recyclerViewScheduledSends' based on your UI text. 
        // If your XML ID is different, update this variable name.
        if (binding.recyclerViewScheduledSends != null) {
            binding.recyclerViewScheduledSends.setLayoutManager(scheduledManager);
            scheduledAdapter = new GalleryAdapter(getContext(), scheduledPhotoList);
            binding.recyclerViewScheduledSends.setAdapter(scheduledAdapter);
        }

        // --- 2. Setup Bottom Box (Recent Photos) ---
        LinearLayoutManager recentManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        binding.recyclerViewRecentPhotos.setLayoutManager(recentManager);
        recentAdapter = new GalleryAdapter(getContext(), recentPhotoList);
        binding.recyclerViewRecentPhotos.setAdapter(recentAdapter);

        // Set click listener for the shift toggle button
        binding.buttonToggleShift.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleShiftState();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Load UI state
        updateUI();
        // Load Data from DB
        loadDashboardData();
    }

    /**
     * Query database for BOTH Scheduled (Pending) and Recent photos.
     */
    private void loadDashboardData() {
        if (getContext() == null) return;

        databaseExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getDatabase(getContext());
            
            // 1. Get Pending Photos (For Top Box)
            List<Photo> pendingPhotos = db.photoDao().getPendingPhotos();
            
            // 2. Get Recent Photos (For Bottom Box) - Limit to 10
            List<Photo> recentPhotos = db.photoDao().getRecentPhotos(10);

            // Update UI on Main Thread
            new Handler(Looper.getMainLooper()).post(() -> {
                if (binding != null) {
                    // Update Scheduled List
                    scheduledPhotoList.clear();
                    if (pendingPhotos != null) {
                        scheduledPhotoList.addAll(pendingPhotos);
                    }
                    if (scheduledAdapter != null) {
                        scheduledAdapter.notifyDataSetChanged();
                    }

                    // Update Recent List
                    recentPhotoList.clear();
                    if (recentPhotos != null) {
                        recentPhotoList.addAll(recentPhotos);
                    }
                    if (recentAdapter != null) {
                        recentAdapter.notifyDataSetChanged();
                    }
                }
            });
        });
    }

    /**
     * Reads the current state from SharedPreferences and updates the Button and Text.
     */
    private void updateUI() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_SHIFT, Context.MODE_PRIVATE);
        boolean isShiftActive = prefs.getBoolean(KEY_IS_SHIFT_ACTIVE, false);
        long lastActionTime = prefs.getLong(KEY_LAST_ACTION_TIME, 0);

        if (isShiftActive) {
            // Shift is currently running
            binding.buttonToggleShift.setText("End Shift");
        } else {
            // Shift is not running
            binding.buttonToggleShift.setText("Start Shift");
        }
    }

    private void toggleShiftState() {
        if (getContext() == null) return;

        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_SHIFT, Context.MODE_PRIVATE);
        boolean isCurrentlyActive = prefs.getBoolean(KEY_IS_SHIFT_ACTIVE, false);
        SharedPreferences.Editor editor = prefs.edit();

        if (isCurrentlyActive) {
            // Logic to END the shift
            editor.putBoolean(KEY_IS_SHIFT_ACTIVE, false);
            editor.putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis());
            editor.apply();

            Toast.makeText(getContext(), "Shift Ended. Good job!", Toast.LENGTH_SHORT).show();
        } else {
            // Logic to START the shift
            editor.putBoolean(KEY_IS_SHIFT_ACTIVE, true);
            editor.putLong(KEY_LAST_ACTION_TIME, System.currentTimeMillis());
            editor.apply();

            Toast.makeText(getContext(), "Shift Started. Tracking active.", Toast.LENGTH_SHORT).show();
        }

        updateUI();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; 
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }
}