package com.autoapply.util;

import org.yaml.snakeyaml.Yaml;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads personal answer values from a gitignored "answers.yaml" file so that
 * private data (CTC, college, company, DOB, etc.) never lives inside the code.
 *
 * Lookup order:
 *   1. answers.yaml in the working directory (user-editable, gitignored)
 *   2. answers.yaml on the classpath (if bundled)
 * If neither is found, the built-in fallback passed to get() is used, so the
 * bot still runs out-of-the-box with generic example values.
 *
 * To use: copy answers.example.yaml -> answers.yaml and fill in your values.
 */
public class Answers {
    private static Map<String, Object> data;

    public static String get(String key, String fallback) {
        if (data == null) load();
        Object v = data.get(key);
        return (v != null && !String.valueOf(v).isBlank()) ? String.valueOf(v) : fallback;
    }

    private static synchronized void load() {
        if (data != null) return;
        Yaml yaml = new Yaml();
        try {
            File f = new File("answers.yaml");
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) {
                    data = yaml.load(in);
                }
            } else {
                try (InputStream in = Answers.class.getClassLoader().getResourceAsStream("answers.yaml")) {
                    if (in != null) data = yaml.load(in);
                }
            }
        } catch (Exception e) {
            System.out.println("[Answers] Could not read answers.yaml: " + e.getMessage());
        }
        if (data == null) {
            data = new HashMap<>();
            System.out.println("[Answers] answers.yaml not found. "
                    + "Copy answers.example.yaml to answers.yaml and fill your values. "
                    + "Using built-in example defaults for now.");
        }
    }
}
