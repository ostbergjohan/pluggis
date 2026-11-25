package se.ostberg.pluggis.speech;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WavTest {

    private static byte[] wav(int rate, int channels, int frames) {
        int data = frames * channels * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + data).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) channels).putInt(rate).putInt(rate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16).putInt(0x61746164).putInt(data);
        for (int i = 0; i < frames * channels; i++) {
            b.putShort((short) 1000);
        }
        return b.array();
    }

    @Test
    void convertsToSixteenKilohertzMono() {
        byte[] out = Wav.toMono16k(wav(24_000, 2, 24_000));   // 1 s stereo 24 kHz
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getShort(22)).isEqualTo((short) 1);
        assertThat(b.getInt(24)).isEqualTo(16_000);
        assertThat(out.length).isEqualTo(44 + 16_000 * 2);
        assertThat(b.getShort(44 + 200)).isEqualTo((short) 1000);
    }

    @Test
    void leavesUnknownDataAlone() {
        byte[] junk = {1, 2, 3};
        assertThat(Wav.toMono16k(junk)).isSameAs(junk);
        byte[] already = wav(16_000, 1, 100);
        assertThat(Wav.toMono16k(already)).isSameAs(already);
    }
}
