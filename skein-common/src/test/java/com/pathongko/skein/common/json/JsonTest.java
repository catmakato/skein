package com.pathongko.skein.common.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {

    @Test
    void roundTripsNestedStructures() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("n", 42L);
        obj.put("f", 3.5);
        obj.put("b", true);
        obj.put("nul", null);
        obj.put("s", "line1\nquote\"end\\slash");
        obj.put("arr", List.of(1L, 2L, "three"));

        String encoded = Json.write(obj);
        Object parsed = Json.read(encoded);

        assertThat(Json.write(parsed)).isEqualTo(encoded);
    }

    @Test
    void keysAreSortedForDeterministicOutput() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("z", 1L);
        obj.put("a", 2L);
        assertThat(Json.write(obj)).isEqualTo("{\"a\":2,\"z\":1}");
    }

    @Test
    void parsesEscapesAndNumbers() {
        Map<?, ?> m = (Map<?, ?>) Json.read("{\"s\":\"a\\tb\",\"i\":7,\"d\":1.5}");
        assertThat(m.get("s")).isEqualTo("a\tb");
        assertThat(m.get("i")).isEqualTo(7L);
        assertThat(m.get("d")).isEqualTo(1.5);
    }

    @Test
    void rejectsTrailingContent() {
        assertThatThrownBy(() -> Json.read("{} garbage")).isInstanceOf(JsonException.class);
    }
}
