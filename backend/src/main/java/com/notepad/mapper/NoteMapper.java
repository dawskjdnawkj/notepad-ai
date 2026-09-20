package com.notepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.notepad.entity.Note;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NoteMapper extends BaseMapper<Note> {

    /**
     * 正常笔记分页查询（支持笔记本/标签/关键词/创建日期筛选）
     */
    @Select("""
            <script>
            SELECT n.* FROM note n
            <if test='tagId != null'>
                JOIN note_tag nt ON nt.note_id = n.id AND nt.tag_id = #{tagId}
            </if>
            WHERE n.user_id = #{userId} AND n.deleted = 0
            <if test='notebookId != null'>AND n.notebook_id = #{notebookId}</if>
            <if test='keyword != null and keyword != ""'>
                AND MATCH(n.title, n.content_text) AGAINST(#{keyword} IN BOOLEAN MODE)
            </if>
            <if test='start != null'>AND n.create_time &gt;= #{start}</if>
            <if test='end != null'>AND n.create_time &lt; #{end}</if>
            ORDER BY n.pinned DESC, n.update_time DESC
            </script>
            """)
    IPage<Note> selectNotePage(Page<Note> page,
                               @Param("userId") Long userId,
                               @Param("notebookId") Long notebookId,
                               @Param("tagId") Long tagId,
                               @Param("keyword") String keyword,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    /**
     * 全文索引尚未迁移时的兼容查询，仅由服务层在识别到缺少 FULLTEXT 索引后调用。
     */
    @Select("""
            <script>
            SELECT n.* FROM note n
            <if test='tagId != null'>
                JOIN note_tag nt ON nt.note_id = n.id AND nt.tag_id = #{tagId}
            </if>
            WHERE n.user_id = #{userId} AND n.deleted = 0
            <if test='notebookId != null'>AND n.notebook_id = #{notebookId}</if>
            <if test='keyword != null and keyword != ""'>
                AND (n.title LIKE CONCAT('%', #{keyword}, '%')
                    OR n.content_text LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test='start != null'>AND n.create_time &gt;= #{start}</if>
            <if test='end != null'>AND n.create_time &lt; #{end}</if>
            ORDER BY n.pinned DESC, n.update_time DESC
            </script>
            """)
    IPage<Note> selectNotePageLike(Page<Note> page,
                                   @Param("userId") Long userId,
                                   @Param("notebookId") Long notebookId,
                                   @Param("tagId") Long tagId,
                                   @Param("keyword") String keyword,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    /**
     * 回收站分页查询
     */
    @Select("""
            SELECT n.* FROM note n
            WHERE n.user_id = #{userId} AND n.deleted = 1
            ORDER BY n.delete_time DESC
            """)
    IPage<Note> selectTrashPage(Page<Note> page, @Param("userId") Long userId);

    @Select("SELECT * FROM note WHERE id = #{id} AND user_id = #{userId} AND deleted = 1")
    Note selectTrashedById(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT id FROM note WHERE user_id = #{userId} AND deleted = 0 AND notebook_id = #{notebookId}")
    List<Long> selectIdsByNotebook(@Param("userId") Long userId, @Param("notebookId") Long notebookId);

    @Select("SELECT id FROM note WHERE user_id = #{userId} AND deleted = #{deleted}")
    List<Long> selectIdsByUser(@Param("userId") Long userId, @Param("deleted") int deleted);

    @Select("SELECT id, user_id FROM note WHERE deleted = 1 AND delete_time < #{before}")
    List<Note> selectExpiredNotes(@Param("before") LocalDateTime before);

    @Update("UPDATE note SET deleted = 1, delete_time = NOW() WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int toTrash(@Param("userId") Long userId, @Param("id") Long id);

    @Update("UPDATE note SET deleted = 1, delete_time = NOW() WHERE notebook_id = #{notebookId} AND user_id = #{userId} AND deleted = 0")
    int toTrashByNotebook(@Param("userId") Long userId, @Param("notebookId") Long notebookId);

    @Update("""
            UPDATE note SET deleted = 0, delete_time = NULL, notebook_id = #{notebookId}
            WHERE id = #{id} AND user_id = #{userId} AND deleted = 1
            """)
    int restore(@Param("userId") Long userId, @Param("id") Long id, @Param("notebookId") Long notebookId);

    @Delete("DELETE FROM note WHERE id = #{id} AND user_id = #{userId}")
    int deleteForever(@Param("userId") Long userId, @Param("id") Long id);

    @Delete("DELETE FROM note WHERE user_id = #{userId} AND deleted = 1")
    int clearTrash(@Param("userId") Long userId);

    @Delete("DELETE FROM note WHERE deleted = 1 AND delete_time < #{before}")
    int deleteExpiredTrash(@Param("before") LocalDateTime before);

    /**
     * 日历标记：某月有笔记创建的日期
     */
    @Select("""
            SELECT DISTINCT DATE_FORMAT(create_time, '%Y-%m-%d') AS d
            FROM note
            WHERE user_id = #{userId} AND deleted = 0
              AND create_time >= #{start} AND create_time < #{end}
            ORDER BY d
            """)
    List<String> selectCalendarDates(@Param("userId") Long userId,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end);
}
