package top.saymzx.easycontrol.app.helper;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;

// 重连辅助类：异常断开时弹出倒计时对话框，自动重连
public class ConnectHelper {
    private static final Handler uiHandler = new Handler(Looper.getMainLooper());
    private static Dialog currentDialog = null;

    public static void showReconnect(Device device, String errorMsg) {
        if (device == null) return;
        // 临时设备不重连
        if (device.isTempDevice()) return;

        uiHandler.post(() -> {
            // 避免重复弹窗
            if (currentDialog != null && currentDialog.isShowing()) {
                ViewTools.dismiss(currentDialog);
            }
            Activity activity = AppData.mainActivity;
            if (activity == null || activity.isFinishing()) return;
            int countdownSeconds;
            try {
                countdownSeconds = Integer.parseInt(AppData.setting.getCountdownTime());
            } catch (NumberFormatException e) {
                countdownSeconds = 5;
            }
            View view = LayoutInflater.from(activity).inflate(R.layout.module_reconnect, null);
            TextView textView = view.findViewById(R.id.reconnect_text);
            Button cancelButton = view.findViewById(R.id.reconnect_cancel);
            // 倒计时文本
            String deviceName = device.name == null || device.name.isEmpty() ? device.uuid : device.name;
            int[] remaining = {countdownSeconds};
            textView.setText(activity.getString(R.string.reconnect_countdown, deviceName, remaining[0]));

            Dialog dialog = ViewTools.createDialog(activity, true, view);
            currentDialog = dialog;
            // 倒计时
            Handler countdownHandler = new Handler(Looper.getMainLooper());
            Runnable countdownRunnable = new Runnable() {
                @Override
                public void run() {
                    remaining[0]--;
                    if (remaining[0] <= 0) {
                        ViewTools.dismiss(dialog);
                        Client.startDevice(device);
                    } else {
                        textView.setText(activity.getString(R.string.reconnect_countdown, deviceName, remaining[0]));
                        countdownHandler.postDelayed(this, 1000);
                    }
                }
            };
            dialog.setOnCancelListener(d -> {
                uiHandler.removeCallbacksAndMessages(null);
                countdownHandler.removeCallbacks(countdownRunnable);
            });
            dialog.show();

            cancelButton.setOnClickListener(v -> {
                countdownHandler.removeCallbacks(countdownRunnable);
                ViewTools.dismiss(dialog);
            });
            countdownHandler.postDelayed(countdownRunnable, 1000);
        });
    }
}
