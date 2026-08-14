package top.saymzx.easycontrol.app.client.tools;

import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Pair;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.view.FullActivity;
import top.saymzx.easycontrol.app.client.view.MiniView;
import top.saymzx.easycontrol.app.client.view.SmallView;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.entity.MyInterface;
import top.saymzx.easycontrol.app.helper.PublicTools;

public class ClientController implements TextureView.SurfaceTextureListener {
    private final Device device;
    private final ClientStream clientStream;
    private final MyInterface.MyFunction handle;
    private final TextureView textureView = new TextureView(AppData.applicationContext);
    private SurfaceTexture surfaceTexture;

    private SmallView smallView;
    private MiniView miniView;
    private FullActivity fullView;

    private volatile Pair<Integer, Integer> videoSize;
    private volatile Pair<Integer, Integer> maxSize;
    private volatile Pair<Integer, Integer> surfaceSize;

    // 执行线程
    private final HandlerThread mainThread = new HandlerThread("easycontrol_client_main");
    private Handler mainHandler;

    public ClientController(Device device, ClientStream clientStream, MyInterface.MyFunction handle) {
        this.device = device;
        this.clientStream = clientStream;
        this.handle = handle;
        mainThread.start();
        mainHandler = new Handler(mainThread.getLooper());
        textureView.setSurfaceTextureListener(this);
        setTouchListener();
        // 启动子服务
        mainHandler.post(this::otherService);
    }

    public void handleAction(String action, ByteBuffer byteBuffer, int delay) {
        if (delay == 0) mainHandler.post(() -> handleAction(action, byteBuffer));
        else mainHandler.postDelayed(() -> handleAction(action, byteBuffer), delay);
    }

