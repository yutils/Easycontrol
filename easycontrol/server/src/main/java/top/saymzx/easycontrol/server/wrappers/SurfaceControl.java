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

    // ===== 截图：SurfaceControl.captureDisplay(息屏/折叠时screencap会拍到黑屏，
    // 改截shadow display的合成输出，与投屏画面一致) =====
    private static final int FLAG_CAPTURE_CONTENT = 0x00000004;
    private static Method captureDisplayMethod = null;
    private static boolean captureNeedsTransaction = false;
    private static Method shbGetHardwareBuffer = null;
    private static Method shbGetColorSpace = null;
    private static Method setDisplayFlagsMethod = null;
    private static Method transactionSetDisplayFlags = null;

    private static void loadCaptureMethods() {
        if (captureDisplayMethod != null) return;
        try {
            setDisplayFlagsMethod = CLASS.getMethod("setDisplayFlags", IBinder.class, int.class, int.class);
        } catch (Exception ignored) {
        }
        if (transactionClass != null) {
            try {
                transactionSetDisplayFlags = transactionClass.getMethod("setDisplayFlags", IBinder.class, int.class, int.class);
            } catch (Exception ignored) {
            }
        }
        // 各版本 captureDisplay 签名不同：API28无usage/rotation，API34新增transactionId，
        // API35(Android15)静态方法移除，改为第一个参数传 Transaction 对象
        Class<?>[][] signatures = new Class<?>[][]{
                {IBinder.class, int.class, int.class, int.class, Rect.class, int.class, boolean.class},
                {IBinder.class, int.class, int.class, int.class, long.class, Rect.class, int.class, boolean.class, int.class},
                {IBinder.class, int.class, int.class, int.class, long.class, Rect.class, int.class, boolean.class, int.class, long.class},
        };
        for (Class<?>[] signature : signatures) {
            try {
                captureDisplayMethod = CLASS.getMethod("captureDisplay", signature);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (captureDisplayMethod == null && transactionClass != null) {
            // Android 15: captureDisplay(Transaction, IBinder, int, int, int, long, Rect, int, boolean, int, long)
            try {
                captureDisplayMethod = CLASS.getMethod("captureDisplay", transactionClass, IBinder.class, int.class, int.class, int.class, long.class, Rect.class, int.class, boolean.class, int.class, long.class);
                captureNeedsTransaction = true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (captureDisplayMethod == null) return;
        try {
            Class<?> shbClass = Class.forName("android.view.SurfaceControl$ScreenshotHardwareBuffer");
            shbGetHardwareBuffer = shbClass.getMethod("getHardwareBuffer");
            shbGetColorSpace = shbClass.getMethod("getColorSpace");
        } catch (Exception ignored) {
        }
    }

    // 虚拟显示默认不可被 captureDisplay 截取，先置上 CAPTURE_CONTENT 标志(尽力而为)
    private static void markDisplayCapturable(IBinder displayToken) {
        try {
            if (useTransaction && transactionSetDisplayFlags != null) {
                Object tx = transactionClass.getConstructor().newInstance();
                transactionSetDisplayFlags.invoke(tx, displayToken, FLAG_CAPTURE_CONTENT, FLAG_CAPTURE_CONTENT);
                transactionApply.invoke(tx);
                transactionClose.invoke(tx);
                return;
            }
            if (setDisplayFlagsMethod != null) {
                setDisplayFlagsMethod.invoke(null, displayToken, FLAG_CAPTURE_CONTENT, FLAG_CAPTURE_CONTENT);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 截取指定显示令牌(投屏shadow display)的当前画面为PNG。返回null表示失败，调用方回退screencap。
     */
    public static byte[] captureDisplayPng(IBinder displayToken, int width, int height) {
        if (displayToken == null) return null;
        loadCaptureMethods();
        if (captureDisplayMethod == null || shbGetHardwareBuffer == null || shbGetColorSpace == null) return null;
        markDisplayCapturable(displayToken);
        Object transaction = null;
        try {
            int usage = 1 << 16; // USAGE_GPU_SAMPLED_IMAGE
            Object shb;
            int paramCount = captureDisplayMethod.getParameterTypes().length;
            if (captureNeedsTransaction) {
                // Android 15: 传 Transaction 对象
                transaction = transactionClass.getConstructor().newInstance();
                shb = captureDisplayMethod.invoke(null, transaction, displayToken, width, height, 1, (long) usage, (Rect) null, 1, true, 0, 0L);
            } else if (paramCount == 7) {
                shb = captureDisplayMethod.invoke(null, displayToken, width, height, 1, (Rect) null, 1, true);
            } else if (paramCount == 9) {
                shb = captureDisplayMethod.invoke(null, displayToken, width, height, 1, (long) usage, (Rect) null, 1, true, 0);
            } else {
                shb = captureDisplayMethod.invoke(null, displayToken, width, height, 1, (long) usage, (Rect) null, 1, true, 0, 0L);
            }
            if (shb == null) return null;
            android.hardware.HardwareBuffer hardwareBuffer = (android.hardware.HardwareBuffer) shbGetHardwareBuffer.invoke(shb);
            android.graphics.ColorSpace colorSpace = (android.graphics.ColorSpace) shbGetColorSpace.invoke(shb);
            if (hardwareBuffer == null) return null;
            try {
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                if (bitmap == null) return null;
                // wrap的bitmap与hardware buffer共享内存，需拷贝为独立可变位图再压缩
                android.graphics.Bitmap copy = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                bitmap.recycle();
                if (copy == null) return null;
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                copy.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
                copy.recycle();
                return output.toByteArray();
            } finally {
                hardwareBuffer.close();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (transaction != null) {
                try {
                    transactionClose.invoke(transaction);
                } catch (Exception ignored) {
                }
            }
        }
    }

    // 截图用：返回默认显示(displayId 0)对应的物理显示ID。
    // DisplayInfo.uniqueId 形如 "local:4630946592180194435"，取冒号后的数字即是 screencap -d 需要的物理ID。
    // 多屏折叠设备上 screencap 默认会选到息屏的那块(黑图)，必须显式指定 -d。
    // 返回 -1 表示无法确定(单屏设备无需指定，走默认路径)。
    public static long getDefaultDisplayPhysicalId() {
        try {
            Object manager = Class.forName("android.hardware.display.DisplayManagerGlobal")
                    .getMethod("getInstance").invoke(null);
            Object info = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, 0);
            if (info == null) return -1;
            java.lang.reflect.Field uniqueIdField = info.getClass().getDeclaredField("uniqueId");
            uniqueIdField.setAccessible(true);
            String uniqueId = (String) uniqueIdField.get(info);
            if (uniqueId == null) return -1;
            int idx = uniqueId.lastIndexOf(':');
            String num = (idx >= 0) ? uniqueId.substring(idx + 1) : uniqueId;
            return Long.parseLong(num);
        } catch (Throwable ignored) {
            return -1;
        }
    }

}
