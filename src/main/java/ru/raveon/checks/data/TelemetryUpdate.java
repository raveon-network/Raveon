package ru.raveon.checks.data;

public record TelemetryUpdate(Kind kind, double[] values) {
    public enum Kind { MOVEMENT, INPUT, SWING, SLOT, USE, RELEASE, PLACE, VELOCITY, EXPLOSION, TELEPORT, DAMAGE }

    public TelemetryUpdate {
        int expected = switch (kind) {
            case MOVEMENT -> 6; case INPUT -> 7; case PLACE -> 9;
            case VELOCITY, EXPLOSION -> 3; case SLOT, USE, DAMAGE -> 1;
            case SWING, RELEASE, TELEPORT -> 0;
        };
        if (values.length != expected) throw new IllegalArgumentException("Telemetry input width mismatch");
        values = values.clone();
        for (double value : values)
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite telemetry input");
    }

    @Override
    public double[] values() {
        return values.clone();
    }
}
