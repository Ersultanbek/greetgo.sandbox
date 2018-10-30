package kz.greetgo.migration.core;

import kz.greetgo.migration.interfaces.ConnectionConfig;
import kz.greetgo.migration.util.ConnectionUtils;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

import static kz.greetgo.migration.util.TimeUtils.showTime;

public class Migration implements Closeable {
    public int portionSize = 1_000_000;
    public int downloadMaxBatchSize = 50_000;
    public int uploadMaxBatchSize = 50_000;
    public int showStatusPingMillis = 5000;


    protected final ConnectionConfig operConfig;
    protected final ConnectionConfig ciaConfig;
    protected Connection operConnection = null, ciaConnection = null;
    protected String tmpClientTable;

    public Migration(ConnectionConfig operConfig, ConnectionConfig ciaConfig) {
        this.operConfig = operConfig;
        this.ciaConfig = ciaConfig;
    }

    @Override
    public void close() {

        closeOperConnection();
        closeCiaConnection();
    }

    protected void closeCiaConnection() {
        if (ciaConnection != null) {
            try {
                ciaConnection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            ciaConnection = null;
        }
    }

    protected void closeOperConnection() {
        if (this.operConnection != null) {
            try {
                this.operConnection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            this.operConnection = null;
        }
    }

    protected void createOperConnection() throws Exception {
        operConnection = ConnectionUtils.create(operConfig);
    }

    protected void createCiaConnection() throws Exception {
        ciaConnection = ConnectionUtils.create(ciaConfig);
    }

    protected void info(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        System.out.println(sdf.format(new Date()) + " [" + getClass().getSimpleName() + "] " + message);
    }

    protected String r(String sql) {
        sql = sql.replaceAll("TMP_CLIENT",tmpClientTable);
        return sql;
    }

    protected PreparedStatement getOperConPrStmnt(String sql) throws Exception{
        return operConnection.prepareStatement(sql);
    }

    protected void exec(String sql) throws SQLException {
        String executingSql = r(sql);
        long startedAt = System.nanoTime();
        try (Statement statement = operConnection.createStatement()) {
            int updates = statement.executeUpdate(executingSql);
            info("Updated " + updates
                    + " records for " + showTime(System.nanoTime(), startedAt)
                    + ", EXECUTED SQL : " + executingSql);

        } catch (SQLException e) {
            info("ERROR EXECUTE SQL for " + showTime(System.nanoTime(), startedAt)
                    + ", message: " + e.getMessage() + ", SQL : " + executingSql);
            throw e;
        }
    }

    public void dropAllTables() throws Exception{
//        language=PostgreSQL
        exec("Drop table if exists TMP_CLIENT");
        //language=PostgreSQL
        exec("Drop table if exists  TMP_CLIENT_address");
        //language=PostgreSQL
        exec("Drop table if exists TMP_CLIENT_phones");
        //language=PostgreSQL
        exec("Drop table if exists TMP_CLIENT_accounts");
        //language=PostgreSQL
        exec("Drop table if exists  TMP_CLIENT_account_transactions");
    }

}
