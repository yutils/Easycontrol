/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.helper;

import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.ErrnoException;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

import top.saymzx.easycontrol.server.Server;
import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.Options;
import top.saymzx.easycontrol.server.wrappers.SurfaceControl;

public final class VideoEncode {
    private static MediaCodec encedec;
    private static MediaFormat encodecFormat;
    public static boolean isHasChangeConfig = false;
    private static boolean useH265;

    private static IBinder display;

    public static void init() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
        useH265 = Options.supportH265 && EncodecTools.isSupportH265();
        // 协议: 0=H264, 1=H265, 2=AV1（保留扩展，当前仅支持H264/H265）
        ByteBuffer byteBuffer = ByteBuffer.allocate(9);
        byteBuffer.put((byte) (useH265 ? 1 : 0));
        byteBuffer.putInt(Device.videoSize.first);
        byteBuffer.putInt(Device.videoSize.second);
        byteBuffer.flip();
        Server.writeVideo(byteBuffer);
        // 创建显示器
        display = SurfaceControl.createDisplay("easycontrol", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
        // 创建Codec
        createEncodecFormat();
        startEncode();
    }

    private static void createEncodecFormat() throws IOException {
        String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        encedec = MediaCodec.createEncoderByType(codecMime);
        encodecFormat = new MediaFormat();
        encodecFormat.setString(MediaFormat.KEY_MIME, codecMime);
        encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        encodecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
        encodecFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            encodecFormat.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Options.maxFps * 3);
        encodecFormat.setFloat("max-fps-to-encoder", Options.maxFps);
        // display the very first frame, and recover from bad quality when no new frames
        encodecFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        encodecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 低延迟参数：仅设置优先级和延迟，不设置COLOR_RANGE以兼容各类硬件编码器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            encodecFormat.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            encodecFormat.setInteger(MediaFormat.KEY_LATENCY, 1);
        }
    }

    // 初始化编码器
    private static Surface surface;

    public static void startEncode() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
        ControlPacket.sendVideoSizeEvent();
        encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
        encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
        encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 绑定Display和Surface
        surface = encedec.createInputSurface();
        setDisplaySurface(display, surface);
        // 启动编码
        encedec.start();
    }

    public static void stopEncode() {
        encedec.stop();
        encedec.reset();
        surface.release();
    }

    private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.displayInfo.width, Device.displayInfo.height), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
            SurfaceControl.setDisplayLayerStack(display, Device.displayInfo.layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    // 客户端开始录屏时请求立即输出关键帧，避免等最长10秒的I帧周期；部分编码器在
    // 开启INTRA_REFRESH时忽略该请求，客户端也有"等首关键帧"的兜底，此处尽力而为
    public static void requestSyncFrame() {
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encedec.setParameters(params);
        } catch (Exception ignored) {
        }
    }

    // 供截图使用：返回投屏shadow display令牌，captureDisplay可截其合成输出(息屏/折叠也能拿到画面)
    public static IBinder getCaptureDisplay() {
        return display;
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public static void encodeOut() throws IOException {
        // 编解码器出错（IllegalStateException/CodecException）不再吞掉，
        // 向上抛由 executeVideoOut 统一 errorClose 拆会话；否则外层循环会以 100% CPU 空转且会话永不拆除
        int outIndex;
        do outIndex = encedec.dequeueOutputBuffer(bufferInfo, -1); while (outIndex < 0);
        ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
        if (buffer == null) return;
        ControlPacket.sendVideoEvent(bufferInfo.presentationTimeUs, buffer);
        encedec.releaseOutputBuffer(outIndex, false);
    }

    public static void release() {
        try {
            stopEncode();
            encedec.release();
            SurfaceControl.destroyDisplay(display);
        } catch (Exception ignored) {
        }
    }

}
