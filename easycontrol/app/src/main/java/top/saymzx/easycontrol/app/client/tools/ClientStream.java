package top.saymzx.easycontrol.app.client.tools;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import top.saymzx.easycontrol.app.BuildConfig;
import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.adb.Adb;
import top.saymzx.easycontrol.app.buffer.BufferStream;
import top.saymzx.easycontrol.app.client.decode.DecodecTools;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.entity.Device;
import top.saymzx.easycontrol.app.entity.MyInterface;
import top.saymzx.easycontrol.app.helper.PublicTools;

public class ClientStream {
    private volatile boolean isClose = false;
    private volatile boolean connectDirect = false;
    private Adb adb;
    private Socket mainSocket;
    private Socket videoSocket;
    private OutputStream mainOutputStream;
    private DataInputStream mainDataInputStream;
    private DataInputStream videoDataInputStream;
    private BufferStream mainBufferStream;
    private BufferStream videoBufferStream;
    private BufferStream shell;
    private Thread connectThread = null;
    private static final String serverName = "/data/local/tmp/easycontrol_server_" + BuildConfig.VERSION_CODE + ".jar";
    private static final boolean supportH265 = DecodecTools.isSupportH265();
    private static final boolean supportOpus = DecodecTools.isSupportOpus();

    private static final int timeoutDelay = 1000 * 15;

    public ClientStream(Device device, MyInterface.MyFunctionBoolean handle) {
        // 幂等保护：确保 handle 只被调用一次，避免超时后连接成功导致重复注册
        AtomicBoolean handleCalled = new AtomicBoolean(false);
        // 超时
        Thread timeOutThread = new Thread(() -> {
            try {
                Thread.sleep(timeoutDelay);
                // 仅在"连接未在时限内完成"时超时处理；连接已成功则绝不再 close，避免刚注册的会话被杀
                if (handleCalled.compareAndSet(false, true)) {
                    PublicTools.logToast("stream", AppData.applicationContext.getString(R.string.toast_timeout), true);
                    handle.run(false);
                    if (connectThread != null) connectThread.interrupt();
                    // 超时后关闭已建立的连接，避免资源泄漏和孤儿连接
                    close();
                }
            } catch (InterruptedException ignored) {
            }
        });
        // 连接
        connectThread = new Thread(() -> {
            try {
                adb = AdbTools.connectADB(device);
                startServer(device);
                connectServer(device);
                if (handleCalled.compareAndSet(false, true)) handle.run(true);
            } catch (Exception e) {
                PublicTools.logToast("stream", getConnectErrorMessage(e), true);
                if (handleCalled.compareAndSet(false, true)) handle.run(false);
            } finally {
                timeOutThread.interrupt();
            }
        });
        connectThread.start();
        timeOutThread.start();
    }

    // 连接失败提示：区分「网络不通」与「被设备拒绝(未开网络调试/未授权)」，便于用户定位原因
    private String getConnectErrorMessage(Exception e) {
        if (e instanceof UnknownHostException || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException)
            return AppData.applicationContext.getString(R.string.toast_connect_network_error);
        if (e instanceof ConnectException || String.valueOf(e.getMessage()).contains("ADB连接失败"))
            return AppData.applicationContext.getString(R.string.toast_connect_rejected);
        return e.toString();
    }

    // 启动Server
    private void startServer(Device device) throws Exception {
        // 先清理可能残留的旧server进程，避免端口占用
        adb.runAdbCmd("pkill -f easycontrol_server 2>/dev/null; sleep 0.2");
        if (BuildConfig.ENABLE_DEBUG_FEATURE || !adb.runAdbCmd("ls /data/local/tmp/easycontrol_*").contains(serverName)) {
            adb.runAdbCmd("rm /data/local/tmp/easycontrol_* ");
            adb.pushFile(AppData.applicationContext.getResources().openRawResource(R.raw.easycontrol_server), serverName, null);
        }
        shell = adb.getShell();
        PublicTools.logToast("stream", "startServer isAudio=" + device.isAudio + " address=" + device.address + " serverPort=" + device.serverPort, false);
        shell.write(ByteBuffer.wrap(("app_process -Djava.class.path=" + serverName + " / top.saymzx.easycontrol.server.Server"
                + " serverPort=" + device.serverPort
                + " listenClip=" + (device.listenClip ? 1 : 0)
                + " isAudio=" + (device.isAudio ? 1 : 0)
                + " maxSize=" + device.maxSize
                + " maxFps=" + device.maxFps
                + " maxVideoBit=" + device.maxVideoBit
                + " keepAwake=" + (device.keepWakeOnRunning ? 1 : 0)
                + " supportH265=" + ((device.useH265 && supportH265) ? 1 : 0)
                + " supportOpus=" + (supportOpus ? 1 : 0)
                + " startApp=" + device.startApp + " \n").getBytes()));
    }

