package top.saymzx.easycontrol.app.client.view;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.tools.ClientController;
import top.saymzx.easycontrol.app.databinding.DialogDeviceStatusBinding;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.PublicTools;

// 设备状态弹窗：白卡片展示被控机电量/系统/存储等信息
public class DeviceStatusDialog {

    public static void show(Context context, Device device, ClientController.DeviceStatus s, boolean isOverlay, Runnable onRefresh) {
        DialogDeviceStatusBinding b = DialogDeviceStatusBinding.inflate(LayoutInflater.from(context));
        // 设备名/地址
        String name = device.isTempDevice() ? s.model : device.name;
        b.statusName.setText(name == null || name.isEmpty() || "----".equals(name) ? s.model : name);
        String addr;
        if (device.isLinkDevice()) addr = context.getString(R.string.dialog_status_conn_usb);
        else if (device.address != null && !device.address.isEmpty()) addr = device.address + ":" + device.adbPort;
        else addr = "—";
        b.statusAddress.setText(addr);
        // 电量
        int percent = (s.batteryLevel >= 0 && s.batteryScale > 0) ? s.batteryLevel * 100 / s.batteryScale : -1;
        if (percent >= 0) {
            b.statusBattery.setText(percent + "%");
            b.statusBatteryBar.setProgress(percent);
        } else {
            b.statusBattery.setText("—");
            b.statusBatteryBar.setProgress(0);
        }
        StringBuilder detail = new StringBuilder(statusText(context, s.batteryStatus));
        if (s.temperature > 0) detail.append(" · ").append(String.format(Locale.US, "%.1f", s.temperature / 10f)).append("℃");
        if (s.voltage > 0) detail.append(" · ").append(String.format(Locale.US, "%.2f", s.voltage / 1000f)).append("V");
        if (!s.batteryTech.isEmpty()) detail.append(" · ").append(s.batteryTech);
        b.statusBatteryDetail.setText(detail);
        // 信息行
        b.statusModel.setText(s.model.isEmpty() ? "—" : s.model);
        b.statusManufacturer.setText(s.manufacturer.isEmpty() ? "—" : s.manufacturer);
        String androidText = "Android " + (s.release.isEmpty() ? "—" : s.release);
        if (!s.sdk.isEmpty()) androidText += " (API " + s.sdk + ")";
        b.statusAndroid.setText(androidText);
        b.statusKernel.setText(s.kernel.isEmpty() ? "—" : s.kernel);
        b.statusResolution.setText(s.resolution.isEmpty() ? "—" : s.resolution);
        b.statusRam.setText(s.ramKb > 0 ? String.format(Locale.US, "%.1f GB", s.ramKb / 1048576f) : "—");
        b.statusStorage.setText(s.storageTotalKb > 0
                ? context.getString(R.string.dialog_status_storage_fmt,
                String.format(Locale.US, "%.1f GB", s.storageUsedKb / 1048576f),
                String.format(Locale.US, "%.1f GB", s.storageTotalKb / 1048576f))
                : "—");
        b.statusBrightness.setText(s.brightnessAuto
                ? context.getString(R.string.dialog_status_brightness_auto)
                : s.brightness >= 0 ? (s.brightness * 100 / 255) + "%" : "—");
        b.statusUptime.setText(formatUptime(context, s.uptimeSec));
        b.statusConnection.setText(device.isLinkDevice()
                ? context.getString(R.string.dialog_status_conn_usb)
                : context.getString(R.string.dialog_status_conn_wireless));
        b.statusEncoding.setText((device.useH265 ? "H.265" : "H.264") + " · " + device.maxFps + "fps"
                + (device.maxVideoBit > 0 ? " · " + device.maxVideoBit + "Mbps" : ""));
        // 弹窗
        AlertDialog dialog = new AlertDialog.Builder(context).setView(b.getRoot()).setCancelable(true).create();
        dialog.setCanceledOnTouchOutside(true);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            // 居中显示
            dialog.getWindow().setGravity(Gravity.CENTER);
            // 悬浮窗弹窗(Application 上下文)没有 Activity 窗口 token，必须显式设为 overlay 类型
            if (isOverlay) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                else
                    dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_PHONE);
            }
        }
        b.statusRefresh.setOnClickListener(v -> {
            dialog.dismiss();
            if (onRefresh != null) onRefresh.run();
        });
        b.statusClose.setOnClickListener(v -> dialog.dismiss());
        // 内容较多，按屏高限制卡片高度，长屏幕/横屏下也能完整滚动查看
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            int screenHeight;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                screenHeight = wm.getCurrentWindowMetrics().getBounds().height();
            } else {
                DisplayMetrics dm = new DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getMetrics(dm);
                screenHeight = dm.heightPixels;
            }
            int maxH = (int) (screenHeight * 0.85f);
            b.getRoot().measure(
                    View.MeasureSpec.makeMeasureSpec(PublicTools.dp2px(300f), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (b.getRoot().getMeasuredHeight() > maxH) {
                ViewGroup.LayoutParams lp = b.getRoot().getLayoutParams();
                if (lp == null) lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, maxH);
                else lp.height = maxH;
                b.getRoot().setLayoutParams(lp);
            }
        }
        dialog.show();
        // 单行文字过长时走马灯滚动（setSelected 即触发，无需抢占焦点）
        TextView[] marqueeViews = {b.statusName, b.statusAddress, b.statusModel, b.statusManufacturer, b.statusAndroid,
                b.statusKernel, b.statusResolution, b.statusRam, b.statusStorage, b.statusBrightness,
                b.statusUptime, b.statusConnection, b.statusEncoding};
        b.getRoot().post(() -> {
            for (TextView v : marqueeViews) v.setSelected(true);
        });
    }

    // 充电状态文本
    private static String statusText(Context context, int status) {
        switch (status) {
            case 2:
                return context.getString(R.string.dialog_status_charging);
            case 3:
                return context.getString(R.string.dialog_status_discharging);
            case 4:
                return context.getString(R.string.dialog_status_not_charging);
            case 5:
                return context.getString(R.string.dialog_status_full);
            default:
                return context.getString(R.string.dialog_status_unknown);
        }
    }

    // 运行时长：天/时/分
    private static String formatUptime(Context context, long sec) {
        if (sec <= 0) return "—";
        long days = sec / 86400;
        long hours = (sec % 86400) / 3600;
        long mins = (sec % 3600) / 60;
        if (days > 0) return context.getString(R.string.dialog_status_uptime_fmt_dh, days, hours);
        if (hours > 0) return context.getString(R.string.dialog_status_uptime_fmt_hm, hours, mins);
        return context.getString(R.string.dialog_status_uptime_fmt_m, mins);
    }
}
