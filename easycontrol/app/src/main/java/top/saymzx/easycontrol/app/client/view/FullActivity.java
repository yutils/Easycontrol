package top.saymzx.easycontrol.app.client.view;

import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.SeekBar;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.ByteBuffer;
import java.util.Objects;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.client.tools.ControlPacket;
import top.saymzx.easycontrol.app.databinding.ActivityFullBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class FullActivity extends AppCompatActivity implements SensorEventListener {
    private boolean isClose = false;
    private Device device;
    private ClientController clientController;
    private ActivityFullBinding activityFullBinding;
    private boolean autoRotate;
    private boolean light = true;
    private boolean mouse = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        if (AppData.setting.getSetFullScreen()) ViewTools.setFullScreen(this);
        activityFullBinding = ActivityFullBinding.inflate(this.getLayoutInflater());
        setContentView(activityFullBinding.getRoot());
        String uuid = getIntent().getStringExtra("uuid");
        device = Client.getDevice(uuid);
        clientController = Client.getClientController(uuid);
        if (device == null || clientController == null) {
            finish();
            return;
        }
        clientController.setFullView(this);
        // 初始化
        activityFullBinding.barView.setVisibility(View.GONE);
        setNavBarHide(device.showNavBarOnConnect);
        autoRotate = AppData.setting.getAutoRotate();
        activityFullBinding.buttonAutoRotate.setImageResource(autoRotate ? R.drawable.un_auto : R.drawable.auto);
        if (!Objects.equals(device.startApp, "")) {
            activityFullBinding.buttonHome.setVisibility(View.GONE);
            activityFullBinding.buttonSwitch.setVisibility(View.GONE);
            activityFullBinding.buttonApp.setVisibility(View.GONE);
        }
        // 按键监听
        setButtonListener();
        setKeyEvent();
        // 更新textureView；双击“全屏”等竞态下 textureView 可能仍挂在旧父容器上，
        // 先解除再添加，避免 “The specified child already has a parent” 崩溃
        View textureView = clientController.getTextureView();
        ViewParent oldParent = textureView.getParent();
        if (oldParent instanceof ViewGroup) ((ViewGroup) oldParent).removeView(textureView);
        activityFullBinding.textureViewLayout.addView(textureView, 0);
        // 同步鼠标状态(切换回全屏时恢复图标与光标覆盖层)
        mouse = clientController.isMouseMode();
        activityFullBinding.buttonMouse.setImageTintList(ColorStateList.valueOf(getResources().getColor(mouse ? R.color.mouseActive : R.color.clientNavIcon)));
        if (mouse) clientController.showCursorOverlay();
        activityFullBinding.textureViewLayout.post(this::updateMaxSize);
        // 页面自动旋转
        AppData.sensorManager.registerListener(this, AppData.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onPause() {
        AppData.sensorManager.unregisterListener(this);
        if (device == null || clientController == null) {
            super.onPause();
            return;
        }
        if (isChangingConfigurations())
            activityFullBinding.textureViewLayout.removeView(clientController.getTextureView());
        else if (!isClose)
            clientController.handleAction(device.fullToMiniOnRunning ? "changeToMini" : "changeToSmall", ByteBuffer.wrap("changeToFull".getBytes()), 0);
        super.onPause();
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        if (device != null && clientController != null)
            activityFullBinding.textureViewLayout.post(this::updateMaxSize);
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    @Override
    public void onBackPressed() {
    }

    private void updateMaxSize() {
        int width = activityFullBinding.textureViewLayout.getMeasuredWidth();
        int height = activityFullBinding.textureViewLayout.getMeasuredHeight();
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        byteBuffer.putInt(width);
        byteBuffer.putInt(height);
        byteBuffer.flip();
        clientController.handleAction("updateMaxSize", byteBuffer, 0);
        if (!device.customResolutionOnConnect && device.changeResolutionOnRunning)
            clientController.handleAction("writeByteBuffer", ControlPacket.createChangeResolutionEvent((float) width / height), 0);
    }

    public void hide() {
        if (device == null || clientController == null) return;
        try {
            isClose = true;
            activityFullBinding.textureViewLayout.removeView(clientController.getTextureView());
            finish();
        } catch (Exception ignored) {
        }
    }

    // 设置按钮监听
    private void setButtonListener() {
        activityFullBinding.buttonBack.setOnClickListener(v -> clientController.handleAction("buttonBack", null, 0));
        activityFullBinding.buttonHome.setOnClickListener(v -> clientController.handleAction("buttonHome", null, 0));
        activityFullBinding.buttonSwitch.setOnClickListener(v -> clientController.handleAction("buttonSwitch", null, 0));
        activityFullBinding.buttonApp.setOnClickListener(v -> {
            clientController.handleAction("changeToApp", null, 0);
            changeBarView();
        });
        activityFullBinding.buttonMini.setOnClickListener(v -> clientController.handleAction("changeToMini", null, 0));
        activityFullBinding.buttonSmall.setOnClickListener(v -> clientController.handleAction("changeToSmall", null, 0));
        activityFullBinding.buttonClose.setOnClickListener(v -> Client.sendAction(device.uuid, "close", null, 0));
        activityFullBinding.buttonRotate.setOnClickListener(v -> {
            clientController.handleAction("buttonRotate", null, 0);
            changeBarView();
        });
        activityFullBinding.buttonNavBar.setOnClickListener(v -> {
            setNavBarHide(activityFullBinding.navBar.getVisibility() == View.GONE);
            changeBarView();
        });
        activityFullBinding.buttonPower.setOnClickListener(v -> {
            clientController.handleAction("buttonPower", null, 0);
            changeBarView();
        });
        activityFullBinding.buttonLight.setOnClickListener(v -> {
            light = !light;
            activityFullBinding.buttonLight.setImageResource(light ? R.drawable.lightbulb_off : R.drawable.lightbulb);
            clientController.handleAction(light ? "buttonLight" : "buttonLightOff", null, 0);
            changeBarView();
        });
        activityFullBinding.buttonVolume.setOnClickListener(v -> changeVolumeBar());
        // 虚拟鼠标：触控板式，开启后光标高亮
        activityFullBinding.buttonMouse.setOnClickListener(v -> {
            mouse = !mouse;
            clientController.handleAction("toggleMouse", null, 0);
            activityFullBinding.buttonMouse.setImageTintList(ColorStateList.valueOf(getResources().getColor(mouse ? R.color.mouseActive : R.color.clientNavIcon)));
            changeBarView();
        });
        activityFullBinding.buttonScreenshot.setOnClickListener(v -> {
            clientController.handleAction("screenshot", null, 0);
            changeBarView();
        });
        activityFullBinding.buttonStatus.setOnClickListener(v -> {
            showStatusDialog();
            changeBarView();
        });
        // 滑块两端图标：直接切静音/切最大
        activityFullBinding.imageVolumeMute.setOnClickListener(v -> setVolumeByIcon(0));
        activityFullBinding.imageVolumeMax.setOnClickListener(v -> setVolumeByIcon(100));
        activityFullBinding.seekbarVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
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
        activityFullBinding.buttonMore.setOnClickListener(v -> changeBarView());
        activityFullBinding.buttonAutoRotate.setOnClickListener(v -> {
            autoRotate = !autoRotate;
            AppData.setting.setAutoRotate(autoRotate);
            activityFullBinding.buttonAutoRotate.setImageResource(autoRotate ? R.drawable.un_auto : R.drawable.auto);
        });
    }

    // 导航栏隐藏
    private void setNavBarHide(boolean isShow) {
        activityFullBinding.navBar.setVisibility(isShow ? View.VISIBLE : View.GONE);
        activityFullBinding.buttonNavBar.setImageResource(isShow ? R.drawable.not_equal : R.drawable.equals);
        activityFullBinding.textureViewLayout.post(this::updateMaxSize);
        // 菜单按钮始终在深色背景上（全屏画面或半透明黑导航栏），统一用白色图标
        activityFullBinding.buttonMore.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.onBlackBacnground)));
    }

    private void changeBarView() {
        boolean toShowView = activityFullBinding.barView.getVisibility() == View.GONE;
        boolean isLandscape = lastOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE || lastOrientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        // 横屏：工具栏在右上角，从右侧水平滑入；竖屏：工具栏在左下角，从下方垂直滑入
        int tx = isLandscape ? PublicTools.dp2px(40f) : 0;
        int ty = isLandscape ? 0 : PublicTools.dp2px(40f);
        ViewTools.viewAnim(activityFullBinding.barView, toShowView, tx, ty, (isStart -> {
            if (isStart && toShowView) activityFullBinding.barView.setVisibility(View.VISIBLE);
            else if (!isStart && !toShowView) {
                activityFullBinding.barView.setVisibility(View.GONE);
                activityFullBinding.volumeBar.setVisibility(View.GONE);
            }
        }));
    }

    // 展开/收起音量滑块：展开前在后台读取被控机当前音量并定位滑块，老系统读取失败则不展开
    private void changeVolumeBar() {
        if (activityFullBinding.volumeBar.getVisibility() == View.VISIBLE) {
            activityFullBinding.volumeBar.setVisibility(View.GONE);
            return;
        }
        activityFullBinding.volumeBar.setVisibility(View.VISIBLE);
        activityFullBinding.seekbarVolume.setProgress(0);
        new Thread(() -> {
            Pair<Integer, Integer> volumeInfo;
            try {
                volumeInfo = clientController.getVolumeInfo();
            } catch (Exception ignored) {
                volumeInfo = null;
            }
            final Pair<Integer, Integer> volumeInfoFinal = volumeInfo;
            runOnUiThread(() -> {
                if (volumeInfoFinal == null) {
                    activityFullBinding.volumeBar.setVisibility(View.GONE);
                    PublicTools.logToast("FullActivity", AppData.applicationContext.getString(R.string.toast_volume_not_support), true);
                    return;
                }
                // 滑块统一 0-100，服务端按被控机自身音量上限换算
                activityFullBinding.seekbarVolume.setMax(100);
                activityFullBinding.seekbarVolume.setProgress(Math.round(volumeInfoFinal.first * 100f / volumeInfoFinal.second));
                updateVolumeIcon(volumeInfoFinal.first);
            });
        }).start();
    }

    // 音量图标随静音状态切换
    private void updateVolumeIcon(int volume) {
        activityFullBinding.buttonVolume.setImageResource(volume == 0 ? R.drawable.volume_off : R.drawable.volume_up);
    }

    // 点击滑块两端图标：直接切到静音(0)或最大(100)，滑块归位并同步被控机
    private void setVolumeByIcon(int volume) {
        activityFullBinding.seekbarVolume.setProgress(volume);
        clientController.handleAction("setVolume", ControlPacket.createVolumeEvent(volume), 0);
        updateVolumeIcon(volume);
    }

    // 设备状态面板：后台读被控机状态后弹窗展示
    private void showStatusDialog() {
        new Thread(() -> {
            ClientController.DeviceStatus status;
            try {
                status = clientController.getDeviceStatus();
            } catch (Exception ignored) {
                status = null;
            }
            final ClientController.DeviceStatus statusFinal = status;
            runOnUiThread(() -> {
                // shell 返回期间 Activity 可能已被 finish(Home/切小窗/断连)，此时 dialog.show() 会抛 BadTokenException
                if (isFinishing() || isDestroyed()) return;
                if (statusFinal == null) {
                    PublicTools.logToast("FullActivity", getString(R.string.toast_status_failed), true);
                    return;
                }
                DeviceStatusDialog.show(this, device, statusFinal, false, this::showStatusDialog);
            });
        }).start();
    }

    private int lastOrientation = -1;

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (!autoRotate || Sensor.TYPE_ACCELEROMETER != sensorEvent.sensor.getType()) return;
        float[] values = sensorEvent.values;
        float x = values[0];
        float y = values[1];
        int newOrientation = lastOrientation;

        if (x > -3 && x < 3 && y >= 4.5) newOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        else if (y > -3 && y < 3 && x >= 4.5)
            newOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        else if (y > -3 && y < 3 && x <= -4.5)
            newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        else if (x > -3 && x < 3 && y <= -4.5)
            newOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;

        if (lastOrientation != newOrientation) {
            lastOrientation = newOrientation;
            setRequestedOrientation(newOrientation);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    // 设置键盘监听
    private void setKeyEvent() {
        activityFullBinding.editText.requestFocus();
        activityFullBinding.editText.setInputType(InputType.TYPE_NULL);
        activityFullBinding.editText.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                clientController.handleAction("writeByteBuffer", ControlPacket.createKeyEvent(event.getKeyCode(), event.getMetaState()), 0);
                return true;
            }
            return false;
        });
    }
}