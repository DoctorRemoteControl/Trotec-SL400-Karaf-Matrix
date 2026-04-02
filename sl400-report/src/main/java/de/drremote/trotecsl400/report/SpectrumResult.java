package de.drremote.trotecsl400.report;

public record SpectrumResult(
        int sampleRate,
        int fftSize,
        double[] frequenciesHz,
        double[] magnitudesDb,
        double dominantFrequencyHz,
        double spectralCentroidHz
) {
}
