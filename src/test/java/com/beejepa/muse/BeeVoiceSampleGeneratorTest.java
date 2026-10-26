package com.beejepa.muse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BeeVoiceSampleGeneratorTest {

    @Test
    public void testAcousticSynthesisWavGeneration() {
        BeeAcousticSynthesizer synth = new BeeAcousticSynthesizer();

        String text = "Brood nest stable at thirty-four degrees. Foraging traffic normal.";
        byte[] wav = synth.synthesizeWav(text);

        assertNotNull(wav);
        assertTrue(wav.length > 44, "WAV byte array must include header and audio data");

        // Validate RIFF header
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);

        // Validate WAVE signature
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);
    }
}
