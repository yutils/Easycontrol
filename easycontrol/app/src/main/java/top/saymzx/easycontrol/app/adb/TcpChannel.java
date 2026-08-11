package top.saymzx.easycontrol.app.adb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class TcpChannel implements AdbChannel {
    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    public TcpChannel(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 10000);
        // 注意：不能设置读超时(setSoTimeout)。直连模式下 ADB 连接在会话期间完全空闲（仅用于承载启动
        // 服务端的 shell），一旦 30s 无数据就会抛 SocketTimeoutException → handleIn close() → shell 流关闭
        // → 服务端 app_process 收到 SIGHUP 被杀死，会话意外断开。半开连接由写路径(writeToChannel catch)
        // 和 close() 关闭通道解除阻塞来兜底。
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
    }

    @Override
    public void write(ByteBuffer data) throws IOException {
        outputStream.write(data.array());
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        byte[] buffer = new byte[size];
        int bytesRead = 0;
        while (bytesRead < size) {
            int read = inputStream.read(buffer, bytesRead, size - bytesRead);
            // 对端关闭时抛异常，避免 handleIn 用零填充缓冲无限空转（死循环 + 烧 CPU）
            if (read == -1) throw new IOException("EOF");
            bytesRead += read;
        }
        return ByteBuffer.wrap(buffer);
    }

    @Override
    public void close() {
        try {
            outputStream.close();
            inputStream.close();
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
