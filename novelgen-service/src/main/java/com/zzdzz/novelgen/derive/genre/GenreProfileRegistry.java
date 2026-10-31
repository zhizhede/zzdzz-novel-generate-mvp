package com.zzdzz.novelgen.derive.genre;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 类型插件注册表；新 run 只接受注册表中存在的类型。
 */
@Component
public class GenreProfileRegistry {

    private final Map<String, GenreProfile<?>> profiles;

    /** 按插件 genre() 注册；重复类型直接失败，避免运行期静默择一。 */
    public GenreProfileRegistry(List<? extends GenreProfile<?>> available) {
        Map<String, GenreProfile<?>> map = new LinkedHashMap<>();
        for (GenreProfile<?> profile : available) {
            String key = normalize(profile.genre());
            if (map.putIfAbsent(key, profile) != null) {
                throw new IllegalStateException("重复类型插件：" + key);
            }
        }
        this.profiles = Map.copyOf(map);
    }

    /** 取类型插件；未知类型抛 IllegalArgumentException。 */
    public GenreProfile<?> require(String genre) {
        GenreProfile<?> profile = profiles.get(normalize(genre));
        if (profile == null) {
            throw new IllegalArgumentException("Unknown genre: " + genre);
        }
        return profile;
    }

    static String normalize(String genre) {
        return genre == null ? "" : genre.strip().toUpperCase(Locale.ROOT);
    }
}
