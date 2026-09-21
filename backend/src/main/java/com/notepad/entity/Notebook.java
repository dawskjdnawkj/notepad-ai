package com.notepad.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notebook")
public class Notebook {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0 正常 / 删除时间戳（epoch 秒）。
     * 不能像别的表那样写死 1 —— 唯一键 uk_user_name(user_id, name, deleted) 下，
     * 两行已删除的同名记录会撞键，名字就永远复用不了了。
     */
    @TableLogic(value = "0", delval = "UNIX_TIMESTAMP(NOW())")
    private Long deleted;
}
