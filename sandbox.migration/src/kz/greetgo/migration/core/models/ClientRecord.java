package kz.greetgo.migration.core.models;

import kz.greetgo.migration.util.SaxHandler;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

public class ClientRecord extends SaxHandler {
  public long number;
  public String id;
  public String surname, name, patronymic;
  public Charm charm = new Charm();
  public java.sql.Date birthDate;
  public List<AddressRecord> addressRecordList;
  public Gender gender;
  public List<Phone> phoneList = new ArrayList<>();

  public void parseRecordData(String recordData) throws SAXException, IOException {
    if (recordData == null) return;
    XMLReader reader = XMLReaderFactory.createXMLReader();
    reader.setContentHandler(this);
    reader.parse(new InputSource(new StringReader(recordData)));
  }


  @Override
  protected void startingTag(Attributes attributes) {
    String path = path();

    if ("/client".equals(path)) {
      id = attributes.getValue("client_id");
      return;
    }
    else if ("/client/surname".equals(path)) {
      surname = attributes.getValue("value");
    }
    else if ("/client/name".equals(path)) {
      name = attributes.getValue("value");
    }
    else if ("/client/patronymic".equals(path)) {
      patronymic = attributes.getValue("value");
      return;
    }
    else if ("/client/charm".equals(path)) {
      charm.name = attributes.getValue("value");
      return;
    }
    else if ("/client/birth".equals(path)) {
      try {
        String date = attributes.getValue("value");
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");
        birthDate = new java.sql.Date(sdf.parse(date).getTime());
        return;
      }
      catch (Exception e){
        birthDate = null;
        e.printStackTrace();
      }
    }
    else if("/client/gender".equals(path)){
       gender = Gender.valueOf(attributes.getValue("value"));
    }
    else if ("/client/address".equals(path)){
      addressRecordList = new ArrayList<>();
    }
    else if("/client/address/fact".equals(path)){
      AddressRecord addressRecord = new AddressRecord();
      addressRecord.client_id = id;
      addressRecord.client_number = number;
      addressRecord.type = AddressType.FACT;
      addressRecord.street = attributes.getValue("street");
      addressRecord.house = attributes.getValue("house");
      addressRecord.flat = attributes.getValue("flat");

      addressRecordList.add(addressRecord);
    }

    else if("/client/address/register".equals(path)){
      AddressRecord addressRecord = new AddressRecord();
      addressRecord.client_id = id;
      addressRecord.client_number = number;
      addressRecord.type = AddressType.REG;
      addressRecord.street = attributes.getValue("street");
      addressRecord.house = attributes.getValue("house");
      addressRecord.flat = attributes.getValue("flat");

      addressRecordList.add(addressRecord);
    }
  }

  @Override
  protected void endedTag(String tagName) throws Exception {
    String path = path() + "/" + tagName;
    if("/client/workPhone".equals(path)){
      Phone phone = new Phone();
      phone.type = PhoneType.WORK;
      phone.number = text();
      phone.client_number = number;
      phone.client_id = id;

      phoneList.add(phone);
    }

    if("/client/homePhone".equals(path)){
      Phone phone = new Phone();
      phone.type = PhoneType.HOME;
      phone.number = text();
      phone.client_number = number;
      phone.client_id = id;

      phoneList.add(phone);
    }

    if("/client/mobilePhone".equals(path)){
      Phone phone = new Phone();
      phone.type = PhoneType.MOBILE;
      phone.number = text();
      phone.client_number = number;
      phone.client_id = id;

      phoneList.add(phone);
    }
  }
}
