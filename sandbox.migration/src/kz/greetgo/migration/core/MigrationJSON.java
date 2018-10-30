package kz.greetgo.migration.core;

import kz.greetgo.migration.core.models.Account;
import kz.greetgo.migration.core.models.Transaction;
import kz.greetgo.migration.interfaces.ConnectionConfig;
import kz.greetgo.migration.util.TimeUtils;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static kz.greetgo.migration.util.TimeUtils.recordsPerSecond;
import static kz.greetgo.migration.util.TimeUtils.showTime;

public class MigrationJSON extends Migration {
  private final String TYPE_ACCOUNT = "new_account";
  private final String TYPE_TRANSACTION = "transaction";

  public MigrationJSON(ConnectionConfig operConfig, ConnectionConfig ciaConfig) {
    super(operConfig, ciaConfig);
  }

  public int migrate() throws Exception {
    long startedAt = System.nanoTime();

    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    Date nowDate = new Date();
    tmpClientTable = "cia_migration_client_" + sdf.format(nowDate);
    createOperConnection();
    dropAllTables();
    //language=PostgreSQL
    exec(" create table TMP_CLIENT_ACCOUNTS (\n" +
      " error varchar(300),\n" +
      "  status int not null default 0,\n" +
      "  account_id int8,\n" +
      "  \n" +
      " cia_client_id  varchar(100),\n" +
      " client_id int8,\n" +
      " account_number varchar(255),\n" +
      " registered_at date," +
      " number bigint not null primary key" +
      ")");

    //language=PostgreSQL
    exec(" create table TMP_CLIENT_ACCOUNT_TRANSACTIONS (\n" +
      " error varchar(300),\n" +
      " status int not null default 0,\n" +
      "  \n" +
      " account_id int8,\n" +
      " account_number varchar(100),\n" +
      " transaction_type varchar(255),\n" +
      " finished_at date,\n" +
      " money double precision, \n" +
      " number bigint not null primary key \n)");
    ////
    createCiaConnection();

    int portionSize = download();

    {
      long now = System.nanoTime();
      info("Downloaded of portion " + portionSize + " finished for " + TimeUtils.showTime(now, startedAt));
    }

    if (portionSize == 0) return 0;

    closeCiaConnection();

    migrateFromTmp();

    {
      long now = System.nanoTime();
      info("MigrationJSON of portion " + portionSize + " finished for " + TimeUtils.showTime(now, startedAt));
    }

    return portionSize;
  }


