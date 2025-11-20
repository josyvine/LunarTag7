package com.lunartag.app.ui.viewer;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.lunartag.app.R;
import com.lunartag.app.data.AppDatabase;
import com.lunartag.app.data.PhotoDao;
import com.lunartag.app.model.Photo;
import com.lunartag.app.utils.Scheduler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImageViewerActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private TextView textCounter;
    private ImageButton btnClose, btnShare, btnDelete;

    private ImageViewerAdapter adapter;
    private List<String> imagePaths;
    private ExecutorService databaseExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        // Initialize Background Executor
        databaseExecutor = Executors.newSingleThreadExecutor();

        // Bind Views
        viewPager = findViewById(R.id.view_pager);
        textCounter = findViewById(R.id.text_counter);
        btnClose = findViewById(R.id.btn_close);
        btnShare = findViewById(R.id.btn_share);
        btnDelete = findViewById(R.id.btn_delete);

        // Get Data from Intent
        if (getIntent() != null) {
            imagePaths = getIntent().getStringArrayListExtra("paths");
            int startPosition = getIntent().getIntExtra("start_position", 0);

            if (imagePaths == null) imagePaths = new ArrayList<>();

            setupViewPager(startPosition);
        }

        setupClickListeners();
    }

    private void setupViewPager(int startPosition) {
        adapter = new ImageViewerAdapter(this, imagePaths);
        viewPager.setAdapter(adapter);
        
        // Jump to the clicked photo
        viewPager.setCurrentItem(startPosition, false);
        updateCounter(startPosition);

        // Update counter when swiping
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateCounter(position);
            }
        });
    }

    private void updateCounter(int position) {
        int current = position + 1;
        int total = imagePaths.size();
        textCounter.setText(current + " / " + total);
    }

    private void setupClickListeners() {
        // Close Button
        btnClose.setOnClickListener(v -> finish());

        // Share Button
        btnShare.setOnClickListener(v -> shareCurrentImage());

        // Delete Button
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private void shareCurrentImage() {
        int currentPos = viewPager.getCurrentItem();
        if (currentPos < 0 || currentPos >= imagePaths.size()) return;

        String path = imagePaths.get(currentPos);
        File file = new File(path);

        if (file.exists()) {
            try {
                // Generate Secure URI
                Uri uri = FileProvider.getUriForFile(
                        this,
                        getPackageName() + ".fileprovider",
                        file
                );

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("image/jpeg");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                startActivity(Intent.createChooser(shareIntent, "Share Image via..."));
            } catch (Exception e) {
                Toast.makeText(this, "Error creating share intent", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "File does not exist", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Photo")
                .setMessage("Are you sure you want to delete this photo?")
                .setPositiveButton("Delete", (dialog, which) -> deleteCurrentImage())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteCurrentImage() {
        int currentPos = viewPager.getCurrentItem();
        if (currentPos < 0 || currentPos >= imagePaths.size()) return;

        String pathToDelete = imagePaths.get(currentPos);

        databaseExecutor.execute(() -> {
            // 1. Clean up Database and Scheduler
            AppDatabase db = AppDatabase.getDatabase(this);
            PhotoDao dao = db.photoDao();
            
            // We need to find the photo ID by its path to cancel the alarm
            // Note: This requires a raw query or iterating, or adding a method to DAO. 
            // Since standard DAO usually gets by ID, we'll assume we fetch all and filter, 
            // or better, add a helper if needed. For now, efficient iteration:
            List<Photo> allPhotos = dao.getAllPhotos(); 
            Photo targetPhoto = null;
            for (Photo p : allPhotos) {
                if (p.getFilePath().equals(pathToDelete)) {
                    targetPhoto = p;
                    break;
                }
            }

            if (targetPhoto != null) {
                // Cancel the alarm
                Scheduler.cancelPhotoSend(this, targetPhoto.getId());
                // Delete from DB using the list delete method we added earlier
                List<Long> idList = new ArrayList<>();
                idList.add(targetPhoto.getId());
                dao.deletePhotos(idList);
            }

            // 2. Delete Physical File
            File file = new File(pathToDelete);
            if (file.exists()) {
                file.delete();
            }

            // 3. Update UI
            new Handler(Looper.getMainLooper()).post(() -> {
                imagePaths.remove(currentPos);
                adapter.notifyItemRemoved(currentPos);
                
                if (imagePaths.isEmpty()) {
                    // If no photos left, close viewer
                    Toast.makeText(this, "All photos deleted", Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    // Update counter
                    // If we deleted the last item, ViewPager automatically shifts back
                    int newPos = viewPager.getCurrentItem();
                    updateCounter(newPos);
                    Toast.makeText(this, "Photo Deleted", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (databaseExecutor != null) {
            databaseExecutor.shutdown();
        }
    }
}
