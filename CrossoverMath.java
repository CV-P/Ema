package com.dualema.alerts;

/** Port of scanner.py's seed-from-first-close EMA comparison, using only completed bars. */
public final class CrossoverMath {
    private CrossoverMath() { }
    public static int direction(double[] closes, int fast, int slow) {
        if (closes == null || closes.length < 65) return 0;
        double f = closes[0], s = closes[0];
        final double af = 2.0 / (fast + 1.0), as = 2.0 / (slow + 1.0);
        double prevF = f, prevS = s;
        for (int i = 0; i < closes.length; i++) {
            if (i == closes.length - 1) { prevF = f; prevS = s; }
            f = af * closes[i] + (1.0 - af) * f;
            s = as * closes[i] + (1.0 - as) * s;
        }
        if (prevF <= prevS && f > s) return 1;
        if (prevF >= prevS && f < s) return -1;
        return 0;
    }
}
