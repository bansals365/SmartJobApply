package com.autoapply.services;

import com.autoapply.util.Answers;
import org.openqa.selenium.WebElement;
import java.util.List;

public class NaukriAnswers {

    public static String getAnswer(String question) {
        if (question == null) return "Yes";
        String q = question.toLowerCase().trim();

        // === Contact — checked first so "number" questions don't hit other rules ===
        String contact = contactAnswer(q);
        if (contact != null) return contact;

        // === Name — may be combined with qualification / passing year in one question ===
        String name = Answers.get("full_name", "");
        if (!name.isEmpty() && q.matches(".*\\b(full name|your name|candidate name|name as per)\\b.*")) {
            StringBuilder sb = new StringBuilder(name);
            if (q.contains("qualification") || q.contains("education") || q.contains("degree")) {
                sb.append(", ").append(Answers.get("highest_qualification", "B.Tech in Computer Science"));
            }
            if (q.matches(".*\\b(pass(ing|ed)? ?out|passing year|year of passing|graduation year|pass out year)\\b.*")) {
                sb.append(", ").append(Answers.get("passing_year", "2021"));
            }
            return sb.toString();
        }

        // === "Which of these locations ...?" — a city list, never a Yes/No ===
        boolean openQ = q.matches("^(which|what|list|mention|specify|(please )?(select|choose))\\b.*");
        if (openQ && q.matches(".*\\b(locations?|city|cities)\\b.*")) {
            return Answers.get("preferred_location", "Bangalore");
        }

        // === Recent application / interview with the company — No unless known otherwise ===
        if (q.matches(".*\\b(applied|interviewed|appeared for (an )?interview)\\b.*\\b(with|for|at|in)\\b.*")
                && q.matches(".*\\b(past|last|previous|recent(ly)?|before|earlier)\\b.*")) {
            return "No";
        }

        // === Previous employment at a specific company ("Previously employed by Cognizant") ===
        // Checked before the skill rules, where "have you worked" means a technology.
        String employer = previousEmployerAnswer(q);
        if (employer != null) return employer;

        // === Face-to-face / walk-in interview availability ===
        if (q.matches(".*\\b(face to face|face-to-face|f2f|walk-?in|in-person|in person)\\b.*")) {
            return Answers.get("f2f_interview", "Yes");
        }

        // === Skill checks (yes/no) — Yes for known skills, unknown ones follow unknown_skill_years ===
        if (!openQ && !q.contains("how many") && (q.contains("do you have experience") || q.contains("do you know")
                || q.contains("are you familiar") || q.contains("have you worked") || q.contains("have you used")
                || q.contains("proficient in"))) {
            return knownSkillYears(q) != null || !unknownSkillYears().equals("0") ? "Yes" : "No";
        }

        // === Experience ===
        String exp = experienceAnswer(q);
        if (exp != null) return exp;

        // === CTC ===
        if (q.contains("current ctc") || q.contains("current salary") || q.contains("present ctc") || q.contains("current package")) {
            return Answers.get("current_ctc", "12");
        }
        if (q.contains("expected ctc") || q.contains("expected salary") || q.contains("salary expectation") || q.contains("expected package")) {
            return Answers.get("expected_ctc", "18");
        }
        if (q.contains("hike") || q.contains("increment expected")) {
            return "50";
        }

        // === Notice Period ===
        String notice = noticeAnswer(q);
        if (notice != null) return notice;

        // === Location ===
        if (q.contains("current location") || q.contains("current city") || q.contains("where are you based") || q.contains("your location")) {
            return Answers.get("current_location", "Bangalore");
        }
        if (q.contains("relocat") || q.contains("open to relocation")) {
            return relocationAnswer(q);
        }
        if (q.contains("preferred location") || q.contains("preferred city")) {
            return Answers.get("preferred_location", "Bangalore");
        }
        if (q.contains("location")) {
            return Answers.get("current_location", "Bangalore");
        }

        // === Work Mode ===
        if (q.contains("work from home") || q.contains("wfh")) {
            return "Yes";
        }
        if (q.contains("remote work") || q.contains("open to remote") || q.contains("remote opportunity")) {
            return "Yes";
        }
        if (q.contains("work mode") || q.contains("working mode") || q.contains("preferred mode")) {
            return "Hybrid";
        }
        if (q.contains("work from office") || q.contains("wfo") || q.contains("on-site") || q.contains("onsite")) {
            return "Yes";
        }
        if (q.contains("night shift") || q.contains("rotational shift") || q.contains("shift")) {
            return "Yes";
        }
        if (q.contains("remote")) {
            return "Yes";
        }

        // === Qualification ===
        if (q.contains("highest qualification") || q.contains("educational qualification")) {
            return "B.Tech";
        }
        if (q.contains("qualification") || q.contains("education")) {
            return "B.Tech in Computer Science";
        }
        if (q.contains("degree")) {
            return "B.Tech";
        }
        if (q.contains("10th") || q.contains("ssc")) {
            return Answers.get("tenth_percentage", "75");
        }
        if (q.contains("12th") || q.contains("hsc") || q.contains("intermediate")) {
            return Answers.get("twelfth_percentage", "75");
        }
        if (q.contains("cgpa") || q.contains("gpa")) {
            return Answers.get("cgpa", "7.5");
        }
        if (q.contains("graduation") || q.contains("percentage") || q.contains("aggregate")) {
            return Answers.get("cgpa", "7.5");
        }
        if (q.contains("college") || q.contains("university") || q.contains("institute")) {
            return Answers.get("college", "XYZ University");
        }
        if (q.contains("passing year") || q.contains("graduation year") || q.contains("year of passing") || q.contains("batch")) {
            return Answers.get("passing_year", "2021");
        }

        // === Current Job ===
        if (q.contains("current company") || q.contains("current employer") || q.contains("present company") || q.contains("current organization")) {
            return Answers.get("current_company", "Your Company");
        }
        if (q.contains("current designation") || q.contains("current role") || q.contains("current position") || q.contains("job title") || q.contains("designation")) {
            return Answers.get("current_designation", "Software Engineer");
        }
        if (q.contains("currently working") || q.contains("currently employed") || q.contains("are you working")) {
            return "Yes";
        }

        // === Skills ===
        if (q.contains("primary skill") || q.contains("main skill") || q.contains("core skill")) {
            return "Java, Spring Boot";
        }
        if (q.contains("secondary skill") || q.contains("other skill")) {
            return "REST APIs, SQL, Hibernate";
        }
        if (q.contains("skills") || q.contains("technical skills") || q.contains("tech stack")) {
            return "Java, Spring Boot, Hibernate, REST APIs, SQL, Git, AWS";
        }

        // === Personal ===
        if (q.contains("date of birth") || q.contains("dob")) {
            return Answers.get("dob", "01/01/2000");
        }
        if (q.contains("gender")) {
            return Answers.get("gender", "Male");
        }
        if (q.contains("marital")) {
            return Answers.get("marital_status", "Single");
        }
        if (q.contains("nationality") || q.contains("citizen")) {
            return "Indian";
        }
        if (q.contains("language")) {
            return "English, Hindi";
        }

        // === Misc ===
        if (q.contains("are you willing") || q.contains("are you open") || q.contains("are you comfortable") ||
                q.contains("are you okay") || q.contains("willingness")) {
            return "Yes";
        }
        if (q.contains("contract") || q.contains("contractual") || q.contains("c2h")) {
            return "Yes";
        }
        if (q.contains("freelance")) {
            return "Yes";
        }
        if (q.contains("bond") || q.contains("service agreement")) {
            return "No";
        }
        if (q.contains("weekend")) {
            return "Yes";
        }
        if (q.contains("travel")) {
            return "Yes";
        }
        if (q.contains("gap") || q.contains("career gap")) {
            return "No";
        }

        // Open "which / what ... ?" questions nothing above recognised (e.g. "Which T24
        // modules have you worked on?") — "Yes" is meaningless there.
        if (q.matches("^(which|what|list|mention|specify|name)\\b.*")) {
            return "Not applicable";
        }

        return "Yes";
    }

