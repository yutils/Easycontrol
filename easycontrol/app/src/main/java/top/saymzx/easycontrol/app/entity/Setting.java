package top.saymzx.easycontrol.app.entity;

import android.content.SharedPreferences;

import java.util.UUID;

public final class Setting {
    private final SharedPreferences sharedPreferences;

    private final SharedPreferences.Editor editor;

    public String getLocale() {
        return sharedPreferences.getString("locale", "");
    }

    public void setLocale(String value) {
        editor.putString("locale", value);
        editor.apply();
    }

    public boolean getAutoRotate() {
        return sharedPreferences.getBoolean("autoRotate", true);
    }

    public void setAutoRotate(boolean value) {
        editor.putBoolean("autoRotate", value);
        editor.apply();
    }

    public String getLocalUUID() {
        if (!sharedPreferences.contains("UUID")) {
            editor.putString("UUID", UUID.randomUUID().toString());
            editor.apply();
        }
        return sharedPreferences.getString("UUID", "");
    }

    // 异常断开时自动重连（默认关闭）
    public boolean getShowReconnect() {
        return sharedPreferences.getBoolean("showReconnect", false);
    }

    public void setShowReconnect(boolean value) {
        editor.putBoolean("showReconnect", value);
        editor.apply();
    }

    // 重连倒计时秒数
    public String getCountdownTime() {
        return sharedPreferences.getString("countdownTime", "5");
    }

    public void setCountdownTime(String value) {
        editor.putString("countdownTime", value);
        editor.apply();
    }

    // 全屏拉伸填充
    public boolean getFillFull() {
        return sharedPreferences.getBoolean("fillFull", false);
    }

    public void setFillFull(boolean value) {
        editor.putBoolean("fillFull", value);
        editor.apply();
    }

    // 沉浸式全屏
    public boolean getSetFullScreen() {
        return sharedPreferences.getBoolean("setFullScreen", true);
    }

    public void setSetFullScreen(boolean value) {
        editor.putBoolean("setFullScreen", value);
        editor.apply();
    }

    // 音频输出声道
    public int getAudioChannel() {
        return sharedPreferences.getInt("audioChannel", 0);
    }

    public void setAudioChannel(int value) {
        editor.putInt("audioChannel", value);
        editor.apply();
    }

    // 启用USB设备检测
    public boolean getEnableUSB() {
        return sharedPreferences.getBoolean("enableUSB", true);
    }

    public void setEnableUSB(boolean value) {
        editor.putBoolean("enableUSB", value);
        editor.apply();
    }

    public Setting(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
        this.editor = sharedPreferences.edit();
    }
}
