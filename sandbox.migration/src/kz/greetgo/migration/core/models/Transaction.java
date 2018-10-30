package kz.greetgo.migration.core.models;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.sql.Date;


public class Transaction {
    public String account_number;
    public Double money;
    public String transaction_type;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "dd-MM-yyyy hh:mm")
    public Date finished_at;
    public Long number;
}
