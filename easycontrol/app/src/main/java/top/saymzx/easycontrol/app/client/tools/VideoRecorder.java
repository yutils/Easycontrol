package top.saymzx.easycontrol.app.client.tools;

import android.content.ContentValues;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.saymzx.easycontrol.app.R;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.PublicTools;

// 客户端录屏：把收到的编码视频帧直接写 MediaMuxer 存 MP4(纯视频，零服务端改动)
public class VideoRecorder {
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private boolean started = false;
    private boolean hasWrittenKeyframe = false;
    private int samplesWritten = 0;
    private long firstPts = -1;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    private File file;
    private final int codecType;

    // codecType: 0=H264, 1=H265, 2=AV1
    public VideoRecorder(Pair<Integer, Integer> videoSize, ByteBuffer csd0, ByteBuffer csd1, int codecType) throws IOException {
        this.codecType = codecType;
        String mime;
        if (codecType == 1) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) throw new IOException("HEVC needs API24+");
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
        } else if (codecType == 2) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IOException("AV1 needs API29+");
            mime = MediaFormat.MIMETYPE_VIDEO_AV1;
        } else {
            mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        File dir = AppData.applicationContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir != null) dir.mkdirs();
        file = new File(dir, "easycontrol_rec_" + System.currentTimeMillis() + ".mp4");
        muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat format = MediaFormat.createVideoFormat(mime, videoSize.first, videoSize.second);
        // 从 csd0/csd1 里解析干净的 SPS/PPS 写进 MediaFormat。
        // 不同编码器输出 codec config 的方式不同：有的把 SPS+PPS 合并进第一个缓冲(csd0)，
        // 有的分成两个缓冲(csd0=SPS, csd1=PPS)；csd1 也可能根本不是参数集(本机编码器
        // 就是 csd0=SPS+PPS、csd1=26000 多字节的无关数据)。直接按"SPS/csd-0 + PPS/csd-1"
        // 用第一个缓冲会把这些多余数据写进 avcC，导致 MP4 无法解码。
        ByteBuffer[] spsPps = extractSpsPps(csd0, csd1);
        PublicTools.logToast("recorder", "csd parse sps=" + (spsPps[0] == null ? 0 : spsPps[0].remaining())
                + " pps=" + (spsPps[1] == null ? 0 : spsPps[1].remaining())
                + " csd1:" + hexHead(csd1), false);
        if (spsPps[0] != null) {
            format.setByteBuffer("csd-0", spsPps[0]);
            if (codecType == 0 && spsPps[1] != null) format.setByteBuffer("csd-1", spsPps[1]);
        } else {
            // 没解析出 start code(可能配置是 length-prefixed 格式)，退回旧行为直接用原始缓冲，
            // 但要先统一成 4 字节 start code —— 1/3 字节 start code 写进 hvcC/avcC 会被解码器当脏数据
            ByteBuffer sps = csd0.duplicate();
            sps.position(8);
            sps = normalizeStartCodesTo4Byte(sps);
            format.setByteBuffer("csd-0", sps);
            if (codecType == 0 && csd1 != null) {
                ByteBuffer pps = csd1.duplicate();
                pps.position(8);
                pps = normalizeStartCodesTo4Byte(pps);
                format.setByteBuffer("csd-1", pps);
            }
            PublicTools.logToast("recorder", "csd fallback raw head:" + hexHead(sps), false);
        }
        trackIndex = muxer.addTrack(format);
        muxer.start();
        started = true;
        PublicTools.logToast("recorder", "init csd0=" + csd0.remaining() + " csd1=" + (csd1 == null ? -1 : csd1.remaining()) + " codec=" + codecType + " size=" + videoSize.first + "x" + videoSize.second, false);
    }

    // 帧格式 [pts:8][帧数据]：用绝对读取与副本，不改动原buffer(解码线程共用)
    public void writeFrame(ByteBuffer data) {
        if (!started || muxer == null) return;
        long pts = data.getLong(0);
        boolean keyframe = isKeyframe(data);
        // MediaMuxer 首帧必须是关键帧，否则输出文件损坏/空白。录制起点常在 GOP 中间
        // (服务端 I 帧间隔最长 10 秒)，需跳过首个关键帧之前的帧，并给关键帧打标记
        if (!hasWrittenKeyframe) {
            if (!keyframe) return;
            hasWrittenKeyframe = true;
        }
        // 服务端编码器输出的是挂钟时间戳(非0起点)，MediaMuxer 要求首样本 PTS=0，
        // 否则 MPEG4Writer 在 stop() 时写不出 moov，需整体偏移到首帧为0
        if (firstPts < 0) firstPts = pts;
        pts -= firstPts;
        int end = data.limit();
        // 崩溃根源(实机 Z Flip HEVC 关键帧)：帧末尾带"空 NAL"——两个 start code 紧挨着
        // (如 00 00 00 01 00 00 01)。MediaMuxer 的 addMultipleLengthPrefixedSamples_l 会整帧扫描
        // start code，用 nextNalStart - currentNalStart - 4 算 NAL 长度；相邻 start code 会算出
        // 负长度 = SIZE_MAX，write(SIZE_MAX) 触发 FORTIFY SIGABRT(与实机崩溃日志完全一致)。
        //
        // 修复：绝不让 muxer 看到任何 start code —— 先把帧切成 NAL(扫描条件与 muxer 的
        // getNextNALUnit 一致，能看到缓冲末尾的 start code)，空 NAL 丢弃，每个 NAL 的裸载荷
        // 单独喂 muxer。muxer 的 addLengthPrefixedSample_l 会给每个样本写 [len] 前缀，
        // 文件里就是干净的标准 AVCC [len][nal]。
        List<int[]> nals = splitNals(data, 8, end);
        boolean first = true;
        for (int[] nal : nals) {
            int off = nal[0], len = nal[1];
            if (len <= 0) continue; // 空 NAL(连续 start code)丢弃
            // 防御性拷贝：muxer 的写入队列异步处理，不与视频线程共享 byte[]，杜绝复用/覆盖
            ByteBuffer payload = copyRange(data, off, len);
            bufferInfo.set(0, len, pts, (keyframe && first) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            try {
                muxer.writeSampleData(trackIndex, payload, bufferInfo);
                samplesWritten++;
                first = false;
            } catch (Exception e) {
                PublicTools.logToast("recorder", "write ERR " + e, false);
                // 单帧写入失败不拖垮视频线程：关闭录制，会话继续
                closeQuietly();
                return;
            }
        }
    }

    // 把帧内所有 Annex-B start code（3字节 00 00 01 或 4字节 00 00 00 01）统一扩成 4字节 00 00 00 01。
    // MediaMuxer 的 addMultipleLengthPrefixedSamples_l 只把 4字节 start code 当分界，帧头 4字节 start code
    // 只会算成 0 长度空 NAL（无害）；而 3字节 00 00 01 在帧头会算成负长度 → write(SIZE_MAX) 崩溃。
    // 多 NAL 帧用归一化后的整帧 Annex-B 喂 muxer（muxer 自己分片 + 写长度前缀），最后 normalizeMangledMp4
    // 剥掉每样本开头的 [0]。返回新 ByteBuffer，不改动原 buffer。
    private static ByteBuffer normalizeStartCodesTo4Byte(ByteBuffer buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.remaining() + 8);
        int end = buf.limit();
        int i = buf.position();
        while (i < end) {
            if (i + 2 < end && buf.get(i) == 0 && buf.get(i + 1) == 0 && buf.get(i + 2) == 1) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                i += 3;
            } else if (i + 3 < end && buf.get(i) == 0 && buf.get(i + 1) == 0
                    && buf.get(i + 2) == 0 && buf.get(i + 3) == 1) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                i += 4;
            } else {
                out.write(buf.get(i));
                i++;
            }
        }
        return ByteBuffer.wrap(out.toByteArray());
    }

    // 把帧(不含 pts，范围 [start, end))切成 NAL 列表，返回每个 NAL 的 [偏移, 长度]（不含 start code）。
    // 扫描条件与 MediaMuxer 的 getNextNALUnit 完全一致：3字节 start code 判定用 i+2<end，
    // 4字节用 i+3<end，因此能看到缓冲末尾的 start code（isSingleNal 用 i+3<end 看不到末尾 3 字节，
    // 这正是 Z Flip 关键帧末尾空 NAL 漏检、把相邻 start code 喂进 muxer 导致崩溃的原因）。
    // 空 NAL(连续 start code 之间的 0 长度载荷)被丢弃；帧无 start code 时整体作为单个 NAL。
    private static List<int[]> splitNals(ByteBuffer buf, int start, int end) {
        List<int[]> nals = new ArrayList<>();
        int i = start;
        int payloadStart = start;
        boolean foundAny = false;
        while (i + 2 < end) {
            if (buf.get(i) == 0 && buf.get(i + 1) == 0) {
                int scLen = 0;
                if (buf.get(i + 2) == 1) {
                    scLen = 3;
                } else if (i + 3 < end && buf.get(i + 2) == 0 && buf.get(i + 3) == 1) {
                    scLen = 4;
                }
                if (scLen > 0) {
                    foundAny = true;
                    // [payloadStart, i) 是一个 NAL（空 NAL 长度为 0，丢弃）
                    if (i > payloadStart) nals.add(new int[]{payloadStart, i - payloadStart});
                    i += scLen;
                    payloadStart = i;
                    continue;
                }
            }
            i++;
        }
        if (!foundAny) {
            nals.add(new int[]{start, end - start});
        } else if (end > payloadStart) {
            nals.add(new int[]{payloadStart, end - payloadStart});
        }
        return nals;
    }

    // 从 buf 的 [off, off+len) 拷出独立 byte[]（muxer 异步队列用，杜绝与视频线程共享缓冲）
    private static ByteBuffer copyRange(ByteBuffer buf, int off, int len) {
        byte[] out = new byte[len];
        ByteBuffer src = buf.duplicate();
        src.position(off);
        src.get(out);
        return ByteBuffer.wrap(out);
    }

    // 从 csd0/csd1 里解析出真正的 SPS(类型7)、PPS(类型8) NAL。
    // csd 缓冲前8字节是pts，扫描需跳过。返回 {SPS, PPS}，均为含 start code 的 Annex-B 数据，
    // 找不到对应 NAL 时该位为 null。
    private static ByteBuffer[] extractSpsPps(ByteBuffer csd0, ByteBuffer csd1) {
        ByteBuffer sps = findNal(csd0, 7);
        ByteBuffer pps = findNal(csd0, 8);
        if (pps == null && csd1 != null) pps = findNal(csd1, 8);
        if (sps == null && csd1 != null) sps = findNal(csd1, 7);
        return new ByteBuffer[]{sps, pps};
    }

    // 在 csd 数据里找第一个指定类型的 Annex-B NAL，返回含 start code 的完整 NAL。
    // 找不到返回 null。数据头8字节为pts，跳过。
    private static ByteBuffer findNal(ByteBuffer csd, int type) {
        if (csd == null) return null;
        int end = csd.limit();
        int i = 8; // 跳过8字节pts
        while (i + 3 < end) {
            if (csd.get(i) == 0 && csd.get(i + 1) == 0) {
                int header;
                if (csd.get(i + 2) == 1) header = i + 3;                 // 00 00 01
                else if (csd.get(i + 2) == 0 && csd.get(i + 3) == 1) header = i + 4; // 00 00 00 01
                else {
                    i++;
                    continue;
                }
                if (header >= end) return null;
                if ((csd.get(header) & 0x1F) == type) {
                    // 找该 NAL 的结束：下一个 start code 或 buffer 尾
                    int nalEnd = end;
                    for (int j = header + 1; j + 3 < end; j++) {
                        if (csd.get(j) == 0 && csd.get(j + 1) == 0
                                && (csd.get(j + 2) == 1 || (csd.get(j + 2) == 0 && csd.get(j + 3) == 1))) {
                            nalEnd = j;
                            break;
                        }
                    }
                    byte[] nal = new byte[nalEnd - i];
                    ByteBuffer view = csd.duplicate();
                    view.position(i);
                    view.limit(nalEnd);
                    view.get(nal);
                    return ByteBuffer.wrap(nal);
                }
                i = header;
            } else i++;
        }
        return null;
    }

    // 诊断用：csd1 数据前若干字节(跳过8字节pts)
    private static String hexHead(ByteBuffer csd) {
        if (csd == null) return "null";
        StringBuilder hex = new StringBuilder();
        int start = Math.min(8, csd.limit());
        int show = Math.min(csd.limit() - start, 48);
        for (int i = 0; i < show; i++) hex.append(String.format("%02x", csd.get(start + i) & 0xFF));
        return hex.toString();
    }

    // 通过访问单元里的 NAL 头判断是否关键帧(Annex B start code 前缀)
    private boolean isKeyframe(ByteBuffer data) {
        if (codecType == 2) return true; // AV1 暂不支持，保守按关键帧处理
        int end = data.capacity();
        int i = 8; // 跳过8字节pts
        while (i + 3 < end) {
            if (data.get(i) != 0 || data.get(i + 1) != 0) {
                i++;
                continue;
            }
            int header;
            if (data.get(i + 2) == 1) {
                header = i + 3;              // 00 00 01
            } else if (data.get(i + 2) == 0 && data.get(i + 3) == 1) {
                header = i + 4;              // 00 00 00 01
            } else {
                i++;
                continue;
            }
            if (header >= end) break;
            int nal = data.get(header) & 0xFF;
            if (codecType == 0) {
                int type = nal & 0x1F;
                if (type == 5) return true;  // H264 IDR
            } else {
                int type = (nal >> 1) & 0x3F;
                // H265 IDR / VPS / SPS / PPS 都标志新GOP起始
                if (type == 19 || type == 20 || type == 32 || type == 33 || type == 34) return true;
            }
            i = header;
        }
        return false;
    }

    // ===== 修正 MuMu 模拟器 MediaMuxer 的 AVC 样本 +4 前缀 bug =====
    // 该模拟器写 H264 轨道时，会给每个样本额外包一层 [本次写入样本总长]，且 stsz 记成
    // 总长+4，导致文件样本形如 [写总长][len][nal](首4字节=stsz-4、次4字节=stsz-8)，无法解码。
    // 健康设备的样本是标准 AVCC [len][nal]：首4字节=stsz-4 恒成立，但次4字节是 NAL 载荷、
    // 不可能等于 stsz-8，判据不会误触发。检测到就剥掉前缀重写文件；检测不到则原样保留。
    private void normalizeMangledMp4() {
        byte[] all;
        try {
            long len = file.length();
            if (len <= 0 || len > 512L * 1024 * 1024) return;
            all = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < all.length) {
                    int n = in.read(all, off, all.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
        } catch (Exception e) {
            return;
        }
        // 顶层 box 扫描：记录每个 box 的边界，并定位 mdat 与 moov(可能在 mdat 前也可能在后)
        List<int[]> topBoxes = new ArrayList<>();
        int pos = 0, mdatOff = -1, mdatHdr = 0, mdatEnd = -1, moovOff = -1, moovEnd = -1;
        while (pos + 8 <= all.length) {
            long boxSize = readU32(all, pos);
            int hdr = 8;
            if (boxSize == 1) {
                if (pos + 16 > all.length) break;
                boxSize = readU64(all, pos + 8);
                hdr = 16;
            } else if (boxSize == 0) boxSize = all.length - pos;
            if (boxSize < hdr || pos + boxSize > all.length) break;
            String type = new String(all, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("mdat")) {
                mdatOff = pos;
                mdatHdr = hdr;
                mdatEnd = pos + (int) boxSize;
            }
            if (type.equals("moov")) {
                moovOff = pos;
                moovEnd = pos + (int) boxSize;
            }
            topBoxes.add(new int[]{pos, (int) boxSize, type.equals("mdat") ? 1 : 0});
            pos += (int) boxSize;
        }
        if (mdatOff < 0 || moovOff < 0) return;
        int mdatPayloadStart = mdatOff + mdatHdr;
        // 在 moov 里找 stsz / stco|co64 / stsc
        int[] boxOff = new int[4];
        findBoxes(all, moovOff + 8, moovEnd, boxOff);
        int stszOff = boxOff[0], stscOff = boxOff[3];
        int stcoOff = boxOff[1] != 0 ? boxOff[1] : boxOff[2];
        boolean co64 = boxOff[2] != 0;
        if (stszOff == 0 || stscOff == 0 || stcoOff == 0) return;
        // stsz: 样本尺寸表。stsz 是 FullBox：+8 版本/标志，+12 sample_size，+16 sample_count，
        // +20 起才是每个样本的尺寸表(可变尺寸时 sample_size=0，尺寸存表)
        if (readU32(all, stszOff + 12) != 0) return; // 定长样本(视频不会出现)，跳过
        int sampleCount = readU32(all, stszOff + 16);
        if (sampleCount <= 0 || sampleCount > (1 << 20)) return;
        int[] sizes = new int[sampleCount];
        int sPos = stszOff + 20;
        for (int i = 0; i < sampleCount; i++) {
            sizes[i] = readU32(all, sPos);
            sPos += 4;
        }
        // stsc: 每个 chunk 的样本数
        int scCnt = readU32(all, stscOff + 12);
        if (scCnt <= 0) return;
        int[] stscFirst = new int[scCnt], stscSpc = new int[scCnt];
        int scPos = stscOff + 16;
        for (int i = 0; i < scCnt; i++) {
            stscFirst[i] = readU32(all, scPos);
            stscSpc[i] = readU32(all, scPos + 4);
            scPos += 12;
        }
        // stco/co64: chunk 起始偏移
        int ccCnt = readU32(all, stcoOff + 12);
        if (ccCnt <= 0) return;
        long[] chunkOff = new long[ccCnt];
        int cPos = stcoOff + 16;
        for (int i = 0; i < ccCnt; i++) {
            chunkOff[i] = co64 ? readU64(all, cPos) : readU32(all, cPos);
            cPos += co64 ? 8 : 4;
        }
        // 计算每个样本的文件偏移
        long[] sampleOff = new long[sampleCount];
        int idx = 0, stscI = 0;
        for (int ci = 0; ci < ccCnt && idx < sampleCount; ci++) {
            while (stscI + 1 < scCnt && stscFirst[stscI + 1] - 1 <= ci) stscI++;
            int spc = stscSpc[stscI];
            if (spc <= 0) return;
            long o = chunkOff[ci];
            for (int k = 0; k < spc && idx < sampleCount; k++) {
                sampleOff[idx] = o;
                o += sizes[idx];
                idx++;
            }
        }
        if (idx != sampleCount) return;
        // 判断每个样本是否被 4 字节前缀污染，两种：
        //  1) 首4字节=0：[0]-前缀 —— 多 NAL 帧经 muxer 分片时对帧头4字节start code写的空 NAL，剥掉恢复干净 AVCC。
        //  2) 首4字节=stsz-4 且 次4字节=stsz-8：MuMu 模拟器给每个样本额外包的一层 [写总长]。
        // 健康设备的样本是标准 AVCC [len][nal]：len≥1，首4字节不可能为0；次4字节是 NAL 载荷，不可能等于 stsz-8，判据不会误触发。
        boolean[] strip = new boolean[sampleCount];
        int stripCount = 0;
        for (int i = 0; i < sampleCount; i++) {
            int S = sizes[i];
            long off = sampleOff[i];
            if (S < 8 || off + S > mdatEnd) continue;
            int first = readU32(all, (int) off);
            if (first == 0) {
                strip[i] = true;
                stripCount++;
            } else if (first == S - 4 && readU32(all, (int) off + 4) == S - 8) {
                strip[i] = true;
                stripCount++;
            }
        }
        if (stripCount == 0) return; // 正常设备，无需处理
        PublicTools.logToast("recorder", "normalize strip " + stripCount + "/" + sampleCount, false);
        // 重写文件：按顶层 box 顺序重建。mdat 换成剥掉前缀的样本并更新大小，
        // moov 里修补 stsz(-4) 与 chunk 偏移，其余 box 原样保留。
        try {
            // 先修补 moov(stsz 尺寸 -4、chunk 偏移按新位置)
            byte[] moov = Arrays.copyOfRange(all, moovOff, moovEnd);
            int relStsz = stszOff - moovOff, relStco = stcoOff - moovOff;
            for (int i = 0; i < sampleCount; i++) {
                // 尺寸表从 +20 起(+16 是 sample_count，不能覆盖)
                writeU32(moov, relStsz + 20 + 4 * i, sizes[i] - (strip[i] ? 4 : 0));
            }
            // 新 chunk 偏移 = mdat 数据起点 + 该 chunk 之前剥掉的总字节数
            long[] newChunkOff = new long[ccCnt];
            int ci = 0;
            stscI = 0;
            int si = 0;
            long strippedBefore = 0;
            while (ci < ccCnt && si < sampleCount) {
                while (stscI + 1 < scCnt && stscFirst[stscI + 1] - 1 <= ci) stscI++;
                int spc = stscSpc[stscI];
                newChunkOff[ci] = mdatPayloadStart + strippedBefore;
                for (int k = 0; k < spc && si < sampleCount; k++) {
                    if (strip[si]) strippedBefore += 4;
                    si++;
                }
                ci++;
            }
            int cPos2 = relStco + 16;
            for (int i = 0; i < ccCnt; i++) {
                if (co64) writeU64(moov, cPos2, newChunkOff[i]);
                else writeU32(moov, cPos2, newChunkOff[i]);
                cPos2 += co64 ? 8 : 4;
            }
            // 逐顶层 box 重建
            ByteArrayOutputStream out = new ByteArrayOutputStream(all.length - stripCount * 4 + 4096);
            for (int[] b : topBoxes) {
                if (b[2] == 1) { // mdat：更新大小 + 写剥掉前缀的样本
                    byte[] hdr = Arrays.copyOfRange(all, b[0], b[0] + mdatHdr);
                    long newMdatSize = mdatHdr + (long) (mdatEnd - mdatPayloadStart) - stripCount * 4L;
                    if (readU32(hdr, 0) == 1) writeU64(hdr, 8, newMdatSize);
                    else writeU32(hdr, 0, newMdatSize);
                    out.write(hdr);
                    for (int i = 0; i < sampleCount; i++) {
                        int skip = strip[i] ? 4 : 0;
                        out.write(all, (int) sampleOff[i] + skip, sizes[i] - skip);
                    }
                } else if (b[0] == moovOff) {
                    out.write(moov); // 修补后的 moov(尺寸不变)
                } else {
                    out.write(all, b[0], b[1]);
                }
            }
            // 写回：先写临时文件再替换
            File tmp = new File(file.getParentFile(), file.getName() + ".fix");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(out.toByteArray());
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(out.toByteArray());
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            PublicTools.logToast("recorder", "normalize ok size=" + file.length(), false);
        } catch (Exception e) {
            PublicTools.logToast("recorder", "normalize ERR " + e, false);
        }
    }

    // ===== 修复"每切片独立 sample"导致硬件解码花屏 =====
    // 部分编码器(实测 Z Flip 的 QTI HEVC)每帧输出 2 个 slice buffer(pts 仅差 ~9 时基单位)，
    // MediaMuxer 对每个 buffer 写 1 个 sample，文件变成每帧 2 个"裸切片 sample"。硬解码器契约是
    // 1 buffer = 1 完整 AU(1 帧)：2 个半帧切片被当成 2 个独立 AU，解码器要么 0 输出(Z Flip 黑屏)、
    // 要么画面花屏(Mi 10)。修复：录完后把同一画面的多个 sample 合并为 1 个完整 AU sample，
    // 重建样本表(stsz/stts/ctts/stss/co64/stsc)。与 normalizeMangledMp4 互补：前者处理模拟器
    // +4 前缀，本方法处理 per-NAL 拆分，先 normalize 后 merge。合并前每组样本已是标准 AVCC
    // [len][nal]，直接拼接即为规范的多 NAL AU。
    private void mergePerNalSamples() {
        byte[] all;
        try {
            long len = file.length();
            if (len <= 0 || len > 512L * 1024 * 1024) return;
            all = new byte[(int) len];
            try (FileInputStream in = new FileInputStream(file)) {
                int off = 0;
                while (off < all.length) {
                    int n = in.read(all, off, all.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
        } catch (Exception e) {
            return;
        }
        // 顶层 box：定位 ftyp/mdat/moov
        int pos = 0, mdatOff = -1, mdatHdr = 0, moovOff = -1, moovEnd = -1;
        int ftypStart = -1, ftypSize = 0;
        while (pos + 8 <= all.length) {
            long boxSize = readU32(all, pos);
            int hdr = 8;
            if (boxSize == 1) {
                if (pos + 16 > all.length) break;
                boxSize = readU64(all, pos + 8);
                hdr = 16;
            } else if (boxSize == 0) boxSize = all.length - pos;
            if (boxSize < hdr || pos + boxSize > all.length) break;
            String type = new String(all, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("ftyp")) {
                ftypStart = pos;
                ftypSize = (int) boxSize;
            } else if (type.equals("mdat")) {
                mdatOff = pos;
                mdatHdr = hdr;
            } else if (type.equals("moov")) {
                moovOff = pos;
                moovEnd = pos + (int) boxSize;
            }
            pos += (int) boxSize;
        }
        if (mdatOff < 0 || moovOff < 0 || ftypStart < 0) return;
        int mdatEnd = mdatOff + mdatHdr + readMdatSize(all, mdatOff, mdatHdr);
        // 在 moov 里找样本表 box
        int[] boxes = new int[7];
        findTrackBoxes(all, moovOff + 8, moovEnd, boxes);
        int stszOff = boxes[0], sttsOff = boxes[1], cttsOff = boxes[2], stssOff = boxes[3];
        int chunkOff = boxes[5] != 0 ? boxes[5] : boxes[4]; // co64 优先
        boolean co64 = boxes[5] != 0;
        int stscOff = boxes[6];
        if (stszOff == 0 || sttsOff == 0 || stscOff == 0 || chunkOff == 0) return;
        if (readU32(all, stszOff + 12) != 0) return; // 定长样本(视频不会出现)，跳过
        int sampleCount = readU32(all, stszOff + 16);
        if (sampleCount <= 0 || sampleCount > (1 << 20)) return;
        // stsz
        int[] sizes = new int[sampleCount];
        int sPos = stszOff + 20;
        for (int i = 0; i < sampleCount; i++) {
            sizes[i] = readU32(all, sPos);
            sPos += 4;
        }
        // stts → 每样本 decode pts + 总时长
        long[] decodePts = new long[sampleCount];
        long totalDur;
        {
            int cnt = readU32(all, sttsOff + 12);
            if (cnt <= 0) return;
            int p = sttsOff + 16;
            long t = 0;
            int idx = 0;
            for (int e = 0; e < cnt && idx < sampleCount; e++) {
                int n = readU32(all, p);
                int d = readU32(all, p + 4);
                p += 8;
                for (int k = 0; k < n && idx < sampleCount; k++) {
                    decodePts[idx++] = t;
                    t += d;
                }
            }
            if (idx != sampleCount) return;
            totalDur = t;
        }
        // ctts → 每样本 composition offset(无则 0)
        boolean hasCtts = cttsOff != 0;
        int[] cttsOffsets = new int[sampleCount];
        if (hasCtts) {
            int cnt = readU32(all, cttsOff + 12);
            int p = cttsOff + 16;
            int idx = 0;
            for (int e = 0; e < cnt && idx < sampleCount; e++) {
                int n = readU32(all, p);
                int off = (int) readU32(all, p + 4);
                p += 8;
                for (int k = 0; k < n && idx < sampleCount; k++) cttsOffsets[idx++] = off;
            }
            if (idx != sampleCount) hasCtts = false;
        }
        // stss → sync 集合(1-based)
        boolean[] sync = new boolean[sampleCount];
        if (stssOff != 0) {
            int cnt = readU32(all, stssOff + 12);
            int p = stssOff + 16;
            for (int e = 0; e < cnt; e++) {
                int s = readU32(all, p);
                p += 4;
                if (s >= 1 && s <= sampleCount) sync[s - 1] = true;
            }
        }
        // 每样本文件偏移(stsc × stco/co64)
        long[] sampleOff = new long[sampleCount];
        {
            int scCnt = readU32(all, stscOff + 12);
            if (scCnt <= 0) return;
            int[] stscFirst = new int[scCnt], stscSpc = new int[scCnt];
            int scPos = stscOff + 16;
            for (int i = 0; i < scCnt; i++) {
                stscFirst[i] = readU32(all, scPos);
                stscSpc[i] = readU32(all, scPos + 4);
                scPos += 12;
            }
            int ccCnt = readU32(all, chunkOff + 12);
            if (ccCnt <= 0) return;
            int cPos = chunkOff + 16;
            int idx = 0, stscI = 0;
            for (int ci = 0; ci < ccCnt && idx < sampleCount; ci++) {
                long o = co64 ? readU64(all, cPos) : readU32(all, cPos);
                cPos += co64 ? 8 : 4;
                while (stscI + 1 < scCnt && stscFirst[stscI + 1] - 1 <= ci) stscI++;
                int spc = stscSpc[stscI];
                if (spc <= 0) return;
                for (int k = 0; k < spc && idx < sampleCount; k++) {
                    sampleOff[idx] = o;
                    o += sizes[idx];
                    idx++;
                }
            }
            if (idx != sampleCount) return;
        }
        // 合成时间(展示时间)= 解码时间戳 + composition offset，这才是判断"同画面"的正确信号。
        // MediaMuxer 对同帧的多个 NAL 样本可能写出重复/跳变的解码时间戳(实测 Z Flip 为
        // [0,0,0,1450,1450,2961,...]，同帧两切片解码戳相同、下一帧从上一帧跳变)，
        // 用解码时间戳算间隔会把不同画面误判成同帧导致整段并成一样本。典型帧间隔取合成
        // 时间间隔分布的上四分位数——同一画面切片的微小间隔占一半样本，用中位数会被拉低。
        // "微小间隔"阈值 = 典型帧间隔/8。
        long[] compTime = new long[sampleCount];
        for (int i = 0; i < sampleCount; i++) compTime[i] = decodePts[i] + (hasCtts ? cttsOffsets[i] : 0);
        long tinyGap = 1;
        if (sampleCount > 1) {
            long[] gaps = new long[sampleCount - 1];
            for (int i = 0; i < sampleCount - 1; i++) gaps[i] = Math.abs(compTime[i + 1] - compTime[i]);
            Arrays.sort(gaps);
            long typical = gaps[gaps.length * 3 / 4];
            tinyGap = Math.max(1, typical / 8);
        }
        // 分组：同画面 = 合成时间相同 或 合成时间间隔微小
        boolean[] boundary = new boolean[sampleCount];
        boundary[0] = true;
        int groups = 1;
        for (int i = 1; i < sampleCount; i++) {
            boolean samePicture = compTime[i] == compTime[i - 1]
                    || Math.abs(compTime[i] - compTime[i - 1]) <= tinyGap;
            boundary[i] = !samePicture;
            if (boundary[i]) groups++;
        }
        if (groups == sampleCount) return; // 每个样本独立成组 → 无 per-NAL 拆分，无需合并
        // 组元数据
        int[] groupStart = new int[groups], groupCount = new int[groups];
        long[] groupPts = new long[groups];
        int[] groupCtts = new int[groups];
        boolean[] groupSync = new boolean[groups];
        {
            int g = 0;
            groupStart[0] = 0;
            groupPts[0] = decodePts[0];
            groupCtts[0] = cttsOffsets[0];
            for (int i = 1; i < sampleCount; i++) {
                if (boundary[i]) {
                    g++;
                    groupStart[g] = i;
                    groupPts[g] = decodePts[i];
                    groupCtts[g] = cttsOffsets[i];
                }
                groupCount[g]++;
            }
            groupCount[0]++;
        }
        int[] mergedSizes = new int[groups];
        for (int g = 0; g < groups; g++) {
            int sz = 0;
            for (int k = 0; k < groupCount[g]; k++) {
                int si = groupStart[g] + k;
                sz += sizes[si];
                if (sync[si]) groupSync[g] = true;
            }
            mergedSizes[g] = sz;
        }
        long[] groupDur = new long[groups];
        for (int g = 0; g < groups; g++) {
            if (g < groups - 1) groupDur[g] = groupPts[g + 1] - groupPts[g];
            else groupDur[g] = totalDur - groupPts[g];
        }
        // 重建样本表
        Map<String, byte[]> replacements = new HashMap<>();
        replacements.put("stsz", buildStsz(mergedSizes));
        replacements.put("stts", buildSttsRle(groupDur));
        if (hasCtts) replacements.put("ctts", buildCttsRle(groupCtts));
        replacements.put("stss", buildStss(groupSync));
        // 单 chunk 布局，新 mdat 起点 = ftyp + mdat 头
        int newMdatPayloadStart = ftypSize + 8;
        if (co64) replacements.put("co64", buildCo64(new long[]{newMdatPayloadStart}));
        else replacements.put("stco", buildStco(new int[]{newMdatPayloadStart}));
        replacements.put("stsc", buildStsc(1, groups, 1));
        // 重写 moov(替换 stbl 内的样本表 box)
        byte[] newMoov = boxBytes("moov", rewriteTree(all, moovOff + 8, moovEnd, replacements));
        // 重建 mdat
        ByteArrayOutputStream mdatPayload = new ByteArrayOutputStream();
        for (int g = 0; g < groups; g++) {
            for (int k = 0; k < groupCount[g]; k++) {
                int si = groupStart[g] + k;
                long soff = sampleOff[si];
                if (soff < 0 || soff + sizes[si] > mdatEnd) return; // 偏移越界，放弃合并(文件保持原样)
                mdatPayload.write(all, (int) soff, sizes[si]);
            }
        }
        byte[] newMdat = boxBytes("mdat", mdatPayload.toByteArray());
        // 组装并替换文件
        try {
            byte[] out = new byte[ftypSize + newMdat.length + newMoov.length];
            System.arraycopy(all, ftypStart, out, 0, ftypSize);
            System.arraycopy(newMdat, 0, out, ftypSize, newMdat.length);
            System.arraycopy(newMoov, 0, out, ftypSize + newMdat.length, newMoov.length);
            File tmp = new File(file.getParentFile(), file.getName() + ".au");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(out);
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(out);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            PublicTools.logToast("recorder", "merge " + sampleCount + "->" + groups + " samples ok size=" + file.length(), false);
        } catch (Exception e) {
            PublicTools.logToast("recorder", "merge ERR " + e, false);
        }
    }

    private static int readMdatSize(byte[] all, int off, int hdr) {
        long boxSize = readU32(all, off);
        if (boxSize == 1) return (int) (readU64(all, off + 8) - hdr);
        return (int) boxSize - hdr;
    }

    private static byte[] boxBytes(String type, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(8 + payload.length);
        bb.putInt(8 + payload.length);
        bb.put(type.getBytes(StandardCharsets.ISO_8859_1));
        bb.put(payload);
        return bb.array();
    }

    private static byte[] fullBoxBytes(String type, byte[] payload) {
        ByteBuffer bb = ByteBuffer.allocate(12 + payload.length);
        bb.putInt(12 + payload.length);
        bb.put(type.getBytes(StandardCharsets.ISO_8859_1));
        bb.putInt(0);
        bb.put(payload);
        return bb.array();
    }

    private static byte[] buildStsz(int[] sizes) {
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + sizes.length * 4);
        bb.putInt(0);
        bb.putInt(sizes.length);
        for (int s : sizes) bb.putInt(s);
        return fullBoxBytes("stsz", bb.array());
    }

    private static byte[] buildSttsRle(long[] durs) {
        List<int[]> rle = new ArrayList<>();
        for (long d : durs) {
            int di = (int) d;
            if (!rle.isEmpty() && rle.get(rle.size() - 1)[1] == di) rle.get(rle.size() - 1)[0]++;
            else rle.add(new int[]{1, di});
        }
        ByteBuffer bb = ByteBuffer.allocate(4 + rle.size() * 8);
        bb.putInt(rle.size());
        for (int[] e : rle) {
            bb.putInt(e[0]);
            bb.putInt(e[1]);
        }
        return fullBoxBytes("stts", bb.array());
    }

    private static byte[] buildCttsRle(int[] offsets) {
        List<int[]> rle = new ArrayList<>();
        for (int o : offsets) {
            if (!rle.isEmpty() && rle.get(rle.size() - 1)[1] == o) rle.get(rle.size() - 1)[0]++;
            else rle.add(new int[]{1, o});
        }
        ByteBuffer bb = ByteBuffer.allocate(4 + rle.size() * 8);
        bb.putInt(rle.size());
        for (int[] e : rle) {
            bb.putInt(e[0]);
            bb.putInt(e[1]);
        }
        return fullBoxBytes("ctts", bb.array());
    }

    private static byte[] buildStss(boolean[] sync) {
        int cnt = 0;
        for (boolean s : sync) if (s) cnt++;
        ByteBuffer bb = ByteBuffer.allocate(4 + cnt * 4);
        bb.putInt(cnt);
        for (int i = 0; i < sync.length; i++) if (sync[i]) bb.putInt(i + 1);
        return fullBoxBytes("stss", bb.array());
    }

    private static byte[] buildCo64(long[] offs) {
        ByteBuffer bb = ByteBuffer.allocate(4 + offs.length * 8);
        bb.putInt(offs.length);
        for (long o : offs) bb.putLong(o);
        return fullBoxBytes("co64", bb.array());
    }

    private static byte[] buildStco(int[] offs) {
        ByteBuffer bb = ByteBuffer.allocate(4 + offs.length * 4);
        bb.putInt(offs.length);
        for (int o : offs) bb.putInt(o);
        return fullBoxBytes("stco", bb.array());
    }

    private static byte[] buildStsc(int first, int spc, int desc) {
        ByteBuffer bb = ByteBuffer.allocate(4 + 12);
        bb.putInt(1);
        bb.putInt(first);
        bb.putInt(spc);
        bb.putInt(desc);
        return fullBoxBytes("stsc", bb.array());
    }

    // 递归重建 box 子树，type 在 replacements 里的 box 用新字节替换，容器 box 递归重建
    private static byte[] rewriteTree(byte[] all, int start, int end, Map<String, byte[]> replacements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(end - start + 64);
        int pos = start;
        while (pos + 8 <= end) {
            long boxSize = readU32(all, pos);
            int hdr = 8;
            if (boxSize == 1) {
                if (pos + 16 > end) break;
                boxSize = readU64(all, pos + 8);
                hdr = 16;
            } else if (boxSize == 0) boxSize = end - pos;
            if (boxSize < hdr || pos + boxSize > end) break;
            String type = new String(all, pos + 4, 4, StandardCharsets.ISO_8859_1);
            byte[] rep = replacements.get(type);
            if (rep != null) {
                out.write(rep, 0, rep.length);
            } else if (type.equals("moov") || type.equals("trak") || type.equals("mdia")
                    || type.equals("minf") || type.equals("stbl") || type.equals("edts") || type.equals("dinf")) {
                byte[] inner = rewriteTree(all, pos + hdr, pos + (int) boxSize, replacements);
                byte[] b = boxBytes(type, inner);
                out.write(b, 0, b.length);
            } else {
                out.write(all, pos, (int) boxSize);
            }
            pos += (int) boxSize;
        }
        return out.toByteArray();
    }

    // 递归遍历容器 box，收集 stsz/stts/ctts/stss/stco/co64/stsc 的绝对偏移(取第一个)
    private static void findTrackBoxes(byte[] all, int start, int end, int[] out) {
        int pos = start;
        while (pos + 8 <= end) {
            long boxSize = readU32(all, pos);
            int hdr = 8;
            if (boxSize == 1) {
                if (pos + 16 > end) break;
                boxSize = readU64(all, pos + 8);
                hdr = 16;
            } else if (boxSize == 0) boxSize = end - pos;
            if (boxSize < hdr || pos + boxSize > end) break;
            String type = new String(all, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int idx = -1;
            if (type.equals("stsz")) idx = 0;
            else if (type.equals("stts")) idx = 1;
            else if (type.equals("ctts")) idx = 2;
            else if (type.equals("stss")) idx = 3;
            else if (type.equals("stco")) idx = 4;
            else if (type.equals("co64")) idx = 5;
            else if (type.equals("stsc")) idx = 6;
            if (idx >= 0 && out[idx] == 0) out[idx] = pos;
            if (type.equals("moov") || type.equals("trak") || type.equals("mdia")
                    || type.equals("minf") || type.equals("stbl")) {
                findTrackBoxes(all, pos + hdr, pos + (int) boxSize, out);
            }
            pos += (int) boxSize;
        }
    }

    // 递归遍历容器 box，收集 stsz/stco/co64/stsc 的绝对偏移(取第一个)，写入 out[0..3]
    private static void findBoxes(byte[] all, int start, int end, int[] out) {
        int pos = start;
        while (pos + 8 <= end) {
            long boxSize = readU32(all, pos);
            int hdr = 8;
            if (boxSize == 1) {
                if (pos + 16 > end) break;
                boxSize = readU64(all, pos + 8);
                hdr = 16;
            } else if (boxSize == 0) boxSize = end - pos;
            if (boxSize < hdr || pos + boxSize > end) break;
            String type = new String(all, pos + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("stsz") && out[0] == 0) out[0] = pos;
            else if (type.equals("stco") && out[1] == 0) out[1] = pos;
            else if (type.equals("co64") && out[2] == 0) out[2] = pos;
            else if (type.equals("stsc") && out[3] == 0) out[3] = pos;
            if (type.equals("moov") || type.equals("trak") || type.equals("mdia")
                    || type.equals("minf") || type.equals("stbl")) {
                findBoxes(all, pos + hdr, pos + (int) boxSize, out);
            }
            pos += (int) boxSize;
        }
    }

    private static int readU32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static long readU64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static void writeU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void writeU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    public void finish() {
        if (!started || muxer == null) return;
        started = false;
        PublicTools.logToast("recorder", "finish samplesWritten=" + samplesWritten + " hasKeyframe=" + hasWrittenKeyframe, false);
        try {
            muxer.stop();
            PublicTools.logToast("recorder", "muxer.stop ok", false);
        } catch (Exception e) {
            PublicTools.logToast("recorder", "muxer.stop ERR " + e, false);
        }
        try {
            muxer.release();
        } catch (Exception ignored) {
        }
        muxer = null;
        // 从没等到关键帧(录屏极短)：删掉无效空文件，不进相册
        if (!hasWrittenKeyframe) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception ignored) {
            }
            return;
        }
        // 修正 MuMu 模拟器 MediaMuxer 的 AVC 样本 +4 前缀 bug(正常设备为无操作)
        normalizeMangledMp4();
        // 合并 per-NAL 样本为完整 AU(修复部分编码器 1 帧 2 切片导致硬解花屏，健康设备为无操作)
        mergePerNalSamples();
        // 导出到相册(API29+ 走 MediaStore，否则 MIUI 等相册看不到 app 私有目录文件)
        exportToGallery();
        PublicTools.logToast("recorder", AppData.applicationContext.getString(R.string.toast_record_saved), true);
    }

    // 会话异常结束时静默关闭(不弹toast)
    public void closeQuietly() {
        try {
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
                muxer = null;
            }
            started = false;
            if (hasWrittenKeyframe && file != null && file.exists()) {
                normalizeMangledMp4();
                mergePerNalSamples();
                exportToGallery();
            } else if (file != null) {
                // 没等到关键帧：删掉无效空文件
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // 导出到相册：API29+ 写入 MediaStore.Video(相册/文件管理可见)；<29 无公共目录写权限，
    // 与截图一致只扫描 app 目录尽力而为。muxer 仍写 app 私有目录临时文件，
    // 导出成功后再删掉(MediaMuxer/normalize 全程操作 File，零改动)
    private void exportToGallery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Easycontrol");
                Uri uri = AppData.applicationContext.getContentResolver()
                        .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("MediaStore insert fail");
                try (OutputStream out = AppData.applicationContext.getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(file)) {
                    if (out == null) throw new IOException("MediaStore open fail");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                // 写入相册成功，删掉 app 私有目录里的临时文件
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception e) {
                PublicTools.logToast("recorder", "export ERR " + e, false);
                // 导出失败：临时文件仍在 app 目录，尽力扫描一次
                scanFile();
            }
        } else {
            scanFile();
        }
    }

    private void scanFile() {
        try {
            MediaScannerConnection.scanFile(AppData.applicationContext,
                    new String[]{file.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        } catch (Exception ignored) {
        }
    }
}
