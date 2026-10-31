package com.zzdzz.novelgen.derive.genre;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 类型合同编解码器注册表。 */
@Component
public class GenreContractCodecRegistry {

    private final Map<String, GenreContractCodec<?>> codecs;

    public GenreContractCodecRegistry(List<GenreContractCodec<?>> available) {
        Map<String, GenreContractCodec<?>> map = new LinkedHashMap<>();
        for (GenreContractCodec<?> codec : available) {
            String key = normalize(codec.genre());
            if (map.putIfAbsent(key, codec) != null) {
                throw new IllegalStateException("重复类型 codec：" + key);
            }
        }
        this.codecs = Map.copyOf(map);
    }

    public GenreContractCodec<?> require(String genre) {
        GenreContractCodec<?> codec = codecs.get(normalize(genre));
        if (codec == null) {
            throw new IllegalArgumentException("Unknown genre codec: " + genre);
        }
        return codec;
    }

    private static String normalize(String genre) {
        return genre == null ? "" : genre.strip().toUpperCase(Locale.ROOT);
    }
}
