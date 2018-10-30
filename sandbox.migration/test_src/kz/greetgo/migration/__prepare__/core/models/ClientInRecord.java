package kz.greetgo.migration.__prepare__.core.models;

import kz.greetgo.migration.core.models.Gender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class ClientInRecord {
  public String id;
  public String surname, name, patronymic,charm;
  public Date birthDate;
  public Gender gender;
  public List<AddressInRecord> addressList;
  public List<PhoneInRecord>phoneList;


    public String toXml() {
      StringBuilder sb = new StringBuilder();
      sb.append("<client client_id=\"").append(id).append("\">\n");
      if (surname != null) sb.append("<surname value=\"").append(surname).append("\"/>\n");
      if (name != null) sb.append("<name value=\"").append(name).append("\"/>");
      if (patronymic != null) sb.append("<patronymic value=\"").append(patronymic).append("\"/>\n");
      if (charm != null) sb.append("<charm value=\"").append(charm).append("\"/>\n");
      if (birthDate != null) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy");
        sb.append("  <birth value=\"").append(sdf.format(birthDate)).append("\"/>\n");
      }
      if (gender != null) sb.append("<gender value = \"").append(gender.toString()).append("\"/>\n");

      if (addressList != null && !addressList.isEmpty()) {
        sb.append("<address>\n");
        for (AddressInRecord address : addressList) {
          sb.append("<").append(address.type);
          if (address.street != null && !address.street.isEmpty())
            sb.append(" street=\"").append(address.street).append("\"");
          if (address.house != null && !address.house.isEmpty())
            sb.append(" house=\"").append(address.house).append("\"");
          if (address.flat != null && !address.flat.isEmpty())
            sb.append(" flat=\"").append(address.flat).append("\"");

          sb.append("/>\n");
        }
        sb.append("</address>\n");
      }

      if (phoneList != null && !phoneList.isEmpty())
      {
        for(PhoneInRecord phone : phoneList)
        {
          if(phone.type == PhoneRecordType.WORK)
            sb.append("<workPhone>").append(phone.number).append("</workPhone>\n");

          if(phone.type == PhoneRecordType.HOME)
            sb.append("<homePhone>").append(phone.number).append("</homePhone>\n");

          if(phone.type == PhoneRecordType.MOBILE)
            sb.append("<mobilePhone>").append(phone.number).append("</mobilePhone>\n");
        }
      }


    sb.append("</client>\n");
    return sb.toString();
  }

  public static void main(String[] args) {
    ClientInRecord r = new ClientInRecord();
    r.id = "ID";
    r.surname = "Surname";
    r.name = "Pat";
    r.patronymic = "Pat";
    r.birthDate = new Date();

    System.out.println(r.toXml());
  }
}
