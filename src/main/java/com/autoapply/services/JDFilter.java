package com.autoapply.services;

import com.autoapply.util.Config;
import java.util.Locale;

public class JDFilter {
    private final Config cfg;

    public JDFilter(Config cfg) { this.cfg = cfg; }

    // Fast check on title only — run BEFORE opening the job tab
    public boolean titleAllowed(String title) {
        if (title == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        // Block if title has any exclude keyword
        boolean excluded = cfg.getFilters().getExclude_keywords().stream()
                .anyMatch(k -> t.contains(k.toLowerCase(Locale.ROOT)));
        return !excluded;
    }

    public boolean companyAllowed(String company) {
        if (company == null || company.isBlank()) return true;
        String c = company.toLowerCase(Locale.ROOT);
        return cfg.getFilters().getExclude_companies().stream()
                .noneMatch(k -> c.contains(k.toLowerCase(Locale.ROOT)));
    }

    public boolean titleHasInclude(String title) {
        if (title == null) return false;
        String t = title.toLowerCase(Locale.ROOT);
        return cfg.getFilters().getInclude_keywords().stream()
                .anyMatch(k -> t.contains(k.toLowerCase(Locale.ROOT)));
    }

    // Full check — exclude only on title, include on title+JD
    public boolean shouldApply(String title, String jdText) {
        String t = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        String jd = (jdText == null ? "" : jdText).toLowerCase(Locale.ROOT);

        // Exclude keywords checked on title only — avoids blocking Java jobs that mention ML in JD
        boolean titleExcluded = cfg.getFilters().getExclude_keywords().stream()
                .anyMatch(k -> t.contains(k.toLowerCase(Locale.ROOT)));
        if (titleExcluded) return false;

        // Include keyword must appear somewhere in title or JD
        String combined = t + " " + jd;
        return cfg.getFilters().getInclude_keywords().stream()
                .anyMatch(k -> combined.contains(k.toLowerCase(Locale.ROOT)));
    }
}