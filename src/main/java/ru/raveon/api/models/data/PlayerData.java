package ru.raveon.api.models.data;

import lombok.Data;

import java.util.UUID;

@Data
public class PlayerData {
    private final String username;
    private final UUID uniqueId;

}
