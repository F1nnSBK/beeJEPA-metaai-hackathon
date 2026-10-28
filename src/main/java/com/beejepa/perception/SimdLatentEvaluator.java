package com.beejepa.perception;

import jakarta.inject.Singleton;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Objects;

/**
 * Hardware-accelerated SIMD evaluator for V-JEPA latent vectors using Java 25 Vector API.
 * Computes Euclidean distances, cosine similarities, and predictor prediction errors
 * S(t) = ||z_pred - z_target||_2 using AVX/NEON SIMD vector lanes without GC allocations.
 */
@Singleton
public final class SimdLatentEvaluator {

    public static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * Computes the Euclidean L2 distance between two latent vectors using SIMD lanes:
     * ||a - b||_2 = sqrt(sum((a_i - b_i)^2))
     *
     * @param a First latent vector
     * @param b Second latent vector
     * @return Euclidean distance in latent space
     */
    public float euclideanDistance(float[] a, float[] b) {
        validateInputs(a, b);
        int length = a.length;
        int i = 0;
        int upperBound = SPECIES.loopBound(length);
        FloatVector sumVector = FloatVector.zero(SPECIES);

        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);
            FloatVector diff = va.sub(vb);
            sumVector = diff.fma(diff, sumVector);
        }

        float sum = sumVector.reduceLanes(VectorOperators.ADD);

        // Process scalar loop tail for remainder lanes
        for (; i < length; i++) {
            float diff = a[i] - b[i];
            sum += diff * diff;
        }

        return (float) Math.sqrt(sum);
    }

    /**
     * Computes the self-supervised V-JEPA predictor surprise score S(t):
     * S(t) = ||z_pred - z_target||_2
     *
     * @param predictedLatent The predictor's expected latent feature representation
     * @param targetLatent The actual encoded target latent representation
     * @return Prediction error / surprise metric S(t)
     */
    public float computeLatentSurprise(float[] predictedLatent, float[] targetLatent) {
        return euclideanDistance(predictedLatent, targetLatent);
    }

    /**
     * Computes the L2 norm of a latent vector:
     * ||a||_2 = sqrt(sum(a_i^2))
     *
     * @param a Latent vector
     * @return L2 norm
     */
    public float l2Norm(float[] a) {
        Objects.requireNonNull(a, "Vector must not be null");
        int length = a.length;
        int i = 0;
        int upperBound = SPECIES.loopBound(length);
        FloatVector sumVector = FloatVector.zero(SPECIES);

        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            sumVector = va.fma(va, sumVector);
        }

        float sum = sumVector.reduceLanes(VectorOperators.ADD);

        for (; i < length; i++) {
            sum += a[i] * a[i];
        }

        return (float) Math.sqrt(sum);
    }

    /**
     * Computes the cosine similarity between two latent vectors using SIMD lanes:
     * cos(a, b) = (a . b) / (||a||_2 * ||b||_2)
     *
     * @param a First latent vector
     * @param b Second latent vector
     * @return Cosine similarity in range [-1.0, 1.0]
     */
    public float cosineSimilarity(float[] a, float[] b) {
        validateInputs(a, b);
        int length = a.length;
        int i = 0;
        int upperBound = SPECIES.loopBound(length);

        FloatVector dotVector = FloatVector.zero(SPECIES);
        FloatVector normAVector = FloatVector.zero(SPECIES);
        FloatVector normBVector = FloatVector.zero(SPECIES);

        for (; i < upperBound; i += SPECIES.length()) {
            FloatVector va = FloatVector.fromArray(SPECIES, a, i);
            FloatVector vb = FloatVector.fromArray(SPECIES, b, i);

            dotVector = va.fma(vb, dotVector);
            normAVector = va.fma(va, normAVector);
            normBVector = vb.fma(vb, normBVector);
        }

        float dotProduct = dotVector.reduceLanes(VectorOperators.ADD);
        float normASquared = normAVector.reduceLanes(VectorOperators.ADD);
        float normBSquared = normBVector.reduceLanes(VectorOperators.ADD);

        for (; i < length; i++) {
            float valA = a[i];
            float valB = b[i];
            dotProduct += valA * valB;
            normASquared += valA * valA;
            normBSquared += valB * valB;
        }

        float denominator = (float) (Math.sqrt(normASquared) * Math.sqrt(normBSquared));
        if (denominator <= 1e-12f) {
            return 0.0f;
        }

        float sim = dotProduct / denominator;
        return Math.clamp(sim, -1.0f, 1.0f);
    }

    /**
     * Returns the bit width of the active SIMD vector register on this architecture
     * (e.g. 128 for ARM NEON, 256 for AVX2, 512 for AVX-512).
     *
     * @return Vector bit length
     */
    public int vectorBitWidth() {
        return SPECIES.vectorBitSize();
    }

    /**
     * Returns the number of 32-bit float lanes in each SIMD vector register.
     *
     * @return Number of concurrent SIMD lanes
     */
    public int vectorLaneCount() {
        return SPECIES.length();
    }

    private static void validateInputs(float[] a, float[] b) {
        Objects.requireNonNull(a, "Vector a must not be null");
        Objects.requireNonNull(b, "Vector b must not be null");
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: a.length=" + a.length + ", b.length=" + b.length
            );
        }
        if (a.length == 0) {
            throw new IllegalArgumentException("Vector must have at least one element");
        }
    }
}

