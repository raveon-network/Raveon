package ru.raveon.api.models;

public interface AbstractCheck {

    String getCheckName();

    default String getAlternativeName() {
        return getCheckName();
    }

    double getViolations();

    /**
     * Returns the time of the last violation in UTC milliseconds or 0 if no violations have occurred.
     * Internally uses {@link System#currentTimeMillis()} when a violation occurs.
     * @return the time of the last violation in UTC milliseconds
     */
    long getLastViolationTime();

    boolean isExperimental();
}