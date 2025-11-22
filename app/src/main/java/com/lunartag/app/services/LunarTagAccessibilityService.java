package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * FIXED LOGIC: DIRECT DETECTION
 * 1. Notification: Scans specifically for "Photo Ready to Send".
 * 2. Share Sheet: Only clicks "WhatsApp" if package is System/Android.
 * 3. WhatsApp: Clicks Send/Group as normal.
 * 4. Home Screen: IGNORED (Safe).
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode"; // "semi" or "full"
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    private boolean isScrolling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // Listen to everything so we don't miss the pop-up
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. CHECK IF JOB IS PENDING
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_JOB_PENDING, false)) return;

        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetApp = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

        // Get Package Name safely
        String pkg = (event.getPackageName() != null) ? event.getPackageName().toString().toLowerCase() : "";
        AccessibilityNodeInfo root = getRootInActiveWindow();

        // =================================================================
        // TRIGGER 1: THE NOTIFICATION (Fixes "Nothing works")
        // =================================================================
        // We look for the specific text in your screenshot: "Photo Ready to Send"
        if (mode.equals("full")) {
            // Method A: Standard Notification Event
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                if (pkg.contains(getPackageName())) {
                    Parcelable data = event.getParcelableData();
                    if (data instanceof Notification) {
                        try {
                            ((Notification) data).contentIntent.send();
                            return;
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
            }

            // Method B: Heads-Up Display (The banner in your screenshot)
            // If the notification appears as a view on screen, we click it directly.
            if (root != null) {
                // Scan for the text visible in your screenshot
                List<AccessibilityNodeInfo> notifNodes = root.findAccessibilityNodeInfosByText("Photo Ready to Send");
                if (!notifNodes.isEmpty()) {
                    showDebugToast("🤖 Clicking Notification Banner...");
                    if (performClick(notifNodes.get(0))) return;
                }
            }
        }

        if (root == null) return;

        // =================================================================
        // TRIGGER 2: SHARE SHEET (Safe Mode)
        // =================================================================
        // We ONLY click "WhatsApp" if the package is "android" (System) or "resolver".
        // We NEVER click if the package is a Launcher/Home.
        
        boolean isSystemShare = pkg.equals("android") || 
                                pkg.contains("chooser") || 
                                pkg.contains("resolver") ||
                                pkg.contains("share"); // Some custom UIs

        // DO NOT RUN THIS ON LAUNCHER (Home Screen Protection)
        if (mode.equals("full") && isSystemShare && !pkg.contains("launcher") && !pkg.contains("home")) {
            
            // 1. Click Target App
            if (scanAndClick(root, targetApp)) return;
            
            // 2. Click Clone
            if (targetApp.toLowerCase().contains("clone") && scanAndClick(root, "WhatsApp")) return;

            // 3. Scroll (Only in Share Sheet)
            performScroll(root);
            return;
        }

        // =================================================================
        // TRIGGER 3: INSIDE WHATSAPP (Semi & Full)
        // =================================================================
        if (pkg.contains("whatsapp")) {
            
            // Priority: Send Button
            if (scanAndClickContentDesc(root, "Send")) {
                showDebugToast("🤖 Message Sent.");
                prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                return;
            }

            // Find Group
            if (!targetGroup.isEmpty()) {
                if (scanAndClick(root, targetGroup)) return;
                if (scanListItemsManually(root, targetGroup)) return;
                performScroll(root);
            }
        }
    }

    // -----------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null) return false;
        if (root.getContentDescription() != null && 
            root.getContentDescription().toString().equalsIgnoreCase(desc)) {
            return performClick(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanAndClickContentDesc(root.getChild(i), desc)) return true;
        }
        return false;
    }

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String targetText) {
        if (root == null) return false;
        // Only dig deep into containers
        if (root.getClassName() != null && 
           (root.getClassName().toString().contains("RecyclerView") || 
            root.getClassName().toString().contains("ListView") ||
            root.getClassName().toString().contains("ViewGroup"))) {
            for (int i = 0; i < root.getChildCount(); i++) {
                if (recursiveCheck(root.getChild(i), targetText)) return true;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanListItemsManually(root.getChild(i), targetText)) return true;
        }
        return false;
    }

    private boolean recursiveCheck(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        if ((node.getText() != null && node.getText().toString().toLowerCase().contains(target.toLowerCase())) ||
            (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(target.toLowerCase()))) {
            return performClick(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveCheck(node.getChild(i), target)) return true;
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int attempts = 0;
        while (target != null && attempts < 6) {
            if (target.isClickable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            target = target.getParent();
            attempts++;
        }
        return false;
    }

    private void performScroll(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            isScrolling = true;
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 1500);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findScrollable(node.getChild(i));
            if (res != null) return res;
        }
        return null;
    }

    private void showDebugToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override public void onInterrupt() {}
}