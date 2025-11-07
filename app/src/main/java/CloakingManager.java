package com.hfm.app;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import java.util.Base64; // <<< THIS LINE HAS BEEN CORRECTED
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class CloakingManager {

    private static final String TAG = "CloakingManager";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String STATIC_SALT = "hfm_messenger_drop_salt";
    // Must be 16 bytes for AES-128/192/256
    private static final byte[] STATIC_IV = "hfm_static_iv_16".getBytes(StandardCharsets.UTF_8);

    /**
     * Generates a secure AES key from a user-provided secret string (the "Secret Number").
     * @param secret The secret string to derive the key from.
     * @return A SecretKeySpec for use with AES.
     * @throws Exception if key generation fails.
     */
    private static SecretKeySpec generateKey(String secret) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        KeySpec spec = new PBEKeySpec(secret.toCharArray(), STATIC_SALT.getBytes(), 65536, 256);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), ALGORITHM);
    }

    /**
     * The "Data Cloak" process. Encrypts a file, encodes it to Base64, and saves it as a fake .log file.
     * @param context The application context to access the cache directory.
     * @param inputFile The original file to cloak.
     * @param secret The secret number for encryption.
     * @return The cloaked file (fake .log), or null on failure.
     */
    public static File cloakFile(Context context, File inputFile, String secret) {
        File encryptedTempFile = null;
        File cloakedFile = null;
        FileInputStream fis = null;
        FileOutputStream fos = null;
        CipherOutputStream cos = null;
        FileInputStream encryptedFis = null;
        FileOutputStream cloakedFos = null;

        try {
            // Step 1: Encrypt the file to a temporary binary file
            encryptedTempFile = new File(context.getCacheDir(), "encrypted_" + System.currentTimeMillis() + ".tmp");
            SecretKeySpec secretKey = generateKey(secret);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            IvParameterSpec iv = new IvParameterSpec(STATIC_IV);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

            fis = new FileInputStream(inputFile);
            fos = new FileOutputStream(encryptedTempFile);
            cos = new CipherOutputStream(fos, cipher);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                cos.write(buffer, 0, bytesRead);
            }
            cos.flush();

            Log.d(TAG, "File encrypted successfully to temp file.");

            // Step 2: Base64 encode the encrypted temp file into the final cloaked .log file
            cloakedFile = new File(context.getCacheDir(), "cloaked_" + System.currentTimeMillis() + ".log");
            encryptedFis = new FileInputStream(encryptedTempFile);
            cloakedFos = new FileOutputStream(cloakedFile, false);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                OutputStream base64OutputStream = Base64.getEncoder().wrap(cloakedFos);
                buffer = new byte[8192];
                while ((bytesRead = encryptedFis.read(buffer)) != -1) {
                    base64OutputStream.write(buffer, 0, bytesRead);
                }
                base64OutputStream.close(); // Important to finalize encoding
            } else {
                // Fallback for older APIs
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                buffer = new byte[8192];
                while ((bytesRead = encryptedFis.read(buffer)) != -1) {
                    byteStream.write(buffer, 0, bytesRead);
                }
                String encodedString = android.util.Base64.encodeToString(byteStream.toByteArray(), android.util.Base64.NO_WRAP);
                cloakedFos.write(encodedString.getBytes());
            }

            Log.d(TAG, "Encrypted file has been Base64 encoded and cloaked as a .log file.");
            return cloakedFile;

        } catch (Exception e) {
            Log.e(TAG, "Cloaking process failed.", e);
            return null;
        } finally {
            // Cleanup streams
            try { if (fis != null) fis.close(); } catch (Exception e) { /* ignore */ }
            try { if (fos != null) fos.close(); } catch (Exception e) { /* ignore */ }
            try { if (cos != null) cos.close(); } catch (Exception e) { /* ignore */ }
            try { if (encryptedFis != null) encryptedFis.close(); } catch (Exception e) { /* ignore */ }
            try { if (cloakedFos != null) cloakedFos.close(); } catch (Exception e) { /* ignore */ }

            // Delete intermediate encrypted file
            if (encryptedTempFile != null && encryptedTempFile.exists()) {
                encryptedTempFile.delete();
            }
        }
    }

    /**
     * The "Restoration" process. Decodes a cloaked file from Base64 and decrypts it.
     * @param cloakedFile The downloaded fake .log file.
     * @param outputFile The final destination for the restored original file.
     * @param secret The secret number for decryption.
     * @return true if successful, false otherwise.
     */
    public static boolean restoreFile(File cloakedFile, File outputFile, String secret) {
        FileInputStream fis = null;
        CipherInputStream cis = null;
        FileOutputStream fos = null;
        InputStream base64InputStream = null;

        try {
            // Step 1: Base64 decode the cloaked file into a CipherInputStream
            SecretKeySpec secretKey = generateKey(secret);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            IvParameterSpec iv = new IvParameterSpec(STATIC_IV);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

            fis = new FileInputStream(cloakedFile);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                base64InputStream = Base64.getDecoder().wrap(fis);
            } else {
                // Fallback for older APIs
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    byteStream.write(buffer, 0, bytesRead);
                }
                byte[] decodedBytes = android.util.Base64.decode(byteStream.toByteArray(), android.util.Base64.NO_WRAP);
                base64InputStream = new ByteArrayInputStream(decodedBytes);
            }

            cis = new CipherInputStream(base64InputStream, cipher);

            // Step 2: Write the decrypted stream to the output file
            fos = new FileOutputStream(outputFile);
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = cis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
            fos.flush();

            Log.d(TAG, "File restored and decrypted successfully.");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Restoration process failed.", e);
            // If decryption fails, delete the partially written file
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
            return false;
        } finally {
            // Cleanup streams
            try { if (fis != null) fis.close(); } catch (Exception e) { /* ignore */ }
            try { if (cis != null) cis.close(); } catch (Exception e) { /* ignore */ }
            try { if (fos != null) fos.close(); } catch (Exception e) { /* ignore */ }
            // base64InputStream is handled by cis.close()
        }
    }
}