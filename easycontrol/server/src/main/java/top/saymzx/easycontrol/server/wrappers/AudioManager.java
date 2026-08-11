/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.os.IInterface;

import java.lang.reflect.Method;

// 通过 audio 服务(android.media.IAudioService)操作系统音量，
// 与系统设置音量使用同一路径，兼容各厂商ROM(部分ROM拦截 media_session 命令但不拦截此处)
public final class AudioManager {
    private static IInterface manager;
    private static Method setStreamVolumeMethod = null;
    private static Method getStreamMaxVolumeMethod = null;

    public static void init(IInterface m) {
        manager = m;
        if (manager == null) return;
        try {
            setStreamVolumeMethod = manager.getClass().getMethod("setStreamVolume", int.class, int.class, int.class, String.class);
        } catch (Exception ignored) {
        }
        try {
            getStreamMaxVolumeMethod = manager.getClass().getMethod("getStreamMaxVolume", int.class);
        } catch (Exception ignored) {
        }
    }

    public static int getStreamMaxVolume(int streamType) {
        if (getStreamMaxVolumeMethod == null || manager == null) return -1;
        try {
            return (Integer) getStreamMaxVolumeMethod.invoke(manager, streamType);
        } catch (Exception ignored) {
            return -1;
        }
    }

    // 设置流音量(绝对音量)，调用成功返回true
    public static boolean setStreamVolume(int streamType, int index) {
        if (setStreamVolumeMethod == null || manager == null) return false;
        try {
            setStreamVolumeMethod.invoke(manager, streamType, index, 0, "com.android.shell");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
