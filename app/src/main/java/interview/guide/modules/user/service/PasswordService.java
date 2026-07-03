package interview.guide.modules.user.service;

import java.util.regex.Pattern;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户密码哈希与校验服务
 */
@Service
public class PasswordService {

    private static final int BCRYPT_STRENGTH = 12;
    private static final Pattern BCRYPT_PATTERN = Pattern.compile(
            "^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$"
    );

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    /**
     * 为明文密码生成 BCrypt 哈希
     */
    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 校验明文密码是否匹配 BCrypt 哈希
     */
    public boolean matches(String rawPassword, String encoded) {
        if (rawPassword == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        if (!BCRYPT_PATTERN.matcher(encoded).matches()) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, encoded);
    }
}
