package com.kaguya.custommobs.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;

/**
 * ペット所有権(cm_pets)の永続化と、FJEconomyの fje_balances に対する残高消費を担う。
 * FJEconomyとはプラグイン間の直接呼び出しを行わず、同じMariaDB(fjeconomy DB)に
 * 別々の接続プールで接続する形で連携する(MinecraftKaguya/CLAUDE.md の設計方針に合わせる)。
 */
public class PetDatabase {

    private final JavaPlugin plugin;
    private HikariDataSource dataSource;

    public PetDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return 接続・テーブル作成に成功したか */
    public boolean initialize() {
        FileConfiguration config = plugin.getConfig();
        try {
            Class.forName("com.kaguya.custommobs.libs.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "MariaDB JDBC Driverが見つかりません。shadeの設定を確認してください", e);
            return false;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("com.kaguya.custommobs.libs.mariadb.jdbc.Driver");
        hikariConfig.setJdbcUrl(config.getString("database.url", "jdbc:mariadb://localhost:3306/fjeconomy"));
        hikariConfig.setUsername(config.getString("database.username", "root"));
        hikariConfig.setPassword(config.getString("database.password", ""));
        hikariConfig.setMaximumPoolSize(config.getInt("database.pool-size", 5));
        hikariConfig.setMaxLifetime(config.getLong("database.max-lifetime", 1800000L));
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setAutoCommit(true);

        try {
            dataSource = new HikariDataSource(hikariConfig);
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
            }
            createTables();
            plugin.getLogger().info("✓ ペット用データベースに接続しました");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "ペット用データベース接続エラー", e);
            if (dataSource != null) {
                dataSource.close();
                dataSource = null;
            }
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cm_pets (" +
                    "  mob_uuid UUID PRIMARY KEY," +
                    "  owner_uuid UUID NOT NULL," +
                    "  mob_type VARCHAR(64) NOT NULL," +
                    "  server_id VARCHAR(20) NOT NULL," +
                    "  display_name VARCHAR(255)," +
                    "  tamed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  INDEX idx_owner (owner_uuid)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("PetDatabaseは初期化されていません");
        }
        return dataSource.getConnection();
    }

    public boolean isReady() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * fje_balances から金額を引き落とす。整数(long)で扱い、トランザクションで囲む。
     * 残高不足・プレイヤー未登録(fje_balancesに行がない)の場合は影響行数0でfalseを返す。
     */
    public boolean tryCharge(UUID uuid, long amount) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE fje_balances SET balance = balance - ? WHERE uuid = ? AND balance >= ?")) {
                ps.setLong(1, amount);
                ps.setObject(2, uuid);
                ps.setLong(3, amount);
                int rows = ps.executeUpdate();
                conn.commit();
                return rows == 1;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void insertPet(UUID mobUuid, UUID ownerUuid, String mobType, String serverId, String displayName) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cm_pets (mob_uuid, owner_uuid, mob_type, server_id, display_name) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, mobUuid);
            ps.setObject(2, ownerUuid);
            ps.setString(3, mobType);
            ps.setString(4, serverId);
            ps.setString(5, displayName);
            ps.executeUpdate();
        }
    }

    /** 所有ペット一覧(mob_type, server_id)。表示用途のみなので単純なレコードで返す */
    public java.util.List<PetRecord> listByOwner(UUID ownerUuid) throws SQLException {
        java.util.List<PetRecord> result = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT mob_type, server_id, display_name FROM cm_pets WHERE owner_uuid = ?")) {
            ps.setObject(1, ownerUuid);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PetRecord(rs.getString("mob_type"), rs.getString("server_id"), rs.getString("display_name")));
                }
            }
        }
        return result;
    }

    public record PetRecord(String mobType, String serverId, String displayName) {
    }

    public void deletePet(UUID mobUuid) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cm_pets WHERE mob_uuid = ?")) {
            ps.setObject(1, mobUuid);
            ps.executeUpdate();
        }
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("ペット用データベース接続を閉じました");
        }
    }
}
