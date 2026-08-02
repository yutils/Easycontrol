package top.saymzx.easycontrol.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.MyBroadcastReceiver;

public class UsbActivity extends AppCompatActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sharedPreferences = this.getSharedPreferences("setting", Context.MODE_PRIVATE);
        if (sharedPreferences.getBoolean("isActive", false)) {
            if (AppData.mainActivity == null) startActivity(new Intent(this, MainActivity.class));
            else {
                Intent intent = new Intent();
                intent.setAction(MyBroadcastReceiver.ACTION_UPDATE_USB);
                sendBroadcast(intent);
            }
        }
        finish();
    }
}