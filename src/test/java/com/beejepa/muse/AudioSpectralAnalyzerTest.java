package com.beejepa.muse;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

public class AudioSpectralAnalyzerTest {

    @Test
    public void testFFTSpectralAnalysisAndProsodyExtraction() {
        AudioSpectralAnalyzer analyzer = new AudioSpectralAnalyzer();
        int sampleRate = 16000;
        int numSamples = sampleRate * 1; // 1 second
        ByteBuffer buffer = ByteBuffer.allocate(numSamples * 2).order(ByteOrder.LITTLE_ENDIAN);

        // Generate synthetic voice: 3 pulsed bursts (3 syllables) around 300 Hz
        for (int i = 0; i < numSamples; i++) {
            float t = (float) i / sampleRate;
            float pulse = 0.0f;
            if ((t >= 0.1f && t <= 0.25f) || (t >= 0.4f && t <= 0.55f) || (t >= 0.7f && t <= 0.85f)) {
                pulse = (float) Math.sin(2.0 * Math.PI * 320.0 * t);
            }
            short pcm = (short) (pulse * 28000.0f);
            buffer.putShort(pcm);
        }

        AudioSpectralAnalyzer.SpectralProfile profile = analyzer.analyzeAudio(buffer.array(), sampleRate);

        assertNotNull(profile);
        assertTrue(profile.estimatedSyllables() >= 2, "Should detect at least 2 syllable bursts");
        assertTrue(profile.meanCentroidHz() >= 200.0f, "Centroid should reflect voice band frequencies");

        // Synthesize bee mirror response from spectrum
        BeeAcousticSynthesizer synth = new BeeAcousticSynthesizer();
        byte[] responseWav = synth.synthesizeFromSpectralProfile(profile);

        assertNotNull(responseWav);
        assertTrue(responseWav.length > 44, "Response WAV must contain audio bytes");
        assertEquals('R', (char) responseWav[0]);
        assertEquals('W', (char) responseWav[8]);
    }

    @Test
    public void testLogNormalConversationalTurnTakingDistribution() {
        ResponseLatencyModel model = new ResponseLatencyModel();

        long minObserved = Long.MAX_VALUE;
        long maxObserved = Long.MIN_VALUE;

        for (int i = 0; i < 50; i++) {
            long delay = model.sampleConversationalDelayMs();
            assertTrue(delay >= 280, "Delay must respect biological floor");
            assertTrue(delay <= 1400, "Delay must stay within conversational ceiling");
            if (delay < minObserved) minObserved = delay;
            if (delay > maxObserved) maxObserved = delay;
        }

        // Verify that delay is not constant (has realistic biological variance)
        assertTrue(maxObserved > minObserved, "Conversational delays must be stochastic and not constant");
    }
}
