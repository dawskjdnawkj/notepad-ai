package com.notepad.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CalendarMarksVO {

    private String month;
    private List<String> dates;
}
