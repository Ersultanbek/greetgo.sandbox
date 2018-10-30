package kz.greetgo.migration.core;

import kz.greetgo.migration.core.models.*;
import kz.greetgo.migration.interfaces.ConnectionConfig;
import kz.greetgo.migration.util.TimeUtils;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static kz.greetgo.migration.util.TimeUtils.recordsPerSecond;
import static kz.greetgo.migration.util.TimeUtils.showTime;

public class MigrationXML extends Migration {

  private Map<String, Long> charmDictionary = new HashMap<>(); // Если буду использовать Set<Charm> то мне нужно найти Charm по полю name а это лишний цикл

  public int portionSize = 1_000_000;
  public int downloadMaxBatchSize = 50_000;
  public int uploadMaxBatchSize = 50_000;
  public int showStatusPingMillis = 5000;

  public MigrationXML(ConnectionConfig operConfig, ConnectionConfig ciaConfig) {
    super(operConfig, ciaConfig);
  }

  private void loadCharmDictionary() throws SQLException {
    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(r("SELECT id,name from charms"))) {
      try (ResultSet rs = selectStmnt.executeQuery()) {
        while (rs.next()) {
          if (charmDictionary != null) {
            Charm ch = new Charm();
            ch.id = rs.getLong(1);
            ch.name = rs.getString(2);
            charmDictionary.put(ch.name, ch.id);
          }
        }
      }
    }
  }

  public int migrate() throws Exception {
    long startedAt = System.nanoTime();

    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    Date nowDate = new Date();
    tmpClientTable = "cia_migration_client_" + sdf.format(nowDate);
    info("TMP_CLIENT = " + tmpClientTable);
    createOperConnection();
    dropAllTables();

    //language=PostgreSQL
    exec("create table TMP_CLIENT (\n" +
      "  client_id int8,\n" +
      "  status int not null default 0,\n" +
      "  error varchar(300),\n" +
      "  \n" +
      "  number bigint not null primary key,\n" +
      "  cia_id varchar(100) not null,\n" +
      "  surname varchar(300),\n" +
      "  name varchar(300),\n" +
      "  patronymic varchar(300),\n" +
      "  birth_date date, \n" +
      "  charm serial,\n" +
      "  gender gender\n" +
      ")");

    //language=PostgreSQL
    exec(" create table TMP_CLIENT_ADDRESS (\n" +
      " error varchar(300),\n" +
      " parent_number bigint not null,\n" +
      "  \n" +
      " cia_client_id  varchar(100),\n" +
      " client_id serial,\n" +
      " type addr_type NOT NULL,\n" +
      " street varchar(255),\n" +
      " house varchar(255),\n" +
      " flat varchar(255),\n" +
      " actual boolean default false" +
      ")");

    //language=PostgreSQL
    exec(" create table TMP_CLIENT_PHONES (\n" +
      " error varchar(300),\n" +
      " parent_number bigint not null,\n" +
      "  \n" +
      " cia_client_id  varchar(100),\n" +
      " client_id serial,\n" +
      " type phone_type NOT NULL,\n" +
      " number varchar(255),\n" +
      " actual boolean default false" +
      ")");

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
      info("MigrationXML of portion " + portionSize + " finished for " + TimeUtils.showTime(now, startedAt));
    }

    return portionSize;
  }

  public ClientRecord getClientByCiaID(String id) throws Exception {
    ClientRecord clientRecord = null;

    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(
      r("SELECT cia_id,surname,\"name\",patronymic,birth_date,charm " +
        "from clients " +
        "WHERE cia_id = '" + id + "'"))) {

      try (ResultSet rs = selectStmnt.executeQuery()) {
        while (rs.next()) {
          clientRecord = new ClientRecord();
          clientRecord.id = rs.getString(1);
          clientRecord.surname = rs.getString(2);
          clientRecord.name = rs.getString(3);
          clientRecord.patronymic = rs.getString(4);
          clientRecord.birthDate = rs.getDate(5);
          clientRecord.charm.name = rs.getString(6);
        }
      }
    }
    return clientRecord;
  }

  public List<AddressRecord> getClientAdrsByCiaID(String id) throws Exception {
    List<AddressRecord> addressRecordList = null;

    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(
      r("SELECT \"type\",street,house,flat " +
        "from TMP_CLIENT_ADDRESS \n" +
        "WHERE cia_client_id = '" + id + "'"))) {

      addressRecordList = new ArrayList<>();
      try (ResultSet rs = selectStmnt.executeQuery()) {
        AddressRecord addressRecord;
        while (rs.next()) {
          addressRecord = new AddressRecord();
          addressRecord.type = AddressType.valueOf(rs.getString(1));
          addressRecord.street = rs.getString(2);
          addressRecord.house = rs.getString(3);
          addressRecord.flat = rs.getString(4);
          addressRecordList.add(addressRecord);
        }
      }
    }
    return addressRecordList;
  }

  public List<Phone> getClientPhnsByCiaID(String id) throws SQLException {
    List<Phone> phoneRecordList = null;

    //language=PostgreSQL
    try (PreparedStatement selectStmnt = operConnection.prepareStatement(
      r("SELECT \"type\",number " +
        "from TMP_CLIENT_PHONES \n" +
        "WHERE cia_client_id = '" + id + "'"))) {

      phoneRecordList = new ArrayList<>();
      try (ResultSet rs = selectStmnt.executeQuery()) {
        Phone phoneRecord;
        while (rs.next()) {
          phoneRecord = new Phone();
          phoneRecord.type = PhoneType.valueOf(rs.getString(1));
          phoneRecord.number = rs.getString(2);
          phoneRecordList.add(phoneRecord);
        }
      }
    }
    return phoneRecordList;

  }

  private int download() throws SQLException, IOException, SAXException {

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

    try (PreparedStatement ciaPS = ciaConnection.prepareStatement(
      "select * from transition_client where status='JUST_INSERTED' order by number limit ?")) {
      loadCharmDictionary();
      info("Prepared statement for : select * from transition_client");

      ciaPS.setInt(1, portionSize);

      Insert insertClient = new Insert("TMP_CLIENT");
      insertClient.field(1, "number", "?");
      insertClient.field(2, "cia_id", "?");
      insertClient.field(3, "surname", "?");
      insertClient.field(4, "name", "?");
      insertClient.field(5, "patronymic", "?");
      insertClient.field(6, "birth_date", "?");
      insertClient.field(7, "charm", "?");
      insertClient.field(8, "gender", "? :: gender");

      Insert insertAddress = new Insert("TMP_CLIENT_ADDRESS");
      insertAddress.field(1, "parent_number", "?");
      insertAddress.field(2, "cia_client_id", "?");
      insertAddress.field(3, "type", "? :: addr_type ");
      insertAddress.field(4, "street", "?");
      insertAddress.field(5, "house", "?");
      insertAddress.field(6, "flat", "?");

      Insert insertPhone = new Insert("TMP_CLIENT_PHONES");
      insertPhone.field(1, "parent_number", "?");
      insertPhone.field(2, "cia_client_id", "?");
      insertPhone.field(3, "type", "?::phone_type");
      insertPhone.field(4, "number", "?");

      //TODO не использовать toString для выполнения логики
      operConnection.setAutoCommit(false);
      try (PreparedStatement operClPS = operConnection.prepareStatement(r(insertClient.toString()));
           PreparedStatement operClAdPs = operConnection.prepareStatement(r(insertAddress.toString()));
           PreparedStatement operClPhPs = operConnection.prepareStatement(r(insertPhone.toString()));
      ) {

        try (ResultSet ciaRS = ciaPS.executeQuery()) {

          info("Got result set for : select * from transition_client");

          int batchSize = 0, recordsCount = 0;

          long startedAt = System.nanoTime();

          while (ciaRS.next()) {
            ClientRecord r = new ClientRecord();
            r.number = ciaRS.getLong("number");
            r.parseRecordData(ciaRS.getString("record_data"));
            r.charm.id = charmDictionary.get(r.charm.name) == null ? -1L : charmDictionary.get(r.charm.name);
            operClPS.setLong(1, r.number);
            operClPS.setString(2, r.id);
            operClPS.setString(3, r.surname);
            operClPS.setString(4, r.name);
            operClPS.setString(5, r.patronymic);
            operClPS.setDate(6, r.birthDate);
            operClPS.setLong(7, r.charm.id);
            operClPS.setString(8, r.gender.toString());

            //Записываем адреса во временную таблицу
            if (r.addressRecordList != null && !r.addressRecordList.isEmpty()) {
              for (AddressRecord addressRecord : r.addressRecordList) {
                operClAdPs.setLong(1, r.number);
                operClAdPs.setString(2, r.id);
                operClAdPs.setString(3, addressRecord.type.name());
                operClAdPs.setString(4, addressRecord.street);
                operClAdPs.setString(5, addressRecord.house);
                operClAdPs.setString(6, addressRecord.flat);
                operClAdPs.addBatch();
              }
            }

            if (r.phoneList != null && !r.phoneList.isEmpty()) {
              for (Phone phone : r.phoneList) {
                operClPhPs.setLong(1, r.number);
                operClPhPs.setString(2, r.id);
                operClPhPs.setString(3, phone.type.name());
                operClPhPs.setString(4, phone.number);
                ;
                operClPhPs.addBatch();
              }
            }

            operClPS.addBatch();
            batchSize++;
            recordsCount++;

            if (batchSize >= downloadMaxBatchSize) {
              operClPS.executeBatch();
              operClAdPs.executeBatch();
              operClPhPs.executeBatch();
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
            operClPS.executeBatch();
            operClAdPs.executeBatch();
            operClPhPs.executeBatch();
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

      try (PreparedStatement inPS = operConnection.prepareStatement(r(
        "select number, error from TMP_CLIENT where error is not null"))) {

        info("Prepared statement for : select number, error from TMP_CLIENT where error is not null");

        try (ResultSet inRS = inPS.executeQuery()) {
          info("Query executed for : select number, error from TMP_CLIENT where error is not null");

          try (PreparedStatement outPS = ciaConnection.prepareStatement(
            "update transition_client set status = 'ERROR', error = ? where number = ?")) {

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
    exec("delete from TMP_CLIENT_ADDRESS where error is not null");//В случае если есть ошибка в записи адреса

    //language=PostgreSQL
    exec("delete from TMP_CLIENT_PHONES where error is not null");//В случае если есть ошибка в записи адреса

    //language=PostgreSQL
    exec("delete from TMP_CLIENT_ADDRESS" +
      " USING TMP_CLIENT \n" +
      " WHERE TMP_CLIENT.error is not null \n" +
      " and \n" +
      " TMP_CLIENT.number = parent_number");

    //language=PostgreSQL
    exec("delete from TMP_CLIENT_PHONES" +
      " USING TMP_CLIENT \n" +
      " WHERE TMP_CLIENT.error is not null \n" +
      " and \n" +
      " TMP_CLIENT.number = parent_number");

    //language=PostgreSQL
    exec("delete from TMP_CLIENT where error is not null");
  }

  private void uploadAllOk() throws Exception {

    info("uploadAllOk goes: maxBatchSize = " + uploadMaxBatchSize);

    final AtomicBoolean working = new AtomicBoolean(true);

    createCiaConnection();
    ciaConnection.setAutoCommit(false);
    try {

      try (PreparedStatement inPS = operConnection.prepareStatement(r("select number from TMP_CLIENT"))) {

        info("Prepared statement for : select number from TMP_CLIENT");

        try (ResultSet inRS = inPS.executeQuery()) {
          info("Query executed for : select number from TMP_CLIENT");

          try (PreparedStatement outPS = ciaConnection.prepareStatement(
            "update transition_client set status = 'OK' where number = ?")) {

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
        }
      }

    } finally {
      closeCiaConnection();
      working.set(false);
    }

  }

  private void migrateFromTmp() throws Exception {

    //language=PostgreSQL
    exec("update TMP_CLIENT set error = 'surname is not defined'\n" +
      "where error is null and surname is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT set error = 'name is not defined'\n" +
      "where error is null and name is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT set error = 'birth_date is not defined'\n" +
      "where error is null and birth_date is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT set error = 'charm is not defined'\n" +
      "where error is null and charm is null");

    //language=PostgreSQL
    exec("update TMP_CLIENT\n" +
      "set error = 'gender is not defined'\n" +
      "where error is null and gender is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS\n" +
      "set error = 'street is not defined'\n" +
      "where error is null and street is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS\n" +
      "set error = 'house is not defined'\n" +
      "where error is null and house is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS\n" +
      "set error = 'flat is not defined'\n" +
      "where error is null and flat is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT_PHONES\n" +
      "set error = 'type is not defined'\n" +
      "where error is null and \"type\" is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT_PHONES\n" +
      "set error = 'type is not defined'\n" +
      "where error is null and \"number\" is null;");

    //language=PostgreSQL
    exec("update TMP_CLIENT\n" +
      "    set error = sub.error\n" +
      "From\n" +
      "    (select parent_number,error from TMP_CLIENT_ADDRESS\n" +
      "      Where error is not null) as sub \n" +
      "where  number = sub.parent_number;");

    //language=PostgreSQL
    exec("update TMP_CLIENT\n" +
      "    set error = sub.error\n" +
      "From\n" +
      "    (select parent_number,error from TMP_CLIENT_PHONES\n" +
      "      Where error is not null) as sub \n" +
      "where  number = sub.parent_number;");

    uploadAndDropErrors();

    //language=PostgreSQL
    exec("with num_ord as (\n" +
      "  select number, cia_id, row_number() over(partition by cia_id order by number desc) as ord \n" +
      "  from TMP_CLIENT\n" +
      ")\n" +
      "\n" +
      "update TMP_CLIENT set status = 2\n" +
      "where status = 0 and number in (select number from num_ord where ord > 1)");

    //language=PostgreSQL
    exec("update TMP_CLIENT t set client_id = c.id\n" +
      "  from clients c\n" +
      "  where c.cia_id = t.cia_id\n");

    //language=PostgreSQL
    exec("update TMP_CLIENT set status = 3 where client_id is not null and status = 0");

    //language=PostgreSQL
    exec("update TMP_CLIENT set client_id = nextval('clients_id_seq') where status = 0");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS set client_id = tmpClient.client_id " +
      "from (Select client_id,cia_id from TMP_CLIENT where client_id is not null) as tmpClient " +
      "where tmpClient.cia_id = cia_client_id");

    //language=PostgreSQL
    exec("update TMP_CLIENT_PHONES set client_id = tmpClient.client_id " +
      "from (Select client_id,cia_id from TMP_CLIENT where client_id is not null) as tmpClient " +
      "where tmpClient.cia_id = cia_client_id");

    //language=PostgreSQL
    exec("insert into clients (id,cia_id, surname, \"name\", patronymic, birth_date,charm) \n" +
      "select client_id,cia_id, surname, \"name\", patronymic, birth_date,charm \n" +
      "from TMP_CLIENT " +
      "where status = 0");


    //Убираем актуальность с прежних данных пользователя
    //language=PostgreSQL
    exec("update client_addr set actual = false from TMP_CLIENT_ADDRESS tmpAdr where tmpAdr.client_id = client ");
    //language=PostgreSQL
    exec("update client_addr set actual = false from TMP_CLIENT_PHONES tmpPhn where tmpPhn.client_id = client ");

    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS set actual = true");
    //language=PostgreSQL
    exec("update TMP_CLIENT_ADDRESS set actual = true");

    //language=PostgreSQL
    exec("insert into client_addr (client,\"type\",street,house,flat,actual)\n" +
      "select client_id, \"type\",street,house,flat,actual\n" +
      "from TMP_CLIENT_ADDRESS \n" +
      "where client_id is not null");

    //language=PostgreSQL
    exec("insert into client_phone (client,\"type\",number,actual)\n" +
      "select client_id, \"type\",number,actual\n" +
      "from TMP_CLIENT_PHONES \n" +
      "where client_id is not null");

    //language=PostgreSQL
    exec("update clients c set surname = s.surname\n" +
      "                 , \"name\" = s.\"name\"\n" +
      "                 , patronymic = s.patronymic\n" +
      "                 , birth_date = s.birth_date\n" +
      "                 , charm = s.charm\n" +
      "from TMP_CLIENT s\n" +
      "where c.id = s.client_id\n" +
      "and s.status = 3");

    //language=PostgreSQL
    exec("update clients set actual = true where id in (\n" +
      "  select client_id from TMP_CLIENT where status = 0\n" +
      ")");

    uploadAllOk();
  }


}
