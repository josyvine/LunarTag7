
package com.safevoice.app.models;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/**
 * A simple data model class (POJO) to represent an emergency contact.
 * It includes helper methods for converting the object to and from a JSONObject,
 * which is useful for storing it in SharedPreferences.
 */
public class Contact {

    private static final String JSON_KEY_NAME = "name";
    private static final String JSON_KEY_PHONE = "phoneNumber";
    private static final String JSON_KEY_UID = "uid"; // New key for Firebase User ID

    private String name;
    private String phoneNumber;
    private String uid; // New field for Firebase User ID

    // Original constructor for phone-only contacts (e.g., primary contact)
    public Contact(String name, String phoneNumber) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.uid = null; // This contact is not a linked Safe Voice user
    }

    // New constructor for linked Safe Voice users
    public Contact(String name, String phoneNumber, @Nullable String uid) {
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.uid = uid;
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    @Nullable
    public String getUid() {
        return uid;
    }

    // Setters
    public void setName(String name) {
        this.name = name;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public void setUid(@Nullable String uid) {
        this.uid = uid;
    }


    /**
     * Converts this Contact object into a JSONObject.
     *
     * @return A JSONObject representation of the contact, or null on error.
     */
    @Nullable
    public JSONObject toJSONObject() {
        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put(JSON_KEY_NAME, this.name);
            jsonObject.put(JSON_KEY_PHONE, this.phoneNumber);
            // Use putOpt to safely handle null uid
            jsonObject.putOpt(JSON_KEY_UID, this.uid);
            return jsonObject;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Creates a Contact object from a JSONObject.
     *
     * @param jsonObject The JSONObject to parse.
     * @return A new Contact object, or null if parsing fails.
     */
    @Nullable
    public static Contact fromJSONObject(JSONObject jsonObject) {
        try {
            String name = jsonObject.getString(JSON_KEY_NAME);
            String phone = jsonObject.getString(JSON_KEY_PHONE);
            // Use optString to safely retrieve the uid, defaulting to null if not present
            String uid = jsonObject.optString(JSON_KEY_UID, null);
            return new Contact(name, phone, uid);
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    // Overriding equals and hashCode is important for managing lists of contacts,
    // for example, to correctly find and remove a specific contact.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Contact contact = (Contact) o;
        // A contact is considered equal if their UIDs match (if they exist),
        // or if their phone numbers match if UIDs are not present.
        if (uid != null && contact.uid != null) {
            return uid.equals(contact.uid);
        }
        return phoneNumber.equals(contact.phoneNumber) && name.equals(contact.name);
    }

    @Override
    public int hashCode() {
        // Use Objects.hash for safe hashing of potentially null fields.
        return Objects.hash(name, phoneNumber, uid);
    }
}