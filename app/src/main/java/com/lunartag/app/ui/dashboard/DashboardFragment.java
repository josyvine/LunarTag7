package com.lunartag.app.ui.dashboard;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.lunartag.app.databinding.FragmentDashboardBinding;

public class DashboardFragment extends Fragment {

    private FragmentDashboardBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentDashboardBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Setup the RecyclerView for horizontal scrolling of recent photos
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        binding.recyclerViewRecentPhotos.setLayoutManager(layoutManager);

        // Set click listener for the shift toggle button using an anonymous inner class
        binding.buttonToggleShift.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Placeholder logic for starting/stopping a shift
                Toast.makeText(getContext(), "Shift Toggled (Placeholder)", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // This method is called when the fragment becomes visible.
        // We will load the current status and update the UI here.
        updateUI();
    }

    private void updateUI() {
        // This is where logic will be added to:
        // 1. Read shift timing and status from SharedPreferences and update the text views.
        // 2. Query the local Room database for the next scheduled send and pending uploads.
        // 3. Query the local Room database for the most recent photos and populate the RecyclerView.
        // This avoids hitting Firestore on every screen load.
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // Important to prevent memory leaks
    }
}