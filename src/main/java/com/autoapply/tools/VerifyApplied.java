package com.autoapply.tools;

import com.autoapply.util.DB;
import com.autoapply.util.Utils;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * Cleanup tool: re-verifies every DB row marked status='applied' against
 * Naukri's live job page and corrects the false positives left behind by the
 * old, unreliable "chatbot said thank you" verification.
 *
 * A job counts as REALLY applied only if its Naukri job page now shows the
 * applied state (apply button = Applied/Withdraw, or an "applied to this job"
 * banner). Everything else is reclassified:
 *   - reachable + not applied  -> status='skipped', reason='false-positive'
 *   - page gone/expired/Oops   -> status='skipped', reason='expired-or-removed'
 *
 * Safe by default (DRY-RUN: only prints). Pass --apply to write changes.
 *
 * Run:
 *   java -cp target/java-job-auto-apply-bot-1.0.0-jar-with-dependencies.jar \
 *        com.autoapply.tools.VerifyApplied [--apply] [--headful] [--limit=N]
 */
public class VerifyApplied {

    public static void main(String[] args) {
        boolean write   = Utils.hasFlag(args, "--apply");
        boolean headful = Utils.hasFlag(args, "--headful");
        boolean debug   = Utils.hasFlag(args, "--debug");
        int limit = Integer.parseInt(Utils.getOption(args, "--limit", "0")); // 0 = all

        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String email = dotenv.get("NAUKRI_EMAIL");
        String pass  = dotenv.get("NAUKRI_PASSWORD");
        if (email == null || pass == null) {
            System.out.println("Missing NAUKRI_EMAIL/NAUKRI_PASSWORD in .env");
            return;
        }

        DB db = new DB("applications.db");
        List<String[]> rows = db.fetchByStatus("applied");
        System.out.println("Found " + rows.size() + " 'applied' records to re-verify.");
        System.out.println("Mode: " + (write ? "APPLY (DB will be updated)" : "DRY-RUN (no changes)")
                + (limit > 0 ? " | limit=" + limit : ""));

        WebDriver driver = Utils.newDriver(!headful);
        // Short implicit wait (the applied-state element loads fast when present;
        // a long wait just penalises every not-found lookup) + a hard page-load
        // cap so a single slow/hanging job page can't stall the whole run.
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(3));
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(20));

        int checked = 0, real = 0, fake = 0, gone = 0;
        try {
            // === Login ===
            driver.get("https://www.naukri.com/nlogin/login");
            driver.findElement(By.id("usernameField")).sendKeys(email);
            driver.findElement(By.id("passwordField")).sendKeys(pass);
            driver.findElement(By.xpath("//button[contains(.,'Login')]")).click();
            try {
                new WebDriverWait(driver, Duration.ofSeconds(25))
                        .until(d -> !d.getCurrentUrl().contains("nlogin"));
                System.out.println("✅ Login OK\n");
            } catch (TimeoutException e) {
                System.out.println("⚠️ Login failed or CAPTCHA needed. Aborting.");
                return;
            }
            Utils.humanSleep(2.0, 3.0);

            for (String[] row : rows) {
                if (limit > 0 && checked >= limit) break;
                String jobId = row[0];
                String title = row[1];
                checked++;

                if (jobId == null || jobId.isEmpty()) {
                    System.out.println("[SKIP] no url: " + title);
                    continue;
                }
                try {
                    try {
                        driver.get(jobId);
                    } catch (TimeoutException pe) {
                        // Page didn't finish loading in time — stop it and check
                        // whatever rendered; the applied banner is usually there.
                        try { ((JavascriptExecutor) driver).executeScript("window.stop();"); } catch (Exception ignored) {}
                    }
                    Utils.humanSleep(0.8, 1.2);

                    String pageTitle = driver.getTitle().toLowerCase();
                    String curUrl    = driver.getCurrentUrl().toLowerCase();
                    boolean errorPage = pageTitle.contains("oops") || pageTitle.contains("error")
                            || pageTitle.contains("not found") || curUrl.contains("/oops") || curUrl.contains("/error");

                    if (errorPage) {
                        gone++;
                        System.out.println("[GONE]  " + title);
                        if (write) db.setStatus(jobId, "skipped", "expired-or-removed");
                        continue;
                    }

                    if (debug) dumpAppliedSignals(driver, title);

                    if (isAppliedOnPage(driver)) {
                        real++;
                        System.out.println("[REAL]  " + title);
                        if (write) db.setStatus(jobId, "applied", "verified");
                    } else {
                        fake++;
                        System.out.println("[FALSE] " + title);
                        if (write) db.setStatus(jobId, "skipped", "false-positive");
                    }
                } catch (Exception e) {
                    System.out.println("[ERR]   " + title + " : " + e.getMessage());
                }
            }
        } finally {
            driver.quit();
        }

        System.out.println("\n==== SUMMARY ====");
        System.out.println("checked          : " + checked);
        System.out.println("really applied   : " + real);
        System.out.println("false positives  : " + fake);
        System.out.println("gone / expired   : " + gone);
        System.out.println(write
                ? "DB updated — false positives moved to status='skipped'."
                : "DRY-RUN — nothing written. Re-run with --apply to commit changes.");
    }

    // Diagnostic: print what the page actually exposes about applied-state, so
    // we can find the correct signal instead of guessing.
    private static void dumpAppliedSignals(WebDriver driver, String title) {
        System.out.println("---- DEBUG: " + title + " ----");
        System.out.println("  url: " + driver.getCurrentUrl());
        try {
            WebElement b = driver.findElement(By.id("apply-button"));
            System.out.println("  #apply-button text = '" + b.getText().trim() + "' class='" + b.getAttribute("class") + "'");
        } catch (Exception e) { System.out.println("  #apply-button: NOT FOUND"); }
        try {
            Object snippet = ((JavascriptExecutor) driver).executeScript(
                "var t=document.body.innerText.toLowerCase();var i=t.indexOf('applied');" +
                "return i<0?'(no \\'applied\\' in body)':t.substring(Math.max(0,i-40),i+60);");
            System.out.println("  body around 'applied': " + snippet);
        } catch (Exception e) { System.out.println("  body scan failed: " + e.getMessage()); }
        try {
            Object btns = ((JavascriptExecutor) driver).executeScript(
                "return JSON.stringify(Array.from(document.querySelectorAll(\"button,[class*='apply'],[class*='Apply']\"))" +
                ".map(function(b){return ((b.innerText||b.textContent||'').trim()+'|'+(b.className+'').slice(0,50));})" +
                ".filter(function(s){return s.length>2;}).slice(0,12));");
            System.out.println("  apply-ish elements: " + btns);
        } catch (Exception e) {}
    }

    // Reliable "already applied" detection on a live Naukri job page.
    // Verified against real pages: once applied, the apply-button container's
    // text becomes "Applied" (and #apply-button disappears). The un-applied
    // state shows "Apply" / "Save\nApply" / "Save" — none of which contain the
    // substring "applied", so it is a clean discriminator.
    private static boolean isAppliedOnPage(WebDriver driver) {
        // 1. Apply-button container flips to "Applied".
        try {
            for (WebElement c : driver.findElements(By.cssSelector("[class*='apply-button-container']"))) {
                if (c.getText().toLowerCase().contains("applied")) return true;
            }
        } catch (Exception ignored) {}
        // 2. Direct apply button text (older / alternate UI variants).
        try {
            String btn = driver.findElement(By.id("apply-button")).getText().toLowerCase();
            if (btn.contains("applied") || btn.contains("withdraw") || btn.contains("view application")) return true;
        } catch (Exception ignored) {}
        return false;
    }
}