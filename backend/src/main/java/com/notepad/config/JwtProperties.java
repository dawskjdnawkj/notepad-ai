package com.notepad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 签名密钥，生产环境必须更换且长度不少于 32 字符 */
    private String secret;

    /** token 有效期（天） */
    private long expireDays = 7;
}
