package com.notepad.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.notepad.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 只取 token 版本号，供拦截器逐请求校验（比 selectById 少查一堆列）
     * <p>
     * 自定义 SQL 不会被 MyBatis-Plus 注入逻辑删除条件，deleted = 0 必须显式写
     */
    @Select("SELECT token_version FROM `user` WHERE id = #{userId} AND deleted = 0")
    Integer selectTokenVersion(@Param("userId") Long userId);

    /**
     * 登出：token 版本号 +1，该用户已签发的所有 token 立即失效
     */
    @Update("UPDATE `user` SET token_version = token_version + 1 WHERE id = #{userId} AND deleted = 0")
    int bumpTokenVersion(@Param("userId") Long userId);

    /**
     * 改密码 / 重置密码：改密与吊销写在同一条 UPDATE 里，
     * 天然不会出现「密码改了但旧 token 还能用」的中间态
     */
    @Update("""
            UPDATE `user` SET password = #{password}, token_version = token_version + 1
            WHERE id = #{userId} AND deleted = 0
            """)
    int updatePasswordAndBumpVersion(@Param("userId") Long userId, @Param("password") String password);
}