    // 连接Server
    private void connectServer(Device device) throws Exception {
        Thread.sleep(50);
        int reTry = 40;
        int reTryTime = timeoutDelay / reTry;
        if (!device.isLinkDevice()) {
            long startTime = System.currentTimeMillis();
            boolean mainConn = false;
            InetSocketAddress inetSocketAddress = new InetSocketAddress(PublicTools.getIp(device.address), device.serverPort);
            for (int i = 0; i < reTry; i++) {
                try {
                    if (!mainConn) {
                        mainSocket = new Socket();
                        mainSocket.connect(inetSocketAddress, timeoutDelay / 2);
                        mainConn = true;
                    }
                    videoSocket = new Socket();
                    videoSocket.connect(inetSocketAddress, timeoutDelay / 2);
                    mainOutputStream = mainSocket.getOutputStream();
                    mainDataInputStream = new DataInputStream(mainSocket.getInputStream());
                    videoDataInputStream = new DataInputStream(videoSocket.getInputStream());
                    connectDirect = true;
                    return;
                } catch (Exception ignored) {
                    if (mainSocket != null) mainSocket.close();
                    if (videoSocket != null) videoSocket.close();
                    mainSocket = null;
                    videoSocket = null;
                    mainConn = false;
                    // 如果超时，直接跳出循环
                    if (System.currentTimeMillis() - startTime >= timeoutDelay / 2 - 1000)
                        i = reTry;
                    else Thread.sleep(reTryTime);
                }
            }
        }
        // 直连失败尝试ADB中转
        for (int i = 0; i < reTry; i++) {
            try {
                if (mainBufferStream == null) mainBufferStream = adb.tcpForward(device.serverPort);
                // 为了减少adb同步阻塞的问题，此处分开音视频流
                if (videoBufferStream == null)
                    videoBufferStream = adb.tcpForward(device.serverPort);
                return;
            } catch (Exception ignored) {
                Thread.sleep(reTryTime);
            }
        }
        throw new Exception(AppData.applicationContext.getString(R.string.toast_connect_server));
    }

    public String runShell(String cmd) throws Exception {
        return adb.runAdbCmd(cmd);
    }

    public byte readByteFromMain() throws IOException, InterruptedException {
        if (connectDirect) return mainDataInputStream.readByte();
        else return mainBufferStream.readByte();
    }

    public byte readByteFromVideo() throws IOException, InterruptedException {
        if (connectDirect) return videoDataInputStream.readByte();
        else return videoBufferStream.readByte();
    }

    public int readIntFromMain() throws IOException, InterruptedException {
        if (connectDirect) return mainDataInputStream.readInt();
        else return mainBufferStream.readInt();
    }

    public int readIntFromVideo() throws IOException, InterruptedException {
        if (connectDirect) return videoDataInputStream.readInt();
        else return videoBufferStream.readInt();
    }

    public ByteBuffer readByteArrayFromMain(int size) throws IOException, InterruptedException {
        if (connectDirect) {
            byte[] buffer = new byte[size];
            mainDataInputStream.readFully(buffer);
            return ByteBuffer.wrap(buffer);
        } else return mainBufferStream.readByteArray(size);
    }

    public ByteBuffer readByteArrayFromVideo(int size) throws IOException, InterruptedException {
        if (connectDirect) {
            byte[] buffer = new byte[size];
            videoDataInputStream.readFully(buffer);
            return ByteBuffer.wrap(buffer);
        }
        return videoBufferStream.readByteArray(size);
    }

    public ByteBuffer readFrameFromMain() throws Exception {
        if (!connectDirect) mainBufferStream.flush();
        return readByteArrayFromMain(readIntFromMain());
    }

    public ByteBuffer readFrameFromVideo() throws Exception {
        if (!connectDirect) videoBufferStream.flush();
        int size = readIntFromVideo();
        return readByteArrayFromVideo(size);
    }

    public void writeToMain(ByteBuffer byteBuffer) throws Exception {
        if (connectDirect) mainOutputStream.write(byteBuffer.array());
        else mainBufferStream.write(byteBuffer);
    }

    public void close() {
        if (isClose) return;
        isClose = true;
        if (shell != null) {
            try {
                PublicTools.logToast("server", new String(shell.readByteArrayBeforeClose().array()), false);
            } catch (Exception ignored) {
            }
            try {
                shell.close();
            } catch (Exception ignored) {
            }
        }
        if (connectDirect) {
            try {
                mainOutputStream.close();
                videoDataInputStream.close();
                mainDataInputStream.close();
                mainSocket.close();
                videoSocket.close();
            } catch (Exception ignored) {
            }
        } else {
            try {
                mainBufferStream.close();
            } catch (Exception ignored) {
            }
            try {
                videoBufferStream.close();
            } catch (Exception ignored) {
            }
        }
    }
}
