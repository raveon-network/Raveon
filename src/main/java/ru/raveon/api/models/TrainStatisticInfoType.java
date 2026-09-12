package ru.raveon.api.models;

public enum TrainStatisticInfoType {
    BOSSBAR, ACTIONBAR;

    public static TrainStatisticInfoType getByName(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ACTIONBAR;
        }
    }
}
