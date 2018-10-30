package kz.greetgo.migration.test;

import kz.greetgo.migration.__prepare__.GenerateCiaData;
import kz.greetgo.migration.__prepare__.core.models.AccountInRecord;
import kz.greetgo.migration.__prepare__.core.models.TransactionInRecord;
import kz.greetgo.migration.core.MigrationJSON;
import kz.greetgo.migration.core.models.Account;
import kz.greetgo.migration.core.models.Transaction;
import kz.greetgo.migration.interfaces.ConnectionConfig;
import kz.greetgo.migration.util.ConfigFiles;
import kz.greetgo.migration.util.ConnectionUtils;
import kz.greetgo.migration.util.RND;
import org.testng.annotations.Test;

import java.io.File;
import java.text.SimpleDateFormat;

import static org.fest.assertions.api.Assertions.assertThat;

public class MigrationJSONTest {

    private MigrationJSON migrationJSON;
    private SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");

    private AccountInRecord prepareAccountTestData() {
        AccountInRecord newAcc = new AccountInRecord();
        newAcc.account_number = RND.phoneNum(16);
        newAcc.registered_at = RND.date(-2 * 365, -1 * 36);
        newAcc.client_id = RND.str(5);
        return newAcc;
    }

    private TransactionInRecord prepareTransactionTestData(String account_number) {
        TransactionInRecord transaction = new TransactionInRecord();
        transaction.account_number = account_number;
        transaction.finished_at =  RND.date(-1 * 31, -1 * 5);
        transaction.money =  (double)RND._int(1_000_000);
        transaction.transaction_type =   RND.str(5);

        return transaction;
    }

    private void startMigration() throws Exception {
        final File file = new File("build/__migration__");
        file.getParentFile().mkdirs();
        file.createNewFile();

        ConnectionConfig operCC = ConnectionUtils.fileToConnectionConfig(ConfigFiles.operDb());
        ConnectionConfig ciaCC = ConnectionUtils.fileToConnectionConfig(ConfigFiles.ciaDb());

        try {
            migrationJSON = new MigrationJSON(operCC, ciaCC);
            migrationJSON.portionSize = 250_000;
            migrationJSON.uploadMaxBatchSize = 50_000;
            migrationJSON.downloadMaxBatchSize = 50_000;

            while (true) {
                int count = migrationJSON.migrate();
                if (count == 0) break;
                if (count > 0) break;
                if (!file.exists()) break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            if (migrationJSON != null)
                migrationJSON.close();
        }
    }

    private void finishMigration() {
       try {
           migrationJSON.dropAllTables();
           migrationJSON.close();
       }
       catch (Exception e){
           e.printStackTrace();
           migrationJSON.close();
       }
    }

    @Test
    public void migrateAccountRecord() {
        GenerateCiaData gcd = new GenerateCiaData();
        AccountInRecord accountInRecord = prepareAccountTestData();

        try {
            Long id = gcd.insertAccountOrTransactionRec(accountInRecord);
            startMigration();
            //
            //
            Account retAccount = migrationJSON.getAccountById(id);
            //
            //
            assertThat(retAccount).isNotNull();
            assertThat(retAccount.account_number).isEqualTo(accountInRecord.account_number);
            assertThat(sdf.format(retAccount.registered_at)).isEqualTo(sdf.format(accountInRecord.registered_at));
            assertThat(retAccount.client_id).isEqualTo(accountInRecord.client_id);
            finishMigration();
        } catch (Exception e) {
            e.printStackTrace();
            finishMigration();
        }

    }

    @Test
    public void migrateTransactionRecord() {
        GenerateCiaData gcd = new GenerateCiaData();
        AccountInRecord accountInRecord = prepareAccountTestData();
        TransactionInRecord transaction = prepareTransactionTestData(accountInRecord.account_number);

        try {
            gcd.insertAccountOrTransactionRec(accountInRecord);
            Long id = gcd.insertAccountOrTransactionRec(transaction);
            startMigration();
            //
            //
            Transaction retTransaction = migrationJSON.getTransactionById(id);
            //
            //
            assertThat(retTransaction).isNotNull();
            assertThat(retTransaction.account_number).isEqualTo(transaction.account_number);
            assertThat(sdf.format(retTransaction.finished_at)).isEqualTo(sdf.format(transaction.finished_at));
            assertThat(retTransaction.money).isEqualTo(transaction.money);
            assertThat(retTransaction.transaction_type).isEqualTo(transaction.transaction_type);
            finishMigration();
        } catch (Exception e)
        {
            e.printStackTrace();
            finishMigration();
        }
    }


    @Test
    public void testAccountToError() {
        GenerateCiaData gcd = new GenerateCiaData();
        AccountInRecord accountInRecord = prepareAccountTestData();
        accountInRecord.account_number = null;
        try {
            gcd.insertAccountOrTransactionRec(accountInRecord);
            Long id = gcd.insertAccountOrTransactionRec(accountInRecord);
            startMigration();
            //
            //
            String statusMess =  gcd.getAccOrTransRecStatus(id);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");
            finishMigration();
        } catch (Exception e)
        {
            e.printStackTrace();
            finishMigration();

        }
    }

    @Test
    public void testTransactionToError() {
        GenerateCiaData gcd = new GenerateCiaData();
        AccountInRecord accountInRecord = prepareAccountTestData();
        TransactionInRecord transaction = prepareTransactionTestData(accountInRecord.account_number);
        transaction.account_number = null;
        transaction.finished_at = null;
        try {
            gcd.insertAccountOrTransactionRec(accountInRecord);
            Long id = gcd.insertAccountOrTransactionRec(transaction);
            assertThat(id).isNotNull();
            startMigration();
            //
            //
            String statusMess =  gcd.getAccOrTransRecStatus(id);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");
            finishMigration();
          } catch (Exception e)
        {
            e.printStackTrace();
            finishMigration();
        }
    }

    @Test
    public void testTransactionToUpdate(){

    }




}
