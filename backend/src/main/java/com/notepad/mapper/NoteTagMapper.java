package com.notepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notepad.entity.NoteTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface NoteTagMapper extends BaseMapper<NoteTag> {

    @Delete("DELETE FROM note_tag WHERE note_id = #{noteId}")
    int deleteByNoteId(@Param("noteId") Long noteId);

    @Delete("DELETE FROM note_tag WHERE tag_id = #{tagId}")
    int deleteByTagId(@Param("tagId") Long tagId);

    @Select("SELECT tag_id FROM note_tag WHERE note_id = #{noteId}")
    List<Long> selectTagIdsByNoteId(@Param("noteId") Long noteId);

    @Select("""
            SELECT COUNT(*) FROM note_tag nt
            JOIN note n ON n.id = nt.note_id AND n.deleted = 0
            WHERE nt.tag_id = #{tagId}
            """)
    long countByTagId(@Param("tagId") Long tagId);

    @Delete("""
            <script>
            DELETE FROM note_tag WHERE note_id IN
            <foreach collection='noteIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int deleteByNoteIds(@Param("noteIds") List<Long> noteIds);

    @Delete("""
            DELETE FROM note_tag WHERE note_id IN (
                SELECT id FROM note WHERE deleted = 1 AND delete_time < #{before}
            )
            """)
    int deleteExpiredTrashTags(@Param("before") LocalDateTime before);
}
