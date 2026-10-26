package com.beejepa.muse;

import jakarta.inject.Singleton;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic Bee Voice Acoustic Synthesizer.
 * Maps Muse text responses into authentic honeybee bioacoustic communication signals.
 *
 * Biological grounded design:
 * - Fundamental frequencies: 210 Hz to 460 Hz (normal hive hum to queen piping).
 * - Wingbeat amplitude modulation: ~26-30 Hz AM envelope.
 * - Harmonics: 1st through 5th harmonic overtone spectrum.
 * - Determinism: Every specific word always produces the exact same phonetic bee pitch & timbre!
 */
@Singleton
public class BeeAcousticSynthesizer {

    public static final int SAMPLE_RATE = 22050; // High clarity, ultra-low memory for Raspberry Pi 4

    public byte[] synthesizeWav(String text) {
        if (text == null || text.isBlank()) {
            return generateEmptyWav();
        }

        List<Float> samples = new ArrayList<>(SAMPLE_RATE * 4);
        String[] tokens = text.trim().split("\\s+");

        for (int wIdx = 0; wIdx < tokens.length; wIdx++) {
            String token = tokens[wIdx];
            boolean isLast = (wIdx == tokens.length - 1);
            synthesizeWord(token, isLast, samples);
        }

        // Apply comb resonance / hive acoustic decay tail (40ms)
        applyHiveCombResonance(samples);

        return encodeWav(samples);
    }

    private void synthesizeWord(String rawToken, boolean isLastWordInSentence, List<Float> samples) {
        String clean = rawToken.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (clean.isBlank()) return;

        boolean isQuestion = rawToken.contains("?");
        boolean isExclamation = rawToken.contains("!");
        boolean hasComma = rawToken.contains(",") || rawToken.contains(";");
        boolean hasPeriod = rawToken.contains(".") || isLastWordInSentence;

        // 1. Deterministic Pitch and Timbre from Word Identity
        int wordHash = Math.abs(clean.hashCode());
        
        // Base fundamental frequency: 220 Hz to 350 Hz
        float baseF0 = 220.0f + (wordHash % 130);

        // Keyword semantic pitch biasing (scientific bioacoustic cues)
        if (clean.contains("queen") || clean.contains("piping") || clean.contains("alert") || clean.contains("danger") || clean.contains("wasp")) {
            baseF0 = 380.0f + (wordHash % 80); // Higher agitation / queen piping register
        } else if (clean.contains("stable") || clean.contains("calm") || clean.contains("brood") || clean.contains("honey")) {
            baseF0 = 210.0f + (wordHash % 50); // Deep, calm hive hum
        }

        // 2. Count syllables (vowel group heuristic)
        int syllables = countSyllables(clean);
        float totalWordDuration = Math.max(0.12f, Math.min(0.42f, 0.08f + syllables * 0.07f));
        float syllableDuration = totalWordDuration / syllables;

        // 3. Synthesize each syllable of the word
        for (int s = 0; s < syllables; s++) {
            float startF = baseF0 + (s * 15.0f);
            float endF = baseF0 + (s * 15.0f);

            // Sentence prosody / inflection
            if (isQuestion && s == syllables - 1) {
                endF += 55.0f; // Rising interrogative inflection
            } else if (hasPeriod && s == syllables - 1) {
                endF -= 35.0f; // Falling declarative cadence
            } else if (isExclamation) {
                startF += 40.0f;
                endF += 20.0f;
            }

            float volume = isExclamation ? 0.90f : 0.75f;
            float modFreq = 26.0f + (wordHash % 6); // 26 - 31 Hz wingbeat flutter

            appendTone(samples, syllableDuration, startF, endF, volume, modFreq);

            // Micro-gap between syllables within the same word
            if (s < syllables - 1) {
                appendSilence(samples, 0.025f);
            }
        }

        // 4. Inter-word pause & punctuation spacing
        if (hasComma) {
            appendSilence(samples, 0.085f);
        } else if (hasPeriod || isQuestion || isExclamation) {
            appendSilence(samples, 0.150f);
        } else {
            appendSilence(samples, 0.045f);
        }
    }

