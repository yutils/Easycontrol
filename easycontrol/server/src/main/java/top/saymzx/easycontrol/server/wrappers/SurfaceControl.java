/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@SuppressLint("PrivateApi")
public final class SurfaceControl {

    private static Class<?> CLASS;

    private static Method getBuiltInDisplayMethod = null;
    private static Method setDisplayPowerModeMethod = null;
    private static Method getPhysicalDisplayTokenMethod = null;
    private static Method getPhysicalDisplayIdsMethod = null;
    private static Method createDisplayMethod = null;
    private static Method destroyDisplayMethod = null;
    private static Class<?> displayControlClass = null;

    // Android 15移除了SurfaceControl的静态方法setDisplaySurface/setDisplayProjection/setDisplayLayerStack/openTransaction/closeTransaction
    // 改用SurfaceControl.Transaction对象。Android 14及以下仍使用静态方法（兼容性更好）。
    private static boolean useTransaction = false;
    private static Class<?> transactionClass = null;
    private static Method transactionSetDisplaySurface = null;
    private static Method transactionSetDisplayProjection = null;
    private static Method transactionSetDisplayLayerStack = null;
    private static Method transactionApply = null;
    private static Method transactionClose = null;
    private static Object currentTransaction = null;

    public static void init() throws ClassNotFoundException {
        CLASS = Class.forName("android.view.SurfaceControl");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
                    getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
                } catch (Exception ignored) {
                    loadDisplayControl();
                    getPhysicalDisplayIdsMethod = displayControlClass.getMethod("getPhysicalDisplayIds");
                    getPhysicalDisplayTokenMethod = displayControlClass.getMethod("getPhysicalDisplayToken", long.class);
                }
            }
            setDisplayPowerModeMethod = CLASS.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        } catch (Exception ignored) {
        }
        // Android 15移除了SurfaceControl.createDisplay/destroyDisplay，改用DisplayControl.createVirtualDisplay/destroyVirtualDisplay
        try {
            createDisplayMethod = CLASS.getMethod("createDisplay", String.class, boolean.class);
            destroyDisplayMethod = CLASS.getMethod("destroyDisplay", IBinder.class);
        } catch (Exception ignored) {
            try {
                loadDisplayControl();
                createDisplayMethod = displayControlClass.getMethod("createVirtualDisplay", String.class, boolean.class);
                destroyDisplayMethod = displayControlClass.getMethod("destroyVirtualDisplay", IBinder.class);
            } catch (Exception ignored2) {
            }
        }
        // 仅在Android 15+上使用Transaction对象（静态方法已被移除）
        // Android 14及以下仍使用静态openTransaction/closeTransaction，兼容性最佳
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                transactionClass = Class.forName("android.view.SurfaceControl$Transaction");
                transactionSetDisplaySurface = transactionClass.getMethod("setDisplaySurface", IBinder.class, Surface.class);
                transactionSetDisplayProjection = transactionClass.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class);
                transactionSetDisplayLayerStack = transactionClass.getMethod("setDisplayLayerStack", IBinder.class, int.class);
                transactionApply = transactionClass.getMethod("apply");
                transactionClose = transactionClass.getMethod("close");
                useTransaction = true;
            } catch (Exception ignored) {
            }
        }
    }

    // 安卓14之后部分函数转移到了DisplayControl
    @SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi"})
    private static void loadDisplayControl() throws Exception {
        if (displayControlClass != null) return;
        try {
            Method createClassLoaderMethod = Class.forName("com.android.internal.os.ClassLoaderFactory").getDeclaredMethod("createClassLoader", String.class, String.class, String.class, ClassLoader.class, int.class, boolean.class, String.class);
            // 使用SYSTEMSERVERCLASSPATH环境变量获取系统服务类路径，兼容不同设备
            String systemServerClasspath = android.system.Os.getenv("SYSTEMSERVERCLASSPATH");
            if (systemServerClasspath == null || systemServerClasspath.isEmpty())
                systemServerClasspath = "/system/framework/services.jar";
            ClassLoader classLoader = (ClassLoader) createClassLoaderMethod.invoke(null, systemServerClasspath, null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");
            // 加载android_servers原生库，否则DisplayControl的native方法会UnsatisfiedLinkError
            Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadMethod.setAccessible(true);
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");
        } catch (Throwable ignored) {
        }
        if (displayControlClass == null) throw new Exception("Failed to load DisplayControl class");
    }

    public static void openTransaction() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (useTransaction) {
            try {
                currentTransaction = transactionClass.getConstructor().newInstance();
                return;
            } catch (Exception ignored) {
                // Transaction创建失败，回退到静态方法
            }
        }
        CLASS.getMethod("openTransaction").invoke(null);
    }

    public static void closeTransaction() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (currentTransaction != null) {
            try {
                transactionApply.invoke(currentTransaction);
                transactionClose.invoke(currentTransaction);
            } catch (Exception ignored) {
            } finally {
                currentTransaction = null;
            }
            return;
        }
        CLASS.getMethod("closeTransaction").invoke(null);
    }

    public static void setDisplayProjection(IBinder displayToken, int orientation, Rect layerStackRect, Rect displayRect) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (currentTransaction != null && transactionSetDisplayProjection != null) {
            transactionSetDisplayProjection.invoke(currentTransaction, displayToken, orientation, layerStackRect, displayRect);
            return;
        }
        CLASS.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class).invoke(null, displayToken, orientation, layerStackRect, displayRect);
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (currentTransaction != null && transactionSetDisplayLayerStack != null) {
            transactionSetDisplayLayerStack.invoke(currentTransaction, displayToken, layerStack);
            return;
        }
        CLASS.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, displayToken, layerStack);
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        if (currentTransaction != null && transactionSetDisplaySurface != null) {
            transactionSetDisplaySurface.invoke(currentTransaction, displayToken, surface);
            return;
        }
        CLASS.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, displayToken, surface);
    }

    public static IBinder createDisplay(String name, boolean secure) throws InvocationTargetException, IllegalAccessException {
        return (IBinder) createDisplayMethod.invoke(null, name, secure);
    }

    public static void destroyDisplay(IBinder displayToken) throws InvocationTargetException, IllegalAccessException {
        destroyDisplayMethod.invoke(null, displayToken);
    }

    public static IBinder getBuiltInDisplay() {
        try {
            if (getBuiltInDisplayMethod == null)
                getBuiltInDisplayMethod = CLASS.getMethod("getBuiltInDisplay", int.class);
            return (IBinder) getBuiltInDisplayMethod.invoke(null, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        if (getPhysicalDisplayTokenMethod == null) return null;
        try {
            return (IBinder) getPhysicalDisplayTokenMethod.invoke(null, physicalDisplayId);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        if (getPhysicalDisplayIdsMethod == null) return null;
        try {
            return (long[]) getPhysicalDisplayIdsMethod.invoke(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        if (setDisplayPowerModeMethod == null) return;
        try {
            setDisplayPowerModeMethod.invoke(null, displayToken, mode);
        } catch (Exception ignored) {
        }
    }

}
