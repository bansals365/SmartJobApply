package com.autoapply.util;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class Config {
    public static class Run {
        private boolean headless;
        private boolean manualCaptcha;
        private int daily_max_applications;
        private double random_delay_sec_min;
        private double random_delay_sec_max;
        // getters/setters
        public boolean isHeadless() { return headless; }
        public void setHeadless(boolean h) { headless = h; }

        public boolean isManualCaptcha() { return manualCaptcha; }
        public void setManualCaptcha(boolean manualCaptcha) { this.manualCaptcha = manualCaptcha; }

        public int getDaily_max_applications() { return daily_max_applications; }
        public void setDaily_max_applications(int d) { daily_max_applications = d; }

        public double getRandom_delay_sec_min() { return random_delay_sec_min; }
        public void setRandom_delay_sec_min(double v) { random_delay_sec_min = v; }

        public double getRandom_delay_sec_max() { return random_delay_sec_max; }
        public void setRandom_delay_sec_max(double v) { random_delay_sec_max = v; }
    }
    public static class Filters {
        private List<String> include_keywords;
        private List<String> exclude_keywords;
        private List<String> exclude_companies = new java.util.ArrayList<>();
        public List<String> getInclude_keywords() { return include_keywords; }
        public List<String> getExclude_keywords() { return exclude_keywords; }
        public List<String> getExclude_companies() { return exclude_companies; }
        public void setExclude_companies(List<String> exclude_companies) { this.exclude_companies = exclude_companies; }

        public void setInclude_keywords(List<String> include_keywords) {
            this.include_keywords = include_keywords;
        }

        public void setExclude_keywords(List<String> exclude_keywords) {
            this.exclude_keywords = exclude_keywords;
        }
    }
    public static class Search {
        private List<String> keywords;
        private String location;
        public List<String> getKeywords() { return keywords; }
        public String getLocation() { return location; }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }
    public static class Site {
        public static class Naukri {
            private int max_scroll_pages;
            private boolean easy_apply_only;
            private int min_experience = 3;
            private int job_age_days = 3;
            private List<String> preferred_locations = new java.util.ArrayList<>();

            public int getMax_scroll_pages() { return max_scroll_pages; }
            public boolean isEasy_apply_only() { return easy_apply_only; }
            public int getMin_experience() { return min_experience; }
            public int getJob_age_days() { return job_age_days; }
            public List<String> getPreferred_locations() { return preferred_locations; }

            public void setMax_scroll_pages(int max_scroll_pages) { this.max_scroll_pages = max_scroll_pages; }
            public void setEasy_apply_only(boolean easy_apply_only) { this.easy_apply_only = easy_apply_only; }
            public void setMin_experience(int min_experience) { this.min_experience = min_experience; }
            public void setJob_age_days(int job_age_days) { this.job_age_days = job_age_days; }
            public void setPreferred_locations(List<String> preferred_locations) { this.preferred_locations = preferred_locations; }
        }
        public static class LinkedIn {
            private boolean easy_apply_only;
            private String job_query;
            private String location;
            public boolean isEasy_apply_only() { return easy_apply_only; }
            public String getJob_query() { return job_query; }
            public String getLocation() { return location; }

            public void setEasy_apply_only(boolean easy_apply_only) {
                this.easy_apply_only = easy_apply_only;
            }

            public void setJob_query(String job_query) {
                this.job_query = job_query;
            }

            public void setLocation(String location) {
                this.location = location;
            }
        }
        public static class NaukriGulf {
            private int max_pages = 5;
            private int experience_years = 4;
            // Apply if the job's minimum is at most experience_years + this many years
            private int experience_tolerance = 2;
            // Gulf locations to search, e.g. "uae", "dubai", "saudi-arabia", "qatar".
            // Empty list = search all Gulf locations.
            private List<String> locations = new java.util.ArrayList<>();
            // Skip cards without the "Easy Apply" tag (external / company-site jobs)
            private boolean easy_apply_only = true;
            public boolean isEasy_apply_only() { return easy_apply_only; }
            public void setEasy_apply_only(boolean easy_apply_only) { this.easy_apply_only = easy_apply_only; }
            // Searched one by one; empty = use search.keywords
            private List<String> keywords = new java.util.ArrayList<>();

            public List<String> getKeywords() { return keywords; }
            public void setKeywords(List<String> keywords) { this.keywords = keywords; }
            public int getMax_pages() { return max_pages; }
            public int getExperience_years() { return experience_years; }
            public int getExperience_tolerance() { return experience_tolerance; }
            public List<String> getLocations() { return locations; }

            public void setMax_pages(int max_pages) { this.max_pages = max_pages; }
            public void setExperience_years(int experience_years) { this.experience_years = experience_years; }
            public void setExperience_tolerance(int experience_tolerance) { this.experience_tolerance = experience_tolerance; }
            public void setLocations(List<String> locations) { this.locations = locations; }
        }
        private Naukri naukri;
        private LinkedIn linkedin;
        private NaukriGulf naukrigulf = new NaukriGulf();
        public Naukri getNaukri() { return naukri; }
        public LinkedIn getLinkedin() { return linkedin; }
        public NaukriGulf getNaukrigulf() { return naukrigulf; }
        public void setNaukrigulf(NaukriGulf naukrigulf) { this.naukrigulf = naukrigulf; }

        public void setNaukri(Naukri naukri) {
            this.naukri = naukri;
        }

        public void setLinkedin(LinkedIn linkedin) {
            this.linkedin = linkedin;
        }
    }

    private Run run;
    private Filters filters;
    private Search search;
    private Site site;

    public Run getRun() { return run; }

    public void setRun(Run run) { this.run = run; }
    public Filters getFilters() { return filters; }
    public void setFilters(Filters filters) { this.filters = filters; }

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }

    public Site getSite() { return site; }
    public void setSite(Site site) { this.site = site; }

    public static Config load() {
        Yaml yaml = new Yaml();
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream("config.yaml")) {
            return yaml.loadAs(in, Config.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.yaml: " + e.getMessage(), e);
        }
    }
}
