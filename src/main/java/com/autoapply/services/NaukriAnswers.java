package com.autoapply.services;

import com.autoapply.util.Answers;
import org.openqa.selenium.WebElement;
import java.util.List;

public class NaukriAnswers {

    public static String getAnswer(String question) {
        if (question == null) return "Yes";
        String q = question.toLowerCase().trim();

        // === Experience ===
        if (q.contains("total experience") || q.contains("years of experience") || q.contains("how many years")) {
            return numeric(q) ? "4" : "4 years";
        }
        if (q.contains("experience in java") || q.contains("java experience") || q.contains("years in java") || q.contains("java exp")) {
            return numeric(q) ? "4" : "4 years";
        }
        if (q.contains("spring boot") || q.contains("experience in spring") || q.contains("spring experience")) {
            return numeric(q) ? "3" : "3.5 years";
        }
        if (q.contains("microservices")) {
            return numeric(q) ? "3" : "3 years";
        }
        if (q.contains("experience in aws") || q.contains("aws experience") || q.contains("cloud experience")) {
            return numeric(q) ? "2" : "2 years";
        }
        if (q.contains("experience in sql") || q.contains("sql experience") || q.contains("database experience")) {
            return numeric(q) ? "4" : "4 years";
        }
        if (q.contains("experience in rest") || q.contains("rest api")) {
            return numeric(q) ? "4" : "4 years";
        }
        if (q.contains("experience in hibernate") || q.contains("hibernate experience")) {
            return numeric(q) ? "3" : "3 years";
        }
        if (q.contains("relevant experience")) {
            return numeric(q) ? "4" : "4 years";
        }
        if (q.contains("experience")) {
            return numeric(q) ? "4" : "4 years";
        }

        // === Skill checks (yes/no) ===
        if (q.contains("do you have experience") || q.contains("do you know") || q.contains("are you familiar") ||
                q.contains("have you worked") || q.contains("have you used") || q.contains("proficient in")) {
            return "Yes";
        }

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
        if (q.contains("notice period")) {
            return numeric(q) ? "30" : "30 days";
        }
        if (q.contains("joining time") || q.contains("when can you join") || q.contains("when can you start") || q.contains("availability")) {
            return "30 days";
        }
        if (q.contains("immediate joiner") || q.contains("join immediately") || q.contains("immediate joining")) {
            return "Yes";
        }

        // === Location ===
        if (q.contains("current location") || q.contains("current city") || q.contains("where are you based") || q.contains("your location")) {
            return Answers.get("current_location", "Bangalore");
        }
        if (q.contains("relocat") || q.contains("open to relocation")) {
            return "Yes";
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

    private static boolean numeric(String q) {
        return q.contains("how many") || q.contains("number of") || q.contains("in numbers") ||
                q.contains("in digits") || q.contains("(in lpa)") || q.contains("(lpa)") ||
                q.contains("(in years)") || q.contains("(years)") || q.contains("in months");
    }
}