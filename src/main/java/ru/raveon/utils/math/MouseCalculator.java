package ru.raveon.utils.math;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class MouseCalculator {
    private final double MINIMUM_DIVISOR = Math.pow(0.2D, 3.0D) * 8.0D * 0.15D - 1.0E-3D;
    private final double ROTATION_EPSILON = 1.0E-6D;

    public float calculateAcceleration(float currentDelta, float previousDelta) {
        return currentDelta - previousDelta;
    }

    public float calculateJerk(float currentAccel, float previousAccel) {
        return currentAccel - previousAccel;
    }

    public float calculateGCDError(float currentDelta, float previousDelta) {
        double current = Math.abs(currentDelta);
        double previous = Math.abs(previousDelta);

        if (current < MINIMUM_DIVISOR || previous < MINIMUM_DIVISOR) {
            return 0.0F;
        }

        double gcd = gcd(current, previous);
        if (gcd < MINIMUM_DIVISOR) {
            return 0.0F;
        }

        double snapped = Math.round(current / gcd) * gcd;
        double error = Math.abs(current - snapped);

        return (float) (error / gcd);
    }

    public float calculateGrid(float currentDelta, float previousDelta) {
        double current = Math.abs(currentDelta);
        double previous = Math.abs(previousDelta);

        if (current < MINIMUM_DIVISOR || previous < MINIMUM_DIVISOR) {
            return 0.0F;
        }

        double gcd = gcd(current, previous);
        return gcd < MINIMUM_DIVISOR ? 0.0F : (float) gcd;
    }

    public float normalizeAngle(float angle) {
        float normalized = angle % 360.0F;
        if (normalized <= -180.0F) {
            normalized += 360.0F;
        } else if (normalized > 180.0F) {
            normalized -= 360.0F;
        }
        return normalized;
    }

    public double gcd(double aInput, double bInput) {
        double a = Math.abs(aInput);
        double b = Math.abs(bInput);

        if (a < ROTATION_EPSILON || b < ROTATION_EPSILON) {
            return 0.0D;
        }

        if (a < b) {
            double temp = a;
            a = b;
            b = temp;
        }

        while (b > MINIMUM_DIVISOR) {
            double temp = a - Math.floor(a / b) * b;
            a = b;
            b = temp;
        }

        return a;
    }

    public float distanceToGrid(float delta, float grid) {
        double absDelta = Math.abs(delta);
        double absGrid = Math.abs(grid);

        if (absDelta < MINIMUM_DIVISOR || absGrid < MINIMUM_DIVISOR) {
            return 0.0F;
        }

        double snapped = Math.round(absDelta / absGrid) * absGrid;
        return (float) Math.abs(absDelta - snapped);
    }

    public float getMinimumDivisor() {
        return (float) MINIMUM_DIVISOR;
    }
}