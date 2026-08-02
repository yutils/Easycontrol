/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;

public final class PowerManager {
    private static final int USER_ACTIVITY_EVENT_OTHER = 0;

    private static IInterface manager;
    private static Method isScreenOnMethod = null;
    private static Method userActivityMethod = null;

    public static void init(IInterface m) {
        manager = m;
        if (manager == null) return;
        try {
            // Android 14+: isDisplayInteractive(int displayId)
            // Android 13-: isInteractive()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                isScreenOnMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
            } else {
                isScreenOnMethod = manager.getClass().getMethod("isInteractive");
            }
        } catch (Exception ignored) {
        }
        try {
            // Android 12+: userActivity(int displayId, long time, int event, int flags)
            // Android 11-: userActivity(long time, int event, int flags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                userActivityMethod = manager.getClass().getMethod("userActivity", int.class, long.class, int.class, int.class);
            } else {
                userActivityMethod = manager.getClass().getMethod("userActivity", long.class, int.class, int.class);
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean isScreenOn(int displayId) {
        if (isScreenOnMethod == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return (boolean) isScreenOnMethod.invoke(manager, displayId);
            }
            return (boolean) isScreenOnMethod.invoke(manager);
        } catch (Exception ignored) {
            return true;
        }
    }

    // 非侵入式防息屏：发送用户活动信号，不修改任何系统设置
    public static void userActivity(int displayId) {
        if (userActivityMethod == null) return;
        try {
            long time = SystemClock.uptimeMillis();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                userActivityMethod.invoke(manager, displayId, time, USER_ACTIVITY_EVENT_OTHER, 0);
            } else {
                userActivityMethod.invoke(manager, time, USER_ACTIVITY_EVENT_OTHER, 0);
            }
        } catch (Exception ignored) {
        }
    }

}
