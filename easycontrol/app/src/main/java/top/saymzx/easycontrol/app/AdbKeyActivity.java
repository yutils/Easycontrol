package top.saymzx.easycontrol.app;

import android.os.Bundle;
import android.util.Pair;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;

import top.saymzx.easycontrol.app.adb.AdbKeyPair;
import top.saymzx.easycontrol.app.databinding.ActivityAdbKeyBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class AdbKeyActivity extends AppCompatActivity {
    private ActivityAdbKeyBinding activityAdbKeyBinding;
    private Pair<File, File> adbKeyFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        ViewTools.setLocale(this);
        activityAdbKeyBinding = ActivityAdbKeyBinding.inflate(this.getLayoutInflater());
        setContentView(activityAdbKeyBinding.getRoot());
        adbKeyFile = PublicTools.getAdbKeyFile(this);
        readKey();
        activityAdbKeyBinding.backButton.setOnClickListener(v -> finish());
        activityAdbKeyBinding.ok.setOnClickListener(v -> writeKey());
    }

    // 读取旧的密钥公钥文件
    private void readKey() {
        try {
            byte[] publicKeyBytes = new byte[(int) adbKeyFile.first.length()];
            byte[] privateKeyBytes = new byte[(int) adbKeyFile.second.length()];

            try (FileInputStream stream = new FileInputStream(adbKeyFile.first)) {
                stream.read(publicKeyBytes);
                activityAdbKeyBinding.adbKeyPub.setText(new String(publicKeyBytes));
            }
            try (FileInputStream stream = new FileInputStream(adbKeyFile.second)) {
                stream.read(privateKeyBytes);
                activityAdbKeyBinding.adbKeyPri.setText(new String(privateKeyBytes));
            }
        } catch (IOException ignored) {
        }
    }

    // 写入新的密钥公钥文件（先校验再覆盖，避免无效密钥导致原密钥丢失）
    private void writeKey() {
        try {
            String pubText = String.valueOf(activityAdbKeyBinding.adbKeyPub.getText());
            String priText = String.valueOf(activityAdbKeyBinding.adbKeyPri.getText());
            // 先写入临时文件校验
            java.io.File tmpPub = new File(adbKeyFile.first + ".tmp");
            java.io.File tmpPri = new File(adbKeyFile.second + ".tmp");
            try (FileWriter pubWriter = new FileWriter(tmpPub)) {
                pubWriter.write(pubText);
                pubWriter.flush();
            }
            try (FileWriter priWriter = new FileWriter(tmpPri)) {
                priWriter.write(priText);
                priWriter.flush();
            }
            // 校验密钥有效性
            AdbKeyPair.read(tmpPub, tmpPri);
            // 校验通过，替换原文件
            tmpPub.renameTo(adbKeyFile.first);
            tmpPri.renameTo(adbKeyFile.second);
            AppData.keyPair = AdbKeyPair.read(adbKeyFile.first, adbKeyFile.second);
            Toast.makeText(this, getString(R.string.toast_success), Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}