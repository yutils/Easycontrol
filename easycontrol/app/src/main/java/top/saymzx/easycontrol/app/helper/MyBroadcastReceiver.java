package top.saymzx.easycontrol.app.helper;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Process;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.adb.UsbChannel;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.AdbTools;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;

public class MyBroadcastReceiver extends BroadcastReceiver {

    public static final String ACTION_UPDATE_USB = "top.saymzx.easycontrol.app.UPDATE_USB";
    private static final String ACTION_USB_PERMISSION = "top.saymzx.easycontrol.app.USB_PERMISSION";
    public static final String ACTION_UPDATE_DEVICE_LIST = "top.saymzx.easycontrol.app.UPDATE_DEVICE_LIST";
    public static final String ACTION_CONTROL = "top.saymzx.easycontrol.app.CONTROL";
    private static final String ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF";

    private DeviceListAdapter deviceListAdapter;
    // 需要自动连接的默认USB设备序列号集合
    private final java.util.Set<String> needStartDefaultUSB = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(Boolean.TRUE);

    // 注册广播
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void register(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_UPDATE_USB);
        filter.addAction(ACTION_UPDATE_DEVICE_LIST);
        filter.addAction(ACTION_CONTROL);
        filter.addAction(ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(this, filter);
    }

    public void unRegister(Context context) {
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action))
            AppData.uiHandler.postDelayed(() -> onConnectUsb(context, intent), 1000);
        else if (ACTION_USB_PERMISSION.equals(action)) onGetUsbPer(intent);
        else if (ACTION_UPDATE_USB.equals(action)) updateUSB();
        else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) onCutUsb(intent);
        else if (ACTION_SCREEN_OFF.equals(action)) handleScreenOff();
        else if (ACTION_UPDATE_DEVICE_LIST.equals(action)) {
            if (deviceListAdapter != null) deviceListAdapter.update();
        } else if (ACTION_CONTROL.equals(action)) handleControl(intent);
    }


    public void setDeviceListAdapter(DeviceListAdapter deviceListAdapter) {
        this.deviceListAdapter = deviceListAdapter;
    }

    private void handleScreenOff() {
        for (Device device : AdbTools.devicesList) Client.sendAction(device.uuid, "close", null, 0);
    }

    private void handleControl(Intent intent) {
        // 只接受本应用自身发送的控制广播，防止任意 App 触发 shell 命令/关闭会话
        if (Binder.getCallingUid() != Process.myUid()) return;
        String action = intent.getStringExtra("action");
        String uuid = intent.getStringExtra("uuid");
        if (action == null || uuid == null) return;
        if (action.equals("runShell")) {
            String cmd = intent.getStringExtra("cmd");
            if (cmd == null) return;
            Client.sendAction(uuid, action, ByteBuffer.wrap(cmd.getBytes()), 0);
        } else Client.sendAction(uuid, action, null, 0);
    }

    // 请求USB设备权限
    @SuppressLint({"MutableImplicitPendingIntent", "UnspecifiedImmutableFlag"})
    private void onConnectUsb(Context context, Intent intent) {
        if (!AppData.setting.getEnableUSB()) return;
        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (usbDevice == null || AppData.usbManager == null) return;
        if (!AppData.usbManager.hasPermission(usbDevice)) {
            Intent usbPermissionIntent = new Intent(ACTION_USB_PERMISSION);
            usbPermissionIntent.setPackage(AppData.applicationContext.getPackageName());
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 1, usbPermissionIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            AppData.usbManager.requestPermission(usbDevice, permissionIntent);
        } else {
            // 已有权限，直接处理
            onGetUsbPer(intent);
        }
    }

    // USB权限获取后处理
    private void onGetUsbPer(Intent intent) {
        if (!AppData.setting.getEnableUSB()) return;
        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (usbDevice == null) return;
        String uuid;
        try {
            uuid = usbDevice.getSerialNumber();
        } catch (SecurityException e) {
            return;
        }
        if (uuid == null) return;
        updateUSB();
        // 如果设备设置了启动时连接，加入自动连接队列
        Device device = AppData.dbHelper.getByUUID(uuid);
        if (device != null && device.connectOnStart) {
            needStartDefaultUSB.add(uuid);
            AppData.uiHandler.postDelayed(() -> {
                if (needStartDefaultUSB.remove(uuid)) {
                    Device d = AppData.dbHelper.getByUUID(uuid);
                    if (d != null) Client.startDevice(d);
                }
            }, 1000);
        }
    }

    // USB设备拔出处理：触发异常断开重连
    private void onCutUsb(Intent intent) {
        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        // 拔出设备时权限可能已丢失，getSerialNumber 可能抛 SecurityException
        String uuid = null;
        if (usbDevice != null) {
            try {
                uuid = usbDevice.getSerialNumber();
            } catch (SecurityException ignored) {
            }
        }
        if (uuid == null) {
            // 无法获取序列号，重新同步USB列表，并通知已消失的设备关闭
            java.util.Set<String> oldUuids = new java.util.HashSet<>(AdbTools.usbDevicesList.keySet());
            updateUSB();
            for (String oldUuid : oldUuids) {
                if (!AdbTools.usbDevicesList.containsKey(oldUuid)) {
                    needStartDefaultUSB.remove(oldUuid);
                    byte[] err = ("usb" + AppData.applicationContext.getString(R.string.toast_stream_closed) + oldUuid).getBytes(StandardCharsets.UTF_8);
                    Client.sendAction(oldUuid, "close", ByteBuffer.wrap(err), 0);
                }
            }
            if (deviceListAdapter != null) deviceListAdapter.update();
            return;
        }
        // 从已连接USB列表和自动连接队列中移除
        AdbTools.usbDevicesList.remove(uuid);
        needStartDefaultUSB.remove(uuid);
        // 通知对应的Client异常断开（带error，触发重连）
        byte[] err = ("usb" + AppData.applicationContext.getString(R.string.toast_stream_closed) + uuid).getBytes(StandardCharsets.UTF_8);
        Client.sendAction(uuid, "close", ByteBuffer.wrap(err), 0);
        if (deviceListAdapter != null) deviceListAdapter.update();
    }

    // 检查已连接的USB设备（enableUSB开关开启时调用）
    public synchronized void checkConnectedUsb() {
        if (!AppData.setting.getEnableUSB()) return;
        if (AppData.usbManager == null) return;
        for (Map.Entry<String, UsbDevice> entry : AppData.usbManager.getDeviceList().entrySet()) {
            UsbDevice usbDevice = entry.getValue();
            if (usbDevice == null) continue;
            if (AppData.usbManager.hasPermission(usbDevice)) {
                String uuid;
                try {
                    uuid = usbDevice.getSerialNumber();
                } catch (SecurityException e) {
                    continue;
                }
                if (uuid == null) continue;
                if (!AdbTools.usbDevicesList.containsKey(uuid)) {
                    // 发现新连接的USB设备
                    Device device = AppData.dbHelper.getByUUID(uuid);
                    if (device == null) {
                        device = new Device(uuid, Device.TYPE_LINK);
                        device.address = uuid;
                        device.name = AppData.dbHelper.getDefaultDeviceName();
                        AppData.dbHelper.insert(device);
                    }
                    AdbTools.usbDevicesList.put(uuid, usbDevice);
                    // 如果设置了启动时连接，自动连接
                    if (device.connectOnStart) {
                        needStartDefaultUSB.add(uuid);
                        String finalUuid = uuid;
                        AppData.uiHandler.postDelayed(() -> {
                            if (needStartDefaultUSB.remove(finalUuid)) {
                                Client.startDevice(AppData.dbHelper.getByUUID(finalUuid));
                            }
                        }, 1000);
                    }
                }
            }
        }
        if (deviceListAdapter != null) deviceListAdapter.update();
    }

    public synchronized void updateUSB() {
        if (AppData.usbManager == null) return;
        AdbTools.usbDevicesList.clear();
        for (Map.Entry<String, UsbDevice> entry : AppData.usbManager.getDeviceList().entrySet()) {
            UsbDevice usbDevice = entry.getValue();
            if (usbDevice == null) continue;
            if (AppData.usbManager.hasPermission(usbDevice)) {
                // 有线设备使用序列号作为唯一标识符
                String uuid;
                try {
                    uuid = usbDevice.getSerialNumber();
                } catch (SecurityException e) {
                    continue;
                }
                if (uuid == null) continue;
                // 若没有该设备，则新建设备
                Device device = AppData.dbHelper.getByUUID(uuid);
                if (device == null) {
                    device = new Device(uuid, Device.TYPE_LINK);
                    device.address = uuid;
                    device.name = AppData.dbHelper.getDefaultDeviceName();
                    AppData.dbHelper.insert(device);
                }
                AdbTools.usbDevicesList.put(uuid, usbDevice);
            }
        }
        if (deviceListAdapter != null) deviceListAdapter.update();
    }

    public synchronized void resetUSB() {
        if (AppData.usbManager == null) return;
        for (Map.Entry<String, UsbDevice> entry : AppData.usbManager.getDeviceList().entrySet()) {
            try {
                UsbDevice usbDevice = entry.getValue();
                if (usbDevice == null) continue;
                if (AppData.usbManager.hasPermission(usbDevice)) new UsbChannel(usbDevice).close();
            } catch (Exception ignored) {
            }
        }
    }

}
