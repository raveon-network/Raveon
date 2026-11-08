package ru.raveon.service.analyze;

import java.sql.*;
import java.util.*;
import ru.raveon.database.modules.PlayerProbabilityDatabase;
import ru.raveon.model.ModelId;

/** Exercises real SQLite DDL/data, including the default autocommit connection. */
public final class DynamicHistoryContractTest {
    public static void run() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            check(db.getAutoCommit(), "Production-style autocommit connection");
            db.createStatement().execute("PRAGMA foreign_keys=ON");
            var history = new PlayerProbabilityDatabase(null);
            call(history,"createTables",new Class<?>[]{Connection.class},db);
            call(history,"restoreModelRegistry",new Class<?>[]{Connection.class},db);
            var fast = ModelId.define("aim_fast",3,"pro");
            var deep = ModelId.define("combat_deep",4,"ultra");
            for (var id : List.of(fast,deep))
                call(history,"ensureModelTables",new Class<?>[]{Connection.class,ModelId.class},db,id);
            UUID player = UUID.randomUUID();
            try (var row=db.prepareStatement("INSERT INTO player_probabilities VALUES (?,?,?,?,?)")) {
                row.setString(1,player.toString());row.setString(2,"test");row.setString(3,"test");
                row.setLong(4,1);row.setLong(5,1);row.executeUpdate();
            }
            for(String table:List.of("player_probability","ultra_probability","m_aim_fast","m_combat_deep")) {
                try(var row=db.prepareStatement("INSERT INTO "+table+"_snapshots VALUES (?,?,?)")) {
                    row.setString(1,player.toString());row.setInt(2,0);row.setLong(3,1);row.executeUpdate();
                }
                try(var row=db.prepareStatement("INSERT INTO "+table+"_entries VALUES (?,?,?,?,?,?)")) {
                    row.setString(1,player.toString());row.setInt(2,0);row.setInt(3,0);
                    row.setDouble(4,table.equals("m_aim_fast")?.9:.1);row.setString(5,"test");row.setLong(6,1);row.executeUpdate();
                }
            }
            check(scalar(db,"SELECT chance FROM m_aim_fast_entries")==.9 && scalar(db,"SELECT chance FROM m_combat_deep_entries")==.1,
                    "Additional model histories are isolated");
            var restored = new PlayerProbabilityDatabase(null);
            call(restored,"createTables",new Class<?>[]{Connection.class},db);
            call(restored,"restoreModelRegistry",new Class<?>[]{Connection.class},db);
            call(restored,"ensureModelTables",new Class<?>[]{Connection.class,ModelId.class},db,fast);
            check(scalar(db,"SELECT COUNT(*) FROM player_probability_entries")==1
                    && scalar(db,"SELECT COUNT(*) FROM m_aim_fast_entries")==1, "Schema initialization preserves legacy and dynamic history");
            check(scalar(db,"SELECT COUNT(*) FROM raveon_model_history_models")==2, "Dynamic registry survives restart without duplicate definitions");
            call(restored,"deleteSnapshotData",new Class<?>[]{Connection.class,UUID.class},db,player);
            for(String table:List.of("player_probability","ultra_probability","m_aim_fast","m_combat_deep"))
                check(scalar(db,"SELECT COUNT(*) FROM "+table+"_entries")==0, "Explicit cleanup includes every persisted model");
        }
        System.out.println("SQLite dynamic history isolation, schema reuse, autocommit and model registry restoration passed");
    }
    private static void call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(target,args);
    }
    private static double scalar(Connection db,String sql)throws Exception{
        try(var s=db.createStatement();var r=s.executeQuery(sql)){if(!r.next())throw new AssertionError(sql);return r.getDouble(1);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
