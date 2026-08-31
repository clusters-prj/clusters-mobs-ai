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

            // Webダッシュボード(fjew)のペットショップページが読む公式カタログ。
            // mobs.ymlのpet:セクションが唯一の真実のソースで、起動のたびにupsertするだけ
            // (Web側からは書き込まない)。
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cm_pet_catalog (" +
                    "  mob_type VARCHAR(64) PRIMARY KEY," +
                    "  display_name VARCHAR(255) NOT NULL," +
                    "  tame_cost BIGINT NOT NULL," +
                    "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // Webで購入されたが、まだゲーム内で /cmob claim を実行していない分の受け取り待ちレコード。
            // fjew側の /api/pets/purchase がINSERTし、こちらはSELECT/UPDATEのみ行う。
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cm_pet_claims (" +
                    "  id INT AUTO_INCREMENT PRIMARY KEY," +
                    "  owner_uuid VARCHAR(36) NOT NULL," +
                    "  mob_type VARCHAR(64) NOT NULL," +
                    "  purchased_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  claimed_at TIMESTAMP NULL DEFAULT NULL," +
                    "  INDEX idx_owner_unclaimed (owner_uuid, claimed_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");

            // 建築ジョブの進行状況。チャンクアンロード/サーバー再起動で本体Mobが
            // 一度「拾い直し」の対象になっても続きから再開できるよう、ブロックを
            // 1つ置くたびに進捗を書き込む(完了したら行ごと消す)。
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cm_pet_build_jobs (" +
                    "  mob_uuid UUID PRIMARY KEY," +
                    "  owner_uuid UUID NOT NULL," +
                    "  listing_id INT NOT NULL," +
                    "  world VARCHAR(64) NOT NULL," +
                    "  origin_x DOUBLE NOT NULL," +
                    "  origin_y DOUBLE NOT NULL," +
                    "  origin_z DOUBLE NOT NULL," +
                    "  next_index INT NOT NULL," +
                    "  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        }
    }

    /** mobs.ymlのpet設定をカタログに反映する。Webショップの表示・価格は常にこの呼び出しが最新化する */
    public void upsertCatalogEntry(String mobType, String displayName, long tameCost) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cm_pet_catalog (mob_type, display_name, tame_cost) VALUES (?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), tame_cost = VALUES(tame_cost)")) {
            ps.setString(1, mobType);
            ps.setString(2, displayName);
            ps.setLong(3, tameCost);
            ps.executeUpdate();
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

    /** Webで購入され、まだ受け取っていない分。/cmob claim から使う */
    public java.util.List<PetClaim> listUnclaimed(UUID ownerUuid) throws SQLException {
        java.util.List<PetClaim> result = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, mob_type FROM cm_pet_claims WHERE owner_uuid = ? AND claimed_at IS NULL")) {
            ps.setString(1, ownerUuid.toString());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PetClaim(rs.getInt("id"), rs.getString("mob_type")));
                }
            }
        }
        return result;
    }

    public record PetClaim(int id, String mobType) {
    }

    public void markClaimed(int claimId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE cm_pet_claims SET claimed_at = CURRENT_TIMESTAMP WHERE id = ?")) {
            ps.setInt(1, claimId);
            ps.executeUpdate();
        }
    }

    /**
     * マーケットプレイスで購入済みの設計図(item_type='blueprint')の中身を取得する。
     * fjewのアップロード処理がJSONの中身をそのまま marketplace_listings.blueprint_json に
     * 複製しているので、ここではファイルI/Oなしで直接読める(DB経由のみで完結させる)。
     *
     * @return JSON文字列とタイトル。所有していない/該当なしの場合は null
     */
    /**
     * 所有判定はキャラクター(UUID)単位ではなくWebアカウント単位で行う。
     * <p>
     * 1つのWebアカウントにJava/Bedrock複数キャラを{@code account_links}で紐付けられる
     * (fjewの{@code getOwnedUuids}と同じ考え方)ので、「今操作しているキャラのUUIDと
     * NFTのowner_uuidが完全一致するか」だけで見ると、別キャラで買った設計図をこちらの
     * キャラでは使えないことになってしまう。同じWebアカウントに紐付いた別UUID名義の
     * NFTも所有扱いにする。account_linksに未登録(Web連携していない)キャラは
     * 従来どおり直接のUUID一致のみで判定する。
     */
    public BlueprintRow findOwnedBlueprintJson(UUID ownerUuid, int listingId) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT l.blueprint_json, l.title FROM marketplace_nfts n " +
                     "JOIN marketplace_listings l ON n.listing_id = l.id " +
                     "WHERE n.listing_id = ? AND l.item_type = 'blueprint' " +
                     "AND (" +
                     "  n.owner_uuid = ? " +
                     "  OR n.owner_uuid IN (" +
                     "    SELECT al2.minecraft_uuid FROM account_links al1 " +
                     "    JOIN account_links al2 ON al2.web_user_id = al1.web_user_id " +
                     "    WHERE al1.minecraft_uuid = ?" +
                     "  )" +
                     ") LIMIT 1")) {
            ps.setInt(1, listingId);
            ps.setString(2, ownerUuid.toString());
            ps.setString(3, ownerUuid.toString());
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BlueprintRow(rs.getString("blueprint_json"), rs.getString("title"));
            }
        }
    }

    public record BlueprintRow(String json, String title) {
    }

    /** 建築ジョブの進行状況を保存する(INSERTなら開始、既存なら進捗の更新)。ブロックを置くたびに呼ぶ想定 */
    public void saveBuildProgress(UUID mobUuid, UUID ownerUuid, int listingId, org.bukkit.Location origin, int nextIndex)
            throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO cm_pet_build_jobs (mob_uuid, owner_uuid, listing_id, world, origin_x, origin_y, origin_z, next_index) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE listing_id = VALUES(listing_id), world = VALUES(world), " +
                     "origin_x = VALUES(origin_x), origin_y = VALUES(origin_y), origin_z = VALUES(origin_z), " +
                     "next_index = VALUES(next_index)")) {
            ps.setObject(1, mobUuid);
            ps.setObject(2, ownerUuid);
            ps.setInt(3, listingId);
            ps.setString(4, origin.getWorld().getName());
            ps.setDouble(5, origin.getX());
            ps.setDouble(6, origin.getY());
            ps.setDouble(7, origin.getZ());
            ps.setInt(8, nextIndex);
            ps.executeUpdate();
        }
    }

    /** 中断していた建築ジョブがあれば取得する。無ければ null */
    public BuildProgress loadBuildProgress(UUID mobUuid) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT owner_uuid, listing_id, world, origin_x, origin_y, origin_z, next_index " +
                     "FROM cm_pet_build_jobs WHERE mob_uuid = ?")) {
            ps.setObject(1, mobUuid);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new BuildProgress(
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getInt("listing_id"),
                        rs.getString("world"),
                        rs.getDouble("origin_x"),
                        rs.getDouble("origin_y"),
                        rs.getDouble("origin_z"),
                        rs.getInt("next_index"));
            }
        }
    }

    public record BuildProgress(UUID ownerUuid, int listingId, String world,
                                 double originX, double originY, double originZ, int nextIndex) {
    }

    public void deleteBuildProgress(UUID mobUuid) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cm_pet_build_jobs WHERE mob_uuid = ?")) {
            ps.setObject(1, mobUuid);
            ps.executeUpdate();
        }
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
