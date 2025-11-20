package com.lunartag.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.lunartag.app.model.Photo;

import java.util.List;

/**
 * Data Access Object (DAO) for the Photo entity.
 * This interface defines the database interactions for the 'photos' table.
 * UPDATED: Added delete capability for multi-select.
 */
@Dao
public interface PhotoDao {

    /**
     * Inserts a new photo record into the database.
     * @param photo The photo object to insert.
     * @return The row ID of the newly inserted photo.
     */
    @Insert
    long insertPhoto(Photo photo);

    /**
     * Updates an existing photo record in the database.
     * @param photo The photo object to update.
     */
    @Update
    void updatePhoto(Photo photo);

    /**
     * Retrieves a single photo by its unique ID.
     * @param id The ID of the photo.
     * @return The Photo object.
     */
    @Query("SELECT * FROM photos WHERE id = :id")
    Photo getPhotoById(long id);

    /**
     * Retrieves all photos from the database, ordered by the most recent capture time first.
     * @return A list of all Photo objects.
     */
    @Query("SELECT * FROM photos ORDER BY captureTimestampReal DESC")
    List<Photo> getAllPhotos();

    /**
     * Retrieves a limited number of the most recent photos.
     * @param limit The maximum number of photos to retrieve.
     * @return A list of the most recent Photo objects.
     */
    @Query("SELECT * FROM photos ORDER BY captureTimestampReal DESC LIMIT :limit")
    List<Photo> getRecentPhotos(int limit);

    /**
     * Retrieves all photos that have a "PENDING" status.
     * @return A list of pending Photo objects.
     */
    @Query("SELECT * FROM photos WHERE status = 'PENDING'")
    List<Photo> getPendingPhotos();

    /**
     * NEW: Deletes a list of photos by their IDs.
     * Used for the multi-select delete feature.
     * @param ids The list of photo IDs to remove.
     */
    @Query("DELETE FROM photos WHERE id IN (:ids)")
    void deletePhotos(List<Long> ids);
}