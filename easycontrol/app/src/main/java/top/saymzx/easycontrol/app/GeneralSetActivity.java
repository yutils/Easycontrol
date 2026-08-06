package top.saymzx.easycontrol.app;

import android.os.Bundle;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

import top.saymzx.easycontrol.app.databinding.ActivityGeneralSetBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class GeneralSetActivity extends AppCompatActivity {
    private ActivityGeneralSetBinding activityGeneralSetBinding;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        ViewTools.setLocale(this);
        activityGeneralSetBinding = ActivityGeneralSetBinding.inflate(this.getLayoutInflater());
        setContentView(activityGeneralSetBinding.getRoot());
        drawUi();
        setButtonListener();
    }

    // 显示行为设置
    private void drawUi() {
        // 异常断开自动重连
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSwitchCard(this,
                getString(R.string.set_show_reconnect), getString(R.string.set_show_reconnect_detail),
                AppData.setting.getShowReconnect(),
                checked -> AppData.setting.setShowReconnect(checked)).getRoot());
        // 重连倒计时
        List<String> countdownList = new ArrayList<>();
        for (int i = 3; i <= 15; i++) countdownList.add(String.valueOf(i));
        ArrayAdapter<String> countdownAdapter = new ArrayAdapter<>(this, R.layout.item_spinner_item, countdownList);
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSpinnerCard(this,
                getString(R.string.set_countdown_time), getString(R.string.set_countdown_time_detail),
                AppData.setting.getCountdownTime(), countdownAdapter,
                value -> AppData.setting.setCountdownTime(value)).getRoot());
        // 全屏拉伸填充
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSwitchCard(this,
                getString(R.string.set_fill_full), getString(R.string.set_fill_full_detail),
                AppData.setting.getFillFull(),
                checked -> AppData.setting.setFillFull(checked)).getRoot());
        // 沉浸式全屏
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSwitchCard(this,
                getString(R.string.set_set_full_screen), getString(R.string.set_set_full_screen_detail),
                AppData.setting.getSetFullScreen(),
                checked -> AppData.setting.setSetFullScreen(checked)).getRoot());
        // 音频输出声道（0=媒体默认, 1=系统, 2=铃声, 3=媒体, 4=闹钟, 5=通知, 6=蓝牙通话, 7=强制系统, 8=双音多频, 9=语音合成, 10=无障碍）
        String[] channelNames = {"媒体(默认)", "系统", "铃声", "媒体", "闹钟", "通知", "蓝牙通话", "强制系统", "双音多频", "语音合成", "无障碍"};
        List<String> channelList = new ArrayList<>(java.util.Arrays.asList(channelNames));
        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(this, R.layout.item_spinner_item, channelList);
        int currentChannel = Math.min(Math.max(AppData.setting.getAudioChannel(), 0), 10);
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSpinnerCard(this,
                getString(R.string.set_audio_channel), getString(R.string.set_audio_channel_detail),
                channelNames[currentChannel], channelAdapter,
                value -> AppData.setting.setAudioChannel(channelList.indexOf(value))).getRoot());
        // 启用USB设备检测
        activityGeneralSetBinding.setDisplay.addView(ViewTools.createSwitchCard(this,
                getString(R.string.set_enable_usb), getString(R.string.set_enable_usb_detail),
                AppData.setting.getEnableUSB(),
                checked -> {
                    AppData.setting.setEnableUSB(checked);
                    if (checked) AppData.myBroadcastReceiver.checkConnectedUsb();
                }).getRoot());
    }

    private void setButtonListener() {
        activityGeneralSetBinding.backButton.setOnClickListener(v -> finish());
    }
}
