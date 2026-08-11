package top.saymzx.easycontrol.app.adb;

import android.hardware.usb.UsbDevice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import top.saymzx.easycontrol.app.buffer.BufferStream;
import top.saymzx.easycontrol.app.entity.MyInterface;

// 此部分代码摘抄借鉴了tananaev大佬的开源代码(https://github.com/tananaev/adblib)以及开源库dadb(https://github.com/mobile-dev-inc/dadb)
// 因为官方adb协议文档写的十分糟糕，因此此部分代码的实现参考了cstyan大佬所整理的文档，再次进行感谢：https://github.com/cstyan/adbDocumentation
public class Adb {
    private volatile boolean isClosed = false;
    private final AdbChannel channel;
    private final AtomicInteger localIdPool = new AtomicInteger(1);
    private int MAX_DATA = AdbProtocol.CONNECT_MAXDATA;
    private final ConcurrentHashMap<Integer, BufferStream> connectionStreams = new ConcurrentHashMap<>(10);
    private final ConcurrentHashMap<Integer, BufferStream> openStreams = new ConcurrentHashMap<>(5);

    private final Thread handleInThread = new Thread(this::handleIn);
    // open() 发出 OPEN 后仍在等待 OKAY 的本地流 id，用于区分「对端拒绝 OPEN」和「对端应答已关闭的流」
    private final Set<Integer> pendingOpens = ConcurrentHashMap.newKeySet();

    public Adb(String address, int port, AdbKeyPair keyPair) throws Exception {
        channel = new TcpChannel(address, port);
        connect(keyPair);
    }

    public Adb(UsbDevice usbDevice, AdbKeyPair keyPair) throws Exception {
        if (usbDevice == null) throw new IOException("no usb connect");
        channel = new UsbChannel(usbDevice);
        connect(keyPair);
    }