    private void handleAction(String action, ByteBuffer byteBuffer) {
        try {
            switch (action) {
                case "changeToSmall":
                    changeToSmall();
                    break;
                case "changeToFull":
                    changeToFull();
                    break;
                case "changeToMini":
                    changeToMini(byteBuffer);
                    break;
                case "changeToApp":
                    changeToApp();
                    break;
                case "buttonPower":
                    clientStream.writeToMain(ControlPacket.createPowerEvent(-1));
                    break;
                case "buttonWake":
                    clientStream.writeToMain(ControlPacket.createPowerEvent(1));
                    break;
                case "buttonLock":
                    clientStream.writeToMain(ControlPacket.createPowerEvent(0));
                    break;
                case "buttonLight":
                    clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_ON));
                    clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_OFF));
                    break;
                case "buttonLightOff":
                    clientStream.writeToMain(ControlPacket.createLightEvent(Display.STATE_UNKNOWN));
                    break;
                case "buttonBack":
                    clientStream.writeToMain(ControlPacket.createKeyEvent(4, 0));
                    break;
                case "buttonHome":
                    clientStream.writeToMain(ControlPacket.createKeyEvent(3, 0));
                    break;
                case "buttonSwitch":
                    clientStream.writeToMain(ControlPacket.createKeyEvent(187, 0));
                    break;
                case "buttonRotate":
                    clientStream.writeToMain(ControlPacket.createRotateEvent());
                    break;
                case "setVolume":
                    // byteBuffer 已是完整音量包([10][int])，直接发送，避免重复包装
                    clientStream.writeToMain(byteBuffer);
                    break;
                case "toggleMouse":
                    toggleMouse();
                    break;
                case "screenshot":
                    clientStream.writeToMain(ControlPacket.createScreenshotEvent());
                    break;
                case "toggleRecord":
                    toggleRecord();
                    break;
                case "saveScreenshot":
                    // PNG 解码/压缩/写盘较耗时(大图可达数秒)，移到后台线程，避免阻塞主线程的控制包/心跳处理
                    new Thread(() -> saveScreenshot(byteBuffer)).start();
                    break;
                case "keepAlive":
                    clientStream.writeToMain(ControlPacket.createKeepAlive());
                    break;
                case "checkSizeAndSite":
                    checkSizeAndSite();
                    break;
                case "checkClipBoard":
                    checkClipBoard();
                    break;
                case "updateSite":
                    updateSite(byteBuffer);
                    break;
                default:
                    if (byteBuffer == null) break;
                case "writeByteBuffer":
                    if (byteBuffer == null) break;
                    clientStream.writeToMain(byteBuffer);
                    break;
                case "updateMaxSize":
                    updateMaxSize(byteBuffer);
                    break;
                case "updateVideoSize":
                    updateVideoSize(byteBuffer);
                    break;
                case "runShell":
                    runShell(byteBuffer);
                    break;
                case "setClipBoard":
                    setClipBoard(byteBuffer);
                    break;
            }
        } catch (Exception ignored) {
            byte[] err = ("controller" + AppData.applicationContext.getString(R.string.toast_stream_closed) + action).getBytes(StandardCharsets.UTF_8);
            Client.sendAction(device.uuid, "close", ByteBuffer.wrap(err), 0);
        }
    }

    private void otherService() {
        handleAction("checkClipBoard", null, 0);
        handleAction("keepAlive", null, 0);
        handleAction("checkSizeAndSite", null, 0);
        // 心跳超时检测：如果超过10秒未收到服务端任何数据，则认为连接已断开
        ClientPlayer player = Client.getClientPlayer(device.uuid);
        if (player != null && player.isKeepAliveTimeout()) {
            byte[] err = ("controller" + AppData.applicationContext.getString(R.string.toast_stream_closed) + "keepAliveTimeout").getBytes(StandardCharsets.UTF_8);
            Client.sendAction(device.uuid, "close", ByteBuffer.wrap(err), 0);
            return;
        }
        mainHandler.postDelayed(this::otherService, 2000);
    }

    public void setFullView(FullActivity fullView) {
        this.fullView = fullView;
    }

    public TextureView getTextureView() {
        return textureView;
    }

    private synchronized void changeToFull() {
        hide();
        if (AppData.mainActivity == null) return;
        Intent intent = new Intent(AppData.mainActivity, FullActivity.class);
        intent.putExtra("uuid", device.uuid);
        AppData.mainActivity.startActivity(intent);
    }

    private synchronized void changeToSmall() {
        hide();
        if (noFloatPermission()) {
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
            changeToFull();
        } else {
            if (smallView == null) smallView = new SmallView(device.uuid);
            AppData.uiHandler.post(smallView::show);
            updateSite(null);
        }
    }

    private synchronized void changeToMini(ByteBuffer byteBuffer) {
        hide();
        if (noFloatPermission()) {
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
            changeToFull();
        } else {
            if (miniView == null) miniView = new MiniView(device.uuid);
            AppData.uiHandler.post(() -> miniView.show(byteBuffer));
        }
    }

    // 检查悬浮窗权限
    private boolean noFloatPermission() {
        // 兼容安卓6以下部分国产ROM检测不准
        try {
            return !PublicTools.checkOverlayPermission(AppData.applicationContext);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void changeToApp() {
        if (noFloatPermission()) {
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_float_per), true);
            return;
        }
        // 在独立线程执行 shell 命令，避免阻塞 mainThread 导致 keepAlive 超时
        new Thread(() -> {
            try {
                String output = clientStream.runShell("dumpsys window | grep mCurrentFocus=Window");
                Matcher matcher = Pattern.compile(" ([a-zA-Z0-9.]+)/").matcher(output);
                if (matcher.find()) {
                    String appPackage = matcher.group(1);
                    // 当前前台为桌面(Launcher)时无法将其移入虚拟显示进行单应用投屏，直接提示
                    if (isHomeApp(appPackage)) {
                        PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_home_single_app), true);
                        return;
                    }
                    Device tempDevice = device.clone(String.valueOf(UUID.randomUUID()));
                    tempDevice.name = "----";
                    tempDevice.startApp = appPackage;
                    tempDevice.smallX += 200;
                    tempDevice.smallY += 200;
                    tempDevice.smallLength -= 200;
                    tempDevice.miniY += 200;
                    // 切换成功后（原服务端已被新服务端 pkill）再关闭原会话，避免误报「连接断开」；
                    // 连接失败则原会话保留，不会丢失
                    tempDevice.onConnectSuccess = () -> Client.sendAction(device.uuid, "close", null, 0);
                    // Client 构造函数需要创建 Dialog，必须在 UI 线程执行
                    AppData.uiHandler.post(() -> Client.startDevice(tempDevice));
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    // 判断前台应用是否为桌面(Launcher)，检测失败时返回 false 交由服务端容错兜底
    private boolean isHomeApp(String appPackage) {
        try {
            String homeOutput = clientStream.runShell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
            Matcher homeMatcher = Pattern.compile("([a-zA-Z0-9.]+)/").matcher(homeOutput);
            String homePackage = "";
            while (homeMatcher.find()) homePackage = homeMatcher.group(1);
            return !homePackage.isEmpty() && Objects.equals(appPackage, homePackage);
        } catch (Exception ignored) {
            return false;
        }
    }

    private synchronized void hide() {
        if (fullView != null) AppData.uiHandler.post(fullView::hide);
        fullView = null;
        if (smallView != null) AppData.uiHandler.post(smallView::hide);
        if (miniView != null) AppData.uiHandler.post(miniView::hide);
        // 视图切换会迁移 textureView，光标覆盖层须同步从旧容器摘除，否则旧 Activity 视图树被 mParent 链钉住泄漏
        AppData.uiHandler.post(() -> {
            if (cursorOverlay != null && cursorOverlay.getParent() != null)
                ((ViewGroup) cursorOverlay.getParent()).removeView(cursorOverlay);
        });
    }

    public void close() {
        hide();
        // 等待视图移除后再释放 SurfaceTexture 和退出线程，避免硬件渲染器访问已死的 Looper
        AppData.uiHandler.post(() -> {
            if (surfaceTexture != null) surfaceTexture.release();
            mainThread.quitSafely();
        });
    }

    private static final int minLength = PublicTools.dp2px(200f);

    private void updateMaxSize(ByteBuffer byteBuffer) {
        int width = Math.max(byteBuffer.getInt(), minLength);
        int height = Math.max(byteBuffer.getInt(), minLength);
        this.maxSize = new Pair<>(width, height);
        AppData.uiHandler.post(this::reCalculateTextureViewSize);
    }

    private void updateVideoSize(ByteBuffer byteBuffer) {
        int width = byteBuffer.getInt();
        int height = byteBuffer.getInt();
        if (width <= 100 || height <= 100) return;
        this.videoSize = new Pair<>(width, height);
        updateSite(null);
        AppData.uiHandler.post(this::reCalculateTextureViewSize);
    }

    private void updateSite(ByteBuffer byteBuffer) {
        if (smallView == null || videoSize == null || !smallView.isShow()) return;
        int x;
        int y;
        boolean isAuto = byteBuffer == null;
        if (videoSize.first < videoSize.second) {
            x = isAuto ? device.smallX : byteBuffer.getInt();
            y = isAuto ? device.smallY : byteBuffer.getInt();
            device.smallX = x;
            device.smallY = y;
        } else {
            x = isAuto ? device.smallXLan : byteBuffer.getInt();
            y = isAuto ? device.smallYLan : byteBuffer.getInt();
            device.smallXLan = x;
            device.smallYLan = y;
        }
        AppData.uiHandler.post(() -> smallView.updateView(x, y));
    }

    // 重新计算TextureView大小
    private void reCalculateTextureViewSize() {
        if (maxSize == null || videoSize == null) return;
        Pair<Integer, Integer> maxSize = this.maxSize;
        if (smallView != null && smallView.isShow()) {
            if (videoSize.first < videoSize.second)
                maxSize = new Pair<>(this.maxSize.first, this.maxSize.first);
            else maxSize = new Pair<>(this.maxSize.second, this.maxSize.second);
        }
        // 根据原画面大小videoSize计算在maxSize空间内的最大缩放大小
        int tmp1 = videoSize.second * maxSize.first / videoSize.first;
        // 横向最大不会超出
        if (maxSize.second > tmp1) surfaceSize = new Pair<>(maxSize.first, tmp1);
            // 竖向最大不会超出
        else
            surfaceSize = new Pair<>(videoSize.first * maxSize.second / videoSize.second, maxSize.second);
        // 全屏拉伸填充：全屏模式下画面与屏幕比例接近时拉伸填满
        if (fullView != null && AppData.setting.getFillFull()) {
            float videoRatio = (float) videoSize.first / videoSize.second;
            float screenRatio = (float) maxSize.first / maxSize.second;
            if (Math.abs(videoRatio - screenRatio) < 0.15f) {
                surfaceSize = new Pair<>(maxSize.first, maxSize.second);
            }
        }
        // 更新大小
        // 容器为 FrameLayout，不继承容器 android:gravity；须在子项上显式 layout_gravity 居中，否则高(竖)屏画面会顶到左边
        // 全屏↔小窗/迷你切换或旋转重建期间 textureView 可能已从父容器移除，getLayoutParams() 返回 null，须判空
        ViewGroup.LayoutParams layoutParams = textureView.getLayoutParams();
        if (layoutParams == null) return;
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            ((FrameLayout.LayoutParams) layoutParams).gravity = Gravity.CENTER;
        }
        layoutParams.width = surfaceSize.first;
        layoutParams.height = surfaceSize.second;
        textureView.setLayoutParams(layoutParams);
    }

    // 检查画面是否超出
    private void checkSizeAndSite() {
        // 碎碎念，感谢 波瑠卡 的关爱，今天一家四口一起去医院进年货去了，每人提了一袋子(´；ω；`)
        if (smallView != null) AppData.uiHandler.post(smallView::checkSizeAndSite);
    }

    // 设置视频区域触摸监听
    @SuppressLint("ClickableViewAccessibility")
    private void setTouchListener() {
        textureView.setOnTouchListener((view, event) -> {
            if (surfaceSize == null) return true;
            if (mouseMode) {
                handleMouseEvent(event);
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int i = event.getActionIndex();
                pointerDownTime[i] = event.getEventTime();
                createTouchPacket(event, MotionEvent.ACTION_DOWN, i);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                createTouchPacket(event, MotionEvent.ACTION_UP, event.getActionIndex());
            else for (int i = 0; i < event.getPointerCount(); i++)
                    createTouchPacket(event, MotionEvent.ACTION_MOVE, i);
            return true;
        });
    }

    private final int[] pointerList = new int[20];
    private final long[] pointerDownTime = new long[10];

    private void createTouchPacket(MotionEvent event, int action, int i) {
        // 防止数组越界（actionIndex 或 pointerId 超出数组范围）
        if (i < 0 || i >= pointerDownTime.length) return;
        int p = event.getPointerId(i);
        if (p < 0 || p >= pointerDownTime.length) return;
        int offsetTime = (int) (event.getEventTime() - pointerDownTime[i]);
        int x = (int) event.getX(i);
        int y = (int) event.getY(i);
        if (action == MotionEvent.ACTION_MOVE) {
            // 减少发送小范围移动(小于4的圆内不做处理)
            int flipY = pointerList[10 + p] - y;
            if (flipY > -4 && flipY < 4) {
                int flipX = pointerList[p] - x;
                if (flipX > -4 && flipX < 4) return;
            }
        }
        pointerList[p] = x;
        pointerList[10 + p] = y;
        handleAction("writeByteBuffer", ControlPacket.createTouchEvent(action, p, (float) x / surfaceSize.first, (float) y / surfaceSize.second, offsetTime), 0);
    }

    // ===== 虚拟鼠标(触控板式相对移动) =====
    // 光标位置由服务端累计，客户端同步累计用于绘制覆盖层
    private volatile boolean mouseMode = false;
    private ImageView cursorOverlay;
    private float cursorX = 0.5f;
    private float cursorY = 0.5f;
    // 单指手势状态
    private boolean moved = false;
    private boolean suppressGesture = false;
    private long singleDownTime = 0;
    private float singleDownX = 0f;
    private float singleDownY = 0f;
    private float lastX = 0f;
    private float lastY = 0f;
    private float pendingDx = 0f;
    private float pendingDy = 0f;
    // 双指手势状态
    private boolean scrollMode = false;
    private boolean twoFingerTap = false;
    private float scrollLastY = 0f;
    private int activePointers = 0;

    private static final float mouseTapThreshold = PublicTools.dp2px(8f);

    public boolean isMouseMode() {
        return mouseMode;
    }

    private void toggleMouse() {
        mouseMode = !mouseMode;
        if (mouseMode) showCursorOverlay();
        else hideCursorOverlay();
    }

    // 切换视图(全屏/小窗)后重新挂载光标覆盖层
    public void showCursorOverlay() {
        AppData.uiHandler.post(() -> {
            ViewGroup parent = (ViewGroup) textureView.getParent();
            if (parent == null) return;
            if (cursorOverlay == null) {
                cursorOverlay = new ImageView(AppData.applicationContext);
                cursorOverlay.setImageResource(R.drawable.cursor);
                cursorOverlay.setClickable(false);
                int size = PublicTools.dp2px(22f);
                parent.addView(cursorOverlay, new FrameLayout.LayoutParams(size, size));
            } else if (cursorOverlay.getParent() != parent) {
                if (cursorOverlay.getParent() != null) ((ViewGroup) cursorOverlay.getParent()).removeView(cursorOverlay);
                parent.addView(cursorOverlay);
            }
            cursorOverlay.setVisibility(View.VISIBLE);
            updateCursorOverlayPosition();
        });
    }

    private void hideCursorOverlay() {
        AppData.uiHandler.post(() -> {
            if (cursorOverlay == null) return;
            cursorOverlay.setVisibility(View.GONE);
            // 从父容器摘除：否则 cursorOverlay 的 mParent 链会一直钉住已销毁的 Activity 视图树导致泄漏
            if (cursorOverlay.getParent() != null) ((ViewGroup) cursorOverlay.getParent()).removeView(cursorOverlay);
        });
    }

    // 光标定位：textureView 在 textureViewLayout 内可能居中偏移，需加上其 left/top
    private void updateCursorOverlayPosition() {
        if (cursorOverlay == null || cursorOverlay.getVisibility() != View.VISIBLE) return;
        cursorOverlay.setX(textureView.getLeft() + cursorX * textureView.getWidth() - cursorOverlay.getWidth() / 2f);
        cursorOverlay.setY(textureView.getTop() + cursorY * textureView.getHeight() - cursorOverlay.getHeight() / 2f);
    }

    // 鼠标手势状态机(UI线程)：单指拖动=光标悬停，快速点按=左键，双指点按=右键，双指滑动=滚轮
    private void handleMouseEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                activePointers = 1;
                moved = false;
                suppressGesture = false;
                scrollMode = false;
                twoFingerTap = false;
                singleDownTime = event.getEventTime();
                singleDownX = event.getX();
                singleDownY = event.getY();
                lastX = singleDownX;
                lastY = singleDownY;
                pendingDx = 0f;
                pendingDy = 0f;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                // 第二指按下：进入滚动模式，取消单击意图
                if (activePointers == 1) {
                    activePointers = 2;
                    scrollMode = true;
                    twoFingerTap = true;
                    pendingDx = 0f;
                    pendingDy = 0f;
                    scrollLastY = (event.getY(0) + event.getY(1)) / 2f;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (scrollMode && activePointers == 2) {
                    float y = (event.getY(0) + event.getY(1)) / 2f;
                    float dy = y - scrollLastY;
                    scrollLastY = y;
                    // 超过阈值才算滚动，避免点击抖动误触发
                    if (Math.abs(dy) > mouseTapThreshold * 0.5f) {
                        twoFingerTap = false;
                        handleAction("writeByteBuffer", ControlPacket.createMouseEvent(MotionEvent.ACTION_SCROLL, 0f, 0f, -dy * 0.01f), 0);
                    }
                } else if (activePointers == 1 && !suppressGesture) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    pendingDx += dx / surfaceSize.first;
                    pendingDy += dy / surfaceSize.second;
                    // 超过按下点阈值视为光标拖动：一次性补发累计位移后逐帧增量发送(1:1触控板手感)
                    float total = Math.abs(event.getX() - singleDownX) + Math.abs(event.getY() - singleDownY);
                    if (!moved && total > mouseTapThreshold) moved = true;
                    if (moved && (pendingDx != 0f || pendingDy != 0f)) {
                        cursorX += pendingDx;
                        cursorY += pendingDy;
                        // 与服务端一致收敛到画面范围内，防止持续边缘拖动导致光标覆盖层偏出画面
                        cursorX = Math.max(0f, Math.min(1f, cursorX));
                        cursorY = Math.max(0f, Math.min(1f, cursorY));
                        handleAction("writeByteBuffer", ControlPacket.createMouseEvent(MotionEvent.ACTION_HOVER_MOVE, pendingDx, pendingDy, 0f), 0);
                        pendingDx = 0f;
                        pendingDy = 0f;
                        updateCursorOverlayPosition();
                    }
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                // 手指从2→1：双指无位移即为右键；剩余单指手势不再响应
                if (activePointers == 2) {
                    if (twoFingerTap) sendMouseClick(2);
                    activePointers = 1;
                    scrollMode = false;
                    suppressGesture = true;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (activePointers == 1 && !suppressGesture) {
                    long dt = event.getEventTime() - singleDownTime;
                    // 快速点按且未移动 = 左键
                    if (!moved && dt < 300) sendMouseClick(1);
                }
                activePointers = 0;
                break;
            }
        }
    }

    private void sendMouseClick(int button) {
        handleAction("writeByteBuffer", ControlPacket.createMouseEvent(MotionEvent.ACTION_DOWN, 0f, 0f, button), 0);
        handleAction("writeByteBuffer", ControlPacket.createMouseEvent(MotionEvent.ACTION_UP, 0f, 0f, button), 0);
    }

    // 剪切板
    private String nowClipboardText = "";

    private void checkClipBoard() {
        if (!device.listenClip) return;
        ClipData clipBoard = AppData.clipBoard.getPrimaryClip();
        if (clipBoard != null && clipBoard.getItemCount() > 0) {
            String newClipBoardText = String.valueOf(clipBoard.getItemAt(0).getText());
            if (!Objects.equals(nowClipboardText, newClipBoardText)) {
                nowClipboardText = newClipBoardText;
                ByteBuffer clipPacket = ControlPacket.createClipboardEvent(nowClipboardText);
                if (clipPacket != null) handleAction("writeByteBuffer", clipPacket, 0);
            }
        }
    }

    private void setClipBoard(ByteBuffer byteBuffer) {
        nowClipboardText = new String(byteBuffer.array());
        AppData.clipBoard.setPrimaryClip(ClipData.newPlainText(MIMETYPE_TEXT_PLAIN, nowClipboardText));
    }

    private void runShell(ByteBuffer byteBuffer) throws Exception {
        String cmd = new String(byteBuffer.array());
        clientStream.runShell(cmd);
    }

    // 读取被控机媒体音量：返回(当前值, 最大值)；读取失败(老系统/media_session不可用)返回null
    public Pair<Integer, Integer> getVolumeInfo() throws Exception {
        String output = clientStream.runShell("cmd media_session volume --stream 3 --get");
        Matcher matcher = Pattern.compile("volume is (\\d+) in range \\[(\\d+)\\.\\.(\\d+)\\]").matcher(output);
        if (!matcher.find()) return null;
        return new Pair<>(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(3)));
    }

    // ===== 远程录屏 =====
    private void toggleRecord() throws Exception {
        ClientPlayer player = Client.getClientPlayer(device.uuid);
        if (player == null) return;
        if (player.isRecording()) player.stopRecord();
        else {
            player.startRecord();
            // 让服务端立即输出关键帧，录屏首帧不必等最长10秒的I帧周期
            clientStream.writeToMain(ControlPacket.createSyncFrameEvent());
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_record_start), true);
        }
    }

    public boolean isRecording() {
        ClientPlayer player = Client.getClientPlayer(device.uuid);
        return player != null && player.isRecording();
    }

    // 保存远程截图(服务端 screencap 回传的 PNG)
    private void saveScreenshot(ByteBuffer pngBuffer) {
        int size = pngBuffer.remaining();
        if (size == 0) {
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_screenshot_failed), true);
            return;
        }
        byte[] png = new byte[size];
        pngBuffer.get(png);
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) throw new Exception("decode fail");
            String name = "easycontrol_" + System.currentTimeMillis() + ".png";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Easycontrol");
                Uri uri = AppData.applicationContext.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new Exception("insert fail");
                try (OutputStream outputStream = AppData.applicationContext.getContentResolver().openOutputStream(uri)) {
                    if (outputStream == null) throw new Exception("open fail");
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                }
            } else {
                File dir = AppData.applicationContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                File file = new File(dir, name);
                try (FileOutputStream outputStream = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                }
                MediaScannerConnection.scanFile(AppData.applicationContext, new String[]{file.getAbsolutePath()}, new String[]{"image/png"}, null);
            }
            bitmap.recycle();
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_screenshot_saved), true);
        } catch (Exception e) {
            PublicTools.logToast("controller", AppData.applicationContext.getString(R.string.toast_screenshot_failed), true);
        }
    }

    // 被控机状态数据（getDeviceStatus 解析填充）
    public static class DeviceStatus {
        public int batteryLevel = -1;
        public int batteryScale = 100;
        public int batteryStatus = 0;
        public float temperature = 0;   // 十分之一摄氏度，如 365 = 36.5℃
        public float voltage = 0;       // 毫伏
        public String batteryTech = "";
        public String model = "";
        public String manufacturer = "";
        public String release = "";
        public String sdk = "";
        public String kernel = "";
        public long ramKb = 0;
        public long storageTotalKb = 0;
        public long storageUsedKb = 0;
        public int brightness = -1;     // 0~255，-1 未知
        public boolean brightnessAuto = false;
        public long uptimeSec = 0;
        public String resolution = "";
    }

    // 读取被控机状态：一次shell按标记分段读电池/属性/内核/内存/存储/亮度/运行时长，分辨率用已有videoSize
    public DeviceStatus getDeviceStatus() throws Exception {
        String output = clientStream.runShell(
                "echo '<<B>>'; dumpsys battery;" +
                        "echo '<<P>>'; getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.build.version.release; getprop ro.build.version.sdk;" +
                        "echo '<<K>>'; uname -r;" +
                        "echo '<<M>>'; cat /proc/meminfo;" +
                        "echo '<<D>>'; df /data;" +
                        "echo '<<L>>'; settings get system screen_brightness; settings get system screen_brightness_mode;" +
                        "echo '<<T>>'; cat /proc/uptime");
        DeviceStatus s = new DeviceStatus();
        int b = output.indexOf("<<B>>"), p = output.indexOf("<<P>>"), k = output.indexOf("<<K>>"),
                m = output.indexOf("<<M>>"), d = output.indexOf("<<D>>"), l = output.indexOf("<<L>>"), t = output.indexOf("<<T>>");
        if (b >= 0 && p > b) parseBattery(s, output.substring(b + 5, p));
        if (p >= 0 && k > p) parseProps(s, output.substring(p + 5, k));
        if (k >= 0 && m > k) s.kernel = output.substring(k + 5, m).trim();
        if (m >= 0 && d > m) parseMem(s, output.substring(m + 5, d));
        if (d >= 0 && l > d) parseDf(s, output.substring(d + 5, l));
        if (l >= 0 && t > l) parseBrightness(s, output.substring(l + 5, t));
        if (t >= 0) parseUptime(s, output.substring(t + 5));
        if (videoSize != null) s.resolution = videoSize.first + "×" + videoSize.second;
        return s;
    }

    private void parseBattery(DeviceStatus s, String section) {
        Matcher matcher = Pattern.compile("\\b(level|scale|status|temperature|voltage|technology):\\s*(\\S+)").matcher(section);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            try {
                switch (key) {
                    case "level":
                        s.batteryLevel = Integer.parseInt(value);
                        break;
                    case "scale":
                        s.batteryScale = Integer.parseInt(value);
                        break;
                    case "status":
                        s.batteryStatus = Integer.parseInt(value);
                        break;
                    case "temperature":
                        s.temperature = Float.parseFloat(value);
                        break;
                    case "voltage":
                        s.voltage = Float.parseFloat(value);
                        break;
                    case "technology":
                        s.batteryTech = value;
                        break;
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // 顺序取前5行非空 getprop 输出：model / manufacturer / brand / release / sdk
    private void parseProps(DeviceStatus s, String section) {
        String[] values = new String[5];
        int n = 0;
        for (String line : section.split("\n")) {
            String v = line.trim();
            if (!v.isEmpty() && n < values.length) values[n++] = v;
        }
        if (n > 0) s.model = values[0];
        if (n > 1) s.manufacturer = values[1];
        if (n > 3) s.release = values[3];
        if (n > 4) s.sdk = values[4];
    }

    private void parseMem(DeviceStatus s, String section) {
        Matcher matcher = Pattern.compile("MemTotal:\\s*(\\d+)").matcher(section);
        if (matcher.find()) {
            try {
                s.ramKb = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void parseDf(DeviceStatus s, String section) {
        // 不依赖挂载点是否为 /data（部分设备 /data 挂在根分区上，df 挂载点列显示为 /），
        // 也不假设大小为纯数字（部分 ROM 的 df 默认人类可读，如 "6.2G"）：取第一条含数字总量/已用的数据行。
        for (String line : section.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3) continue;
            Long total = toKb(p[1]);
            Long used = toKb(p[2]);
            if (total != null && total > 0) {
                s.storageTotalKb = total;
                if (used != null) s.storageUsedKb = used;
                return;
            }
        }
    }

    // 把 df 的大小列换算成 KB：纯数字按 1K 块，带 G/M/K/T 后缀按人类可读换算；无法识别返回 null
    private Long toKb(String v) {
        if (v == null) return null;
        String s2 = v.trim().toUpperCase(Locale.US);
        long mult = 1;
        if (s2.endsWith("G")) {
            mult = 1024 * 1024;
            s2 = s2.substring(0, s2.length() - 1);
        } else if (s2.endsWith("M")) {
            mult = 1024;
            s2 = s2.substring(0, s2.length() - 1);
        } else if (s2.endsWith("K")) {
            s2 = s2.substring(0, s2.length() - 1);
        } else if (s2.endsWith("T")) {
            mult = 1024 * 1024 * 1024;
            s2 = s2.substring(0, s2.length() - 1);
        }
        try {
            return (long) (Double.parseDouble(s2) * mult);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void parseBrightness(DeviceStatus s, String section) {
        String v0 = "", v1 = "";
        for (String line : section.split("\n")) {
            String v = line.trim();
            if (v.isEmpty()) continue;
            if (v0.isEmpty()) v0 = v;
            else {
                v1 = v;
                break;
            }
        }
        if (!v0.isEmpty() && !"null".equals(v0)) {
            try {
                s.brightness = Integer.parseInt(v0);
            } catch (NumberFormatException ignored) {
            }
        }
        s.brightnessAuto = "1".equals(v1);
    }

    private void parseUptime(DeviceStatus s, String section) {
        String first = section.trim();
        int sp = first.indexOf(' ');
        if (sp > 0) first = first.substring(0, sp);
        try {
            s.uptimeSec = (long) Double.parseDouble(first.trim());
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
        // 初始化
        if (this.surfaceTexture == null) {
            this.surfaceTexture = surfaceTexture;
            handle.run();
        } else textureView.setSurfaceTexture(this.surfaceTexture);
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
    }

}