    // Pick the best matching clickable option from chatbot button list
    public static WebElement getBestOption(List<WebElement> options, String question) {
        String answer = getAnswer(question).toLowerCase().trim();
        // Exact match
        for (WebElement opt : options) {
            if (opt.getText().trim().toLowerCase().equals(answer)) return opt;
        }
        // Partial match
        for (WebElement opt : options) {
            String t = opt.getText().trim().toLowerCase();
            if (answer.contains(t) || t.contains(answer)) return opt;
        }
        // Yes / No preference
        if (answer.startsWith("yes")) {
            for (WebElement opt : options) {
                if (opt.getText().trim().toLowerCase().startsWith("yes")) return opt;
            }
        }
        if (answer.startsWith("no")) {
            for (WebElement opt : options) {
                if (opt.getText().trim().toLowerCase().startsWith("no")) return opt;
            }
        }
        return options.get(0);
    }

    // Notice-period options come as "30 days", "1 Month", "One month", ... — map
    // them to a common "N days" form so the answer matches whichever is offered.
    public static String normalizePeriod(String s) {
        return s.replaceAll("\\b(1|one)\\s*months?\\b", "30 days")
                .replaceAll("\\b(2|two)\\s*months?\\b", "60 days")
                .replaceAll("\\b(3|three)\\s*months?\\b", "90 days");
    }

