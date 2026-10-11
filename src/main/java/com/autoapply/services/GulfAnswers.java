package com.autoapply.services;

import com.autoapply.util.Answers;

/**
 * Answers for Naukri Gulf application questionnaires. Gulf recruiters ask a few
 * things Indian portals don't (visa status, salary in AED, driving licence,
 * passport, Gulf experience), so those are handled here first; everything else
 * falls through to the shared NaukriAnswers rules.
 */
public class GulfAnswers {

    public static String getAnswer(String question) {
        if (question == null) return "Yes";
        String q = question.toLowerCase().trim();

        // === Free-text "tell us about your work" questions — checked first, since
        // they often mention skills/experience that would otherwise match a Yes rule ===
        if (q.matches(".*\\b(describe|explain|elaborate|tell us|tell me|walk us|share an example|summari[sz]e|what did you build|what you personally built"
                + "|why (do|should|are|would)|fit for|good fit|right fit|what makes you|about yourself|your strengths|motivat\\w*|cover letter)\\b.*")) {
            return Answers.get("profile_summary",
                    "Java backend developer experienced in Spring Boot, microservices, REST APIs and SQL databases.");
        }

        // === Contact / notice period — before the salary and "join in" relocation rules ===
        String contact = NaukriAnswers.contactAnswer(q);
        if (contact != null) return contact;
        String notice = NaukriAnswers.noticeAnswer(q);
        if (notice != null) return notice;

        // === Salary asked in INR / LPA — answer with the Indian CTC values ===
        // Whole-word match so "lac" doesn't hit "place", "rs" doesn't hit "years"
        boolean inr = q.contains("₹") || q.matches(".*\\b(inr|lpa|lakhs?|lacs?|rupees?|rs)\\b.*");
        // Free-text answers carry the currency ("15000 AED", "14 LPA (INR)"); bare
        // numbers only when the question asks for digits. Number-type inputs are
        // stripped to digits by the bot anyway.
        boolean digitsOnly = q.matches(".*\\b(in numbers?|in digits|numeric|only numbers?)\\b.*");
        if (inr && (q.contains("current") || q.contains("present") || q.contains("last drawn"))) {
            return withCurrency(Answers.get("current_ctc", "12"), "LPA (INR)", digitsOnly);
        }
        if (inr && (q.contains("expected") || q.contains("expectation"))) {
            return withCurrency(Answers.get("expected_ctc", "18"), "LPA (INR)", digitsOnly);
        }

        // === Salary (Gulf jobs quote monthly salary in AED) ===
        // Salary asked in US dollars
        boolean usd = q.contains("$") || q.matches(".*\\b(usd|dollars?)\\b.*");
        if (usd && q.contains("salary") || usd && q.contains("ctc")) {
            return q.contains("expected") || q.contains("expectation")
                    ? withCurrency(Answers.get("expected_salary_usd", "3000"), "USD per month", digitsOnly)
                    : withCurrency(Answers.get("current_salary_usd", "1000"), "USD per month", digitsOnly);
        }

        // Current pay is quoted as the real Indian CTC unless AED is explicitly asked
        boolean aed = q.matches(".*\\b(aed|dirhams?)\\b.*");
        if (q.contains("current salary") || q.contains("current monthly") || q.contains("current ctc")
                || q.contains("present salary") || q.contains("last drawn")) {
            return aed
                    ? withCurrency(Answers.get("gulf_current_salary", "4000"), "AED per month", digitsOnly)
                    : withCurrency(Answers.get("current_ctc", "12"), "LPA (INR)", digitsOnly);
        }
        if (q.contains("expected salary") || q.contains("salary expectation") || q.contains("expected monthly")
                || q.contains("expected ctc") || q.contains("aed") || q.contains("salary")) {
            return withCurrency(Answers.get("gulf_expected_salary_aed", "10000"), "AED per month", digitsOnly);
        }

        // === Job type ("Internship" / "Job", "Full-time" / "Contract" ...) ===
        if (q.contains("job type") || q.contains("employment type") || q.contains("type of employment")) {
            return "Full-time Job";
        }

        // === Visa / documents ===
        if (q.contains("visa")) {
            return Answers.get("visa_status", "Not in Gulf / Need Visa");
        }
        if (q.contains("passport")) {
            return "Yes";
        }
        if (q.contains("driving") || q.contains("licen")) {
            return Answers.get("driving_license", "No");
        }

        // === Gulf-specific experience ===
        if (q.contains("gulf experience") || q.contains("gcc experience") || q.contains("uae experience")
                || q.contains("middle east experience") || q.contains("experience in uae") || q.contains("experience in gulf")) {
            return Answers.get("gulf_experience_years", "0");
        }

        // === Location / relocation ===
        if (q.contains("current location") || q.contains("current country") || q.contains("where are you based")
                || q.contains("residence") || q.contains("residing") || q.contains("current city")) {
            return Answers.get("current_location", "India");
        }
        if (q.contains("relocat") || q.contains("willing to move") || q.contains("join in")) {
            return "Yes";
        }

        // === Nationality ===
        if (q.contains("nationality")) {
            return Answers.get("nationality", "Indian");
        }

        return NaukriAnswers.getAnswer(question);
    }

    private static String withCurrency(String amount, String unit, boolean digitsOnly) {
        return digitsOnly ? amount : amount + " " + unit;
    }
}