  public Account getAccountById(Long id) throws SQLException {
    Account account = null;

    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(
      r("SELECT cia_client_id,account_number,registered_at,\"number\" \n " +
        "from TMP_CLIENT_ACCOUNTS " +
        "WHERE number = '" + id + "'"))) {

      try (ResultSet rs = selectStmnt.executeQuery()) {
        while (rs.next()) {
          account = new Account();
          account.client_id = rs.getString(1);
          account.account_number = rs.getString(2);
          account.registered_at = rs.getDate(3);
          account.number = rs.getLong(4);
        }
      }
    }
    return account;
  }


  public Transaction getTransactionById(Long id) throws SQLException {
    Transaction transaction = null;

    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(
      r("SELECT finished_at,account_number,money,transaction_type \n " +
        "from TMP_CLIENT_ACCOUNT_TRANSACTIONS " +
        "WHERE number = '" + id + "'"))) {

      try (ResultSet rs = selectStmnt.executeQuery()) {
        while (rs.next()) {
          transaction = new Transaction();
          transaction.finished_at = rs.getDate(1);
          transaction.account_number = rs.getString(2);
          transaction.money = rs.getDouble(3);
          transaction.transaction_type = rs.getString(4);
        }
      }
    }
    return transaction;
  }


  public int download() throws SQLException, IOException, SAXException {

    final AtomicBoolean working = new AtomicBoolean(true);
    final AtomicBoolean showStatus = new AtomicBoolean(false);

    final Thread see = new Thread(() -> {

      while (working.get()) {

        try {
          Thread.sleep(showStatusPingMillis);
        } catch (InterruptedException e) {
          break;
        }
        showStatus.set(true);
      }

    });

    see.start();

    try (PreparedStatement ciaAcTrPS = ciaConnection.prepareStatement(
      "select * from transition_account_transaction where status='JUST_INSERTED' order by number limit ?")) {
      info("Prepared statement for : select * from transition_account_transaction");

      ciaAcTrPS.setInt(1, portionSize);

      Insert insertAccount = new Insert("TMP_CLIENT_ACCOUNTS");
      insertAccount.field(1, "cia_client_id", "?");
      insertAccount.field(2, "account_number", "?");
      insertAccount.field(3, "registered_at", "?");
      insertAccount.field(4, "number", "?");

      Insert insertTransaction = new Insert("TMP_CLIENT_ACCOUNT_TRANSACTIONS");
      insertTransaction.field(1, "account_number", "?");
      insertTransaction.field(2, "transaction_type", "?");
      insertTransaction.field(3, "finished_at", "?");
      insertTransaction.field(4, "money", "?");
      insertTransaction.field(5, "number", "?");

      operConnection.setAutoCommit(false);
      //TODO не использовать toString для выполнения логики

      //TODO раздели логику и вынеси каждый prepareStatement в свои функции

      try (PreparedStatement operAcPS = operConnection.prepareStatement(r(insertAccount.toString()));
           PreparedStatement operTrPS = operConnection.prepareStatement(r(insertTransaction.toString()))
      ) {

        try (ResultSet ciaRS = ciaAcTrPS.executeQuery()) {
          MyJsonParser parser = new MyJsonParser();
          info("Got result set for : select * from transition_account_transaction");

          int batchSize = 0, recordsCount = 0;

          long startedAt = System.nanoTime();

          while (ciaRS.next()) {

            Long number = ciaRS.getLong("number");
            String jsonString = ciaRS.getString("record_data");
            String type = parser.getFieldValue(jsonString, "type");

            switch (type) {
              case TYPE_ACCOUNT: {
                Account account = parser.mapFromJSON(jsonString, Account.class);
                account = account == null ? new Account() : account;
                operAcPS.setString(1, account.client_id);
                operAcPS.setString(2, account.account_number);
                operAcPS.setDate(3, account.registered_at);
                operAcPS.setLong(4, number);
                operAcPS.addBatch();
                break;
              }
              case TYPE_TRANSACTION: {
                Transaction transaction = parser.mapFromJSON(jsonString, Transaction.class);
                transaction = transaction == null ? new Transaction() : transaction;
                operTrPS.setString(1, transaction.account_number);
                operTrPS.setString(2, transaction.transaction_type);
                operTrPS.setDate(3, transaction.finished_at);
                operTrPS.setObject(4, transaction.money);
                operTrPS.setLong(5, number);
                operTrPS.addBatch();
                break;
              }
            }

            batchSize++;
            recordsCount++;

            if (batchSize >= downloadMaxBatchSize) {
              operAcPS.executeBatch();
              operTrPS.executeBatch();
              operConnection.commit();
              batchSize = 0;
            }

            if (showStatus.get()) {
              showStatus.set(false);

              long now = System.nanoTime();
              info(" -- downloaded records " + recordsCount + " for " + showTime(now, startedAt)
                + " : " + recordsPerSecond(recordsCount, now - startedAt));
            }

          }

          if (batchSize > 0) {
            operAcPS.executeBatch();
            operTrPS.executeBatch();
            operConnection.commit();
          }

          {
            long now = System.nanoTime();
            info("TOTAL Downloaded records " + recordsCount + " for " + showTime(now, startedAt)
              + " : " + recordsPerSecond(recordsCount, now - startedAt));
          }

          return recordsCount;
        }
      } finally {
        operConnection.setAutoCommit(true);
        working.set(false);
        see.interrupt();
      }
    }
  }

  private void uploadAndDropErrors() throws Exception {
    info("uploadAndDropErrors goes : maxBatchSize = " + uploadMaxBatchSize);

    final AtomicBoolean working = new AtomicBoolean(true);

    createCiaConnection();
    ciaConnection.setAutoCommit(false);
    try {

      try (PreparedStatement inAcPS = operConnection.prepareStatement(r(
        "select number, error from TMP_CLIENT_ACCOUNTS where error is not null"));
           PreparedStatement inTrPS = operConnection.prepareStatement(r(
             "select number, error from TMP_CLIENT_ACCOUNT_TRANSACTIONS where error is not null"))
      ) {

        info("Prepared statement for : select number, error from TMP_CLIENT_ACCOUNTS where error is not null");
        info("Prepared statement for : select number, error from TMP_CLIENT_ACCOUNT_TRANSACTIONS where error is not null");

        try (ResultSet inRS = inAcPS.executeQuery();
             ResultSet inTrRS = inTrPS.executeQuery()
        ) {
          info("Query executed for : select number, error from TMP_CLIENT_ACCOUNTS where error is not null");
          info("Query executed for : select number, error from TMP_CLIENT_ACCOUNT_TRANSACTIONS where error is not null");

          try (PreparedStatement outPS = ciaConnection.prepareStatement(
            "update transition_account_transaction set status = 'ERROR', error = ? where number = ?")) {

            int batchSize = 0, recordsCount = 0;

            final AtomicBoolean showStatus = new AtomicBoolean(false);

            new Thread(() -> {
              while (working.get()) {
                try {
                  Thread.sleep(showStatusPingMillis);
                } catch (InterruptedException e) {
                  break;
                }
                showStatus.set(true);
              }
            }).start();

            long startedAt = System.nanoTime();

            while (inRS.next()) {

              outPS.setString(1, inRS.getString("error"));
              outPS.setLong(2, inRS.getLong("number"));
              outPS.addBatch();
              batchSize++;
              recordsCount++;

              if (batchSize >= uploadMaxBatchSize) {
                outPS.executeBatch();
                ciaConnection.commit();
                batchSize = 0;
              }

              if (showStatus.get()) {
                showStatus.set(false);

                long now = System.nanoTime();
                info(" -- uploaded errors " + recordsCount + " for " + TimeUtils.showTime(now, startedAt)
                  + " : " + TimeUtils.recordsPerSecond(recordsCount, now - startedAt));
              }
            }

            while (inTrRS.next()) {

              outPS.setString(1, inTrRS.getString("error"));
              outPS.setLong(2, inTrRS.getLong("number"));
              outPS.addBatch();
              batchSize++;
              recordsCount++;

              if (batchSize >= uploadMaxBatchSize) {
                outPS.executeBatch();
                ciaConnection.commit();
                batchSize = 0;
              }

              if (showStatus.get()) {
                showStatus.set(false);

                long now = System.nanoTime();
                info(" -- uploaded errors " + recordsCount + " for " + TimeUtils.showTime(now, startedAt)
                  + " : " + TimeUtils.recordsPerSecond(recordsCount, now - startedAt));
              }
            }

            if (batchSize > 0) {
              outPS.executeBatch();
              ciaConnection.commit();
            }

            {
              long now = System.nanoTime();
              info("TOTAL Uploaded errors " + recordsCount + " for " + TimeUtils.showTime(now, startedAt)
                + " : " + TimeUtils.recordsPerSecond(recordsCount, now - startedAt));
            }
          }
        }
      }

    } finally {
      closeCiaConnection();
      working.set(false);
    }

    //language=PostgreSQL
    exec("delete from TMP_CLIENT_ACCOUNTS where error is not null");//В случае если есть ошибка в записи адреса

    //language=PostgreSQL
    exec("delete from TMP_CLIENT_ACCOUNT_TRANSACTIONS where error is not null");//В случае если есть ошибка в записи адреса

  }


  private void uploadAllOkRec(ResultSet inRS, PreparedStatement outPS, int batchSize, int recordsCount, AtomicBoolean showStatus) throws SQLException {
    long startedAt = System.nanoTime();

    while (inRS.next()) {

      outPS.setLong(1, inRS.getLong("number"));
      outPS.addBatch();
      batchSize++;
      recordsCount++;

      if (batchSize >= uploadMaxBatchSize) {
        outPS.executeBatch();
        ciaConnection.commit();
        batchSize = 0;
      }

      if (showStatus.get()) {
        showStatus.set(false);

        long now = System.nanoTime();
        info(" -- uploaded ok records " + recordsCount + " for " + TimeUtils.showTime(now, startedAt)
          + " : " + TimeUtils.recordsPerSecond(recordsCount, now - startedAt));
      }
    }

    if (batchSize > 0) {
      outPS.executeBatch();
      ciaConnection.commit();
    }

    {
      long now = System.nanoTime();
      info("TOTAL Uploaded ok records " + recordsCount + " for " + TimeUtils.showTime(now, startedAt)
        + " : " + TimeUtils.recordsPerSecond(recordsCount, now - startedAt));
    }
  }

  private void uploadAllOk() throws Exception {
    info("uploadAllOk goes: maxBatchSize = " + uploadMaxBatchSize);

    final AtomicBoolean working = new AtomicBoolean(true);

    createCiaConnection();
    ciaConnection.setAutoCommit(false);
    try {

      try (PreparedStatement inAcPS = operConnection.prepareStatement(r("select number from TMP_CLIENT_ACCOUNTS"));
           PreparedStatement inTrPS = operConnection.prepareStatement(r("select number from TMP_CLIENT_ACCOUNT_TRANSACTIONS"))
      ) {

        info("Prepared statement for : select number from TMP_CLIENT_ACCOUNTS");
        info("Prepared statement for : select number from TMP_CLIENT_ACCOUNT_TRANSACTIONS");

        try (ResultSet inAcRS = inAcPS.executeQuery();
             ResultSet inTrRS = inTrPS.executeQuery()) {
          info("Query executed for : select number from TMP_CLIENT_ACCOUNTS");
          info("Query executed for : select number from TMP_CLIENT_ACCOUNT_TRANSACTIONS");

          try (PreparedStatement outPS = ciaConnection.prepareStatement(
            "update transition_account_transaction set status = 'OK' where number = ?")) {

            int batchSize = 0, recordsCount = 0;

            final AtomicBoolean showStatus = new AtomicBoolean(false);

            new Thread(() -> {

              while (true) {

                if (!working.get()) break;

                try {
                  Thread.sleep(showStatusPingMillis);
                } catch (InterruptedException e) {
                  break;
                }

                showStatus.set(true);
              }

            }).start();

            uploadAllOkRec(inAcRS, outPS, batchSize, recordsCount, showStatus);
            batchSize = 0;
            recordsCount = 0;
            showStatus.set(true);
            uploadAllOkRec(inTrRS, outPS, batchSize, recordsCount, showStatus);

          }
        }
      }

    } finally {
      closeCiaConnection();
      working.set(false);
    }

  }

  private void migrateFromTmp() throws Exception {
    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS set error = 'account_number is not defined'\n" +
      "where error is null and account_number  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS set error = 'registered_at is not defined'\n" +
      "where error is null and registered_at  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'account_number is not defined'\n" +
      "where error is null and account_number  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'transaction_type is not defined'\n" +
      "where error is null and transaction_type  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'finished_at is not defined'\n" +
      "where error is null and finished_at  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'money is not defined'\n" +
      "where error is null and money  is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'account_number is not defined'\n" +
      "where error is null and account_number  is null");

    uploadAndDropErrors();

    //language=PostgreSQL
    exec("with num_ord as (\n" +
      "  select number, account_number, row_number() over(partition by account_number order by number desc) as ord \n" +
      "  from TMP_CLIENT_ACCOUNTS\n" +
      ")\n" +
      "\n" +
      "update TMP_CLIENT_ACCOUNTS set status = 2\n" +
      "where status = 0 and number in (select number from num_ord where ord > 1)");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS t set client_id = c.id\n" +
      "  from clients c\n" +
      "  where c.cia_id = t.cia_client_id\n");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS set status = 0 where client_id is not null  and status = 0");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS  set status = 3, account_id = ca.id\n" +
      "from client_account ca " +
      "where ca.number = account_number");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNTS set account_id = nextval('client_account_id_seq') where status = 0");

    //language=PostgreSQL
    exec("insert into client_account (id,client,number,registered_at) \n" +
      "select account_id as id,client_id as client,account_number as number,registered_at \n" +
      "from TMP_CLIENT_ACCOUNTS " +
      "where status = 0");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS t set account_id = acc.id\n" +
      "  from client_account acc\n" +
      "  where acc.number = t.account_number\n");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS t set account_id = acc.account_id\n" +
      "  from TMP_CLIENT_ACCOUNTS acc\n" +
      "  where acc.account_number = t.account_number\n");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS set error = 'account_number is not defined'\n" +
      "where error is null and account_id  is null");

    uploadAndDropErrors();

    //language=PostgreSQL
    exec("update TMP_CLIENT_ACCOUNT_TRANSACTIONS as tmpTr set status = 3\n" +
      "from client_account_transaction tr \n" +
      "where tr.money = tmpTr.money \n" +
      "and tr.finished_at = tmpTr.finished_at\n" +
      "and tr.account = tmpTr.account_id");

    //language=PostgreSQL
    exec("insert into client_account_transaction (account,money,finished_at,\"type\") \n" +
      "select account_id as account,money,finished_at,\"transaction_type\" as \"type\" \n" +
      "from TMP_CLIENT_ACCOUNT_TRANSACTIONS " +
      "where status = 0");

    uploadAllOk();
  }

}
