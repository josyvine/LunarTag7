package com.safevoice.app.utils;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A helper class for interacting with the mobilefacenet.tflite model.
 * It handles loading the model, pre-processing input images, running inference,
 * and calculating the similarity between two faces.
 */
public class FaceVerifier {

    private static final String TAG = "FaceVerifier";
    private static final String MODEL_FILE = "mobilefacenet.tflite";

    // Model-specific configuration
    private static final int INPUT_IMAGE_SIZE = 112; // The model expects 112x112 pixel images
    private static final int EMBEDDING_SIZE = 128;   // The model outputs a 128-float embedding vector
    private static final int BYTES_PER_CHANNEL = 4;  // Float size

    private final Interpreter tflite;

    /**
     * Loads the TFLite model from the assets folder.
     * Throws an IOException if the model file cannot be loaded.
     *
     * @param context The application context.
     * @throws IOException If the model file is not found or cannot be loaded.
     */
    public FaceVerifier(Context context) throws IOException {
        this.tflite = new Interpreter(loadModelFile(context.getAssets()));
    }

    /**
     * Generates a facial embedding from a given Bitmap.
     *
     * @param bitmap The input image, which should contain a cropped face.
     * @return A float array of size 128 representing the facial embedding.
     */
    public float[] getFaceEmbedding(Bitmap bitmap) {
        // 1. Pre-process the image: resize and convert to ByteBuffer
        ByteBuffer inputBuffer = preprocessImage(bitmap);

        // 2. Prepare the output buffer
        float[][] outputEmbedding = new float[1][EMBEDDING_SIZE];

        // 3. Run inference with the TFLite model
        tflite.run(inputBuffer, outputEmbedding);

        // 4. Return the resulting embedding vector
        return outputEmbedding[0];
    }

    /**
     * Calculates the cosine similarity between two facial embeddings.
     * The result ranges from -1 (completely different) to 1 (identical).
     * A common threshold for a "match" is > 0.8.
     *
     * @param emb1 The first embedding.
     * @param emb2 The second embedding.
     * @return The cosine similarity score.
     */
    public double calculateSimilarity(float[] emb1, float[] emb2) {
        if (emb1 == null || emb2 == null || emb1.length != EMBEDDING_SIZE || emb2.length != EMBEDDING_SIZE) {
            return -1.0; // Invalid input
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < EMBEDDING_SIZE; i++) {
            dotProduct += emb1[i] * emb2[i];
            normA += emb1[i] * emb1[i];
            normB += emb2[i] * emb2[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }


    /**
     * Memory-maps the TFLite model file from the assets folder.
     */
    private MappedByteBuffer loadModelFile(AssetManager assetManager) throws IOException {
        AssetFileDescriptor fileDescriptor = assetManager.openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Pre-processes the input bitmap to match the model's requirements.
     * - Resizes the image to 112x112.
     * - Normalizes pixel values to be between -1 and 1.
     * - Converts the image data into a ByteBuffer.
     */
    private ByteBuffer preprocessImage(Bitmap bitmap) {
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_IMAGE_SIZE, INPUT_IMAGE_SIZE, true);

        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE * 3 * BYTES_PER_CHANNEL);
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[INPUT_IMAGE_SIZE * INPUT_IMAGE_SIZE];
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.getWidth(), 0, 0, resizedBitmap.getWidth(), resizedBitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < INPUT_IMAGE_SIZE; ++i) {
            for (int j = 0; j < INPUT_IMAGE_SIZE; ++j) {
                final int val = intValues[pixel++];
                // Normalize the pixel values from [0, 255] to [-1, 1] as required by many face models.
                byteBuffer.putFloat((((val >> 16) & 0xFF) - 127.5f) / 128.0f);
                byteBuffer.putFloat((((val >> 8) & 0xFF) - 127.5f) / 128.0f);
                byteBuffer.putFloat(((val & 0xFF) - 127.5f) / 128.0f);
            }
        }
        return byteBuffer;
    }
            }
