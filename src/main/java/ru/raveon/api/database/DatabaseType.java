package ru.raveon.api.database;

public enum DatabaseType {
    MYSQL {
        @Override
        public String generateUrl(String host, int port, String table) {
            return "jdbc:mysql://%s:%d/%s?useSSL=false&autoReconnect=true".formatted(host, port, table);
        }
    },
    SQLITE {
        @Override
        public String generateUrl(String host, int port, String table) {
            return "jdbc:sqlite:%s".formatted(table);
        }
    };

    public static DatabaseType getByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }

        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public abstract String generateUrl(String host, int port, String table);
}
