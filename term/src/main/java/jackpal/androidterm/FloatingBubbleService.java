package jackpal.androidterm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

/**
 * Floating keep-alive bubble: a small draggable terminal icon shown over
 * other apps while terminal sessions are running. While it is alive the
 * service stays in the foreground and holds a partial wake lock, so shell
 * processes keep running when the app is backgrounded or the screen is
 * off. Dragging the bubble onto the remove target at the bottom of the
 * screen dismisses it (sessions themselves are not touched).
 */
public class FloatingBubbleService extends Service {
    public static final String PREF_FLOATING_BUBBLE = "floating_bubble";

    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "floating_bubble";

    private static boolean sDismissed = false;

    private WindowManager mWindowManager;
    private ImageView mBubble;
    private WindowManager.LayoutParams mBubbleParams;
    private TextView mRemoveTarget;
    private WindowManager.LayoutParams mRemoveParams;
    private PowerManager.WakeLock mWakeLock;

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_FLOATING_BUBBLE, true);
    }

    public static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static void refresh(Context context, boolean sessions_running) {
        if (!sessions_running) {
            sDismissed = false;
            context.stopService(new Intent(context, FloatingBubbleService.class));
            return;
        }
        if (sDismissed || !isEnabled(context) || !canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, FloatingBubbleService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void dismiss() {
        sDismissed = true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getText(R.string.bubble_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.bubble_channel_description));
        nm.createNotificationChannel(channel);

        Intent open = new Intent(this, Term.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_service_notification_icon)
                .setContentTitle(getText(R.string.bubble_notification_title))
                .setContentText(getText(R.string.bubble_notification_text))
                .setContentIntent(pending)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TermDebug.LOG_TAG + ":bubble");
        mWakeLock.acquire();

        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        showBubble();
    }

    private void showBubble() {
        int size = dp(56);
        mBubble = new ImageView(this);
        mBubble.setImageResource(R.drawable.ic_terminal);
        mBubble.setImageTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
        mBubble.setBackgroundResource(R.drawable.bg_bubble);
        int pad = dp(14);
        mBubble.setPadding(pad, pad, pad, pad);

        mBubbleParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mBubbleParams.x = metrics.widthPixels - size - dp(16);
        mBubbleParams.y = metrics.heightPixels / 3;

        mBubble.setOnTouchListener(new BubbleTouchListener());
        mWindowManager.addView(mBubble, mBubbleParams);
    }

    private void showRemoveTarget() {
        if (mRemoveTarget != null) {
            return;
        }
        int size = dp(72);
        mRemoveTarget = new TextView(this);
        mRemoveTarget.setText("✕");
        mRemoveTarget.setTextSize(28);
        mRemoveTarget.setGravity(Gravity.CENTER);
        mRemoveTarget.setBackgroundResource(R.drawable.bg_bubble_remove);
        mRemoveParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mRemoveParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        mRemoveParams.y = dp(48);
        mWindowManager.addView(mRemoveTarget, mRemoveParams);
    }

    private void hideRemoveTarget() {
        if (mRemoveTarget != null) {
            mWindowManager.removeView(mRemoveTarget);
            mRemoveTarget = null;
        }
    }

    private boolean isOverRemoveTarget(float raw_x, float raw_y) {
        if (mRemoveTarget == null) {
            return false;
        }
        int[] location = new int[2];
        mRemoveTarget.getLocationOnScreen(location);
        return raw_x >= location[0] && raw_x <= location[0] + mRemoveTarget.getWidth()
                && raw_y >= location[1] && raw_y <= location[1] + mRemoveTarget.getHeight();
    }

    private class BubbleTouchListener implements View.OnTouchListener {
        private int start_x;
        private int start_y;
        private float touch_x;
        private float touch_y;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    start_x = mBubbleParams.x;
                    start_y = mBubbleParams.y;
                    touch_x = event.getRawX();
                    touch_y = event.getRawY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - touch_x);
                    int dy = (int) (event.getRawY() - touch_y);
                    if (!dragging && Math.abs(dx) + Math.abs(dy) > dp(8)) {
                        dragging = true;
                        showRemoveTarget();
                    }
                    if (dragging) {
                        mBubbleParams.x = start_x + dx;
                        mBubbleParams.y = start_y + dy;
                        mWindowManager.updateViewLayout(mBubble, mBubbleParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean over_target = dragging && isOverRemoveTarget(event.getRawX(), event.getRawY());
                    hideRemoveTarget();
                    if (over_target) {
                        dismiss();
                        stopSelf();
                    } else if (!dragging) {
                        Intent open = new Intent(FloatingBubbleService.this, Term.class);
                        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(open);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    @Override
    public void onDestroy() {
        hideRemoveTarget();
        if (mBubble != null) {
            mWindowManager.removeView(mBubble);
            mBubble = null;
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
