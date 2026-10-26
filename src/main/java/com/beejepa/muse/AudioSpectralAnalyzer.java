package com.beejepa.muse;

import jakarta.inject.Singleton;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast In-Memory FFT & Bioacoustic Audio Spectral Analyzer.
 * Extracts prosody, syllable onsets, spectral centroid, and pitch contour
 * directly from raw microphone PCM/WAV audio on Java Virtual Threads.
 */
@Singleton
public class AudioSpectralAnalyzer {

    public record SyllableOnset(float timeSec, float energy, float centroidHz) {}

    public record SpectralProfile(
            int estimatedSyllables,
            float meanCentroidHz,
            float pitchTiltHz,       // positive if pitch rose towards the end (question), negative if dropped
            float energyLevel,       // 0.0 (whisper) to 1.0 (loud/agitated)
            List<SyllableOnset> onsets
    ) {}

    private static final int FFT_SIZE = 1024;

    /**
     * Analyzes raw 16-bit mono PCM audio (or stripped WAV).
     */
    public SpectralProfile analyzeAudio(byte[] audioBytes, int defaultSampleRate) {
        int sampleRate = defaultSampleRate;
        if (audioBytes != null && audioBytes.length > 44 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
            ByteBuffer headerBb = ByteBuffer.wrap(audioBytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN);
            int detectedRate = headerBb.getInt();
            if (detectedRate >= 8000 && detectedRate <= 96000) {
                sampleRate = detectedRate;
            }
        }

        float[] pcm = decodeToFloatArray(audioBytes);
        if (pcm.length < FFT_SIZE) {
            return new SpectralProfile(1, 280.0f, 0.0f, 0.5f, List.of(new SyllableOnset(0.1f, 0.5f, 280.0f)));
        }

        int hopSize = FFT_SIZE / 2; // 50% overlap
        int numFrames = (pcm.length - FFT_SIZE) / hopSize;
        float[] frameEnergies = new float[numFrames];
        float[] frameCentroids = new float[numFrames];

        float[] window = hanningWindow(FFT_SIZE);
        float[] real = new float[FFT_SIZE];
        float[] imag = new float[FFT_SIZE];

        float totalEnergySum = 0.0f;
        float weightedCentroidSum = 0.0f;

        for (int f = 0; f < numFrames; f++) {
            int offset = f * hopSize;
            for (int i = 0; i < FFT_SIZE; i++) {
                real[i] = pcm[offset + i] * window[i];
                imag[i] = 0.0f;
            }

            // Radix-2 In-place Cooley-Tukey FFT
            fft(real, imag);

            // Compute power spectrum & spectral centroid
            float frameEnergy = 0.0f;
            float numWeightedFreq = 0.0f;
            float denomMag = 0.0f;

            for (int k = 1; k < FFT_SIZE / 2; k++) {
                float mag = (float) Math.sqrt(real[k] * real[k] + imag[k] * imag[k]);
                float freq = (float) k * sampleRate / FFT_SIZE;

                // Restrict voice frequency band (80 Hz to 3500 Hz)
                if (freq >= 80.0f && freq <= 3500.0f) {
                    frameEnergy += mag * mag;
                    numWeightedFreq += freq * mag;
                    denomMag += mag;
                }
            }

            frameEnergies[f] = frameEnergy;
            frameCentroids[f] = denomMag > 1e-4f ? (numWeightedFreq / denomMag) : 250.0f;

            totalEnergySum += frameEnergy;
            weightedCentroidSum += frameCentroids[f] * frameEnergy;
        }

        float meanCentroid = totalEnergySum > 1e-4f ? (weightedCentroidSum / totalEnergySum) : 260.0f;

        // Detect syllable onsets via local energy peaks
        List<SyllableOnset> onsets = detectOnsets(frameEnergies, frameCentroids, hopSize, sampleRate);
        int syllables = Math.max(1, Math.min(8, onsets.size()));

        // Calculate pitch tilt (end frequency minus start frequency)
        float firstHalfCentroid = 0.0f;
        float secondHalfCentroid = 0.0f;
        int mid = numFrames / 2;
        int count1 = 0, count2 = 0;

        for (int i = 0; i < numFrames; i++) {
            if (i < mid) {
                firstHalfCentroid += frameCentroids[i];
                count1++;
            } else {
                secondHalfCentroid += frameCentroids[i];
                count2++;
            }
        }
        float tilt = (count2 > 0 && count1 > 0)
                ? (secondHalfCentroid / count2) - (firstHalfCentroid / count1)
                : 0.0f;

        float normalizedEnergy = Math.min(1.0f, totalEnergySum / (numFrames * 50.0f + 1e-4f));

        return new SpectralProfile(syllables, meanCentroid, tilt, normalizedEnergy, onsets);
    }

