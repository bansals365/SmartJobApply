package com.autoapply.sites;

import com.autoapply.util.Config;
import com.autoapply.util.DB;
import com.autoapply.util.Utils;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LinkedInEasyApply {
    private final Config cfg;
    private final DB db;
    public LinkedInEasyApply(Config cfg, DB db){ this.cfg = cfg; this.db = db; }

    public void run(){
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String email = dotenv.get("LINKEDIN_EMAIL");
        String pass = dotenv.get("LINKEDIN_PASSWORD");
        if (email == null || pass == null){
            System.out.println("Missing LINKEDIN_EMAIL/LINKEDIN_PASSWORD in .env");
            return;
        }

        WebDriver driver = Utils.newDriver(cfg.getRun().isHeadless());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(8));
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(8));

        try {
            // login
            driver.get("https://www.linkedin.com/login");
            driver.findElement(By.id("username")).sendKeys(email);
            driver.findElement(By.id("password")).sendKeys(pass);
            driver.findElement(By.xpath("//button[@type='submit']")).click();
            Utils.humanSleep(cfg.getRun().getRandom_delay_sec_min(), cfg.getRun().getRandom_delay_sec_max());

            // search with Easy Apply filter
            String q = cfg.getSite().getLinkedin().getJob_query();
            String loc = cfg.getSite().getLinkedin().getLocation();
            String url = "https://www.linkedin.com/jobs/search/?keywords=" + q + "&location=" + loc + "&f_AL=true";
            driver.get(url);

            // scroll to load
            for (int i=0; i<5; i++){
                new Actions(driver).scrollByAmount(0, 2000).perform();
                Utils.humanSleep(0.8, 1.6);
            }

            List<WebElement> cards = driver.findElements(By.cssSelector("li[data-occludable-job-id]"));
            System.out.println("Found cards: " + cards.size());

            int count = 0, cap = cfg.getRun().getDaily_max_applications();

            for (WebElement c : cards){
                if (count >= cap) break;
                try {
                    WebElement title = c.findElement(By.cssSelector("a.job-card-list__title--link"));
                    String link = title.getAttribute("href");
                    String t = title.getText();
                    String company = "";
                    try {
                        company = c.findElement(
                                By.cssSelector("div.artdeco-entity-lockup__subtitle span")
                        ).getText().trim();
                    } catch (Exception ignored) {}

                    // open detail in new tab
                    ((JavascriptExecutor)driver).executeScript("window.open(arguments[0], '_blank');", link);
                    ArrayList<String> tabs = new ArrayList<>(driver.getWindowHandles());
                    driver.switchTo().window(tabs.get(tabs.size()-1));
                    Utils.humanSleep(3, 5); // buffer for detail page load

                    // click Easy Apply button (if available)
                    WebDriverWait wait1 = new WebDriverWait(driver, Duration.ofSeconds(15));

                    //WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

                    try {
                        // Pehle page ko thoda scroll kar (button visible karne ke liye)
                        ((JavascriptExecutor) driver).executeScript("window.scrollBy(0, 400)");

                        // Fallback locators
                        By[] locators = new By[]{
                                By.id("jobs-apply-button-id"),
                                By.xpath("//button[contains(@class,'jobs-apply-button')]"),
                                By.xpath("//button[contains(@aria-label,'Easy Apply')]")
                        };

                        WebElement easyApply = null;

                        for (By locator : locators) {
                            try {
                                easyApply = wait1.until(ExpectedConditions.elementToBeClickable(locator));
                                if (easyApply != null) {
                                    break; // mil gaya, loop tod do
                                }
                            } catch (Exception ignored) {}
                        }

                        if (easyApply != null) {
                            easyApply.click();
                            System.out.println("✅ Easy Apply button clicked!");
                        } else {
                            System.out.println("❌ Easy Apply button not found for this job.");
                        }

                    } catch (Exception e) {
                        System.out.println("⚠ Error while trying to click Easy Apply: " + e.getMessage());
                    }

                    try{

                    // multi-step form: wait for Submit or Next
                        boolean applied = false;
                        for (int step=0; step<10; step++){
                            List<WebElement> submit = driver.findElements(By.xpath("//button[contains(text(),'Submit application')]"));
                            if (!submit.isEmpty()){
                                submit.get(0).click();
                                applied = true;
                                break;
                            }

                            List<WebElement> next = driver.findElements(By.xpath("//button[contains(text(),'Next')]"));
                            if (!next.isEmpty()){
                                next.get(0).click();
                                Utils.humanSleep(0.6, 1.2);
                            } else {
                                // Manual intervention if required
                                System.out.println("⚠️ Complete the form manually in this tab, then press Enter to continue...");
                                new java.util.Scanner(System.in).nextLine();
                                applied = true;
                                break;
                            }
                        }

                        if (applied) {
                            count++;
                            db.upsert("linkedin", link, t, company, "", "applied", "");
                        }

                    } catch (TimeoutException te){
                        db.upsert("linkedin", link, t, company, "", "skipped", "no easy apply");
                    }

                    driver.close();
                    driver.switchTo().window(tabs.get(0));
                } catch (Exception e){
                    System.out.println("Card error: " + e.getMessage());
                }
            }

        } finally {
            driver.quit();
        }
    }
}
