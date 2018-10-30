package kz.greetgo.migration.test;

import kz.greetgo.migration.__prepare__.GenerateCiaData;
import kz.greetgo.migration.__prepare__.core.models.ClientInRecord;
import kz.greetgo.migration.core.models.AddressRecord;
import kz.greetgo.migration.core.models.ClientRecord;
import kz.greetgo.migration.core.MigrationXML;
import kz.greetgo.migration.core.models.Gender;
import kz.greetgo.migration.core.models.Phone;
import kz.greetgo.migration.interfaces.ConnectionConfig;
import kz.greetgo.migration.util.ConfigFiles;
import kz.greetgo.migration.util.ConnectionUtils;
import kz.greetgo.migration.util.RND;
import kz.greetgo.migration.__prepare__.core.models.AddressInRecord;
import kz.greetgo.migration.__prepare__.core.models.PhoneInRecord;
import kz.greetgo.migration.__prepare__.core.models.PhoneRecordType;
import org.testng.TestNG;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.fest.assertions.api.Assertions.assertThat;

@Test
public class MigrationXMLTest extends TestNG {

    private MigrationXML migrationXML;

    private ClientInRecord prepareTestData(){
        ClientInRecord clientInRecord = new ClientInRecord();
        clientInRecord.id           =  RND.str(8);
        clientInRecord.name         =  RND.str(5);
        clientInRecord.surname      =  RND.str(5);
        clientInRecord.patronymic   =  RND.str(5);
        clientInRecord.birthDate    =  RND.date(-100 * 365, -10 * 365);
        clientInRecord.charm        = "TESTCharm";
        clientInRecord.addressList  =  new ArrayList<>();
        clientInRecord.phoneList    =  new ArrayList<>();
        clientInRecord.gender       = Gender.FEMALE;

        AddressInRecord addressInRecordReg = new AddressInRecord();
        addressInRecordReg.street   = "TESTStreet";
        addressInRecordReg.house    = "TESTHouse";
        addressInRecordReg.flat     = "TESTFlat";
        addressInRecordReg.type     = "fact";

        AddressInRecord addressInRecordFact = new AddressInRecord();
        addressInRecordFact.street   = "TESTStreet";
        addressInRecordFact.house    = "TESTHouse";
        addressInRecordFact.flat     = "TESTFlat";
        addressInRecordFact.type     =  "register";

        PhoneInRecord phoneInRecord  = generatePhone();

        clientInRecord.addressList.add(addressInRecordReg);
        clientInRecord.addressList.add(addressInRecordFact);
        clientInRecord.phoneList.add(phoneInRecord);
        return clientInRecord;
    }

    private AddressInRecord generateAddress(){
        AddressInRecord address = new AddressInRecord();
        address.type = (RND.bool(50) ? "fact" : "register");
        address.flat =  RND.str(20);
        address.house =  RND.str(20);
        address.street =  RND.str(20);

        return address;
    }

    private PhoneInRecord generatePhone() {
        PhoneInRecord phoneInRecord = new PhoneInRecord();
        phoneInRecord.number = RND.phoneNum(11);
        phoneInRecord.type = (RND.bool(33) ? PhoneRecordType.HOME : (RND.bool(33) ? PhoneRecordType.MOBILE : PhoneRecordType.WORK));
        return phoneInRecord;
    }

    private void startMigration () throws Exception{
        final File file = new File("build/__migration__");
        file.getParentFile().mkdirs();
        file.createNewFile();

        ConnectionConfig operCC = ConnectionUtils.fileToConnectionConfig(ConfigFiles.operDb());
        ConnectionConfig ciaCC = ConnectionUtils.fileToConnectionConfig(ConfigFiles.ciaDb());

        try {
            migrationXML = new MigrationXML(operCC, ciaCC);
            migrationXML.portionSize = 250_000;
            migrationXML.uploadMaxBatchSize = 50_000;
            migrationXML.downloadMaxBatchSize = 50_000;

            while (true) {
                int count = migrationXML.migrate();
                if (count == 0) break;
                if (count > 0) break;
                if (!file.exists()) break;
            }
        }
        catch (Exception e){
            e.printStackTrace();
            if(migrationXML != null)
                migrationXML.close();
        }
    }

    private void finishMigration(){
        migrationXML.close();
    }

