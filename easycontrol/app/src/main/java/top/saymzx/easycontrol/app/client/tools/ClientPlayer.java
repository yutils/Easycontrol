package top.saymzx.easycontrol.app.client.tools;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Pair;
import android.view.Surface;

import java.nio.ByteBuffer;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.client.Client;
import top.saymzx.easycontrol.app.client.decode.AudioDecode;
import top.saymzx.easycontrol.app.client.decode.VideoDecode;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.PublicTools;

public class ClientPlayer {
    private volatile boolean isClose = false;
    private final String uuid;
    private final ClientController clientController;
    private final ClientStream clientStream;
    private final Thread mainStreamInThread = new Thread(this::mainStreamIn);
    private final Thread videoStreamInThread = new Thread(this::videoStreamIn);
    private Handler playHandler = null;
    private final HandlerThread playHandlerThread = new HandlerThread("easycontrol_play", Thread.MAX_PRIORITY);
    private static final int AUDIO_EVENT = 1;
    private static final int CLIPBOARD_EVENT = 2;
    private static final int CHANGE_SIZE_EVENT = 3;
    private static final int KEEP_ALIVE_EVENT = 4;
    // 心跳超时检测：超过该时间未收到任何数据(含心跳响应)则认为连接断开
    private static final int KEEP_ALIVE_TIMEOUT = 1000 * 10;
    private volatile long lastReceiveTime = System.currentTimeMillis();

    public ClientPlayer(String uuid, ClientStream clientStream) {
        this.uuid = uuid;
        clientController = Client.getClientController(uuid);
        this.clientStream = clientStream;
        if (clientController == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            playHandlerThread.start();
            playHandler = new Handler(playHandlerThread.getLooper());
        }
        mainStreamInThread.start();
        videoStreamInThread.start();
    }

    private void mainStreamIn() {
        AudioDecode audioDecode = null;
        boolean useOpus = true;
        try {
            byte audioFlag = clientStream.readByteFromMain();
            PublicTools.logToast("player", "audio flag=" + audioFlag, false);
            if (audioFlag == 1)
                useOpus = clientStream.readByteFromMain() == 1;
            lastReceiveTime = System.currentTimeMillis();
            // 循环处理报文
            while (!Thread.interrupted()) {
                byte type = clientStream.readByteFromMain();
                lastReceiveTime = System.currentTimeMillis();
                switch (type) {
                    case AUDIO_EVENT:
                        ByteBuffer audioFrame = clientStream.readFrameFromMain();
                        if (audioDecode != null) audioDecode.decodeIn(audioFrame);
                        else {
                            audioDecode = new AudioDecode(useOpus, audioFrame, playHandler);
                            PublicTools.logToast("player", "AudioDecode created, useOpus=" + useOpus, false);
                        }
                        break;
                    case CLIPBOARD_EVENT:
                        clientController.handleAction("setClipBoard", clientStream.readByteArrayFromMain(clientStream.readIntFromMain()), 0);
                        break;
                    case CHANGE_SIZE_EVENT:
                        clientController.handleAction("updateVideoSize", clientStream.readByteArrayFromMain(8), 0);
                        break;
                    case KEEP_ALIVE_EVENT:
                        // 心跳响应，无需处理
                        break;
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            PublicTools.logToast("player", e.toString(), false);
        } finally {
            if (audioDecode != null) audioDecode.release();
        }
    }

    private void videoStreamIn() {
        VideoDecode videoDecode = null;
        Surface surface = null;
        try {
            int codecType = clientStream.readByteFromVideo();
            Pair<Integer, Integer> videoSize = new Pair<>(clientStream.readIntFromVideo(), clientStream.readIntFromVideo());
            surface = new Surface(clientController.getTextureView().getSurfaceTexture());
            ByteBuffer csd0 = clientStream.readFrameFromVideo();
            // H264需要csd1(PPS)，H265(1)和AV1(2)不需要
            ByteBuffer csd1 = (codecType == 0) ? clientStream.readFrameFromVideo() : null;
            videoDecode = new VideoDecode(videoSize, surface, csd0, csd1, codecType, playHandler, () -> {
                // 解码器致命错误：关闭会话，避免视频解码线程停摆后缓冲无界增长
                byte[] err = ("video" + AppData.applicationContext.getString(R.string.toast_stream_closed)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Client.sendAction(uuid, "close", ByteBuffer.wrap(err), 0);
            });
            while (!Thread.interrupted()) videoDecode.decodeIn(clientStream.readFrameFromVideo());
        } catch (Exception ignored) {
        } finally {
            if (videoDecode != null) videoDecode.release();
            if (surface != null) surface.release();
        }
    }

    public void close() {
        if (isClose) return;
        isClose = true;
        mainStreamInThread.interrupt();
        videoStreamInThread.interrupt();
        // 延迟退出 playHandlerThread，等待视图移除后再退出，避免硬件渲染器访问已死的 Looper
        AppData.uiHandler.post(() -> playHandlerThread.quitSafely());
    }

    // 检查心跳是否超时（连接异常断开）
    public boolean isKeepAliveTimeout() {
        return System.currentTimeMillis() - lastReceiveTime > KEEP_ALIVE_TIMEOUT;
    }
}
