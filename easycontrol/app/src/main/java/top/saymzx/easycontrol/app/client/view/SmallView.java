package top.saymzx.easycontrol.app.client.view;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.SeekBar;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ModuleSmallViewBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class SmallView extends ViewOutlineProvider {
    private final Device device;
    private ClientController clientController;
    private volatile boolean isShow = false;
    private boolean light = true;
    private boolean mouse = false;

    // 悬浮窗
    private final ModuleSmallViewBinding smallView = ModuleSmallViewBinding.inflate(LayoutInflater.from(AppData.applicationContext));
    private final WindowManager.LayoutParams smallViewParams =
            new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    LayoutParamsFlagFocus,
                    PixelFormat.TRANSLUCENT
            );

    private static final int LayoutParamsFlagFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
    private static final int LayoutParamsFlagNoFocus = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

    public SmallView(String uuid) {
        device = Client.getDevice(uuid);
        clientController = Client.getClientController(uuid);
        if (device == null || clientController == null) return;
        smallViewParams.gravity = Gravity.START | Gravity.TOP;
        // 设置默认导航栏状态
        setNavBarHide(device.showNavBarOnConnect);
        // 设置监听控制
        setFloatVideoListener();
        setReSizeListener();
        setBarListener();
        setButtonListener();
        setKeyEvent();
        // 设置圆角
        smallView.body.setOutlineProvider(this);
        smallView.body.setClipToOutline(true);
    }

    public void show() {
        if (device == null || clientController == null) return;
        // 初始化
        smallView.barView.setVisibility(View.GONE);
        smallViewParams.x = device.smallX;
        smallViewParams.y = device.smallY;
        updateMaxSize();
        if (!Objects.equals(device.startApp, "")) {
            smallView.buttonHome.setVisibility(View.GONE);
            smallView.buttonSwitch.setVisibility(View.GONE);
            smallView.buttonApp.setVisibility(View.GONE);
            smallView.textureViewLayout.setPadding(0, PublicTools.dp2px(25f), 0, 0);
        }
        // 自定义分辨率(2:1)
        if (!device.customResolutionOnConnect && device.changeResolutionOnRunning)
            clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent(0.5f), 0);
        // 显示
        AppData.windowManager.addView(smallView.getRoot(), smallViewParams);
        smallView.textureViewLayout.addView(clientController.getTextureView(), 0);
        // 同步鼠标状态(小窗重开时恢复图标与光标覆盖层)
        mouse = clientController.isMouseMode();
        smallView.buttonMouse.setImageTintList(ColorStateList.valueOf(AppData.applicationContext.getColor(mouse ? R.color.mouseActive : R.color.clientNavIcon)));
        if (mouse) clientController.showCursorOverlay();
        ViewTools.viewAnim(smallView.getRoot(), true, 0, PublicTools.dp2px(40f), null);
        isShow = true;
    }

    public void hide() {
        if (device == null || clientController == null) return;
        try {
            smallView.textureViewLayout.removeView(clientController.getTextureView());
            AppData.windowManager.removeView(smallView.getRoot());
            isShow = false;
        } catch (Exception ignored) {
        }
    }

    // 设置焦点监听
    @SuppressLint("ClickableViewAccessibility")
    private void setFloatVideoListener() {
        smallView.getRoot().setOnTouchHandle(event -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                if (device.smallToMiniOnRunning)
                    clientController.handleAction("changeToMini", ByteBuffer.wrap("changeToSmall".getBytes()), 0);
                else if (smallViewParams.flags != LayoutParamsFlagNoFocus) {
                    smallView.editText.clearFocus();
                    smallViewParams.flags = LayoutParamsFlagNoFocus;
                    AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
                }
            } else if (smallViewParams.flags != LayoutParamsFlagFocus) {
                smallViewParams.flags = LayoutParamsFlagFocus;
                AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
                smallView.editText.requestFocus();
            }
        });
    }

    // 设置上横条监听控制
    @SuppressLint("ClickableViewAccessibility")
    private void setBarListener() {
        AtomicBoolean isFilp = new AtomicBoolean(false);
        AtomicInteger xx = new AtomicInteger();
        AtomicInteger yy = new AtomicInteger();
        AtomicInteger paramsX = new AtomicInteger();
        AtomicInteger paramsY = new AtomicInteger();
        smallView.bar.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    xx.set((int) event.getRawX());
                    yy.set((int) event.getRawY());
                    paramsX.set(smallViewParams.x);
                    paramsY.set(smallViewParams.y);
                    isFilp.set(false);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int x = (int) event.getRawX();
                    int y = (int) event.getRawY();
                    int flipX = x - xx.get();
                    int flipY = y - yy.get();
                    // 适配一些机器将点击视作小范围移动(小于4的圆内不做处理)
                    if (!isFilp.get()) {
                        if (flipX * flipX + flipY * flipY < 16) return true;
                        isFilp.set(true);
                    }
                    // 拖动限制，避免拖到状态栏
                    if (y < statusBarHeight + 10) return true;
                    // 更新
                    updateSite(paramsX.get() + flipX, paramsY.get() + flipY);
                    break;
                }
                case MotionEvent.ACTION_UP:
                    if (!isFilp.get()) changeBarView();
                    break;
            }
            return true;
        });
    }

    // 设置按钮监听
    private void setButtonListener() {
        smallView.buttonBack.setOnClickListener(v -> clientController.handleAction("buttonBack", null, 0));
        smallView.buttonHome.setOnClickListener(v -> clientController.handleAction("buttonHome", null, 0));
        smallView.buttonSwitch.setOnClickListener(v -> clientController.handleAction("buttonSwitch", null, 0));
        smallView.buttonApp.setOnClickListener(v -> {
            clientController.handleAction("changeToApp", null, 0);
            changeBarView();
        });
        smallView.buttonMini.setOnClickListener(v -> clientController.handleAction("changeToMini", null, 0));
        smallView.buttonFull.setOnClickListener(v -> clientController.handleAction("changeToFull", null, 0));
        smallView.buttonClose.setOnClickListener(v -> Client.sendAction(device.uuid, "close", null, 0));
        smallView.buttonRotate.setOnClickListener(v -> {
            clientController.handleAction("buttonRotate", null, 0);
            changeBarView();
        });
        smallView.buttonNavBar.setOnClickListener(v -> {
            setNavBarHide(smallView.navBar.getVisibility() == View.GONE);
            changeBarView();
        });
        smallView.buttonPower.setOnClickListener(v -> {
            clientController.handleAction("buttonPower", null, 0);
            changeBarView();
        });
        smallView.buttonLight.setOnClickListener(v -> {
            light = !light;
            smallView.buttonLight.setImageResource(light ? R.drawable.lightbulb_off : R.drawable.lightbulb);
            clientController.handleAction(light ? "buttonLight" : "buttonLightOff", null, 0);
            changeBarView();
        });
        smallView.buttonVolume.setOnClickListener(v -> changeVolumeBar());
        // 虚拟鼠标：触控板式，开启后光标高亮
        smallView.buttonMouse.setOnClickListener(v -> {
            mouse = !mouse;
            clientController.handleAction("toggleMouse", null, 0);
            smallView.buttonMouse.setImageTintList(ColorStateList.valueOf(AppData.applicationContext.getColor(mouse ? R.color.mouseActive : R.color.clientNavIcon)));
            changeBarView();
        });
        smallView.buttonScreenshot.setOnClickListener(v -> {
            clientController.handleAction("screenshot", null, 0);
            changeBarView();
        });
        smallView.buttonStatus.setOnClickListener(v -> {
            showStatusDialog();
            changeBarView();
        });
        // 滑块两端图标：直接切静音/切最大
        smallView.imageVolumeMute.setOnClickListener(v -> setVolumeByIcon(0));
        smallView.imageVolumeMax.setOnClickListener(v -> setVolumeByIcon(100));
        smallView.seekbarVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                clientController.handleAction("setVolume", ControlPacket.createVolumeEvent(progress), 0);
                updateVolumeIcon(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    // 展开/收起音量滑块：展开前在后台读取被控机当前音量并定位滑块，老系统读取失败则不展开
    private void changeVolumeBar() {
        if (smallView.volumeBar.getVisibility() == View.VISIBLE) {
            smallView.volumeBar.setVisibility(View.GONE);
            return;
        }
        smallView.volumeBar.setVisibility(View.VISIBLE);
        smallView.seekbarVolume.setProgress(0);
        new Thread(() -> {
            Pair<Integer, Integer> volumeInfo;
            try {
                volumeInfo = clientController.getVolumeInfo();
            } catch (Exception ignored) {
                volumeInfo = null;
            }
            final Pair<Integer, Integer> volumeInfoFinal = volumeInfo;
            AppData.uiHandler.post(() -> {
                if (volumeInfoFinal == null) {
                    smallView.volumeBar.setVisibility(View.GONE);
                    PublicTools.logToast("SmallView", AppData.applicationContext.getString(R.string.toast_volume_not_support), true);
                    return;
                }
                // 滑块统一 0-100，服务端按被控机自身音量上限换算
                smallView.seekbarVolume.setMax(100);
                smallView.seekbarVolume.setProgress(Math.round(volumeInfoFinal.first * 100f / volumeInfoFinal.second));
                updateVolumeIcon(volumeInfoFinal.first);
            });
        }).start();
    }

    // 音量图标随静音状态切换
    private void updateVolumeIcon(int volume) {
        smallView.buttonVolume.setImageResource(volume == 0 ? R.drawable.volume_off : R.drawable.volume_up);
    }

    // 点击滑块两端图标：直接切到静音(0)或最大(100)，滑块归位并同步被控机
    private void setVolumeByIcon(int volume) {
        smallView.seekbarVolume.setProgress(volume);
        clientController.handleAction("setVolume", ControlPacket.createVolumeEvent(volume), 0);
        updateVolumeIcon(volume);
    }

    // 设备状态面板：后台读被控机状态后弹窗展示(悬浮窗非Activity，用Application上下文)
    private void showStatusDialog() {
        new Thread(() -> {
            ClientController.DeviceStatus status;
            try {
                status = clientController.getDeviceStatus();
            } catch (Exception ignored) {
                status = null;
            }
            final ClientController.DeviceStatus statusFinal = status;
            AppData.uiHandler.post(() -> {
                if (statusFinal == null) {
                    PublicTools.logToast("SmallView", AppData.applicationContext.getString(R.string.toast_status_failed), true);
                    return;
                }
                // 悬浮窗非 Activity，Application 上下文没有窗口 token，DeviceStatusDialog 内部会切 overlay 类型
                DeviceStatusDialog.show(AppData.applicationContext, device, statusFinal, true, this::showStatusDialog);
            });
        }).start();
    }

    // 导航栏隐藏
    private void setNavBarHide(boolean isShow) {
        smallView.navBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
        smallView.buttonNavBar.setImageResource(isShow ? R.drawable.not_equal : R.drawable.equals);
    }

    private void changeBarView() {
        boolean toShowView = smallView.barView.getVisibility() == View.GONE;
        ViewTools.viewAnim(smallView.barView, toShowView, 0, PublicTools.dp2px(-40f), (isStart -> {
            if (isStart && toShowView) smallView.barView.setVisibility(View.VISIBLE);
            else if (!isStart && !toShowView) {
                smallView.barView.setVisibility(View.GONE);
                smallView.volumeBar.setVisibility(View.GONE);
            }
        }));
    }

    // 设置悬浮窗大小拖动按钮监听控制
    @SuppressLint("ClickableViewAccessibility")
    private void setReSizeListener() {
        smallView.reSize.setOnTouchListener((v, event) -> {
            int sizeX = (int) (event.getRawX() - smallViewParams.x);
            int sizeY = (int) (event.getRawY() - smallViewParams.y);
            int length = Math.max(sizeX, sizeY);
            ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
            if (textureViewLayoutParams.width < textureViewLayoutParams.height)
                device.smallLength = length;
            else device.smallLengthLan = length;
            updateMaxSize();
            return true;
        });
    }

    private void updateSite(int x, int y) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(x);
        byteBuffer.putInt(y);
        byteBuffer.flip();
        clientController.handleAction("updateSite", byteBuffer, 0);
    }

    public void updateView(int x, int y) {
        smallViewParams.x = x;
        smallViewParams.y = y;
        AppData.windowManager.updateViewLayout(smallView.getRoot(), smallViewParams);
    }

    private void updateMaxSize() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(device.smallLength);
        byteBuffer.putInt(device.smallLengthLan);
        byteBuffer.flip();
        clientController.handleAction("updateMaxSize", byteBuffer, 0);
    }

    public boolean isShow() {
        return isShow;
    }

    // 设置键盘监听
    private void setKeyEvent() {
        smallView.editText.setInputType(InputType.TYPE_NULL);
        smallView.editText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                clientController.handleAction("writeByteBuffer", ControlPacket.createKeyEvent(event.getKeyCode(), event.getMetaState()), 0);
                return true;
            }
            return false;
        });
    }

    // 检查画面是否超出
    public void checkSizeAndSite() {
        if (!isShow) return;
        DisplayMetrics screenSize = PublicTools.getScreenSize();
        int screenMaxWidth = screenSize.widthPixels - 50;
        int screenMaxHeight = screenSize.heightPixels - statusBarHeight - 50;
        ViewGroup.LayoutParams textureViewLayoutParams = clientController.getTextureView().getLayoutParams();
        int width = textureViewLayoutParams.width;
        int height = textureViewLayoutParams.height;
        int startX = smallViewParams.x;
        int startY = smallViewParams.y;
        // 检测到大小超出
        if (width > screenMaxWidth + 200 || height > screenMaxHeight + 200) {
            int maxLength = Math.min(screenMaxWidth, screenMaxHeight);
            if (width < height) device.smallLength = maxLength;
            else device.smallLengthLan = maxLength;
            updateMaxSize();
            updateSite(0, statusBarHeight);
            return;
        }
        // 检测到位置超出过多
        int halfWidth = (int) (width * 0.5);
        if (startX < -1 * halfWidth) updateSite(-1 * halfWidth + 50, startY);
        if (startX > screenSize.widthPixels - halfWidth)
            updateSite(screenSize.widthPixels - halfWidth - 50, startY);
        if (startY < statusBarHeight / 2) updateSite(startX, statusBarHeight);
        if (startY > screenSize.heightPixels - 100)
            updateSite(startX, screenSize.heightPixels - 200);
    }

    @Override
    public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AppData.applicationContext.getResources().getDimension(R.dimen.cron));
    }

    private static int statusBarHeight = 0;

    static {
        @SuppressLint("InternalInsetResource") int resourceId = AppData.applicationContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = AppData.applicationContext.getResources().getDimensionPixelSize(resourceId);
        }
    }

}