    // Years per skill. Defaults below; answers.yaml "skill_years" overrides/extends them,
    // e.g. skill_years: "java:4, spring boot:3.5, kafka:2, redis:2".
    private static final String DEFAULT_SKILL_YEARS =
            "java:4, j2ee:4, spring boot:3.5, spring:3.5, microservices:3, aws:2, cloud:2, sql:4, database:4, "
            + "rest:4, api:4, hibernate:3, backend:4, back end:4, back-end:4";

    // Generic words that don't name a skill ("experience in IT", "relevant experience")
    private static final java.util.Set<String> GENERIC_EXP = java.util.Set.of(
            "total", "overall", "relevant", "it", "software", "development", "software development", "coding",
            "programming", "industry", "professional", "work", "the industry", "this field", "years", "end to end coding");

    static java.util.Map<String, String> skillYears() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String src : new String[]{DEFAULT_SKILL_YEARS, Answers.get("skill_years", "")}) {
            for (String pair : src.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2 && !kv[0].isBlank()) m.put(kv[0].trim().toLowerCase(), kv[1].trim());
            }
        }
        return m;
    }

    // Years for the longest known skill named in the question, or null
    static String knownSkillYears(String q) {
        String best = null, years = null;
        for (java.util.Map.Entry<String, String> e : skillYears().entrySet()) {
            if (q.matches(".*\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b.*")
                    && (best == null || e.getKey().length() > best.length())) {
                best = e.getKey();
                years = e.getValue();
            }
        }
        return years;
    }

    // Years claimed for skills not in the list. Defaults to total experience (the old
    // behaviour); set unknown_skill_years: "0" to answer honestly for unfamiliar tech.
    static String unknownSkillYears() {
        return Answers.get("unknown_skill_years", Answers.get("total_experience_years", "4"));
    }

    // Experience questions. Known skill → its years; a named but unknown skill →
    // unknown_skill_years; otherwise total experience. null when not an experience question.
    static String experienceAnswer(String q) {
        // Also catches recruiter typos ("expeience", "experiance") and the "exp" shorthand
        boolean expQ = q.contains("experience") || q.contains("how many years")
                || q.matches(".*\\b(exp\\w*nce|exp)\\b.*");
        if (!expQ) return null;
        String years = knownSkillYears(q);
        if (years == null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\b(?:in|into|as an?|as|with|on)\\s+([a-z0-9.+#/ -]+?)\\s*(?:\\?|$|\\(|,|developer|technolog)")
                    .matcher(q.substring(Math.max(0, q.indexOf("exp"))));
            String skill = m.find() ? m.group(1).trim() : "";
            boolean namedSkill = !skill.isEmpty() && !GENERIC_EXP.contains(skill);
            years = namedSkill ? unknownSkillYears() : Answers.get("total_experience_years", "4");
        }
        if (numeric(q)) return years.contains(".") ? years.substring(0, years.indexOf('.')) : years;
        return years.equals("0") ? "No experience" : years + " years";
    }

    // "Are you living in or ready to relocate to Chennai?" — Yes for every city by
    // default; when answers.yaml has relocation_cities, Yes only for those (and the
    // current location) and No for other named cities.
    static String relocationAnswer(String q) {
        String allowed = Answers.get("relocation_cities", "");
        if (allowed.isBlank()) return "Yes";
        String all = (allowed + "," + Answers.get("current_location", "")).toLowerCase();
        for (String c : all.split(",")) {
            String city = c.trim();
            if (city.length() > 2 && q.contains(city)) return "Yes";
        }
        // Generic "are you open to relocation?" (no city named) → Yes
        return q.matches(".*\\b(relocate|relocation) to\\b.*") ? "No" : "Yes";
    }

    // "Previously employed by X?" / "Are you an ex-employee of X?" / "Have you worked
    // for X before?" — Yes only if X is current_company or listed in past_companies.
    // null when the question isn't about a previous employer.
    static String previousEmployerAnswer(String q) {
        boolean employerQ = q.matches(".*\\b(employed|ex[- ]?employee|alumni|alumnus|payroll)\\b.*")
                || q.matches(".*\\bworked (for|at|with) .*\\b(before|previously|earlier|in the past)\\b.*");
        if (!employerQ || q.contains("currently employed") || q.contains("are you employed")) return null;
        String mine = (Answers.get("current_company", "") + "," + Answers.get("past_companies", "")).toLowerCase();
        for (String c : mine.split(",")) {
            String name = c.trim().split("\\s+")[0];
            if (name.length() > 2 && q.contains(name)) return "Yes";
        }
        return "No";
    }

    // Mobile / country code questions. null when the question isn't about contact details.
    static String contactAnswer(String q) {
        String cc = Answers.get("country_code", "+91");
        String mobile = Answers.get("mobile", "");
        boolean phoneQ = q.matches(".*\\b(mobile|phone|contact number|whatsapp|cell)\\b.*");
        if (q.contains("country code") || q.contains("dial code") || q.matches(".*\\bisd\\b.*")) {
            return phoneQ && !mobile.isEmpty() && q.contains("with") ? cc + " " + mobile : cc;
        }
        String email = Answers.get("email", "");
        boolean emailQ = q.matches(".*\\b(e-?mail|email id|mail id)\\b.*");
        if (phoneQ && !mobile.isEmpty()) {
            String phone = q.contains("with country code") || q.contains("including country code") ? cc + " " + mobile : mobile;
            return emailQ && !email.isEmpty() ? phone + ", " + email : phone;
        }
        if (emailQ && !email.isEmpty()) return email;
        return null;
    }

    // Notice period / joining questions, answered from notice_period_days.
    // null when the question isn't about joining.
    static String noticeAnswer(String q) {
        int days = Integer.parseInt(Answers.get("notice_period_days", "30").replaceAll("[^0-9]", ""));
        // "Can you join within 30 days?" / "join in 2 months" → Yes only if the notice fits
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\bjoin\\w*\\s+(?:in|within)\\s+(\\d+)\\s*(days?|weeks?|months?)").matcher(q);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            int limit = m.group(2).startsWith("month") ? n * 30 : m.group(2).startsWith("week") ? n * 7 : n;
            return days <= limit ? "Yes" : "No";
        }
        if (q.contains("notice period") || q.contains("joining time") || q.contains("when can you join")
                || q.contains("when can you start") || q.contains("how soon") || q.contains("earliest")
                || q.contains("availability to join") || q.contains("joining date") || q.contains("start date")) {
            if (q.contains("month")) return numeric(q) ? String.valueOf(days / 30) : (days / 30) + " months";
            return numeric(q) ? String.valueOf(days) : (days == 0 ? "Immediate" : days + " days");
        }
        if (q.contains("immediate") || q.contains("join immediately")) {
            return days <= 15 ? "Yes" : "No";
        }
        return null;
    }

    private static boolean numeric(String q) {
        return q.contains("how many") || q.contains("number of") || q.contains("in numbers") ||
                q.contains("in digits") || q.contains("(in lpa)") || q.contains("(lpa)") ||
                q.contains("(in years)") || q.contains("(years)") || q.contains("in months");
    }
}