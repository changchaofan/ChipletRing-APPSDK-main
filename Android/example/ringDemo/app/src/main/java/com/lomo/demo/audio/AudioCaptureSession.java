package com.lomo.demo.audio;

import android.text.TextUtils;

import com.lm.sdk.AdPcmTool;
import com.lomo.demo.FileUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class AudioCaptureSession {
    public static final byte AUDIO_TYPE_PCM = 0x00;
    public static final byte AUDIO_TYPE_ADPCM = 0x01;
    public static final int INPUT_SOURCE_UNKNOWN = 0;
    public static final int INPUT_SOURCE_CONTROL_AUDIO = 1;
    public static final int INPUT_SOURCE_SET_AUDIO = 2;

    private static final int SAMPLE_RATE = 8000;
    private static final short CHANNEL_COUNT = 1;
    private static final short BITS_PER_SAMPLE = 16;
    private static final double HIGH_PASS_CUTOFF_HZ = 120.0;
    private static final double LOW_PASS_CUTOFF_HZ = 3400.0;

    private byte currentAudioType = AUDIO_TYPE_ADPCM;
    private boolean recording;
    private String rawPath;
    private String pcmPath;
    private String wavPath;
    private String skip1WavPath;
    private int rawBytes;
    private AdPcmTool adPcmTool;
    private ByteArrayOutputStream pcmBuffer;
    private List<byte[]> acceptedChunks;
    private int activeInputSource = INPUT_SOURCE_UNKNOWN;
    private int acceptedChunkCount;
    private int ignoredChunkCount;

    public void setCurrentAudioType(byte audioType) {
        currentAudioType = audioType;
    }

    public byte getCurrentAudioType() {
        return currentAudioType;
    }

    public boolean isRecording() {
        return recording;
    }

    public void start(String basePathWithoutExtension) {
        if (TextUtils.isEmpty(basePathWithoutExtension)) {
            throw new IllegalArgumentException("basePathWithoutExtension is empty");
        }
        rawPath = basePathWithoutExtension + (currentAudioType == AUDIO_TYPE_ADPCM ? ".adpcm" : ".raw.pcm");
        pcmPath = basePathWithoutExtension + ".pcm";
        wavPath = basePathWithoutExtension + ".wav";
        skip1WavPath = basePathWithoutExtension + "_skip1.wav";
        deleteIfExists(rawPath);
        deleteIfExists(pcmPath);
        deleteIfExists(wavPath);
        deleteIfExists(skip1WavPath);
        rawBytes = 0;
        adPcmTool = new AdPcmTool();
        pcmBuffer = new ByteArrayOutputStream();
        acceptedChunks = new ArrayList<>();
        activeInputSource = INPUT_SOURCE_UNKNOWN;
        acceptedChunkCount = 0;
        ignoredChunkCount = 0;
        recording = true;
    }

    public void appendControlAudio(byte[] bytes) {
        append(bytes, INPUT_SOURCE_CONTROL_AUDIO);
    }

    public void appendSetAudio(byte[] bytes) {
        append(bytes, INPUT_SOURCE_SET_AUDIO);
    }

    private void append(byte[] bytes, int inputSource) {
        if (!recording || bytes == null || bytes.length == 0) {
            return;
        }
        if (activeInputSource == INPUT_SOURCE_UNKNOWN) {
            activeInputSource = inputSource;
        } else if (activeInputSource != inputSource) {
            ignoredChunkCount++;
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(rawPath, true)) {
            fos.write(bytes);
            rawBytes += bytes.length;
            acceptedChunkCount++;
            acceptedChunks.add(bytes.clone());
            if (currentAudioType == AUDIO_TYPE_ADPCM) {
                byte[] pcmChunk = adPcmTool.adpcmToPcmFromJNI(bytes);
                if (pcmChunk != null && pcmChunk.length > 0) {
                    pcmBuffer.write(pcmChunk, 0, pcmChunk.length);
                }
            } else {
                pcmBuffer.write(bytes, 0, bytes.length);
            }
        } catch (IOException e) {
            throw new IllegalStateException("append audio failed", e);
        }
    }

    public AudioExportResult stop() {
        recording = false;
        byte[] rawAudio = readAllBytes(rawPath);
        if (rawAudio.length == 0) {
            return new AudioExportResult(
                    rawPath,
                    pcmPath,
                    wavPath,
                    skip1WavPath,
                    0,
                    0,
                    currentAudioType,
                    false,
                    activeInputSource,
                    acceptedChunkCount,
                    ignoredChunkCount
            );
        }

        byte[] pcmAudio = pcmBuffer == null ? new byte[0] : pcmBuffer.toByteArray();
        writeBytes(pcmPath, pcmAudio);
        writeWavFile(wavPath, pcmAudio);
        if (currentAudioType == AUDIO_TYPE_ADPCM) {
            writeWavFile(skip1WavPath, buildPreviewPcm(decodeAdpcmWithSkip(1)));
        }
        return new AudioExportResult(
                rawPath,
                pcmPath,
                wavPath,
                skip1WavPath,
                rawAudio.length,
                pcmAudio.length,
                currentAudioType,
                true,
                activeInputSource,
                acceptedChunkCount,
                ignoredChunkCount
        );
    }

    private byte[] decodeAdpcmWithSkip(int skipBytesPerChunk) {
        if (acceptedChunks == null || acceptedChunks.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream variantPcm = new ByteArrayOutputStream();
        AdPcmTool variantTool = new AdPcmTool();
        for (byte[] chunk : acceptedChunks) {
            if (chunk == null || chunk.length <= skipBytesPerChunk) {
                continue;
            }
            byte[] payload = new byte[chunk.length - skipBytesPerChunk];
            System.arraycopy(chunk, skipBytesPerChunk, payload, 0, payload.length);
            byte[] decoded = variantTool.adpcmToPcmFromJNI(payload);
            if (decoded != null && decoded.length > 0) {
                variantPcm.write(decoded, 0, decoded.length);
            }
        }
        return variantPcm.toByteArray();
    }

    private static byte[] buildPreviewPcm(byte[] pcmAudio) {
        if (pcmAudio == null || pcmAudio.length < 2) {
            return new byte[0];
        }
        int length = pcmAudio.length - (pcmAudio.length % 2);
        int sampleCount = length / 2;
        double[] samples = new double[sampleCount];
        long sum = 0L;
        for (int i = 0; i < sampleCount; i++) {
            short sample = readSample16Le(pcmAudio, i * 2);
            samples[i] = sample;
            sum += sample;
        }
        double dcOffset = (double) sum / sampleCount;
        for (int i = 0; i < sampleCount; i++) {
            samples[i] -= dcOffset;
        }
        samples = applyHighPass(samples, HIGH_PASS_CUTOFF_HZ);
        samples = applyLowPass(samples, LOW_PASS_CUTOFF_HZ);
        byte[] cleaned = new byte[length];
        for (int i = 0; i < sampleCount; i++) {
            writeSample16Le(cleaned, i * 2, clampToPcm16((int) Math.round(samples[i])));
        }
        return applyMakeupGain(cleaned);
    }

    private static byte[] applyMakeupGain(byte[] pcmAudio) {
        int peak = 0;
        for (int i = 0; i < pcmAudio.length; i += 2) {
            peak = Math.max(peak, Math.abs(readSample16Le(pcmAudio, i)));
        }
        if (peak <= 0) {
            return pcmAudio;
        }
        double gain = Math.min(4.0, 20000.0 / peak);
        if (gain <= 1.1) {
            return pcmAudio;
        }
        byte[] boosted = new byte[pcmAudio.length];
        for (int i = 0; i < pcmAudio.length; i += 2) {
            int boostedSample = (int) Math.round(readSample16Le(pcmAudio, i) * gain);
            writeSample16Le(boosted, i, clampToPcm16(boostedSample));
        }
        return boosted;
    }

    private static double[] applyHighPass(double[] input, double cutoffHz) {
        double[] output = new double[input.length];
        if (input.length == 0) {
            return output;
        }
        double dt = 1.0 / SAMPLE_RATE;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double alpha = rc / (rc + dt);
        output[0] = input[0];
        double previousInput = input[0];
        double previousOutput = output[0];
        for (int i = 1; i < input.length; i++) {
            double currentInput = input[i];
            double currentOutput = alpha * (previousOutput + currentInput - previousInput);
            output[i] = currentOutput;
            previousInput = currentInput;
            previousOutput = currentOutput;
        }
        return output;
    }

    private static double[] applyLowPass(double[] input, double cutoffHz) {
        double[] output = new double[input.length];
        if (input.length == 0) {
            return output;
        }
        double dt = 1.0 / SAMPLE_RATE;
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double alpha = dt / (rc + dt);
        output[0] = input[0];
        for (int i = 1; i < input.length; i++) {
            output[i] = output[i - 1] + alpha * (input[i] - output[i - 1]);
        }
        return output;
    }

    private static void deleteIfExists(String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
    }

    private static void writeBytes(String path, byte[] bytes) {
        try (FileOutputStream fos = new FileOutputStream(path, false)) {
            fos.write(bytes);
            fos.flush();
        } catch (IOException e) {
            throw new IllegalStateException("write bytes failed", e);
        }
    }

    private static byte[] readAllBytes(String path) {
        File file = new File(path);
        if (!file.exists() || file.length() <= 0) {
            return new byte[0];
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] data = new byte[(int) raf.length()];
            raf.readFully(data);
            return data;
        } catch (IOException e) {
            throw new IllegalStateException("read audio failed", e);
        }
    }

    private static void writeWavFile(String wavPath, byte[] pcmAudio) {
        try (FileOutputStream fos = new FileOutputStream(wavPath, false)) {
            int totalDataLen = pcmAudio.length + 36;
            int byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8;
            fos.write(new byte[]{
                    'R', 'I', 'F', 'F',
                    (byte) (totalDataLen & 0xff),
                    (byte) ((totalDataLen >> 8) & 0xff),
                    (byte) ((totalDataLen >> 16) & 0xff),
                    (byte) ((totalDataLen >> 24) & 0xff),
                    'W', 'A', 'V', 'E',
                    'f', 'm', 't', ' ',
                    16, 0, 0, 0,
                    1, 0,
                    (byte) CHANNEL_COUNT, 0,
                    (byte) (SAMPLE_RATE & 0xff),
                    (byte) ((SAMPLE_RATE >> 8) & 0xff),
                    (byte) ((SAMPLE_RATE >> 16) & 0xff),
                    (byte) ((SAMPLE_RATE >> 24) & 0xff),
                    (byte) (byteRate & 0xff),
                    (byte) ((byteRate >> 8) & 0xff),
                    (byte) ((byteRate >> 16) & 0xff),
                    (byte) ((byteRate >> 24) & 0xff),
                    (byte) (CHANNEL_COUNT * BITS_PER_SAMPLE / 8), 0,
                    (byte) BITS_PER_SAMPLE, 0,
                    'd', 'a', 't', 'a',
                    (byte) (pcmAudio.length & 0xff),
                    (byte) ((pcmAudio.length >> 8) & 0xff),
                    (byte) ((pcmAudio.length >> 16) & 0xff),
                    (byte) ((pcmAudio.length >> 24) & 0xff)
            });
            fos.write(pcmAudio);
            fos.flush();
        } catch (IOException e) {
            throw new IllegalStateException("write wav failed", e);
        }
    }

    private static short readSample16Le(byte[] data, int offset) {
        int low = data[offset] & 0xff;
        int high = data[offset + 1];
        return (short) ((high << 8) | low);
    }

    private static void writeSample16Le(byte[] data, int offset, short sample) {
        data[offset] = (byte) (sample & 0xff);
        data[offset + 1] = (byte) ((sample >> 8) & 0xff);
    }

    private static short clampToPcm16(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }

    public static String buildSessionBasePath(android.content.Context context, String prefix) {
        String time = FileUtil.getDateTime().replace(":", "-").replace(" ", "_");
        return FileUtil.getSDPath(context, prefix + "_" + time);
    }

    public static final class AudioExportResult {
        public final String rawPath;
        public final String pcmPath;
        public final String wavPath;
        public final String skip1WavPath;
        public final int rawSize;
        public final int pcmSize;
        public final byte audioType;
        public final boolean hasAudio;
        public final int inputSource;
        public final int acceptedChunkCount;
        public final int ignoredChunkCount;

        public AudioExportResult(
                String rawPath,
                String pcmPath,
                String wavPath,
                String skip1WavPath,
                int rawSize,
                int pcmSize,
                byte audioType,
                boolean hasAudio,
                int inputSource,
                int acceptedChunkCount,
                int ignoredChunkCount
        ) {
            this.rawPath = rawPath;
            this.pcmPath = pcmPath;
            this.wavPath = wavPath;
            this.skip1WavPath = skip1WavPath;
            this.rawSize = rawSize;
            this.pcmSize = pcmSize;
            this.audioType = audioType;
            this.hasAudio = hasAudio;
            this.inputSource = inputSource;
            this.acceptedChunkCount = acceptedChunkCount;
            this.ignoredChunkCount = ignoredChunkCount;
        }
    }
}
