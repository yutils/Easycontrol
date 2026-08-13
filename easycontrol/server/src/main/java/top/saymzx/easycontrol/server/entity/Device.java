/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.entity;

import android.content.IOnPrimaryClipChangedListener;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Pair;
import android.view.Display;
import android.view.IRotationWatcher;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.saymzx.easycontrol.server.helper.ControlPacket;
import top.saymzx.easycontrol.server.helper.VideoEncode;
import top.saymzx.easycontrol.server.wrappers.ClipboardManager;
import top.saymzx.easycontrol.server.wrappers.DisplayManager;
import top.saymzx.easycontrol.server.wrappers.InputManager;
import top.saymzx.easycontrol.server.wrappers.PowerManager;
import top.saymzx.easycontrol.server.wrappers.SurfaceControl;
import top.saymzx.easycontrol.server.wrappers.WindowManager;
import top.saymzx.easycontrol.server.wrappers.AudioManager;

public final class Device {
    private static int displayId = Display.DEFAULT_DISPLAY;
    private static VirtualDisplay virtualDisplay;
    public static Pair<Integer, Integer> realSize;
    public static DisplayInfo displayInfo;
    public static Pair<Integer, Integer> videoSize;
    private static boolean needReset = false;

    public static void init() throws Exception {
        // 若启动单个应用则需创建虚拟Dispaly
        if (!Objects.equals(Options.startApp, "")) {
            try {
                virtualDisplay = DisplayManager.createVirtualDisplay();
                displayId = virtualDisplay.getDisplay().getDisplayId();
                startAndMoveAppToVirtualDisplay();
                needReset = true;
            } catch (Exception e) {
                // 无法移入虚拟显示（如桌面/系统应用，或系统不支持虚拟显示），回退为普通全屏镜像
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }
                displayId = Display.DEFAULT_DISPLAY;
                Options.startApp = "";
                // needReset 保持 false：release() 时 fallbackResolution() 被跳过，不再触碰失败的虚拟显示
            }
        }
        getRealSize();
        updateSize();
        // 旋转监听
        setRotationListener();
        // 剪切板监听
        if (Options.listenerClip) setClipBoardListener();
        // 防息屏改为非侵入式userActivity，由executeVideoOut定期调用，无需在此初始化
    }

    // 打开并移动应用
    private static void startAndMoveAppToVirtualDisplay() throws IOException, InterruptedException {
        int appStackId = getAppStackId();
        if (appStackId == -1) {
            Device.execReadOutput("monkey -p " + Options.startApp + " -c android.intent.category.LAUNCHER 1");
            appStackId = getAppStackId();
        }
        if (appStackId == -1) throw new IOException("error app");
        Device.execReadOutput("am display move-stack " + appStackId + " " + displayId);
    }

    private static int getAppStackId() throws IOException, InterruptedException {
        String amStackList = Device.execReadOutput("am stack list");
        Matcher m = Pattern.compile("taskId=([0-9]+): " + Options.startApp).matcher(amStackList);
        if (!m.find()) return -1;
        return Integer.parseInt(Objects.requireNonNull(m.group(1)));
    }

    private static void getRealSize() throws IOException, InterruptedException {
        String output = Device.execReadOutput("wm size");
        String patStr;
        // 查看当前分辨率
        patStr = (output.contains("Override") ? "Override" : "Physical") + " size: (\\d+)x(\\d+)";
        Matcher matcher = Pattern.compile(patStr).matcher(output);
        if (matcher.find()) {
            String width = matcher.group(1);
            String height = matcher.group(2);
            if (width == null || height == null) return;
            realSize = new Pair<>(Integer.parseInt(width), Integer.parseInt(height));
        }
    }

    private static void updateSize() {
        displayInfo = DisplayManager.getDisplayInfo(displayId);
        boolean isPortrait = displayInfo.width < displayInfo.height;
        int major = isPortrait ? displayInfo.height : displayInfo.width;
        int minor = isPortrait ? displayInfo.width : displayInfo.height;
        if (major > Options.maxSize) {
            minor = minor * Options.maxSize / major;
            major = Options.maxSize;
        }
        // 某些厂商实现的解码器只接受16的倍数，所以需要缩放至最近参数
        minor = minor + 8 & ~15;
        major = major + 8 & ~15;
        videoSize = isPortrait ? new Pair<>(minor, major) : new Pair<>(major, minor);
    }

    // 修改分辨率
    public static void changeResolution(float targetRatio) {
        try {
            // 安全阈值(长宽比最多三倍)
            if (targetRatio > 3 || targetRatio < 0.34) return;
            // 没有获取到真实分辨率
            if (realSize == null) return;

            float originalRatio = (float) realSize.first / realSize.second;
            // 计算变化比率
            float ratioChange = targetRatio / originalRatio;
            // 根据比率变化确定新的长和宽
            int newWidth, newHeight;
            if (ratioChange > 1) {
                newWidth = realSize.first;
                newHeight = (int) (realSize.second / ratioChange);
            } else {
                newWidth = (int) (realSize.first * ratioChange);
                newHeight = realSize.second;
            }
            changeResolution(newWidth, newHeight);
        } catch (Exception ignored) {
        }
    }

    // 修改分辨率
    public static void changeResolution(int width, int height) {
        try {
            float originalRatio = (float) realSize.first / realSize.second;
            // 安全阈值(长宽比最多三倍)
            if (originalRatio > 3 || originalRatio < 0.34) return;

            needReset = true;

            // 缩放至16倍数
            width = width + 8 & ~15;
            height = height + 8 & ~15;
            // 避免分辨率相同，会触发安全机制导致系统崩溃
            if (width == height) width -= 16;

            // 修改分辨率
            if (virtualDisplay != null) virtualDisplay.resize(width, height, displayInfo.density);
            else Device.execReadOutput("wm size " + width + "x" + height);

            // 更新，需延迟一段时间
            Thread.sleep(200);
            updateSize();
            VideoEncode.isHasChangeConfig = true;
        } catch (Exception ignored) {
        }
    }

    // 恢复分辨率
    public static void fallbackResolution() throws IOException, InterruptedException {
        if (Device.needReset) {
            if (virtualDisplay != null) {
                int appStackId = getAppStackId();
                // 应用仍在栈中则先移回默认显示再释放虚拟显示，避免被镜像的应用被销毁
                if (appStackId != -1)
                    Device.execReadOutput("am display move-stack " + appStackId + " " + Display.DEFAULT_DISPLAY);
                virtualDisplay.release();
            } else {
                if (Device.realSize != null)
                    Device.execReadOutput("wm size " + Device.realSize.first + "x" + Device.realSize.second);
                else Device.execReadOutput("wm size reset");
            }
        }
    }

    private static String nowClipboardText = "";

    public static void setClipBoardListener() {
        ClipboardManager.addPrimaryClipChangedListener(new IOnPrimaryClipChangedListener.Stub() {
            public void dispatchPrimaryClipChanged() {
                String newClipboardText = ClipboardManager.getText();
                if (newClipboardText == null) return;
                if (!newClipboardText.equals(nowClipboardText)) {
                    nowClipboardText = newClipboardText;
                    // 发送报文
                    ControlPacket.sendClipboardEvent(nowClipboardText);
                }
            }
        });
    }

    public static void setClipboardText(String text) {
        nowClipboardText = text;
        ClipboardManager.setText(nowClipboardText);
    }

    private static void setRotationListener() {
        WindowManager.registerRotationWatcher(new IRotationWatcher.Stub() {
            public void onRotationChanged(int rotation) {
                updateSize();
                VideoEncode.isHasChangeConfig = true;
            }
        }, displayId);
    }

    private static final PointersState pointersState = new PointersState();

    public static void touchEvent(int action, Float x, Float y, int pointerId, int offsetTime) {
        Pointer pointer = pointersState.get(pointerId);

        if (pointer == null) {
            if (action != MotionEvent.ACTION_DOWN) return;
            pointer = pointersState.newPointer(pointerId, SystemClock.uptimeMillis() - 50);
        }

        pointer.x = x * displayInfo.width;
        pointer.y = y * displayInfo.height;
        int pointerCount = pointersState.update();

        if (action == MotionEvent.ACTION_UP) {
            pointersState.remove(pointerId);
            if (pointerCount > 1)
                action = MotionEvent.ACTION_POINTER_UP | (pointer.id << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == MotionEvent.ACTION_DOWN) {
            if (pointerCount > 1)
                action = MotionEvent.ACTION_POINTER_DOWN | (pointer.id << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        MotionEvent event = MotionEvent.obtain(pointer.downTime, pointer.downTime + offsetTime, action, pointerCount, pointersState.pointerProperties, pointersState.pointerCoords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectEvent(event);
    }

    public static void keyEvent(int keyCode, int meta) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event1 = new KeyEvent(now, now, MotionEvent.ACTION_DOWN, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        KeyEvent event2 = new KeyEvent(now, now, MotionEvent.ACTION_UP, keyCode, 0, meta, -1, 0, 0, InputDevice.SOURCE_KEYBOARD);
        injectEvent(event1);
        injectEvent(event2);
    }

    // ===== 虚拟鼠标(触控板式相对移动) =====
    // 光标位置由服务端累计：客户端只发归一化位移增量，首用初始化为屏幕中心
    private static float mouseX = -1;
    private static float mouseY = -1;
    private static long mouseDownTime = 0;

    // action 复用 MotionEvent(0=DOWN,1=UP,7=HOVER_MOVE,8=SCROLL)；dx/dy 为归一化位移，data 为 buttonState(1左/2右)或滚动量
    public static void mouseEvent(int action, float dx, float dy, float data) {
        if (displayInfo == null) return;
        if (mouseX < 0) {
            mouseX = displayInfo.width / 2f;
            mouseY = displayInfo.height / 2f;
        }
        if (action == MotionEvent.ACTION_HOVER_MOVE) {
            mouseX += dx * displayInfo.width;
            mouseY += dy * displayInfo.height;
            if (mouseX < 0) mouseX = 0;
            else if (mouseX >= displayInfo.width) mouseX = displayInfo.width - 1;
            if (mouseY < 0) mouseY = 0;
            else if (mouseY >= displayInfo.height) mouseY = displayInfo.height - 1;
        } else if (action == MotionEvent.ACTION_DOWN) {
            mouseDownTime = SystemClock.uptimeMillis();
        }
        long now = SystemClock.uptimeMillis();
        long downTime = (action == MotionEvent.ACTION_UP) ? mouseDownTime : now;
        // 单指针鼠标事件
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = mouseX;
        coords[0].y = mouseY;
        if (action == MotionEvent.ACTION_SCROLL) coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, data);
        // buttonState 语义是「当前被按住按键的位图」：DOWN 时携带按下的按键，UP 时按键已松开必须为 0，
        // 否则被控机侧会认为按键仍处于按住状态(按钮高亮残留/长按误判)
        int buttonState = (action == MotionEvent.ACTION_DOWN) ? (int) data : 0;
        MotionEvent event = MotionEvent.obtain(downTime, now, action, 1, properties, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        injectEvent(event);
    }

    private static void injectEvent(InputEvent inputEvent) {
        try {
            if (displayId != Display.DEFAULT_DISPLAY)
                InputManager.setDisplayId(inputEvent, displayId);
            InputManager.injectInputEvent(inputEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (Exception ignored) {
        }
    }

    public static void changeScreenPowerMode(int mode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            long[] physicalDisplayIds = SurfaceControl.getPhysicalDisplayIds();
            if (physicalDisplayIds == null) return;
            for (long physicalDisplayId : physicalDisplayIds) {
                IBinder token = SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
                if (token != null) SurfaceControl.setDisplayPowerMode(token, mode);
            }
        } else {
            IBinder d = SurfaceControl.getBuiltInDisplay();
            if (d != null) SurfaceControl.setDisplayPowerMode(d, mode);
        }
    }

    public static void changePower(int mode) {
        if (mode == -1) keyEvent(26, 0);
        else {
            try {
                String output = execReadOutput("dumpsys deviceidle | grep mScreenOn");
                Boolean isScreenOn = null;
                if (output.contains("mScreenOn=true")) isScreenOn = true;
                else if (output.contains("mScreenOn=false")) isScreenOn = false;
                if (isScreenOn != null && isScreenOn ^ (mode == 1)) Device.keyEvent(26, 0);
            } catch (Exception ignored) {
            }
        }
    }

    public static void rotateDevice() {
        boolean accelerometerRotation = !WindowManager.isRotationFrozen(displayId);
        WindowManager.freezeRotation(displayId, (displayInfo.rotation == 0 || displayInfo.rotation == 3) ? 1 : 0);
        if (accelerometerRotation) WindowManager.thawRotation(displayId);
    }

    // 修改媒体音量(STREAM_MUSIC，滑块0-100)：优先用 AudioService 绝对音量(系统设置音量同一路径，全ROM通用)；
    // AudioService 不可用(极老系统)时按键兜底(仅静音)
    public static void changeVolume(int volume) {
        if (volume < 0 || volume > 100) return;
        int stream = 3; // STREAM_MUSIC
        int serverMax = AudioManager.getStreamMaxVolume(stream);
        if (serverMax > 0) {
            int index = Math.round(volume * serverMax / 100f);
            if (AudioManager.setStreamVolume(stream, index)) return;
        }
        if (volume == 0) for (int i = 0; i < 20; i++) keyEvent(KeyEvent.KEYCODE_VOLUME_DOWN, 0);
    }

    public static String execReadOutput(String cmd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder().command("sh", "-c", cmd).start();
        StringBuilder builder = new StringBuilder();
        String line;
        try {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while ((line = bufferedReader.readLine()) != null) builder.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) throw new IOException("命令执行错误" + cmd);
            return builder.toString();
        } finally {
            // 读取失败或命令异常退出时兜底销毁子进程，避免孤儿进程/线程泄漏
            process.destroy();
        }
    }

    // 读取命令原始输出(字节)，供二进制数据(如 screencap 的 PNG)使用
    public static byte[] execReadOutputBytes(String cmd) throws IOException, InterruptedException {
        Process process = new ProcessBuilder().command("sh", "-c", cmd).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        try {
            try (InputStream inputStream = process.getInputStream()) {
                while ((len = inputStream.read(buffer)) != -1) output.write(buffer, 0, len);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) throw new IOException("命令执行错误" + cmd);
            return output.toByteArray();
        } finally {
            process.destroy();
        }
    }

    // 非侵入式防息屏：发送用户活动信号，不修改任何系统设置
    // 由Server.executeVideoOut定期调用
    public static void keepActive() {
        if (Options.keepAwake) PowerManager.userActivity(displayId);
    }

}
