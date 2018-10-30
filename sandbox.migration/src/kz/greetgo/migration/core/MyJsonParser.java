package kz.greetgo.migration.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MyJsonParser {
  JsonFactory factory = new JsonFactory();
  JsonParser parser;
  Map<String, String> map = new HashMap<>();

  public String getFieldValue(String jsonStr, String field) throws IOException {
    parser = factory.createParser(jsonStr);

    while (!parser.isClosed()) {
      JsonToken token = parser.nextToken();

      if (JsonToken.FIELD_NAME.equals(token)) {
        String fieldName = parser.getCurrentName();
        if (fieldName != null && fieldName.equals(fieldName)) {
          parser.nextToken();
          String val = parser.getValueAsString();
          parser.close();

          return val;
        }
      }
    }
    parser.close();
    return null;
  }

  public <T> T mapFromJSON(String json, Class<T> tClass) {

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try {
      return objectMapper.readValue(json, tClass);
    } catch (Exception e) {
      //TODO error?
      e.printStackTrace();
    }
    return null;
  }

}
