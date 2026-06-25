package com.autoapply.services;

import java.util.Map;

public class CoverLetter {
    public String generate(Map<String, String> ctx){
        String job = ctx.getOrDefault("job_title","the role");
        String cmp = ctx.getOrDefault("company","your company");
        String skills = ctx.getOrDefault("skills","backend development");
        String name = ctx.getOrDefault("name","Your Name");
        String phone = ctx.getOrDefault("phone","+91-XXXXXXXXXX");
        String email = ctx.getOrDefault("email","you@example.com");
        return "Dear Hiring Manager,\n\n" +
                "I am excited to apply for the " + job + " role at " + cmp + ". I have hands-on experience in " + skills + ".\n" +
                "- Built reliable services and APIs\n" +
                "- Improved performance and stability\n" +
                "- Collaborated across teams to deliver features\n\n" +
                "Looking forward to discussing how I can contribute.\n\n" +
                name + "\n" + phone + "\n" + email + "\n";
    }
}
