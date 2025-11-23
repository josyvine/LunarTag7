package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * LUNARTAG ROBOT - "AGGRESSIVE SCANNER" EDITION
 * 
 * 1. SCANS SCREEN for "Photo Ready to Send". If seen -> CLICK.
 * 2. SEMI-AUTO: Works every time.
 * 3. LOGS: Forces logs to UI.
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";

    // States
    private static final int STATE_IDLE = 0;
    private static final int STATE_WAITING_FOR_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0; // Instant
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        currentState = STATE_IDLE;
        
        // PROOF OF LIFE: If you don't see this Toast, the Service is NOT enabled in Settings.
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(this, "🤖 ROBOT CONNECTED", Toast.LENGTH_SHORT).show());
            
        performBroadcastLog("🔴 ROBOT STARTED. Scanning screen...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        
        // 1. Get Package Name
        String pkgName = "unknown";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // GLOBAL SCANNER: "Photo Ready to Send"
        // ====================================================================
        // We do this regardless of state. If we see the text, we click it.
        if (mode.equals("full")) {
            // Strategy A: Check the Event Text (Notifications/Toasts)
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence t : texts) {
                    if (t.toString().toLowerCase().contains("photo ready to send")) {
                        performBroadcastLog("🔔 Text Event Detected: " + t);
                        // Try to open notification content directly
                        if (event.getParcelableData() instanceof Notification) {
                            try {
                                ((Notification) event.getParcelableData()).contentIntent.send();
                                performBroadcastLog("🚀 Intent Sent!");
                                currentState = STATE_WAITING_FOR_SHARE_SHEET;
                                return;
                            } catch (Exception e) { /* ignore */ }
                        }
                    }
                }
            }

            // Strategy B: Check the Screen Content (Banners/Windows)
            if (root != null) {
                // Use the EXACT text from your screenshot
                List<AccessibilityNodeInfo> banners = root.findAccessibilityNodeInfosByText("Photo Ready to Send");
                if (!banners.isEmpty()) {
                    AccessibilityNodeInfo banner = banners.get(0);
                    performBroadcastLog("👁️ Banner Found on Screen!");
                    if (performClick(banner)) {
                        performBroadcastLog("✅ Banner Clicked.");
                        currentState = STATE_WAITING_FOR_SHARE_SHEET;
                        return;
                    } else {
                        // Try clicking the parent (The container)
                        if (banner.getParent() != null && performClick(banner.getParent())) {
                            performBroadcastLog("✅ Banner Parent Clicked.");
                            currentState = STATE_WAITING_FOR_SHARE_SHEET;
                            return;
                        }
                    }
                }
            }
        }

        // ====================================================================
        // SEMI-AUTO: ALWAYS ON
        // ====================================================================
        if (mode.equals("semi") && pkgName.contains("whatsapp")) {
            // If we just arrived in WhatsApp, reset state to searching
            if (currentState == STATE_IDLE || currentState == STATE_WAITING_FOR_SHARE_SHEET) {
                performBroadcastLog("⚡ Semi-Auto: WhatsApp Detected. Searching...");
                currentState = STATE_SEARCHING_GROUP;
            }
            // Allow flow to fall through to WhatsApp logic below
        }

        // ====================================================================
        // FULL-AUTO: SHARE SHEET
        // ====================================================================
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_SHARE_SHEET) {
            if (root != null) {
                // 1. Clone
                if (scanAndClick(root, "WhatsApp (Clone)")) {
                    performBroadcastLog("✅ Clone Selected.");
                    currentState = STATE_SEARCHING_GROUP;
                    return;
                }
                // 2. Main WhatsApp (Click to open dialog)
                if (!pkgName.contains("whatsapp")) {
                    if (scanAndClick(root, "WhatsApp")) {
                        performBroadcastLog("👆 Clicked WhatsApp. Waiting...");
                        return;
                    }
                }
                performScroll(root);
            }
        }

        // ====================================================================
        // WHATSAPP LOGIC (Semi & Full)
        // ====================================================================
        if (pkgName.contains("whatsapp")) {
            if (currentState == STATE_SEARCHING_GROUP) {
                handleGroupSearch(root, prefs);
            } else if (currentState == STATE_CLICKING_SEND) {
                handleFinalSend(root);
            }
        }
    }

    // ====================================================================
    // ACTIONS
    // ====================================================================

    private void handleGroupSearch(AccessibilityNodeInfo root, SharedPreferences prefs) {
        if (root == null) return;
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
        
        if (scanAndClick(root, targetGroup)) {
            performBroadcastLog("✅ Group Found: " + targetGroup);
            currentState = STATE_CLICKING_SEND;
            return;
        }
        performBroadcastLog("🔎 Searching group...");
        performScroll(root);
    }

    private void handleFinalSend(AccessibilityNodeInfo root) {
        if (root == null) return;
        
        boolean sent = false;
        if (scanAndClickContentDesc(root, "Send")) sent = true;
        if (!sent) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
            if (!nodes.isEmpty()) {
                performClick(nodes.get(0));
                sent = true;
            }
        }

        if (sent) {
            performBroadcastLog("🚀 SENT! Resetting.");
            currentState = STATE_IDLE;
        }
    }

    // ====================================================================
    // HELPERS
    // ====================================================================

    private void performBroadcastLog(String msg) {
        try {
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null || desc == null) return false;
        if (root.getContentDescription() != null && 
            root.getContentDescription().toString().equalsIgnoreCase(desc)) {
            return performClick(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanAndClickContentDesc(root.getChild(i), desc)) return true;
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 500);
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

    @Override
    public void onInterrupt() {}
}