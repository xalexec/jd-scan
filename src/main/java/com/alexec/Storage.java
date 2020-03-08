package com.alexec;

import com.alexec.model.Config;
import com.alexec.model.Goods;
import org.apache.http.cookie.Cookie;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Storage {
    public static Config config = new Config();
    public static final Map<String, Goods> goodsMap = new ConcurrentHashMap<>();

    public static Cookie getCookie(String key) {
        if (null == config.getBasicCookieStore()) {
            return null;
        }
        for (Cookie cookie : config.getBasicCookieStore().getCookies()) {
            if (key.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }
}
