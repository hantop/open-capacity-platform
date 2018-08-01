package com.open.capacity.security.service;

import com.open.capacity.security.dto.LoginUser;
import com.open.capacity.security.dto.Token;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.security.Key;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * token存到redis的实现类<br>
 * jwt实现的token
 *
 * @author owen 624191343@qq.com
 */
@Primary
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String LOGIN_USER_KEY = "LOGIN_USER_KEY";
    private static Key KEY = null;

    //token过期秒数
    @Value("${token.expire.seconds}")
    private Integer expireSeconds;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private SysLogService logService;

    //私钥
    @Value("${token.jwtSecret}")
    private String jwtSecret;

    /**
     * 根据登陆用户生成token
     * @param loginUser 登陆用户
     * @return
     */
    public Token saveToken(LoginUser loginUser) {
        loginUser.setToken(UUID.randomUUID().toString());
        String jwtToken = createJWTToken(loginUser);
        loginUser.setJwtToken(jwtToken);
        cacheLoginUser(loginUser);
        // 登陆日志
        logService.save(loginUser.getId(), "登陆", true, null);
        return new Token(jwtToken, loginUser.getLoginTime());
    }

    /**
     * 生成jwt
     *
     * @param loginUser 登陆用户
     * @return
     */
    private String createJWTToken(LoginUser loginUser) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(LOGIN_USER_KEY, loginUser.getToken());// 放入一个随机字符串，通过该串可找到登陆用户

        String jwtToken = Jwts.builder().setClaims(claims).signWith(SignatureAlgorithm.HS256, getKeyInstance())
                .compact();

        return jwtToken;
    }

    /**
     * 缓存登陆用户的信息
     * @param loginUser 登陆用户
     */
    private void cacheLoginUser(LoginUser loginUser) {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireSeconds * 1000);
        // 根据uuid将loginUser缓存
        redisTemplate.boundValueOps(getTokenKey(loginUser.getToken())).set(loginUser, expireSeconds, TimeUnit.SECONDS);
    }

    /**
     * 更新缓存的用户信息
     */
    public void refresh(LoginUser loginUser) {
        cacheLoginUser(loginUser);
    }

    /**
     * 根据token获取登陆用户
     * @param jwtToken token
     * @return
     */
    public LoginUser getLoginUser(String jwtToken) {
        String uuid = getUUIDFromJWT(jwtToken);
        if (uuid != null) {
            return (LoginUser) redisTemplate.boundValueOps(getTokenKey(uuid)).get();
        }
        return null;
    }


    /**
     * 删除token
     * @param jwtToken jwtToken
     * @return
     */
    public boolean deleteToken(String jwtToken) {
        String uuid = getUUIDFromJWT(jwtToken);
        if (uuid != null) {
            String key = getTokenKey(uuid);
            LoginUser loginUser = (LoginUser) redisTemplate.opsForValue().get(key);
            if (loginUser != null) {
                redisTemplate.delete(key);
                // 退出日志
                logService.save(loginUser.getId(), "退出", true, null);

                return true;
            }
        }

        return false;
    }

    /**
     * 获取token key
     * @param uuid 用户id
     * @return
     */
    private String getTokenKey(String uuid) {
        return "tokens:" + uuid;
    }

    /**
     * 获取key实力
     * @return
     */
    private Key getKeyInstance() {
        if (KEY == null) {
            synchronized (TokenService.class) {
                if (KEY == null) {// 双重锁
                    byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(jwtSecret);
                    KEY = new SecretKeySpec(apiKeySecretBytes, SignatureAlgorithm.HS256.getJcaName());
                }
            }
        }
        return KEY;
    }

    /**
     * 获取jwt的UUID
     * @param jwtToken
     * @return
     */
    private String getUUIDFromJWT(String jwtToken) {
        if ("null".equals(jwtToken) || StringUtils.isBlank(jwtToken)) {
            return null;
        }

        Map<String, Object> jwtClaims = null;
        try {
            jwtClaims = Jwts.parser().setSigningKey(getKeyInstance()).parseClaimsJws(jwtToken).getBody();
            return MapUtils.getString(jwtClaims, LOGIN_USER_KEY);
        } catch (ExpiredJwtException e) {
            log.error("{}已过期", jwtToken);
        } catch (Exception e) {
            log.error("{}", e);
        }
        return null;
    }
}
