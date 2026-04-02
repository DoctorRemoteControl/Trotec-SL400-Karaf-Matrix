package de.drremote.trotecsl400.report;

import java.nio.file.Path;
import java.util.Arrays;

public final class AudioSpectrumAnalyzer {
    private static final double EPS = 1e-12;

    private AudioSpectrumAnalyzer() {
    }

    public static SpectrumResult analyze(Path wavFile) throws Exception {
        return analyze(wavFile, 4096, 0.5);
    }

    public static SpectrumResult analyze(Path wavFile, int fftSize, double overlap) throws Exception {
        if (fftSize <= 0 || (fftSize & (fftSize - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two");
        }
        if (overlap < 0 || overlap >= 1.0) {
            throw new IllegalArgumentException("Overlap must be in [0, 1)");
        }
        int sampleRate = WavReader.readSampleRate(wavFile);
        int channels = WavReader.readChannelCount(wavFile);
        if (channels != 1) {
            throw new IllegalArgumentException("Unsupported WAV for FFT: expected mono PCM16");
        }
        short[] pcm = WavReader.readPcm16(wavFile);
        if (pcm.length == 0) {
            throw new IllegalArgumentException("Empty WAV clip");
        }

        double[] samples = new double[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            samples[i] = pcm[i] / 32768.0;
        }

        int hop = Math.max(1, (int) Math.round(fftSize * (1.0 - overlap)));
        int windowCount = 0;
        double[] power = new double[fftSize / 2 + 1];

        double[] window = hannWindow(fftSize);
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];

        if (samples.length < fftSize) {
            Arrays.fill(real, 0);
            Arrays.fill(imag, 0);
            for (int i = 0; i < samples.length; i++) {
                real[i] = samples[i] * window[i];
            }
            fft(real, imag);
            accumulatePower(real, imag, power);
            windowCount = 1;
        } else {
            for (int start = 0; start + fftSize <= samples.length; start += hop) {
                for (int i = 0; i < fftSize; i++) {
                    real[i] = samples[start + i] * window[i];
                    imag[i] = 0.0;
                }
                fft(real, imag);
                accumulatePower(real, imag, power);
                windowCount++;
            }
        }

        if (windowCount == 0) {
            throw new IllegalArgumentException("No FFT windows available");
        }

        double[] magnitudes = new double[power.length];
        for (int i = 0; i < power.length; i++) {
            double avgPower = power[i] / windowCount;
            magnitudes[i] = Math.sqrt(Math.max(avgPower, 0.0));
        }

        double[] magnitudesDb = new double[magnitudes.length];
        for (int i = 0; i < magnitudes.length; i++) {
            magnitudesDb[i] = 20.0 * Math.log10(Math.max(magnitudes[i], EPS));
        }

        double[] freqs = new double[magnitudes.length];
        for (int i = 0; i < freqs.length; i++) {
            freqs[i] = i * (sampleRate / (double) fftSize);
        }

        double dominantFreq = 0.0;
        double dominantMag = -1.0;
        double weightedSum = 0.0;
        double magSum = 0.0;
        for (int i = 1; i < magnitudes.length; i++) {
            double mag = magnitudes[i];
            if (mag > dominantMag) {
                dominantMag = mag;
                dominantFreq = freqs[i];
            }
            weightedSum += freqs[i] * mag;
            magSum += mag;
        }
        double centroid = magSum > 0 ? weightedSum / magSum : 0.0;

        return new SpectrumResult(
                sampleRate,
                fftSize,
                freqs,
                magnitudesDb,
                dominantFreq,
                centroid
        );
    }

    private static void accumulatePower(double[] real, double[] imag, double[] power) {
        for (int i = 0; i < power.length; i++) {
            double re = real[i];
            double im = imag[i];
            power[i] += (re * re + im * im);
        }
    }

    private static double[] hannWindow(int n) {
        double[] window = new double[n];
        if (n == 1) {
            window[0] = 1.0;
            return window;
        }
        for (int i = 0; i < n; i++) {
            window[i] = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / (n - 1));
        }
        return window;
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tr = real[i];
                double ti = imag[i];
                real[i] = real[j];
                imag[i] = imag[j];
                real[j] = tr;
                imag[j] = ti;
            }
            int m = n >> 1;
            while (j >= m && m >= 2) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wlenR = Math.cos(ang);
            double wlenI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wr = 1.0;
                double wi = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k;
                    int v = i + k + len / 2;
                    double vr = real[v] * wr - imag[v] * wi;
                    double vi = real[v] * wi + imag[v] * wr;

                    real[v] = real[u] - vr;
                    imag[v] = imag[u] - vi;
                    real[u] += vr;
                    imag[u] += vi;

                    double nextWr = wr * wlenR - wi * wlenI;
                    wi = wr * wlenI + wi * wlenR;
                    wr = nextWr;
                }
            }
        }
    }
}
