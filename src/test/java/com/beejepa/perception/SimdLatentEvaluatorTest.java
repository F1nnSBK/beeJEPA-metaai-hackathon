package com.beejepa.perception;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SimdLatentEvaluatorTest {

    private SimdLatentEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new SimdLatentEvaluator();
    }

    @Test
    void testVectorCapabilities() {
        Assertions.assertTrue(evaluator.vectorBitWidth() >= 128, "Hardware vector width should be at least 128 bits");
        Assertions.assertTrue(evaluator.vectorLaneCount() >= 4, "Float SIMD lane count should be at least 4");
    }

    @Test
    void testEuclideanDistanceIdenticalVectors() {
        float[] a = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        float[] b = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};

        float distance = evaluator.euclideanDistance(a, b);
        Assertions.assertEquals(0.0f, distance, 1e-6f);
    }

    @Test
    void testEuclideanDistanceKnownValues() {
        float[] a = new float[]{0.0f, 0.0f, 0.0f, 0.0f};
        float[] b = new float[]{3.0f, 4.0f, 0.0f, 0.0f};

        float distance = evaluator.euclideanDistance(a, b);
        Assertions.assertEquals(5.0f, distance, 1e-5f);
    }

    @Test
    void testEuclideanDistanceNonMultipleLengthTail() {
        // Length 23 is prime, guaranteeing scalar tail execution for any SIMD width (4, 8, 16 lanes)
        int length = 23;
        float[] a = new float[length];
        float[] b = new float[length];

        float scalarSum = 0.0f;
        for (int i = 0; i < length; i++) {
            a[i] = (i + 1) * 0.5f;
            b[i] = (i + 1) * 0.2f;
            float diff = a[i] - b[i];
            scalarSum += diff * diff;
        }
        float expectedDistance = (float) Math.sqrt(scalarSum);

        float actualDistance = evaluator.euclideanDistance(a, b);
        Assertions.assertEquals(expectedDistance, actualDistance, 1e-4f);
    }

    @Test
    void testCosineSimilarityIdenticalAndOpposite() {
        float[] a = new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f, 7.0f, 8.0f};
        float[] b = new float[]{2.0f, 4.0f, 6.0f, 8.0f, 10.0f, 12.0f, 14.0f, 16.0f}; // 2 * a

        float simIdentical = evaluator.cosineSimilarity(a, b);
        Assertions.assertEquals(1.0f, simIdentical, 1e-5f);

        float[] c = new float[]{-1.0f, -2.0f, -3.0f, -4.0f, -5.0f, -6.0f, -7.0f, -8.0f};
        float simOpposite = evaluator.cosineSimilarity(a, c);
        Assertions.assertEquals(-1.0f, simOpposite, 1e-5f);
    }

    @Test
    void testCosineSimilarityOrthogonal() {
        float[] a = new float[]{1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f};
        float[] b = new float[]{0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};

        float sim = evaluator.cosineSimilarity(a, b);
        Assertions.assertEquals(0.0f, sim, 1e-5f);
    }

    @Test
    void testL2Norm() {
        float[] a = new float[]{3.0f, 4.0f, 0.0f, 0.0f, 0.0f};
        float norm = evaluator.l2Norm(a);
        Assertions.assertEquals(5.0f, norm, 1e-5f);
    }

    @Test
    void testDimensionMismatch() {
        float[] a = new float[]{1.0f, 2.0f, 3.0f};
        float[] b = new float[]{1.0f, 2.0f};

        Assertions.assertThrows(IllegalArgumentException.class, () -> evaluator.euclideanDistance(a, b));
        Assertions.assertThrows(IllegalArgumentException.class, () -> evaluator.cosineSimilarity(a, b));
    }
}

