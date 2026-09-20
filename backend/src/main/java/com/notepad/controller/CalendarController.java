package com.notepad.controller;

import com.notepad.common.Result;
import com.notepad.common.UserContext;
import com.notepad.vo.CalendarMarksVO;
import com.notepad.service.NoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
public class CalendarController {

    private final NoteService noteService;

    @GetMapping("/marks")
    public Result<CalendarMarksVO> marks(@RequestParam("month") String month) {
        return Result.ok(noteService.calendarMarks(UserContext.getUserId(), month));
    }
}
