package com.notepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notepad.entity.Reminder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ReminderMapper extends BaseMapper<Reminder> {

    @Update("""
            UPDATE reminder SET status = 2, update_time = NOW()
            WHERE note_id = #{noteId} AND user_id = #{userId} AND status = 0
            """)
    int cancelPending(@Param("noteId") Long noteId, @Param("userId") Long userId);

    @Update("""
            <script>
            UPDATE reminder SET status = 2, update_time = NOW()
            WHERE user_id = #{userId} AND status = 0 AND note_id IN
            <foreach collection='noteIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int cancelByNoteIds(@Param("userId") Long userId, @Param("noteIds") List<Long> noteIds);
}
