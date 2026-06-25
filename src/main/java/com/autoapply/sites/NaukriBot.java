package com.autoapply.sites;

import com.autoapply.services.JDFilter;
import com.autoapply.services.NaukriAnswers;
import com.autoapply.util.Config;
import com.autoapply.util.DB;
import com.autoapply.util.Utils;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.*;
import java.util.NoSuchElementException;

public class NaukriBot {
    private final Config cfg;
    private final DB db;

    public NaukriBot(Config cfg, DB db) {
        this.cfg = cfg;
        this.db = db;
    }

    public void run() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String email = dotenv.get("NAUKRI_EMAIL");
        String pass = dotenv.get("NAUKRI_PASSWORD");
        if (email == null || pass == null) {
            System.out.println("Missing NAUKRI_EMAIL/NAUKRI_PASSWORD in .env");
            return;
        }

        WebDriver driver = Utils.newDriver(cfg.getRun().isHeadless());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(8));

        try {
            // === Login ===
            driver.get("https://www.naukri.com/nlogin/login");
            driver.findElement(By.id("usernameField")).sendKeys(email);
            driver.findElement(By.id("passwordField")).sendKeys(pass);
            driver.findElement(By.xpath("//button[contains(.,'Login')]")).click();

            // Wait for redirect away from login page
            try {
                new WebDriverWait(driver, Duration.ofSeconds(25))
                        .until(d -> !d.getCurrentUrl().contains("nlogin"));
                System.out.println("✅ Login successful");
            } catch (TimeoutException e) {
                System.out.println("⚠️ Login may have failed or CAPTCHA needed. Continuing anyway...");
            }
            Utils.humanSleep(cfg.getRun().getRandom_delay_sec_min(), cfg.getRun().getRandom_delay_sec_max());

            // === Build search URL ===
            String kw = String.join(" ", cfg.getSearch().getKeywords()).toLowerCase().trim().replace(" ", "-");
            String loc = Optional.ofNullable(cfg.getSearch().getLocation()).filter(s -> !s.isEmpty()).orElse("india").toLowerCase().trim().replace(" ", "-");
            String url = "https://www.naukri.com/" + kw + "-jobs-in-" + loc + "?jobAge=30";
            System.out.println("🔍 Search URL: " + url);
            driver.get(url);
            Utils.humanSleep(1.0, 1.5);

            // === Experience Filter ===
            try {
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
                int targetYears = cfg.getSite().getNaukri().getMin_experience();

                By expContainerBy = By.cssSelector("div[data-filter-id='experience']");
                WebElement expContainer = wait.until(ExpectedConditions.presenceOfElementLocated(expContainerBy));
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", expContainer);

                By railBy = By.cssSelector("div[data-filter-id='experience'] .rc-slider-rail");
                By handleBy = By.cssSelector("div[data-filter-id='experience'] .rc-slider .handle");
                By valueBy = By.cssSelector("div[data-filter-id='experience'] .handle .inside span");

                WebElement rail = wait.until(ExpectedConditions.visibilityOfElementLocated(railBy));
                WebElement handle = wait.until(ExpectedConditions.elementToBeClickable(handleBy));

                int width = rail.getSize().getWidth();
                int maxYears = 30;
                int targetXFromLeft = (int) Math.round(width * (double) targetYears / (double) maxYears);
                int xFromCenter = -(width / 2) + targetXFromLeft;

                new org.openqa.selenium.interactions.Actions(driver)
                        .moveToElement(rail, xFromCenter, 0)
                        .click()
                        .perform();

                Utils.humanSleep(0.8, 1.2);

                String shown = "";
                try { shown = driver.findElement(valueBy).getText().trim(); } catch (Exception ignore) {}

                if (!String.valueOf(targetYears).equals(shown)) {
                    int railLeft = rail.getLocation().getX();
                    int handleLeft = handle.getLocation().getX();
                    int currentXFromLeft = handleLeft - railLeft;
                    int delta = targetXFromLeft - currentXFromLeft;
                    new org.openqa.selenium.interactions.Actions(driver)
                            .clickAndHold(handle).moveByOffset(delta, 0).release().perform();
                    Utils.humanSleep(0.6, 1.0);
                    try { shown = driver.findElement(valueBy).getText().trim(); } catch (Exception ignore) {}
                }
                System.out.println("Experience filter set to: " + (shown.isEmpty() ? "unknown" : shown));

            } catch (Exception e) {
                System.out.println("⚠️ Could not set Experience filter: " + e.getMessage());
            }

            // === Setup ===
            JDFilter filter = new JDFilter(cfg);
            int count = 0;
            int cap = cfg.getRun().getDaily_max_applications();
            int maxPages = cfg.getSite().getNaukri().getMax_scroll_pages();

            // === Pagination Loop ===
            try {
                int page = 0;
                while (page < maxPages) {
                    WebElement paginationDiv = driver.findElement(By.cssSelector("div.styles_pages__v1rAK"));
                    List<WebElement> pageLinks = paginationDiv.findElements(By.tagName("a"));

                    if (page >= pageLinks.size()) {
                        System.out.println("✅ No more pages.");
                        break;
                    }

                    try {
                        WebElement pageLink = pageLinks.get(page);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", pageLink);
                        Utils.humanSleep(0.5, 1.0);
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", pageLink);
                        Utils.humanSleep(1.5, 2.0);
                        System.out.println("➡️ Page " + (page + 1));
                    } catch (Exception e) {
                        System.out.println("❌ Page click failed: " + e.getMessage());
                        break;
                    }

                    // === Job Cards Loop ===
                    List<WebElement> cards = driver.findElements(By.cssSelector("div.srp-jobtuple-wrapper"));
                    System.out.println("📌 Cards on page: " + cards.size());

                    for (WebElement card : cards) {
                        if (count >= cap) {
                            System.out.println("Reached daily max: " + cap);
                            return;
                        }
                        try {
                            List<WebElement> titleLinks = card.findElements(By.cssSelector("a.title"));
                            if (titleLinks.isEmpty()) continue;

                            WebElement titleA = titleLinks.get(0);
                            String title = titleA.getText();
                            String link = titleA.getAttribute("href");
                            String company = "";
                            try { company = card.findElement(By.cssSelector(".subTitle.ellipsis.fleft")).getText(); } catch (Exception ignored) {}

                            // Skip already-processed jobs (debug mode re-opens them to capture DOM)
                            if (!DEBUG_CHATBOT && db.exists(link)) {
                                System.out.println("⏭️ Already processed: " + title);
                                continue;
                            }

                            // Title pre-filter — avoid opening irrelevant tabs at all
                            if (!filter.titleAllowed(title)) {
                                System.out.println("⏭️ Title blocked: " + title);
                                db.upsert("naukri", link, title, company, "", "skipped", "title-filter");
                                continue;
                            }

                            ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", link);
                            ArrayList<String> tabs = new ArrayList<>(driver.getWindowHandles());
                            driver.switchTo().window(tabs.get(tabs.size() - 1));
                            Utils.humanSleep(1.0, 1.5);

                            // Extract JD from specific section, not entire body (avoids sidebar noise)
                            String jdText = "";
                            String[] jdSelectors = {".job-desc", "#job-desc", "[class*='jd-desc']", "[class*='job-desc']", ".dang-inner-html"};
                            for (String sel : jdSelectors) {
                                try {
                                    jdText = driver.findElement(By.cssSelector(sel)).getText();
                                    if (!jdText.isEmpty()) break;
                                } catch (Exception ignored) {}
                            }
                            if (jdText.isEmpty()) {
                                try { jdText = driver.findElement(By.cssSelector("body")).getText(); } catch (Exception ignored) {}
                            }

                            if (!filter.titleHasInclude(title) && !filter.shouldApply(title, jdText)) {
                                System.out.println("⏭️ JD filter skip: " + title);
                                db.upsert("naukri", link, title, company, "", "skipped", "filter");
                                driver.close();
                                driver.switchTo().window(tabs.get(0));
                                continue;
                            }

                            // === Easy Apply ===
                            boolean applied = false;
                            try {
                                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
                                By applyBy = findApplyButton(driver);
                                if (applyBy == null) throw new Exception("No easy apply button found");
                                WebElement applyBtn = wait.until(ExpectedConditions.elementToBeClickable(applyBy));
                                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", applyBtn);
                                applyBtn.click();
                                Utils.humanSleep(1.5, 2.0);

                                applied = runChatbot(driver, wait);

                            } catch (Exception e) {
                                System.out.println("❌ Easy Apply not found: " + e.getMessage());
                            }

                            if (applied) {
                                db.upsert("naukri", link, title, company, "", "applied", "");
                                count++;
                                System.out.println("✅ Applied (" + count + "/" + cap + "): " + title + " @ " + company);
                            } else {
                                db.upsert("naukri", link, title, company, "", "skipped", "no-apply-button");
                            }

                            driver.close();
                            driver.switchTo().window(tabs.get(0));
                            Utils.humanSleep(cfg.getRun().getRandom_delay_sec_min(), cfg.getRun().getRandom_delay_sec_max());

                        } catch (Exception e) {
                            System.out.println("⚠️ Card error: " + e.getMessage());
                            // Recover to main tab if we somehow lost it
                            try {
                                ArrayList<String> tabs = new ArrayList<>(driver.getWindowHandles());
                                if (tabs.size() > 1) { driver.close(); driver.switchTo().window(tabs.get(0)); }
                            } catch (Exception ignored) {}
                        }
                    }
                    page++;
                }

            } catch (Exception e) {
                System.out.println("❌ Pagination error: " + e.getMessage());
            }

        } finally {
            driver.quit();
        }
    }

    // Handles the chatbot Q&A. Returns true if application was submitted.
    private boolean runChatbot(WebDriver driver, WebDriverWait wait) {
        String lastBotMsg = "";
        int staleTick = 0;
        int iter = 0;

        while (iter++ < 40) {
            // Oops / error page guard — if Naukri navigated away from the job page, abort
            try {
                String currentUrl = driver.getCurrentUrl();
                String pageTitle = driver.getTitle().toLowerCase();
                if (pageTitle.contains("oops") || pageTitle.contains("error") || pageTitle.contains("not found")
                        || currentUrl.contains("/oops") || currentUrl.contains("/error")) {
                    System.out.println("⚠️ Oops page detected, aborting chatbot. URL: " + currentUrl);
                    return false;
                }
            } catch (Exception ignored) {}

            try {
                WebElement container = wait.until(
                        ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".chatbot_MessageContainer"))
                );

                // Reliable success signal: once the application is actually
                // submitted, Naukri redirects to the saveApply confirmation page
                // ("Applied to ..." with a green tick). Chatbot text like
                // "thank you for your response" shows up mid-flow too, so it
                // CANNOT be trusted — relying on it caused false positives
                // (e.g. Lenskart job marked applied when it never submitted).
                if (isApplyConfirmed(driver)) {
                    System.out.println("🔎 Verify: saveApply confirmation page detected → applied=true");
                    return true;
                }

                List<WebElement> botItems = container.findElements(By.cssSelector(".botItem.chatbot_ListItem"));
                if (botItems.isEmpty()) { Utils.humanSleep(0.5, 0.8); continue; }

                WebElement lastItem = botItems.get(botItems.size() - 1);
                String labelText = lastItem.getText().trim();
                String lower = labelText.toLowerCase();

                // Skip intro/greeting messages but don't loop forever on them
                boolean isIntro = lower.startsWith("hi ") || lower.startsWith("hello ") ||
                        lower.contains("thank you for showing interest") ||
                        lower.contains("answer all the recruiter's questions") ||
                        lower.contains("please answer");
                if (isIntro) {
                    if (labelText.equals(lastBotMsg)) { staleTick++; if (staleTick > 5) break; }
                    lastBotMsg = labelText;
                    Utils.humanSleep(0.7, 1.0);
                    continue;
                }

                // Stale detection — same question not progressing
                if (labelText.equals(lastBotMsg)) {
                    staleTick++;
                    if (staleTick > 3) { System.out.println("⚠️ Chatbot stuck, breaking."); break; }
                    Utils.humanSleep(0.7, 1.0);
                    continue;
                }
                staleTick = 0;
                lastBotMsg = labelText;

                debugDumpChatbot(driver, iter, labelText);
                if (DEBUG_READONLY) {
                    System.out.println("🐞 [debug] read-only — captured first question, not answering.");
                    return false;
                }

                String answer = NaukriAnswers.getAnswer(labelText);

                // 1. Checkbox options — either a Yes/No skill question or a city multi-select.
                List<WebElement> checkboxes = driver.findElements(
                        By.cssSelector("[class*='chatbot'] input[type='checkbox'], [class*='Chatbot'] input[type='checkbox']")
                );
                if (!checkboxes.isEmpty()) {
                    String ans = answer.toLowerCase().trim();
                    boolean booleanAns = ans.equals("yes") || ans.equals("no");
                    boolean anyChecked = false;
                    WebElement skipCheckbox = null;
                    WebElement firstOption = null;

                    for (WebElement cb : checkboxes) {
                        String lbl = getRadioLabel(driver, cb).toLowerCase().trim();
                        if (lbl.contains("skip")) { skipCheckbox = cb; continue; }
                        if (firstOption == null) firstOption = cb;
                    }

                    if (booleanAns) {
                        // Yes/No question rendered as checkboxes — match the answer's own
                        // label, never a city. (Fixes "noida".contains("no") false match
                        // that made the bot answer "No" to every skill question.)
                        // Pass 1: exact/prefix match. Pass 2: loose contains (catches
                        // labels that aren't fully rendered on the first read).
                        for (WebElement cb : checkboxes) {
                            String lbl = getRadioLabel(driver, cb).toLowerCase().trim();
                            if (lbl.equals(ans) || lbl.startsWith(ans + " ") || lbl.startsWith(ans + ",")) {
                                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", cb);
                                System.out.println("☑️ Selected: " + getRadioLabel(driver, cb));
                                anyChecked = true;
                                break;
                            }
                        }
                        if (!anyChecked) {
                            for (WebElement cb : checkboxes) {
                                String lbl = getRadioLabel(driver, cb).toLowerCase().trim();
                                if (lbl.contains("skip")) continue;
                                // word-boundary contains so "no" won't match "noida"
                                if (lbl.matches(".*\\b" + java.util.regex.Pattern.quote(ans) + "\\b.*")) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", cb);
                                    System.out.println("☑️ Selected: " + getRadioLabel(driver, cb));
                                    anyChecked = true;
                                    break;
                                }
                            }
                        }
                    } else {
                        // City / multi-select question — match preferred locations. Guard
                        // against tiny labels (e.g. "no") matching a city substring.
                        List<String> preferred = cfg.getSite().getNaukri().getPreferred_locations();
                        for (String pref : preferred) {
                            for (WebElement cb : checkboxes) {
                                String lbl = getRadioLabel(driver, cb).toLowerCase().trim();
                                if (lbl.contains("skip") || lbl.length() < 4) continue;
                                if (lbl.contains(pref) || pref.contains(lbl)) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", cb);
                                    System.out.println("☑️ Selected: " + getRadioLabel(driver, cb));
                                    anyChecked = true;
                                    Utils.humanSleep(0.2, 0.4);
                                }
                            }
                        }
                    }

                    if (!anyChecked && skipCheckbox != null) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", skipCheckbox);
                        System.out.println("⏭️ No match available, skipping");
                    } else if (!anyChecked && firstOption != null) {
                        // No skip and nothing matched — pick the first real option so the
                        // Save control enables and the application can still progress.
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", firstOption);
                        System.out.println("☑️ Selected (fallback): " + getRadioLabel(driver, firstOption));
                    }

                    Utils.humanSleep(0.5, 0.8);
                    clickSaveButton(driver);
                    lastBotMsg = "";
                    Utils.humanSleep(0.8, 1.2);
                    continue;
                }

                // 2. Radio button options (Yes/No/Skip style with Save button)
                List<WebElement> radioInputs = driver.findElements(
                        By.cssSelector("[class*='chatbot'] input[type='radio'], [class*='Chatbot'] input[type='radio']")
                );
                if (!radioInputs.isEmpty()) {
                    WebElement bestRadio = pickBestRadio(driver, radioInputs, answer);
                    // Select + fire change/input so Naukri enables the Save control.
                    ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].scrollIntoView({block:'center'});" +
                        "arguments[0].click();" +
                        "arguments[0].checked=true;" +
                        "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));" +
                        "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));", bestRadio);
                    Utils.humanSleep(0.5, 1.0);
                    clickSaveButton(driver);
                    lastBotMsg = "";
                    Utils.humanSleep(0.8, 1.2);
                    continue;
                }

                // 3. Clickable option buttons (suggested replies / chips)
                List<WebElement> optBtns = container.findElements(
                        By.cssSelector(".suggestedReplies button, .chatbot_Options button, [class*='optionItem'] button, [class*='botButton'], [class*='chatbot'] li button")
                );
                if (!optBtns.isEmpty()) {
                    WebElement best = NaukriAnswers.getBestOption(optBtns, labelText);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", best);
                    Utils.humanSleep(0.2, 0.4);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", best);
                    clickSaveButton(driver);
                    lastBotMsg = "";
                    Utils.humanSleep(0.8, 1.2);
                    continue;
                }

                // 4. Text input — contenteditable div (.textArea)
                List<WebElement> inputs = driver.findElements(By.cssSelector("div[contenteditable='true'].textArea"));
                if (!inputs.isEmpty()) {
                    WebElement inputBox = inputs.get(0);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", inputBox);
                    // Type a real char so Naukri's input handler fires, then set full text + input event.
                    inputBox.click();
                    inputBox.sendKeys(answer);
                    ((JavascriptExecutor) driver).executeScript(
                        "arguments[0].dispatchEvent(new Event('input',{bubbles:true}));", inputBox);
                    Utils.humanSleep(0.3, 0.5);
                    clickSaveButton(driver);
                    lastBotMsg = "";
                    Utils.humanSleep(0.8, 1.2);
                    continue;
                }

                // 5. Regular text input (e.g. notice period field)
                List<WebElement> regularInputs = driver.findElements(
                        By.cssSelector("[class*='chatbot'] input[type='text'], [class*='Chatbot'] input[type='text'], input[placeholder*='message'], input[placeholder*='Message']")
                );
                if (!regularInputs.isEmpty()) {
                    WebElement inputBox = regularInputs.get(0);
                    ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", inputBox);
                    inputBox.clear();
                    inputBox.sendKeys(answer);
                    Utils.humanSleep(0.3, 0.5);
                    clickSaveButton(driver);
                    lastBotMsg = "";
                    Utils.humanSleep(0.8, 1.2);
                    continue;
                }

                Utils.humanSleep(0.5, 0.8);

            } catch (StaleElementReferenceException e) {
                staleTick = 0;
            } catch (TimeoutException | NoSuchElementException e) {
                break;
            }
        }

        // Final verification. The ONLY trustworthy signals that the job was
        // actually submitted are: (1) the saveApply confirmation page, or
        // (2) the apply button flipping to Applied/Withdraw. A bare "job URL"
        // is NOT proof — that used to mark un-submitted jobs as applied.
        try {
            if (isApplyConfirmed(driver)) {
                System.out.println("🔎 Verify: saveApply confirmation page → applied=true");
                return true;
            }
            // Guard against Oops/error pages
            String pageTitle = driver.getTitle().toLowerCase();
            String currentUrl = driver.getCurrentUrl();
            if (pageTitle.contains("oops") || pageTitle.contains("error") || pageTitle.contains("not found")
                    || currentUrl.contains("/oops") || currentUrl.contains("/error")) {
                System.out.println("⚠️ On error page after chatbot — not applied. URL: " + currentUrl);
                return false;
            }
            String btnText = driver.findElement(By.id("apply-button")).getText().toLowerCase();
            boolean ok = btnText.contains("applied") || btnText.contains("withdraw") || btnText.contains("view application");
            System.out.println("🔎 Verify: apply-button text = '" + btnText + "' → applied=" + ok);
            return ok;
        } catch (NoSuchElementException e) {
            // Apply button gone but no confirmation page reached → NOT applied.
            String url = "";
            try { url = driver.getCurrentUrl(); } catch (Exception ignored) {}
            System.out.println("🔎 Verify: no confirmation page & apply-button missing, url=" + url + " → applied=false");
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // Ground-truth check that the application was REALLY submitted. Naukri
    // redirects to /myapply/saveApply and renders a green «Applied to "<title>"»
    // banner only after a successful submit. This is the single source of truth
    // — it replaces the old, unreliable "chatbot said thank you" heuristic.
    private boolean isApplyConfirmed(WebDriver driver) {
        try {
            String url = driver.getCurrentUrl().toLowerCase();
            if (url.contains("myapply/saveapply")) return true;
        } catch (Exception ignored) {}
        try {
            String body = driver.findElement(By.cssSelector("body")).getText().toLowerCase();
            if (body.contains("applied to \"")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // Try multiple selectors to find the Apply button (Naukri UI changes frequently)
    private By findApplyButton(WebDriver driver) {
        String[] selectors = {
            "#apply-button",
            "[data-auto='apply-button']",
            "button[class*='apply-button']",
            "button[class*='applyButton']",
            "//button[normalize-space()='Apply Now']",
            "//button[normalize-space()='Easy Apply']",
            "//button[normalize-space()='Apply']",
            "//button[contains(@class,'apply') and not(contains(@class,'chat'))]"
        };
        for (String sel : selectors) {
            try {
                List<WebElement> els = sel.startsWith("//")
                    ? driver.findElements(By.xpath(sel))
                    : driver.findElements(By.cssSelector(sel));
                if (!els.isEmpty() && els.get(0).isDisplayed()) {
                    System.out.println("🔘 Apply button found via: " + sel);
                    return sel.startsWith("//") ? By.xpath(sel) : By.cssSelector(sel);
                }
            } catch (Exception ignored) {}
        }
        return null; // fast fail — caller will skip this job immediately
    }

    // Pick the radio input whose label best matches the answer
    private WebElement pickBestRadio(WebDriver driver, List<WebElement> radios, String answer) {
        String ans = answer.toLowerCase().trim();
        for (WebElement radio : radios) {
            String labelText = getRadioLabel(driver, radio).toLowerCase();
            if (labelText.equals(ans) || ans.contains(labelText) || labelText.contains(ans)) return radio;
        }
        // Yes/No fallback
        if (ans.startsWith("yes")) {
            for (WebElement r : radios) if (getRadioLabel(driver, r).toLowerCase().startsWith("yes")) return r;
        }
        if (ans.startsWith("no")) {
            for (WebElement r : radios) if (getRadioLabel(driver, r).toLowerCase().startsWith("no")) return r;
        }
        return radios.get(0);
    }

    private String getRadioLabel(WebDriver driver, WebElement radio) {
        // Try label[for=id]
        try {
            String id = radio.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                return driver.findElement(By.cssSelector("label[for='" + id + "']")).getText().trim();
            }
        } catch (Exception ignored) {}
        // Try parent element text
        try { return radio.findElement(By.xpath("..")).getText().trim(); } catch (Exception ignored) {}
        return "";
    }

    // Debug helper: dump the chatbot drawer HTML + a summary of every button on
    // the page to debug_chatbot.txt, so the real DOM (esp. the Save button class
    // and where it lives) can be inspected. Enable with -Ddebug.chatbot=true.
    private static final boolean DEBUG_CHATBOT = Boolean.getBoolean("debug.chatbot");
    private static final boolean DEBUG_READONLY = Boolean.getBoolean("debug.readonly");

    private void debugDumpChatbot(WebDriver driver, int iter, String questionLabel) {
        if (!DEBUG_CHATBOT) return;
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            String html = (String) js.executeScript(
                "var el=document.querySelector(\"[class*='chatbot'],[class*='Chatbot']\");" +
                "if(!el) return '';" +
                "var p=el; for(var i=0;i<8 && p.parentElement;i++){ if((p.className+'').toLowerCase().indexOf('drawer')>=0) break; p=p.parentElement; }" +
                "return p.outerHTML;");
            Object btns = js.executeScript(
                "return JSON.stringify(Array.from(document.querySelectorAll('button,[role=button]')).map(function(b){" +
                "var r=b.getBoundingClientRect();" +
                "return {text:(b.innerText||b.textContent||'').trim().slice(0,40), cls:(b.className+'').slice(0,70)," +
                "vis:(r.width>0&&r.height>0), dis:b.disabled};}));");

            String block = "\n\n========== ITER " + iter + " | Q: " + questionLabel + " ==========\n" +
                "---- BUTTONS ----\n" + btns + "\n" +
                "---- DRAWER HTML ----\n" + html + "\n";
            java.nio.file.Files.writeString(java.nio.file.Paths.get("debug_chatbot.txt"), block,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            System.out.println("🐞 [debug] iter=" + iter + " Q=\"" + questionLabel + "\" buttons=" + btns);
        } catch (Exception e) {
            System.out.println("🐞 [debug] dump failed: " + e.getMessage());
        }
    }

    // Click the chatbot's Save/Continue control. In Naukri's chatbot this is a
    //   <div class="sendMsg">Save</div>  inside  <div class="send disabled">
    // — a DIV, not a <button>, and the parent carries a "disabled" class until the
    // current question is answered. A button-only search never finds it, which is
    // why the bot used to get stuck. Returns true only if a click actually fired.
    private boolean clickSaveButton(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // 1. Naukri chatbot send/save control (the real case).
        try {
            Object res = js.executeScript(
                "var send=document.querySelector(\"[id^='sendMsg'].send, .sendMsgbtn_container .send, [class*='chatbot'] .send\");" +
                "if(!send) return 'none';" +
                "if((send.className+'').toLowerCase().indexOf('disabled')>=0) return 'disabled';" +
                "var t=send.querySelector('.sendMsg')||send;" +
                "t.click();" +
                "return ((t.innerText||t.textContent||'Save').trim());");
            if (res instanceof String) {
                String s = (String) res;
                if (!s.equals("none") && !s.equals("disabled")) {
                    System.out.println("💾 Save clicked: " + s);
                    Utils.humanSleep(0.4, 0.8);
                    return true;
                }
                if (s.equals("disabled")) {
                    // Answer not registered yet — let the loop re-answer and retry.
                    System.out.println("⏳ Save still disabled — answer not registered yet.");
                    return false;
                }
            }
        } catch (Exception ignored) {}

        // 2. Fallback for <button>-based chatbot variants (other recruiters).
        WebElement chatContainer = null;
        for (String sel : new String[]{"[class*='chatbot_Drawer']", "[class*='chatbot']", "[class*='Chatbot']"}) {
            try {
                List<WebElement> els = driver.findElements(By.cssSelector(sel));
                if (!els.isEmpty()) { chatContainer = els.get(0); break; }
            } catch (Exception ignored) {}
        }
        if (chatContainer != null) {
            for (String text : new String[]{"Save", "Continue", "Next", "Submit", "Send", "Proceed"}) {
                try {
                    List<WebElement> btns = chatContainer.findElements(
                            By.xpath(".//button[normalize-space(.)='" + text + "']"));
                    for (WebElement btn : btns) {
                        if (btn.isDisplayed() && btn.isEnabled()) {
                            System.out.println("🔘 Save (button) clicked: " + btn.getText().trim());
                            js.executeScript("arguments[0].click();", btn);
                            Utils.humanSleep(0.4, 0.8);
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }
}