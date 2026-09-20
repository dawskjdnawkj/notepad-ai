package com.notepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notepad.entity.NoteImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NoteImageMapper extends BaseMapper<NoteImage> {

    /**
     * 把当前用户下的临时图片（note_id 为空）绑定到指定笔记
     */
    @Update("""
            <script>
            UPDATE note_image SET note_id = #{noteId}
            WHERE user_id = #{userId} AND note_id IS NULL
            AND url IN
            <foreach collection="urls" item="u" open="(" separator="," close=")">
                #{u}
            </foreach>
            </script>
            """)
    int bindToNote(@Param("userId") Long userId, @Param("noteId") Long noteId, @Param("urls") List<String> urls);

    /**
     * 物理删除（绕过逻辑删除），用于孤儿图片清理
     */
    @Delete("""
            <script>
            DELETE FROM note_image WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int physicalDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 物理删除已逻辑删除且超过保留期的记录（清理累积的软删记录）
     */
    @Delete("DELETE FROM note_image WHERE deleted = 1 AND create_time < #{before}")
    int purgeDeleted(@Param("before") LocalDateTime before);
}