    private void connect(AdbKeyPair keyPair) throws Exception {
        // 连接ADB并认证
        channel.write(AdbProtocol.generateConnect());
        AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
        if (message.command == AdbProtocol.CMD_AUTH) {
            channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, keyPair.signPayload(message.payload)));
            message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
            if (message.command == AdbProtocol.CMD_AUTH) {
                channel.write(AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, keyPair.publicKeyBytes));
                message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
            }
        }
        if (message.command != AdbProtocol.CMD_CNXN) {
            channel.close();
            throw new Exception("ADB连接失败");
        }
        MAX_DATA = message.arg1;
        // 启动后台进程
        handleInThread.setPriority(Thread.MAX_PRIORITY);
        handleInThread.start();
    }

    private BufferStream open(String destination, boolean canMultipleSend) throws InterruptedException {
        int localId = localIdPool.getAndIncrement() * (canMultipleSend ? 1 : -1);
        // 先登记再发 OPEN，保证对端任何响应到达时 pendingOpens 已包含该 id
        pendingOpens.add(localId);
        try {
            writeToChannel(AdbProtocol.generateOpen(localId, destination));
            BufferStream bufferStream = null;
            synchronized (this) {
                while (!isClosed && (bufferStream = openStreams.get(localId)) == null) {
                    wait();
                }
            }
            openStreams.remove(localId);
            return bufferStream;
        } finally {
            pendingOpens.remove(localId);
        }
    }

    public String restartOnTcpip(int port) throws InterruptedException {
        BufferStream bufferStream = open("tcpip:" + port, false);
        synchronized (this) {
            while (!bufferStream.isClosed()) {
                wait();
            }
        }
        return new String(bufferStream.readByteArrayBeforeClose().array());
    }

    public void pushFile(InputStream file, String remotePath, MyInterface.MyFunctionInt handleProcess) throws Exception {
        // 打开链接
        BufferStream bufferStream = open("sync:", false);
        try {
            // 发送信令，建立push通道
            String sendString = remotePath + ",33206";
            byte[] bytes = sendString.getBytes();
            bufferStream.write(AdbProtocol.generateSyncHeader("SEND", sendString.length()));
            bufferStream.write(ByteBuffer.wrap(bytes));
            // 发送文件
            byte[] byteArray = new byte[10240 - 8];
            int hasSendLen = 0;
            int allNeedSendLen = file.available();
            int lastProcess = 0;
            int len = file.read(byteArray, 0, byteArray.length);
            do {
                bufferStream.write(AdbProtocol.generateSyncHeader("DATA", len));
                bufferStream.write(ByteBuffer.wrap(byteArray, 0, len));
                hasSendLen += len;
                int newProcess = (int) (((float) hasSendLen / allNeedSendLen) * 100);
                if (newProcess != lastProcess) {
                    lastProcess = newProcess;
                    if (handleProcess != null) handleProcess.run(lastProcess);
                }
                len = file.read(byteArray, 0, byteArray.length);
            } while (len > 0);
            // 传输完成，为了方便，文件日期定为2024.1.1 0:0
            bufferStream.write(AdbProtocol.generateSyncHeader("DONE", 1704038400));
            bufferStream.write(AdbProtocol.generateSyncHeader("QUIT", 0));
            do {
                synchronized (this) {
                    wait();
                }
            } while (!bufferStream.isClosed());
        } finally {
            file.close();
        }
    }

    public String runAdbCmd(String cmd) throws Exception {
        BufferStream bufferStream = open("shell:" + cmd, true);
        synchronized (this) {
            while (!bufferStream.isClosed()) {
                wait();
            }
        }
        return new String(bufferStream.readByteArrayBeforeClose().array());
    }

    public BufferStream getShell() throws InterruptedException {
        return open("shell:", true);
    }

    public BufferStream tcpForward(int port) throws IOException, InterruptedException {
        BufferStream bufferStream = open("tcp:" + port, true);
        if (bufferStream.isClosed()) throw new IOException("error forward");
        return bufferStream;
    }

    public BufferStream localSocketForward(String socketName) throws IOException, InterruptedException {
        BufferStream bufferStream = open("localabstract:" + socketName, true);
        if (bufferStream.isClosed()) throw new IOException("error forward");
        return bufferStream;
    }

    private void handleIn() {
        try {
            while (!Thread.interrupted()) {
                AdbProtocol.AdbMessage message = AdbProtocol.AdbMessage.parseAdbMessage(channel);
                BufferStream bufferStream = connectionStreams.get(message.arg1);
                boolean isNeedNotify = bufferStream == null;
                // 新连接
                if (isNeedNotify) {
                    // 对端 CLSE 应答的是本地已关闭的流，直接忽略，避免创建"幽灵"流在 openStreams 里无限泄漏。
                    // 但对端 CLSE 拒绝了尚未 OKAY 的 OPEN（如服务端端口还没监听）时，仍需创建并关闭该流，
                    // 让 open() 醒来拿到已关闭的流、调用方(tcpForward)得以重试 —— 否则 open() 永远等不到 OKAY 会挂死。
                    if (message.command == AdbProtocol.CMD_CLSE && !pendingOpens.contains(message.arg1)) continue;
                    bufferStream = createNewStream(message.arg1, message.arg0, message.arg1 > 0);
                }
                switch (message.command) {
                    case AdbProtocol.CMD_OKAY:
                        bufferStream.setCanWrite(true);
                        break;
                    case AdbProtocol.CMD_WRTE:
                        bufferStream.pushSource(message.payload);
                        writeToChannel(AdbProtocol.generateOkay(message.arg1, message.arg0));
                        break;
                    case AdbProtocol.CMD_CLSE:
                        bufferStream.close();
                        isNeedNotify = true;
                        break;
                }
                if (isNeedNotify) {
                    synchronized (this) {
                        notifyAll();
                    }
                }
            }
        } catch (Exception ignored) {
            close();
        }
    }

    private void writeToChannel(ByteBuffer byteBuffer) {
        synchronized (channel) {
            try {
                channel.write(byteBuffer);
            } catch (Exception ignored) {
                close();
            }
        }
    }

    private BufferStream createNewStream(int localId, int remoteId, boolean canMultipleSend) throws Exception {
        return new BufferStream(false, canMultipleSend, new BufferStream.UnderlySocketFunction() {
            @Override
            public void connect(BufferStream bufferStream) {
                connectionStreams.put(localId, bufferStream);
                openStreams.put(localId, bufferStream);
            }

            @Override
            public void write(BufferStream bufferStream, ByteBuffer buffer) {
                while (buffer.hasRemaining()) {
                    byte[] byteArray = new byte[Math.min(MAX_DATA - 128, buffer.remaining())];
                    buffer.get(byteArray);
                    writeToChannel(AdbProtocol.generateWrite(localId, remoteId, byteArray));
                }
            }

            @Override
            public void flush(BufferStream bufferStream) {
                writeToChannel(AdbProtocol.generateOkay(localId, remoteId));
            }

            @Override
            public void close(BufferStream bufferStream) {
                connectionStreams.remove(localId);
                writeToChannel(AdbProtocol.generateClose(localId, remoteId));
            }
        });
    }

    public boolean isClosed() {
        return isClosed;
    }

    public void close() {
        if (isClosed) return;
        isClosed = true;
        handleInThread.interrupt();
        for (Object bufferStream : connectionStreams.values().toArray())
            ((BufferStream) bufferStream).close();
        channel.close();
        synchronized (this) {
            notifyAll();
        }
    }

}
