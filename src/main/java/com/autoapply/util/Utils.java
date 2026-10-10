package com.autoapply.util;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import java.util.Random;

public class Utils {
    private static final Random RND = new Random();

    public static WebDriver newDriver(boolean headless){
        // Driver resolution order:
        // 1. A locally supplied chromedriver — CHROMEDRIVER env var, or ./driver/chromedriver.exe.
        //    This works even when the Chrome-for-Testing metadata host
        //    (googlechromelabs.github.io) is blocked/unreachable on the network.
        //    Keep this file's version matched to the installed Chrome major version.
        // 2. Fall back to WebDriverManager (needs internet). clearResolutionCache()
        //    forces a fresh version detection so a stale cached entry (e.g. "chrome=149")
        //    isn't reused after Chrome auto-updates, which caused
        //    SessionNotCreatedException ("only supports Chrome N, current browser is M").
        String local = System.getenv("CHROMEDRIVER");
        if (local == null || local.isBlank()) {
            java.io.File f = new java.io.File("driver/chromedriver.exe");
            if (f.exists()) local = f.getAbsolutePath();
        }
        if (local != null && !local.isBlank() && new java.io.File(local).exists()) {
            System.setProperty("webdriver.chrome.driver", local);
            System.out.println("🧩 Using local chromedriver: " + local);
        } else {
            WebDriverManager.chromedriver().clearResolutionCache().setup();
        }
        ChromeOptions opts = new ChromeOptions();
        if (headless) opts.addArguments("--headless=new");
        opts.addArguments("--disable-blink-features=AutomationControlled");
        opts.addArguments("--start-maximized");
        opts.addArguments("--disable-infobars");
        opts.addArguments("--lang=en-US,en");
        opts.addArguments("--no-sandbox");
        opts.addArguments("--disable-dev-shm-usage");
        opts.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        opts.setExperimentalOption("excludeSwitches", java.util.List.of("enable-automation", "enable-logging"));
        opts.setExperimentalOption("useAutomationExtension", false);
        return new ChromeDriver(opts);
    }

    public static void humanSleep(double minSec, double maxSec){
        try {
            double s = minSec + (maxSec - minSec) * RND.nextDouble();
            Thread.sleep((long)(s * 1000));
        } catch (InterruptedException ignored){}
    }

    public static boolean hasFlag(String[] args, String flag){
        for (String a: args) if (a.equalsIgnoreCase(flag)) return true;
        return false;
    }

    public static String getOption(String[] args, String key, String def){
        for (int i = 0; i < args.length; i++) {
            // --key=value
            if (args[i].startsWith(key+"=")){
                return args[i].substring((key+"=").length());
            }
            // --key value  (space separated)
            if (args[i].equalsIgnoreCase(key) && i + 1 < args.length){
                return args[i+1];
            }
        }
        return def;
    }
}
