package kz.greetgo.migration.__prepare__;

import kz.greetgo.migration.__prepare__.core.DbWorker;
import kz.greetgo.migration.__prepare__.core.models.*;
import kz.greetgo.migration.core.models.Gender;
import kz.greetgo.migration.util.ConfigFiles;
import kz.greetgo.migration.util.FileUtils;
import kz.greetgo.migration.util.RND;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static kz.greetgo.migration.util.TimeUtils.recordsPerSecond;
import static kz.greetgo.migration.util.TimeUtils.showTime;


public class GenerateCiaData {
  public static void main(String[] args) throws Exception {
    GenerateCiaData gcd = new GenerateCiaData();

    if (args.length >= 1) {
      gcd.finishCount = Integer.parseInt(args[0].replaceAll("_", ""));
    }

    gcd.execute();
  }

  public Integer finishCount = null;
  public final String[] charmList = {"Терпиливый", "Добряк", "Умный", "Слабый", "Глупый", "Внимательный", "Хитрый", "Скучный"};

  private static final int MAX_STORING_ID_COUNT = 1_000_000;
  private static final int MAX_BATCH_SIZE = 50_000;
  private static final long PING_MILLIS = 2500;

  void info(String message) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
    System.out.println(sdf.format(new Date()) + " [" + getClass().getSimpleName() + "] " + message);
  }

  Connection connection;

  private void execute() throws Exception {
    try (Connection connection = DbWorker.createConnection(ConfigFiles.ciaDb())) {
      this.connection = connection;

      prepareData();

    }
  }

  public long insertClientRec(ClientInRecord record) throws Exception {
    try (Connection connection = DbWorker.createConnection(ConfigFiles.ciaDb())) {
      this.connection = connection;

      try (PreparedStatement ps = connection.prepareStatement("insert into transition_client (record_data) values (?) RETURNING \"number\"")) {
        ps.setString(1, record.toXml());

        try (ResultSet generatedKeys = ps.executeQuery()) {
          generatedKeys.next();
          return generatedKeys.getLong(1);
        }
      }
    }
  }

  public long insertAccountOrTransactionRec(Object record) throws Exception {
    try (Connection connection = DbWorker.createConnection(ConfigFiles.ciaDb())) {
      this.connection = connection;

      try (PreparedStatement ps = connection.prepareStatement("insert into transition_account_transaction (record_data) values (?) RETURNING \"number\"")) {
        ps.setString(1, record.toString());

        try (ResultSet generatedKeys = ps.executeQuery()) {
          generatedKeys.next();
          return generatedKeys.getLong(1);
        }
      }
    }
  }


  public String getCiaClientRecStatus(Long ciaRecId) throws Exception {
    try (Connection connection = DbWorker.createConnection(ConfigFiles.ciaDb())) {
      this.connection = connection;

      try (PreparedStatement ps = connection.prepareStatement(
        "Select status from transition_client where number = ?")) {
        ps.setLong(1, ciaRecId);

        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
    }
    return null;
  }

  public String getAccOrTransRecStatus(Long ciaRecId) throws Exception {
    try (Connection connection = DbWorker.createConnection(ConfigFiles.ciaDb())) {
      this.connection = connection;

      try (PreparedStatement ps = connection.prepareStatement(
        "Select status from transition_account_transaction where number = ?")) {
        ps.setLong(1, ciaRecId);

        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            return rs.getString(1);
          }
        }
      }
    }
    return null;
  }

  private final File storingIdsFile = new File("build/storing_ids.txt");
  private final Set<String> storingIdSet = new HashSet<>();
  private List<String> storingIdList = new ArrayList<>();

  private void loadStoringIds() {
    if (!storingIdsFile.exists()) return;
    storingIdList = Arrays.stream(FileUtils.fileToStr(storingIdsFile).split("\n"))
      .collect(Collectors.toList());
    storingIdSet.clear();
    storingIdSet.addAll(storingIdList);
  }

  private void saveStoringIds() throws IOException {
    storingIdsFile.getParentFile().mkdirs();
    FileUtils.putStrToFile(storingIdSet.stream().sorted().collect(Collectors.joining("\n")), storingIdsFile);
  }

  String tryAddToStore(String id) {
    if (storingIdSet.size() > MAX_STORING_ID_COUNT) return id;
    if (storingIdSet.contains(id)) return id;
    storingIdSet.add(id);
    storingIdList.add(id);
    return id;
  }

  String rndStoreId() {
    if (storingIdList.size() == 0) return null;
    return storingIdList.get(RND._int(storingIdList.size()));
  }

  private final File workingFile = new File("build/__working__");

  private void prepareData() throws Exception {

    workingFile.getParentFile().mkdirs();

    final AtomicBoolean working = new AtomicBoolean(true);
    final AtomicBoolean show = new AtomicBoolean(false);

    final Thread see = new Thread(() -> {

      while (workingFile.exists() && working.get()) {

        try {
          Thread.sleep(PING_MILLIS);
        } catch (InterruptedException e) {
          break;
        }

        show.set(true);

      }

      working.set(false);
      workingFile.delete();
    });

    workingFile.createNewFile();
    see.start();

    info("Work has begun");

    loadStoringIds();

    info("Storing ids loaded");

    connection.setAutoCommit(false);

    try {
      try (PreparedStatement ps = connection.prepareStatement("insert into transition_client (record_data) values (?)");
           PreparedStatement psAcTr = connection.prepareStatement("insert into transition_account_transaction (record_data) values (?)")) {
        int batchSize = 0, inserts = 0;
        long startedAt = System.nanoTime();

        while (working.get()) {

          ClientInRecord r = new ClientInRecord();
          r.id = RND.bool(50) ? rndStoreId() : null;
          if (r.id == null) r.id = tryAddToStore(RND.str(10));
          r.surname = RND.bool(4) ? null : RND.str(20);
          r.name = RND.bool(4) ? null : RND.str(20);
          r.patronymic = RND.bool(10) ? null : RND.str(20);
          r.birthDate = RND.bool(10) ? null : RND.date(-100 * 365, -10 * 365);
          r.charm = charmList[RND._int(charmList.length - 1)];
          r.gender = RND.bool(50) ? Gender.FEMALE : Gender.MALE;
          r.addressList = new ArrayList<>();
          for (int i = 0; i < (RND.bool(50) ? 2 : 1); i++) {
            AddressInRecord address = new AddressInRecord();
            address.type = (RND.bool(50) ? "fact" : "register");
            address.flat = RND.bool(10) ? null : RND.str(20);
            address.house = RND.bool(10) ? null : RND.str(20);
            address.street = RND.bool(10) ? null : RND.str(20);
            r.addressList.add(address);
          }
          r.phoneList = new ArrayList<>();
          for (int i = 0; i < RND._int(5); i++) {
            PhoneInRecord phoneInRecord = new PhoneInRecord();
            phoneInRecord.number = RND.bool(20) ? null : RND.phoneNum(11);
            phoneInRecord.type = (RND.bool(33) ? PhoneRecordType.HOME : (RND.bool(33) ? PhoneRecordType.MOBILE : PhoneRecordType.WORK));

            r.phoneList.add(phoneInRecord);
          }

          ///Generation of Account and Transactions
          for (int i = 0; i < RND._int(3); i++) {
            AccountInRecord account = new AccountInRecord();
            account.account_number = RND.bool(10) ? null : RND.phoneNum(2) + RND.str(5);
            account.registered_at = RND.bool(10) ? null : RND.date(-2 * 365, -1 * 365);
            account.client_id = RND.bool(10) ? null : r.id;

            psAcTr.setString(1, account.toString());
            psAcTr.addBatch();

            for (int j = 0; j < RND._int(3); j++) {
              TransactionInRecord transaction = new TransactionInRecord();
              transaction.account_number = RND.bool(10) ? null : account.account_number;
              transaction.finished_at = RND.bool(10) ? null : RND.date(-1 * 31, -1 * 5);
              transaction.money = RND.bool(10) ? null : (double) RND._int(1_000_000);
              transaction.transaction_type = RND.bool(10) ? null : RND.str(5);

              psAcTr.setString(1, transaction.toString());
              psAcTr.addBatch();
            }
          }

          ps.setString(1, r.toXml());
          ps.addBatch();
          batchSize++;
          inserts++;

          if (batchSize >= MAX_BATCH_SIZE) {
            ps.executeBatch();
            psAcTr.executeBatch();
            connection.commit();
            batchSize = 0;
          }

          if (show.get()) {
            show.set(false);
            long now = System.nanoTime();

            info(" -- Inserted records " + inserts + " for "
              + showTime(now, startedAt) + " - " + recordsPerSecond(inserts, now - startedAt));
          }

          if (finishCount != null && inserts >= finishCount) {
            working.set(false);
            break;
          }
        }

        if (batchSize > 0) {
          ps.executeBatch();
          connection.commit();
        }

        long now = System.nanoTime();
        info("TOTAL: Inserted records " + inserts + " for "
          + showTime(now, startedAt) + " - " + recordsPerSecond(inserts, now - startedAt));
      }

    } finally {
      connection.setAutoCommit(true);
    }

    info("see.interrupt();");
    see.interrupt();
    info("see.join();");
    see.join();

    info("save storing ids...");

    saveStoringIds();

    info("Finish");
  }


  public void genAndInsertClient(AtomicBoolean working) throws SQLException {

  }
}
