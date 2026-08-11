package top.saymzx.easycontrol.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;

import androidx.appcompat.app.AppCompatActivity;

import com.kotlinx.appUpdate.AppUpdate;

import java.io.InputStream;

import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.tools.AdbTools;
import top.saymzx.easycontrol.app.databinding.ActivityMainBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.helper.DeviceListAdapter;
import top.saymzx.easycontrol.app.helper.MyBroadcastReceiver;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 100;

    private ActivityMainBinding activityMainBinding;
    public DeviceListAdapter deviceListAdapter;
    private boolean checkUpdate = true;  // 从 setting 读取，OK
    // 广播
    private final MyBroadcastReceiver myBroadcastReceiver = new MyBroadcastReceiver();

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppData.init(this);
        AppData.myBroadcastReceiver = myBroadcastReceiver;
        ViewTools.setStatusAndNavBar(this);
        ViewTools.setLocale(this);
        // 主动申请所需权限
        requestNecessaryPermissions();
        activityMainBinding = ActivityMainBinding.inflate(this.getLayoutInflater());
        setContentView(activityMainBinding.getRoot());
        // 设置设备列表适配器
        deviceListAdapter = new DeviceListAdapter(this);
        activityMainBinding.devicesList.setAdapter(deviceListAdapter);
        myBroadcastReceiver.setDeviceListAdapter(deviceListAdapter);
        // 设置按钮监听
        setButtonListener();
        // 注册广播监听
        myBroadcastReceiver.register(this);
        // 重置已连接设备
        myBroadcastReceiver.resetUSB();
        // 自启动设备
        AppData.uiHandler.postDelayed(() -> {
            for (Device device : AdbTools.devicesList)
                if (device.connectOnStart) Client.startDevice(device);
        }, 2000);
        //检查更新
        if (savedInstanceState != null) {
            checkUpdate = savedInstanceState.getBoolean("checkUpdate", false);
        }
        if (checkUpdate) {
            //更新地址
            AppUpdate.baseUrl = "http://apk.kotlinx.com:9999";
            AppUpdate.showCheckUpdateErrorToast = false;
            AppUpdate apk = new AppUpdate();
            apk.checkAndUpdate(this);
            checkUpdate = false;
        }
    }

    // 主动申请运行时所需权限
    private void requestNecessaryPermissions() {
        // 1. 悬浮窗权限（SYSTEM_ALERT_WINDOW）——小窗/迷你模式必需
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
        // 2. 运行时权限：文件读取（Android 10以下）、通知（Android 13+）
        java.util.List<String> permissions = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            String[] permArray = new String[permissions.size()];
            permissions.toArray(permArray);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(permArray, REQUEST_CODE_PERMISSIONS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    // 权限被拒绝，可在此提示用户
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        myBroadcastReceiver.unRegister(this);
        AppData.mainActivity = null;
        super.onDestroy();
    }

    // 设置按钮监听
    private void setButtonListener() {
        activityMainBinding.buttonAdd.setOnClickListener(v -> startActivity(new Intent(this, DeviceDetailActivity.class)));
        activityMainBinding.buttonSet.setOnClickListener(v -> startActivity(new Intent(this, SetActivity.class)));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK && requestCode == 1 && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                deviceListAdapter.pushFile(null, null);
            } else try {
                String fileName = "easycontrol_push_file";
                ContentResolver contentProvider = getContentResolver();
                InputStream inputStream = contentProvider.openInputStream(uri);
                //根据Uri查询文件名
                try {
                    try (Cursor cursor = contentProvider.query(uri, null, null, null, null)) {
                        if (cursor != null) {
                            cursor.moveToFirst();
                            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            fileName = cursor.getString(nameIndex);
                        }
                    }
                    deviceListAdapter.pushFile(inputStream, fileName);
                } catch (Exception e) {
                    if (inputStream != null) try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            } catch (Exception ignored) {
                deviceListAdapter.pushFile(null, null);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("checkUpdate", checkUpdate);
    }
}