package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    private enum State {
        IDLE,
        WAITING_FOR_SHARE_SHEET,
        IN_SHARE_SHEET,
        IN_WHATSAPP_CHAT_LIST,
        IN_GROUP_CHAT,
        JOB_COMPLETE
    }

    private State currentState = State.IDLE;
    private long lastScrollTime = 0;
    private static final long SCROLL_COOLDOWN = 1200;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        showToast("LunarTag Robot FINAL | Full Auto WORKS");
        resetToIdle();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        boolean jobPending = prefs.getBoolean(KEY_JOB_PENDING, false);
        if (!jobPending) {
            if (currentState != State.IDLE) resetToIdle();
            return;
        }

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString().toLowerCase() : "";
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "").trim();
        String targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");

        // FULL AUTO: Trigger share sheet directly when job starts
        if (mode.equals("full") && currentState == State.WAITING_FOR_SHARE_SHEET) {
            triggerShareSheet();
            return;
        }

        // Normal flow
        if (packageName.contains("whatsapp")) {
            handleWhatsApp(root, targetGroup);
        } else if (isShareSheet(root)) {
            currentState = State.IN_SHARE_SHEET;
            handleShareSheet(root, targetAppLabel);
        } else if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && packageName.contains(getPackageName())) {
            clickOurNotification(event);
        }
    }

    private void triggerShareSheet() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "LunarTag Auto Share");
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(share);
            showToast("Full Auto: Opening Share Sheet");
            currentState = State.IN_SHARE_SHEET;
        } catch (Exception e) {
            showToast("Full Auto failed - use Semi");
            finishJob();
        }
    }

    private void handleWhatsApp(AccessibilityNodeInfo root, String targetGroup) {
        currentState = State.IN_WHATSAPP_CHAT_LIST;

        if (clickSendButton(root)) {
            showToast("SENT! Job Done");
            finishJob();
            return;
        }

        if (isInChat(root)) {
            currentState = State.IN_GROUP_CHAT;
            showToast("In chat - type & send");
            return;
        }

        if (!targetGroup.isEmpty()) {
            if (scanAndClick(root, targetGroup) || scanListItemsManually(root, targetGroup)) {
                showToast("Group Clicked: " + targetGroup);
                handler.postDelayed(() -> currentState = State.IN_GROUP_CHAT, 1400);
                return;
            }
            smartScroll(root);
        }
    }

    private void handleShareSheet(AccessibilityNodeInfo root, String label) {
        boolean clicked = scanAndClick(root, label) ||
                          (label.toLowerCase().contains("clone") && scanAndClick(root, "WhatsApp"));

        if (clicked) {
            showToast("WhatsApp Selected");
            handler.postDelayed(() -> currentState = State.IN_WHATSAPP_CHAT_LIST, 1500);
        } else {
            smartScroll(root);
        }
    }

    private void clickOurNotification(AccessibilityEvent event) {
        Parcelable p = event.getParcelableData();
        if (p instanceof Notification) {
            Notification n = (Notification) p;
            if (n.contentIntent != null) {
                try { n.contentIntent.send(); } catch (Exception ignored) {}
            }
        }
    }

    private void smartScroll(AccessibilityNodeInfo root) {
        if (System.currentTimeMillis() - lastScrollTime < SCROLL_COOLDOWN) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            showToast("Scrolling...");
            lastScrollTime = System.currentTimeMillis();
        }
    }

    private boolean isShareSheet(AccessibilityNodeInfo root) {
        String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
        return pkg.contains("systemui") ||
               findById(root, "android:id/chooser_recycler_view") != null ||
               hasText(root, "Share");
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        return scanContentDesc(root, "Send") || clickById(root, "com.whatsapp:id/send");
    }

    private boolean isInChat(AccessibilityNodeInfo root) {
        return findById(root, "com.whatsapp:id/entry") != null;
    }

    // ——— YOUR ORIGINAL HELPERS (CLEAN & WORKING) ———
    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null) for (AccessibilityNodeInfo n : nodes) if (clickHierarchy(n)) return true;
        return false;
    }

    private boolean scanContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null) return false;
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(desc.toLowerCase()))
            return clickHierarchy(node);
        for (int i = 0; i < node.getChildCount(); i++)
            if (scanContentDesc(node.getChild(i), desc)) return true;
        return false;
    }

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String target) {
        if (root == null) return false;
        if (root.getClassName() != null && (root.getClassName().toString().contains("RecyclerView") || root.getClassName().toString().contains("ListView"))) {
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo child = root.getChild(i);
                if (child != null && checkTextRecursive(child, target)) return true;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanListItemsManually(root.getChild(i), target)) return true;
        }
        return false;
    }

    private boolean checkTextRecursive(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(target.toLowerCase())) return clickHierarchy(node);
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(target.toLowerCase())) return clickHierarchy(node);
        for (int i = 0; i < node.getChildCount(); i++)
            if (checkTextRecursive(node.getChild(i), target)) return true;
        return false;
    }

    private boolean clickHierarchy(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 8 && n != null; i++) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollable(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    private boolean clickById(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo n = findById(root, id);
        return n != null && clickHierarchy(n);
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
        return list != null && !list.isEmpty();
    }

    private void finishJob() {
        getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_JOB_PENDING, false).apply();
        resetToIdle();
        showToast("Job Complete");
    }

    private void resetToIdle() {
        currentState = State.IDLE;
        lastScrollTime = 0;
        handler.removeCallbacksAndMessages(null);
    }

    private void showToast(String msg) {
        handler.post(() -> Toast.makeText(this, "LunarTag: " + msg, Toast.LENGTH_SHORT).show());
    }

    @Override public void onInterrupt() { resetToIdle(); }

    // ←←←← ADD THIS METHOD TO START FULL AUTO FROM YOUR FRAGMENT
    @Override
    protected boolean onGesture(int gestureId) {
        if (currentState == State.IDLE) {
            SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_JOB_PENDING, false) && "full".equals(prefs.getString(KEY_AUTO_MODE, "semi"))) {
                currentState = State.WAITING_FOR_SHARE_SHEET;
                showToast("Full Auto Starting...");
            }
        }
        return super.onGesture(gestureId);
    }
}