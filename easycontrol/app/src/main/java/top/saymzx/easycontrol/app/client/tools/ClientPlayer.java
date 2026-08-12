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
    private static final int SCREENSHOT_EVENT = 5;
    // 心跳超时检测：超过该时间未收到任何数据(含心跳响应)则认为连接断开
    private static final int KEEP_ALIVE_TIMEOUT = 1000 * 10;
    private volatile long lastReceiveTime = System.currentTimeMillis();

    // ===== 远程录屏(客户端把收到的编码帧直接写 MediaMuxer) =====
    // 所有录屏状态只在视频线程接触，避免竞争
    private volatile boolean recordRequested = false;
    private volatile boolean isRecording = false;
    private VideoRecorder recorder;
    // 缓存的视频流头，供录屏初始化
    private int headerCodecType = 0;
    private Pair<Integer, Integer> headerVideoSize;
    private ByteBuffer headerCsd0;
    private ByteBuffer headerCsd1;

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
                    case SCREENSHOT_EVENT:
                        // 远程截图PNG数据，长度0表示截图失败
                        clientController.handleAction("saveScreenshot", clientStream.readByteArrayFromMain(clientStream.readIntFromMain()), 0);
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
            // 缓存流头，供录屏初始化使用。
            // 注意：不能直接缓存原 buffer —— VideoDecode 构造时会消费 csd0/csd1（position 走到 limit），
            // 且 BufferNew 底层数组可能被复用。必须复制一份独立数据，否则录屏初始化拿到的 SPS/PPS 是空的。
            headerCodecType = codecType;
            headerVideoSize = videoSize;
            headerCsd0 = copyCsd(csd0);
            headerCsd1 = csd1 == null ? null : copyCsd(csd1);
            videoDecode = new VideoDecode(videoSize, surface, csd0, csd1, codecType, playHandler, () -> {
                // 解码器致命错误：关闭会话，避免视频解码线程停摆后缓冲无界增长
                byte[] err = ("video" + AppData.applicationContext.getString(R.string.toast_stream_closed)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Client.sendAction(uuid, "close", ByteBuffer.wrap(err), 0);
            });
            while (!Thread.interrupted()) {
                ByteBuffer data = clientStream.readFrameFromVideo();
                // 录屏：收到录制请求后初始化(用已缓存流头)，每帧写入；停止请求后由本线程收尾
                if (recordRequested && recorder == null) {
                    try {
                        recorder = new VideoRecorder(headerVideoSize, headerCsd0, headerCsd1, headerCodecType);
                        isRecording = true;
                    } catch (Exception e) {
                        // 编码格式/容器不支持(如HEVC需API24+)，放弃录屏并提示
                        recordRequested = false;
                        PublicTools.logToast("recorder", AppData.applicationContext.getString(R.string.toast_record_unsupported), true);
                    }
                }
                if (recorder != null) {
                    if (!recordRequested) {
                        recorder.finish();
                        recorder = null;
                        isRecording = false;
                    } else recorder.writeFrame(data);
                }
                videoDecode.decodeIn(data);
            }
        } catch (Exception ignored) {
        } finally {
            if (videoDecode != null) videoDecode.release();
            if (surface != null) surface.release();
            // 会话结束(含异常断开)时兜底关闭录屏，避免残留半成品muxer
            if (recorder != null) {
                recorder.closeQuietly();
                recorder = null;
                isRecording = false;
            }
        }
    }

    // ===== 录屏控制(由控制线程调用，标志位通知视频线程) =====
    public void startRecord() {
        recordRequested = true;
    }

    public void stopRecord() {
        recordRequested = false;
    }

    public boolean isRecording() {
        return isRecording;
    }

    // 复制一份独立的 csd 数据（含前8字节pts，保持与原始帧一致，录屏侧照常跳过）
    private static ByteBuffer copyCsd(ByteBuffer src) {
        byte[] copy = new byte[src.remaining()];
        ByteBuffer view = src.duplicate();
        view.position(0);
        view.get(copy);
        return ByteBuffer.wrap(copy);
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
