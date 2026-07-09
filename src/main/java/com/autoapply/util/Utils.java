package com.autoapply.util;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import java.util.Random;

public class Utils {
    private static final Random RND = new Random();

    public static WebDriver newDriver(boolean headless){
        WebDriverManager.chromedriver().setup();
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