    private List<SyllableOnset> detectOnsets(float[] energies, float[] centroids, int hopSize, int sampleRate) {
        List<SyllableOnset> list = new ArrayList<>();
        float maxE = 0.001f;
        for (float e : energies) {
            if (e > maxE) maxE = e;
        }

        float threshold = maxE * 0.15f; // Onset threshold at 15% of max peak
        int minHopDistance = (int) (0.12f * sampleRate / hopSize); // Minimum 120ms between syllables
        int lastOnsetFrame = -minHopDistance;

        for (int i = 1; i < energies.length - 1; i++) {
            if (energies[i] > threshold && energies[i] > energies[i - 1] && energies[i] >= energies[i + 1]) {
                if (i - lastOnsetFrame >= minHopDistance) {
                    float timeSec = (float) (i * hopSize) / sampleRate;
                    list.add(new SyllableOnset(timeSec, energies[i] / maxE, centroids[i]));
                    lastOnsetFrame = i;
                }
            }
        }

        if (list.isEmpty()) {
            list.add(new SyllableOnset(0.1f, 0.5f, 260.0f));
        }
        return list;
    }

    private void fft(float[] real, float[] imag) {
        int n = real.length;
        // Bit reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tempR = real[i];
                real[i] = real[j];
                real[j] = tempR;
                float tempI = imag[i];
                imag[i] = imag[j];
                imag[j] = tempI;
            }
        }

        // Cooley-Tukey butterfly computations
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            float wlenR = (float) Math.cos(angle);
            float wlenI = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wR = 1.0f;
                float wI = 0.0f;
                for (int j = 0; j < len / 2; j++) {
                    float uR = real[i + j];
                    float uI = imag[i + j];
                    float vR = real[i + j + len / 2] * wR - imag[i + j + len / 2] * wI;
                    float vI = real[i + j + len / 2] * wI + imag[i + j + len / 2] * wR;
                    real[i + j] = uR + vR;
                    imag[i + j] = uI + vI;
                    real[i + j + len / 2] = uR - vR;
                    imag[i + j + len / 2] = uI - vI;
                    float nextWR = wR * wlenR - wI * wlenI;
                    wI = wR * wlenI + wI * wlenR;
                    wR = nextWR;
                }
            }
        }
    }

    private float[] hanningWindow(int size) {
        float[] win = new float[size];
        for (int i = 0; i < size; i++) {
            win[i] = 0.5f * (1.0f - (float) Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return win;
    }

    private float[] decodeToFloatArray(byte[] audioBytes) {
        // Check for standard 44-byte WAV header
        int startOffset = 0;
        if (audioBytes.length > 44 && audioBytes[0] == 'R' && audioBytes[1] == 'I' && audioBytes[2] == 'F' && audioBytes[3] == 'F') {
            startOffset = 44;
        }

        int sampleCount = (audioBytes.length - startOffset) / 2;
        float[] pcm = new float[sampleCount];
        ByteBuffer bb = ByteBuffer.wrap(audioBytes, startOffset, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < sampleCount; i++) {
            short s = bb.getShort();
            pcm[i] = s / 32768.0f;
        }
        return pcm;
    }
}
