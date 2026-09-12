package ru.raveon.database.model;

import java.util.UUID;

public record ViolationRecord(
        UUID playerUuid,
        String checkName,
        int vls,
        String server,
        long timestamp,
        String verbose
) {
}
