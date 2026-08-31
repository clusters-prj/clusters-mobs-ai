package com.kaguya.custommobs.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AiBehaviorConfig {
    private final String type;
    private final Map<String, Object> params;

    public AiBehaviorConfig(String type, Map<String, Object> params) {
        this.type = type;
        this.params = params == null ? Collections.emptyMap() : new HashMap<>(params);
    }

    public String getType() { return type; }

    /** YAMLに文字列で書かれていてもClassCastExceptionにならないよう、緩めに解釈する */
    public double getDouble(String key, double def) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public int getInt(String key, int def) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public boolean getBoolean(String key, boolean def) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    public String getString(String key, String def) {
        Object v = params.get(key);
        return v == null ? def : v.toString();
    }
}
