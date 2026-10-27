package com.huidu.farmersdelight.compat;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads the same player content used by the standalone pack through HandbookCore's public API. */
final class HandbookContent {
    private static final String API = "com.example.handbookcore.api.";
    private final ClassLoader loader;
    private final Map<String, Class<?>> types = new HashMap<>();
    private final Map<String, Constructor<?>> constructors = new HashMap<>();
    private final Method vanillaIcon;
    private final Method craftEngineItem;

    HandbookContent(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        vanillaIcon = type("HandbookIcon").getMethod("vanilla", Material.class);
        craftEngineItem = type("HandbookItem").getMethod("craftEngine", String.class, Material.class);
    }

    Definition read(InputStream resource) throws IOException, InvalidConfigurationException, ReflectiveOperationException {
        if (resource == null) throw new IOException("Missing bundled handbook/farmersdelight.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        Data root = new Data(yaml.getValues(false));
        List<Object> categories = new ArrayList<>();
        for (Object raw : root.list("categories")) {
            Data category = data(raw);
            Object icon = vanillaIcon.invoke(null, material(category.text("icon", "BOOK")));
            categories.add(create("HandbookCategory",
                    new Class<?>[]{NamespacedKey.class, String.class, String.class, type("HandbookIcon"), int.class, String.class},
                    category.key("id"), category.required("title"), category.text("description", ""), icon,
                    category.integer("order", 0), category.text("permission", "")));
        }
        List<Object> entries = new ArrayList<>();
        for (Object raw : root.list("entries")) {
            Data entry = data(raw);
            List<Object> pages = new ArrayList<>();
            for (Object rawPage : entry.list("pages")) {
                Data page = data(rawPage);
                List<Object> blocks = new ArrayList<>();
                for (Object rawBlock : page.list("blocks")) blocks.add(block(rawBlock));
                pages.add(create("HandbookPage", new Class<?>[]{String.class, List.class},
                        page.text("title", ""), blocks));
            }
            List<String> keywords = new ArrayList<>();
            for (Object keyword : entry.list("keywords")) {
                if (!(keyword instanceof String text)) throw new IllegalArgumentException("keywords must contain text");
                keywords.add(text);
            }
            entries.add(create("HandbookEntry",
                    new Class<?>[]{NamespacedKey.class, NamespacedKey.class, String.class, String.class,
                            List.class, int.class, String.class, List.class},
                    entry.key("id"), entry.key("category"), entry.required("title"), entry.text("summary", ""),
                    keywords, entry.integer("order", 0), entry.text("permission", ""), pages));
        }
        return new Definition(create("HandbookContribution", new Class<?>[]{List.class, List.class}, categories, entries),
                entries.size());
    }

    private Object block(Object raw) throws ReflectiveOperationException {
        if (raw instanceof String text) return create("HandbookBlock$Text", new Class<?>[]{String.class}, text);
        Data value = data(raw);
        return switch (value.required("type")) {
            case "text" -> create("HandbookBlock$Text", new Class<?>[]{String.class}, value.required("text"));
            case "link" -> create("HandbookBlock$Link", new Class<?>[]{String.class, NamespacedKey.class},
                    value.required("label"), value.key("entry"));
            case "command" -> create("HandbookBlock$Command", new Class<?>[]{String.class, String.class, boolean.class},
                    value.required("label"), value.required("command"), value.bool("close-before-run", true));
            case "space" -> create("HandbookBlock$Space", new Class<?>[]{int.class}, value.integer("lines", 1));
            case "item" -> {
                Object item = craftEngineItem.invoke(null, value.required("ce-item"), material(value.required("material")));
                yield create("HandbookBlock$Item", new Class<?>[]{type("HandbookItem"), String.class},
                        item, value.required("caption"));
            }
            default -> throw new IllegalArgumentException("Unsupported handbook block: " + value.required("type"));
        };
    }

    private Object create(String name, Class<?>[] signature, Object... arguments) throws ReflectiveOperationException {
        Constructor<?> constructor = constructors.get(name);
        if (constructor == null) {
            constructor = type(name).getConstructor(signature);
            constructors.put(name, constructor);
        }
        return constructor.newInstance(arguments);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        Class<?> type = types.get(name);
        if (type == null) {
            type = Class.forName(API + name, true, loader);
            types.put(name, type);
        }
        return type;
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("Invalid handbook material: " + name);
        }
        return material;
    }

    private static Data data(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException("Handbook element must be a mapping");
        return new Data(map);
    }

    record Definition(Object contribution, int entryCount) { }

    private record Data(Map<?, ?> values) {
        String text(String key, String fallback) {
            Object value = values.containsKey(key) ? values.get(key) : fallback;
            if (!(value instanceof String text)) throw new IllegalArgumentException(key + " must be text");
            return text;
        }
        String required(String key) {
            String value = text(key, "");
            if (value.isBlank()) throw new IllegalArgumentException("Missing handbook " + key);
            return value;
        }
        NamespacedKey key(String field) {
            String raw = required(field);
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key == null || !raw.contains(":")) throw new IllegalArgumentException("Invalid handbook ID: " + raw);
            return key;
        }
        List<?> list(String key) {
            if (!(values.get(key) instanceof List<?> list)) throw new IllegalArgumentException(key + " must be a list");
            return list;
        }
        int integer(String key, int fallback) {
            Object value = values.containsKey(key) ? values.get(key) : fallback;
            if (!(value instanceof Integer integer)) throw new IllegalArgumentException(key + " must be an integer");
            return integer;
        }
        boolean bool(String key, boolean fallback) {
            Object value = values.containsKey(key) ? values.get(key) : fallback;
            if (!(value instanceof Boolean bool)) throw new IllegalArgumentException(key + " must be a boolean");
            return bool;
        }
    }
}
