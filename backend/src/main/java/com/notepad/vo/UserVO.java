package com.notepad.vo;

import com.notepad.entity.User;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;

    public static UserVO from(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getAvatar());
    }
}
