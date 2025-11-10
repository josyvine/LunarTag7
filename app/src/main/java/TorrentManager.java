package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.ErrorCode;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;

import org.libtorrent4j.swig.create_torrent;  // NEW: For creating .torrent from data file
import org.libtorrent4j.swig.file_storage;  // NEW: For file storage in torrent creation
import org.libtorrent4j.swig.libtorrent;     // NEW: For default piece size and bdecode

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<Sha1Hash, String> hashToIdMap; // infoHash -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // CORRECT API: Get the type ID from the static alertTypeId() method for each alert class.
                return new int[]{
                        StateUpdateAlert.alertTypeId(),
                        TorrentFinishedAlert.alertTypeId(),
                        TorrentErrorAlert.alertTypeId()
                };
            }

            @Override
            public void alert(Alert<?> alert) {
                // CORRECT API: Switch on the integer type of the alert.
                int alertType = alert.type().swig();

                if (alertType == StateUpdateAlert.alertTypeId()) {
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alertType == TorrentFinishedAlert.alertTypeId()) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alertType == TorrentErrorAlert.alertTypeId()) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session, this will start the DHT and other services
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        for (TorrentStatus status : alert.status()) {
            // CORRECT API: .infoHash() is now .infoHashes().v1() for the v1 hash.
            String dropRequestId = hashToIdMap.get(status.infoHashes().v1());
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | ↓ " + (status.downloadPayloadRate() / 1024) + " KB/s | ↑ " + (status.uploadPayloadRate() / 1024) + " KB/s");
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) (status.totalDone() / status.totalWanted() * 100));  // FIXED: Progress as %
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, (long) status.totalDone());
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHashes().v1());
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String dropRequestId = hashToIdMap.get(handle.infoHashes().v1());
        // CORRECT API: The error message is now directly on the alert's message() method.
        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {  // FIXED: Renamed param to dataFile for clarity
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            // FIXED: Create .torrent from data file (single-file torrent)
            torrentFile = createTorrentFile(dataFile);  // NEW: Helper method below
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);
            
            AddTorrentParams params = new AddTorrentParams();
            // CORRECT API: The method is .ti(ti)
            params.ti(torrentInfo);
            // CORRECT API: The method is .savePath(string)
            params.savePath(dataFile.getParentFile().getAbsolutePath());  // Save path to data location
            
            // CORRECT API: Use getSession().addTorrent(params)
            sessionManager.getSession().addTorrent(params);
            // CORRECT API: The method is .findTorrent(hash)
            TorrentHandle handle = sessionManager.getSession().findTorrent(torrentInfo.infoHashes().v1());

            if (handle != null) {
                activeTorrents.put(dropRequestId, handle);
                hashToIdMap.put(handle.infoHashes().v1(), dropRequestId);
                // CORRECT API: The method is .makeMagnetUri()
                String magnetLink = handle.makeMagnetUri();
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();  // Cleanup temp .torrent
            }
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        // NEW: Create single-file torrent (no trackers; add via ct.addTracker if needed)
        file_storage fs = new file_storage();
        fs.add_file(dataFile.getName(), dataFile.length());
        int pieceSize = (int) libtorrent.default_torrent_piece_size(fs);
        create_torrent ct = new create_torrent(fs, pieceSize);
        ct.set_root("");  // Root for single file

        // Generate and bencode
        ct.generate();
        byte[] torrentBytes = ct.generate().bencode();

        // Write to temp .torrent file
        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
        }
        return tempTorrent;
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        // CORRECT API: .parseMagnetUri is a static method on AddTorrentParams.
        AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink);
        params.savePath(saveDirectory.getAbsolutePath());
        // CORRECT API: Use getSession().addTorrent(params)
        sessionManager.getSession().addTorrent(params);
        // CORRECT API: The method is .infoHashes().v1()
        TorrentHandle handle = sessionManager.getSession().findTorrent(params.infoHashes().v1());

        if (handle != null) {
            activeTorrents.put(dropRequestId, handle);
            hashToIdMap.put(handle.infoHashes().v1(), dropRequestId);
            Log.d(TAG, "Started download for request ID: " + dropRequestId);
        } else {
            Log.e(TAG, "Failed to get TorrentHandle after adding download from magnet link.");
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }
        Sha1Hash hash = handle.infoHashes().v1();
        String dropRequestId = hashToIdMap.get(hash);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(hash);
        }
        // CORRECT API: The method is .removeTorrent(handle) on getSession()
        sessionManager.getSession().removeTorrent(handle);
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null; // Allow re-initialization if needed
    }
}