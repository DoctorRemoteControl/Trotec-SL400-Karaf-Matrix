package de.drremote.trotecsl400.runtime;

import java.nio.file.Path;

public final class AudioHintAnalyzer {
    private AudioHintAnalyzer() {
    }

    public static String analyze(Path wavFile, int sampleRate) throws Exception {
        short[] samples = WavReader.readPcm16(wavFile);
        if (samples.length == 0) {
            return "low signal / uncertain";
        }

        double rms = 0.0;
        double peak = 0.0;
        int zeroCross = 0;
        short prev = samples[0];
        for (short s : samples) {
            double v = s / 32768.0;
            rms += v * v;
            peak = Math.max(peak, Math.abs(v));
            if ((prev >= 0 && s < 0) || (prev < 0 && s >= 0)) {
                zeroCross++;
            }
            prev = s;
        }
        rms = Math.sqrt(rms / samples.length);
        double rmsDb = 20.0 * Math.log10(Math.max(rms, 1e-9));
        double zcr = (double) zeroCross / samples.length;
        double crest = peak / Math.max(rms, 1e-9);

        double low = bandEnergy(samples, sampleRate, new double[]{80, 120, 200});
        double mid = bandEnergy(samples, sampleRate, new double[]{500, 1000, 2000});
        double high = bandEnergy(samples, sampleRate, new double[]{4000, 6000});
        double total = low + mid + high + 1e-9;
        double lowRatio = low / total;
        double midRatio = mid / total;
        double highRatio = high / total;

        if (rmsDb < -45.0) {
            return "low signal / uncertain";
        }
        if (lowRatio > 0.55 && zcr < 0.05) {
            return crest < 2.0 ? "wind / rumble" : "bass-heavy music";
        }
        if (midRatio > 0.45 && zcr >= 0.05 && zcr <= 0.15) {
            return "voices / crowd";
        }
        if (highRatio > 0.45 && zcr > 0.12) {
            return "broad noise";
        }
        if (lowRatio > 0.4 && zcr < 0.08 && crest < 2.0) {
            return "mechanical / hum";
        }
        if (crest > 4.0 && zcr > 0.1) {
            return "mixed / uncertain";
        }
        return "mixed / uncertain";
    }

    private static double bandEnergy(short[] samples, int sampleRate, double[] freqs) {
        double sum = 0.0;
        for (double f : freqs) {
            sum += goertzelPower(samples, sampleRate, f);
        }
        return sum;
    }

    private static double goertzelPower(short[] samples, int sampleRate, double targetFreq) {
        int n = samples.length;
        double k = 0.5 + ((n * targetFreq) / sampleRate);
        double w = (2.0 * Math.PI * k) / n;
        double cosine = Math.cos(w);
        double sine = Math.sin(w);
        double coeff = 2.0 * cosine;

        double q0 = 0;
        double q1 = 0;
        double q2 = 0;
        for (short s : samples) {
            q0 = coeff * q1 - q2 + s;
            q2 = q1;
            q1 = q0;
        }
        double real = q1 - q2 * cosine;
        double imag = q2 * sine;
        return real * real + imag * imag;
    }
}