    @Test
    public void migrateClientRecord(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();

        try{
            gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);

                assertThat(migratedRecord).isNotNull();
                assertThat(migratedRecord.id).isNotNull();
                assertThat(migratedRecord.name).isNotNull();
                assertThat(migratedRecord.surname).isNotNull();
                assertThat(migratedRecord.birthDate).isNotNull();
                assertThat(migratedRecord.charm).isNotNull();

                assertThat(migratedRecord.id).isEqualTo(clientInRecord.id);
                assertThat(migratedRecord.name).isEqualTo(clientInRecord.name);
                assertThat(migratedRecord.surname).isEqualTo(clientInRecord.surname);
                assertThat(migratedRecord.charm.name).isEqualTo("-1"); // -1 если нет такого характера в справочнике

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }

    }


    @Test
    public void migrateClientAddressRecord(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.addressList.clear();
        AddressInRecord addressInRecord = generateAddress();
        clientInRecord.addressList.add(addressInRecord);

        try{
            gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);
            //
            //
            List<AddressRecord> addressRecordList =  migrationXML.getClientAdrsByCiaID(migratedRecord.id);
            //
            //
            assertThat(addressRecordList).isNotNull();
            assertThat(addressRecordList).isNotEmpty();
            assertThat(addressRecordList.get(0).flat).isEqualTo(addressInRecord.flat);
            assertThat(addressRecordList.get(0).house).isEqualTo(addressInRecord.house);
            assertThat(addressRecordList.get(0).street).isEqualTo(addressInRecord.street);

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }

        // assertThat()

    }


    @Test
    public void migrateClientPhoneRecord(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.phoneList = new ArrayList<>();
        PhoneInRecord phoneInRecord = generatePhone();
        clientInRecord.phoneList.add(phoneInRecord);

        try{
            gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);
            //
            //
            List<Phone> phoneRecordList =  migrationXML.getClientPhnsByCiaID(migratedRecord.id);
            //
            //
            assertThat(phoneRecordList).isNotNull();
            assertThat(phoneRecordList).isNotEmpty();

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }

    @Test
    public void testClientPhoneNumToError(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.phoneList.get(0).number = null;

        try{
            Long ciaRecordId = gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);
            //
            //
            String statusMess =  gcd.getCiaClientRecStatus(ciaRecordId);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }

    @Test
    public void testClientAddressToError(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.addressList.get(0).street = null;
        clientInRecord.addressList.get(0).flat = null;
        clientInRecord.addressList.get(0).house = null;

        try{
            Long ciaRecordId = gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);
            //
            //
            String statusMess =  gcd.getCiaClientRecStatus(ciaRecordId);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }

    @Test
    public void testClientNameToError(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.name = null;

        try{
            Long ciaRecordId = gcd.insertClientRec(clientInRecord);
            startMigration();
            //
            //
            String statusMess =  gcd.getCiaClientRecStatus(ciaRecordId);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }


    @Test
    public void testClientSurnameToError(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.surname = null;

        try{
            Long ciaRecordId = gcd.insertClientRec(clientInRecord);
            startMigration();
            //
            //
            String statusMess =  gcd.getCiaClientRecStatus(ciaRecordId);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }


    @Test
    public void testClientBirthDayToError(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        clientInRecord.birthDate = null;

        try{
            Long ciaRecordId = gcd.insertClientRec(clientInRecord);
            startMigration();
            //
            //
            String statusMess =  gcd.getCiaClientRecStatus(ciaRecordId);
            //
            //
            assertThat(statusMess).isNotNull();
            assertThat(statusMess).isEqualTo("ERROR");

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }
    }

    @Test
    public void testClientToUpdate(){
        GenerateCiaData gcd = new GenerateCiaData();
        ClientInRecord clientInRecord = prepareTestData();
        try{
            gcd.insertClientRec(clientInRecord);
            startMigration();
            ClientRecord migratedRecord = migrationXML.getClientByCiaID(clientInRecord.id);

            clientInRecord = prepareTestData();
            clientInRecord.id = migratedRecord.id;

            gcd.insertClientRec(clientInRecord);
            startMigration();
            //
            //
            ClientRecord updatedRecord = migrationXML.getClientByCiaID(clientInRecord.id);
            //
            //
            assertThat(migratedRecord).isNotNull();
            assertThat(updatedRecord).isNotNull();
            assertThat(migratedRecord.id).isEqualTo(updatedRecord.id);
            assertThat(migratedRecord.name).isNotEqualTo(updatedRecord.name);
            assertThat(migratedRecord.surname).isNotEqualTo(updatedRecord.surname);
            assertThat(migratedRecord.birthDate).isNotEqualTo(updatedRecord.birthDate);

            finishMigration();
        }
        catch (Exception e ){
            e.printStackTrace();
            System.out.println("Cannot insert data to Transition_client");
            finishMigration();
        }

        // assertThat()
    }


}
