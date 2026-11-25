package se.ostberg.pluggis.speech;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Små hjälpare för wav (PCM 16 bitar) – för enheter som vill ha mindre ljud. */
public final class Wav {

    public static final int SMALL_RATE = 16_000;

    private Wav() {
    }

    /**
     * Gör om en wav (16-bitars PCM, valfri samplingsfrekvens och 1–2 kanaler) till 16 kHz mono. Går filen inte
     * att tolka returneras den oförändrad.
     */
    public static byte[] toMono16k(byte[] wav) {
        try {
            ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            if (wav.length < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {   // "RIFF", "WAVE"
                return wav;
            }
            int pos = 12, channels = 0, rate = 0, bits = 0, dataOff = -1, dataLen = 0;
            while (pos + 8 <= wav.length) {
                int id = b.getInt(pos);
                int len = b.getInt(pos + 4);
                int body = pos + 8;
                if (id == 0x20746d66) {   // "fmt "
                    if (b.getShort(body) != 1) {
                        return wav;   // inte PCM
                    }
                    channels = b.getShort(body + 2);
                    rate = b.getInt(body + 4);
                    bits = b.getShort(body + 14);
                } else if (id == 0x61746164) {   // "data"
                    dataOff = body;
                    // strömmad wav kan ha 0 eller -1 som längd: ta resten av filen
                    dataLen = len <= 0 || body + len > wav.length ? wav.length - body : len;
                    break;
                }
                if (len < 0) {
                    return wav;
                }
                pos = body + len + (len & 1);
            }
            if (dataOff < 0 || bits != 16 || channels < 1 || channels > 2 || rate <= 0) {
                return wav;
            }
            int frames = dataLen / (2 * channels);
            if (rate == SMALL_RATE && channels == 1) {
                return wav;
            }
            int outFrames = (int) ((long) frames * SMALL_RATE / rate);
            ByteBuffer out = ByteBuffer.allocate(44 + outFrames * 2).order(ByteOrder.LITTLE_ENDIAN);
            header(out, outFrames * 2);
            double step = (double) rate / SMALL_RATE;
            for (int i = 0; i < outFrames; i++) {
                double src = i * step;
                int i0 = (int) src;
                int i1 = Math.min(i0 + 1, frames - 1);
                double f = src - i0;
                double v = sample(b, dataOff, channels, i0) * (1 - f) + sample(b, dataOff, channels, i1) * f;
                out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(v))));
            }
            return out.array();
        } catch (RuntimeException e) {
            return wav;
        }
    }

    private static double sample(ByteBuffer b, int dataOff, int channels, int frame) {
        int p = dataOff + frame * 2 * channels;
        if (channels == 1) {
            return b.getShort(p);
        }
        return (b.getShort(p) + b.getShort(p + 2)) / 2.0;
    }

    private static void header(ByteBuffer out, int dataBytes) {
        out.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157);
        out.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SMALL_RATE).putInt(SMALL_RATE * 2).putShort((short) 2).putShort((short) 16);
        out.putInt(0x61746164).putInt(dataBytes);
    }
}