    private void appendTone(List<Float> samples, float durationS, float startF, float endF, float volume, float modFreq) {
        int numSamples = (int) (durationS * SAMPLE_RATE);
        float phase = 0.0f;
        float modPhase = 0.0f;
        float attack = 0.025f;
        float decay = 0.035f;

        for (int i = 0; i < numSamples; i++) {
            float t = (float) i / SAMPLE_RATE;
            float progress = (float) i / Math.max(1, numSamples - 1);
            float curF = startF + (endF - startF) * progress;

            // Attack & Decay Envelope
            float env = 1.0f;
            if (t < attack) {
                env = t / attack;
            } else if (t > durationS - decay) {
                env = (durationS - t) / decay;
            }
            env = Math.max(0.0f, Math.min(1.0f, env));

            // Wingbeat AM flutter
            float am = 0.65f + 0.35f * (float) Math.sin(modPhase);
            modPhase += 2.0f * (float) Math.PI * modFreq / SAMPLE_RATE;

            // Authentic insect harmonic overtone series
            float s1 = (float) Math.sin(phase);
            float s2 = (float) Math.sin(2.0 * phase) * 0.52f;
            float s3 = (float) Math.sin(3.0 * phase) * 0.32f;
            float s4 = (float) Math.sin(4.0 * phase) * 0.16f;
            float s5 = (float) Math.sin(5.0 * phase) * 0.08f;

            float val = (s1 + s2 + s3 + s4 + s5) / 2.08f;
            // Exoskeleton acoustic saturation
            val = (float) Math.tanh(val * 1.4);

            samples.add(val * env * am * volume);

            phase += 2.0f * (float) Math.PI * curF / SAMPLE_RATE;
        }
    }

    private void appendSilence(List<Float> samples, float durationS) {
        int count = (int) (durationS * SAMPLE_RATE);
        for (int i = 0; i < count; i++) {
            samples.add(0.0f);
        }
    }

    private void applyHiveCombResonance(List<Float> samples) {
        int delay = (int) (0.035f * SAMPLE_RATE); // 35ms comb reflection
        int size = samples.size();
        for (int i = 0; i < size; i++) {
            if (i + delay < size) {
                float echo = samples.get(i) * 0.22f;
                samples.set(i + delay, samples.get(i + delay) + echo);
            }
        }
    }

    private int countSyllables(String word) {
        if (word == null || word.length() <= 3) return 1;
        String w = word.toLowerCase();
        int count = 0;
        boolean prevVowel = false;
        String vowels = "aeiouy";

        for (int i = 0; i < w.length(); i++) {
            boolean isVowel = vowels.indexOf(w.charAt(i)) != -1;
            if (isVowel && !prevVowel) {
                count++;
            }
            prevVowel = isVowel;
        }
        if (w.endsWith("e") && count > 1 && vowels.indexOf(w.charAt(w.length() - 2)) == -1) {
            count--;
        }
        return Math.max(1, Math.min(4, count));
    }

    private byte[] encodeWav(List<Float> samples) {
        int totalAudioLen = samples.size() * 2; // 16-bit mono = 2 bytes per sample
        int totalDataLen = totalAudioLen + 36;
        int byteRate = SAMPLE_RATE * 2;

        ByteBuffer buffer = ByteBuffer.allocate(44 + totalAudioLen).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buffer.putInt(totalDataLen);
        buffer.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');

        // fmt chunk
        buffer.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buffer.putInt(16); // subchunk1size (16 for PCM)
        buffer.putShort((short) 1); // audio format (1 = PCM)
        buffer.putShort((short) 1); // num channels (1 = mono)
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(byteRate);
        buffer.putShort((short) 2); // block align
        buffer.putShort((short) 16); // bits per sample

        // data chunk
        buffer.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buffer.putInt(totalAudioLen);

        // Find max peak for normalization
        float maxPeak = 0.001f;
        for (float s : samples) {
            float abs = Math.abs(s);
            if (abs > maxPeak) maxPeak = abs;
        }

        // Write 16-bit PCM samples
        for (float s : samples) {
            float normalized = (s / maxPeak) * 0.94f;
            short pcm = (short) Math.max(-32768, Math.min(32767, normalized * 32767.0f));
            buffer.putShort(pcm);
        }

        return buffer.array();
    }

    private byte[] generateEmptyWav() {
        return encodeWav(List.of(0.0f, 0.0f));
    }
}
