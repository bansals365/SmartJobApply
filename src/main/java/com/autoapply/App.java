package com.autoapply;

import com.autoapply.util.Config;
import com.autoapply.util.Utils;
import com.autoapply.util.DB;
import com.autoapply.sites.NaukriBot;
import com.autoapply.sites.LinkedInEasyApply;

public class App {
    public static void main(String[] args) throws Exception {
        var cfg = Config.load();
        String site = Utils.getOption(args, "--site", "naukri");

        // CLI flags override config.yaml values
        if (Utils.hasFlag(args, "--headful")) cfg.getRun().setHeadless(false);
        if (Utils.hasFlag(args, "--headless")) cfg.getRun().setHeadless(true);
        if (Utils.hasFlag(args, "--manual-captcha")) cfg.getRun().setManualCaptcha(true);

        DB db = new DB("applications.db");

        switch (site) {
            case "naukri" -> new NaukriBot(cfg, db).run();
            case "linkedin" -> new LinkedInEasyApply(cfg, db).run();
            default -> System.out.println("Unknown --site value. Use naukri or linkedin.");
        }
    }
}
