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
        // 音频输出声道
        String[] channelNames = getResources().getStringArray(R.array.audio_channels);
        List<String> channelList = new ArrayList<>(java.util.Arrays.asList(channelNames));
        ArrayAdapter<String> channelAdapter = new ArrayAdapter<>(this, R.layout.item_spinner_item, channelList);
        int currentChannel = Math.min(Math.max(AppData.setting.getAudioChannel(), 0), channelNames.length - 1);
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
