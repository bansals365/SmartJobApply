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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
        // No implicit wait: the bot probes many selectors that are legitimately
        // absent (fallback company/JD/apply selectors, chatbot input types), and an
        // implicit wait made every miss cost its full timeout — 30-60s per job.
        // Places that really need to wait use explicit WebDriverWaits instead.
        driver.manage().timeouts().implicitlyWait(Duration.ZERO);

        try {
            // === Login ===
            driver.get("https://www.naukri.com/nlogin/login");
            new WebDriverWait(driver, Duration.ofSeconds(20))
                    .until(ExpectedConditions.visibilityOfElementLocated(By.id("usernameField")))
                    .sendKeys(email);
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

            // === Setup ===
            JDFilter filter = new JDFilter(cfg);
            int count = 0;
            int cap = cfg.getRun().getDaily_max_applications();
            int maxPages = cfg.getSite().getNaukri().getMax_scroll_pages();
            int minExp = cfg.getSite().getNaukri().getMin_experience();
            String loc = Optional.ofNullable(cfg.getSearch().getLocation()).orElse("").toLowerCase().trim();
            String locSlug = loc.isEmpty() ? "" : "-in-" + loc.replaceAll("\\s+", "-");

            // === Search + pagination, driven entirely by URL ===
            // Keyword, experience and page number go into the URL instead of dragging
            // the experience slider and clicking page links — the clicks drifted into
            // unrelated results (pages full of "Sales Executive" for a Java search).
            try {
                for (String keyword : cfg.getSearch().getKeywords()) {
                    String kwSlug = keyword.toLowerCase().trim().replaceAll("\\s+", "-");
                    String query = "?k=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                            + (loc.isEmpty() ? "" : "&l=" + URLEncoder.encode(loc, StandardCharsets.UTF_8))
                            + "&experience=" + minExp + "&jobAge=" + cfg.getSite().getNaukri().getJob_age_days();

                    for (int page = 1; page <= maxPages; page++) {
                        String url = "https://www.naukri.com/" + kwSlug + "-jobs" + locSlug + (page > 1 ? "-" + page : "") + query;
                        driver.get(url);
                        System.out.println("➡️ [" + keyword + "] Page " + page + ": " + url);

                        // === Job Cards Loop ===
                        List<WebElement> cards;
                        try {
                            cards = new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                                    ExpectedConditions.presenceOfAllElementsLocatedBy(By.cssSelector("div.srp-jobtuple-wrapper")));
                        } catch (TimeoutException e) {
                            cards = List.of();
                        }
                        Utils.humanSleep(0.8, 1.2);
                        System.out.println("📌 Cards on page: " + cards.size());
                        if (cards.isEmpty()) {
                            System.out.println("✅ No more results for: " + keyword);
                            break;
                        }
                        // Naukri pads thin result sets with unrelated jobs (Sales Executive,
                        // ...). Once a whole page has no relevant title, later pages won't either.
                        boolean anyRelevant = cards.stream().anyMatch(c -> c.findElements(By.cssSelector("a.title")).stream()
                                .anyMatch(a -> filter.titleHasInclude(a.getText())));
                        if (!anyRelevant) {
                            System.out.println("✅ No relevant jobs left for: " + keyword);
                            break;
                        }

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
                                for (String sel : new String[]{"a.comp-name", ".comp-name", "[class*='comp-name']", ".subTitle"}) {
                                    List<WebElement> c = card.findElements(By.cssSelector(sel));
                                    if (!c.isEmpty() && !c.get(0).getText().isBlank()) { company = c.get(0).getText().trim(); break; }
                                }
                                if (company.isEmpty()) {
                                    // Card text is "title\ncompany\nrating..." — take the line after the title
                                    String[] lines = card.getText().split("\\R");
                                    if (lines.length > 1) company = lines[1].trim();
                                }

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
                                if (!filter.companyAllowed(company)) {
                                    System.out.println("⏭️ Company blocked: " + title + " @ " + company);
                                    db.upsert("naukri", link, title, company, "", "skipped", "company-filter");
                                    continue;
                                }

                                ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", link);
                                ArrayList<String> tabs = new ArrayList<>(driver.getWindowHandles());
                                driver.switchTo().window(tabs.get(tabs.size() - 1));
                                // Wait for the job page to render its apply control or JD
                                try {
                                    new WebDriverWait(driver, Duration.ofSeconds(12)).until(d -> !d.findElements(By.cssSelector(
                                            "#apply-button, #company-site-button, [class*='apply-button'], .job-desc, [class*='job-desc'], .dang-inner-html")).isEmpty());
                                } catch (TimeoutException e) {
                                    System.out.println("⚠️ Job page slow to load: " + title);
                                }
                                Utils.humanSleep(0.5, 0.8);

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
                                String skipReason = "chatbot-incomplete";
                                try {
                                    WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
                                    By applyBy = findApplyButton(driver);
                                    if (applyBy == null) {
                                        skipReason = isExternalApply(driver) ? "external-apply" : "no-apply-button";
                                        throw new Exception(skipReason.equals("external-apply")
                                                ? "apply on company site (external)" : "No easy apply button found");
                                    }
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
                                    db.upsert("naukri", link, title, company, "", "skipped", skipReason);
                                    System.out.println("⏭️ Not applied (" + skipReason + "): " + title + " @ " + company);
                                }

                                driver.close();
                                driver.switchTo().window(tabs.get(0));
                                Utils.humanSleep(cfg.getRun().getRandom_delay_sec_min(), cfg.getRun().getRandom_delay_sec_max());

                            } catch (Exception e) {
                                // Dead browser session (Chrome closed/crashed/updated mid-run):
                                // every further command will fail, so abort instead of spamming.
                                if (isDeadSession(e)) {
                                    System.out.println("🛑 Browser session lost (invalid session id) — Chrome was closed, crashed, or updated mid-run. Aborting.");
                                    return;
                                }
                                System.out.println("⚠️ Card error: " + e.getMessage());
                                // Recover to main tab if we somehow lost it
                                try {
                                    ArrayList<String> tabs = new ArrayList<>(driver.getWindowHandles());
                                    if (tabs.size() > 1) { driver.close(); driver.switchTo().window(tabs.get(0)); }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (isDeadSession(e)) {
                    System.out.println("🛑 Browser session lost (invalid session id) — Chrome was closed, crashed, or updated mid-run. Aborting.");
                } else {
                    System.out.println("❌ Pagination error: " + e.getMessage());
                }
            }

        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    // A dead browser session (Chrome closed/crashed/updated mid-run) surfaces as
    // NoSuchSessionException, or any WebDriverException whose message carries
    // "invalid session id". Once it happens every subsequent command fails, so
    // the run must abort rather than log the same error for every remaining card.
    private boolean isDeadSession(Throwable e) {
        if (e instanceof NoSuchSessionException) return true;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("invalid session id");
    }

    // Every kind of answer control the chatbot renders (union of the selectors used below)
    private static final String CHATBOT_INPUTS =
            "[class*='chatbot'] input[type='checkbox'], [class*='Chatbot'] input[type='checkbox'], "
            + "[class*='chatbot'] input[type='radio'], [class*='Chatbot'] input[type='radio'], "
            + ".suggestedReplies button, .chatbot_Options button, [class*='optionItem'] button, [class*='botButton'], [class*='chatbot'] li button, "
            + "div[contenteditable='true'].textArea, "
            + "[class*='chatbot'] input[type='text'], [class*='Chatbot'] input[type='text'], input[placeholder*='message'], input[placeholder*='Message']";

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
                        lower.contains("answer all the recruiter's questions") ||                        lower.contains("please answer");
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

                // The question bubble can render before its answer controls — wait briefly for them
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(3))
                            .until(d -> !d.findElements(By.cssSelector(CHATBOT_INPUTS)).isEmpty());
                } catch (TimeoutException ignored) {}

                String answer = NaukriAnswers.getAnswer(labelText);
                System.out.println("❓ Q: " + labelText.replaceAll("\\s+", " ") + " → " + answer);

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
                        // Multi-select question. City lists match preferred locations;
                        // anything else (skills, tools, ...) matches the answer text or
                        // the include keywords. Guard against tiny labels (e.g. "no")
                        // matching a substring.
                        List<String> wanted = new ArrayList<>(cfg.getSite().getNaukri().getPreferred_locations());
                        wanted.addAll(cfg.getFilters().getInclude_keywords());
                        for (WebElement cb : checkboxes) {
                            String lbl = getRadioLabel(driver, cb).toLowerCase().trim();
                            if (lbl.contains("skip") || lbl.length() < 3) continue;
                            boolean match = ans.matches(".*\\b" + java.util.regex.Pattern.quote(lbl) + "\\b.*")
                                    || wanted.stream().anyMatch(w -> lbl.contains(w) || (lbl.length() >= 4 && w.contains(lbl)));
                            if (match) {
                                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", cb);
                                System.out.println("☑️ Selected: " + getRadioLabel(driver, cb));
                                anyChecked = true;
                                Utils.humanSleep(0.2, 0.4);
                            }
                        }
                    }

                    if (!anyChecked && skipCheckbox != null) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", skipCheckbox);
                        System.out.println("⏭️ No match available, skipping. Options: " + optionLabels(driver, checkboxes));
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
                    System.out.println("🔘 Selected: " + getRadioLabel(driver, bestRadio) + "  (options: " + optionLabels(driver, radioInputs) + ")");
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
                // Only visible boxes — a hidden one exists before the question needs it
                List<WebElement> inputs = new ArrayList<>(driver.findElements(By.cssSelector("div[contenteditable='true'].textArea")));
                inputs.removeIf(e -> !e.isDisplayed());
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
                List<WebElement> regularInputs = new ArrayList<>(driver.findElements(
                        By.cssSelector("[class*='chatbot'] input[type='text'], [class*='Chatbot'] input[type='text'], input[placeholder*='message'], input[placeholder*='Message']")
                ));
                regularInputs.removeIf(e -> !e.isDisplayed());
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
            } catch (ElementNotInteractableException e) {
                // Control rendered but not usable yet — re-answer the same question next pass
                // instead of abandoning the application (the loop's iteration cap bounds retries).
                System.out.println("⏳ Answer control not interactable yet, retrying");
                lastBotMsg = "";
                Utils.humanSleep(0.8, 1.2);
            } catch (TimeoutException | NoSuchElementException e) {
                break;
            }
        }

        // Final verification. The ONLY trustworthy signals that the job was
        // actually submitted are: (1) the saveApply confirmation page, or
        // (2) the apply button flipping to Applied/Withdraw. A bare "job URL"
        // is NOT proof — that used to mark un-submitted jobs as applied.
        // Give the post-submit redirect to saveApply a moment to happen.
        try {
            new WebDriverWait(driver, Duration.ofSeconds(6)).until(d -> isApplyConfirmed(d));
        } catch (Exception ignored) {}
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

    // Jobs that redirect to the employer's own site have an "Apply on company
    // site" button instead of the Naukri chatbot — nothing to automate there.
    private boolean isExternalApply(WebDriver driver) {
        try {
            return !driver.findElements(By.xpath(
                "//button[contains(translate(normalize-space(.),'ABCDEFGHIJKLMNOPQRSTUVWXYZ','abcdefghijklmnopqrstuvwxyz'),'company site')]"
                + " | //*[@id='company-site-button']")).isEmpty();
        } catch (Exception e) {
            return false;
        }
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

    // Pick the radio input whose label best matches the answer. Notice-period
    // options ("1 Month", "3 months", "90 days") are normalised to "N days" first.
    // Word-boundary matching so "no" doesn't hit "noida"/"not", and empty labels
    // (not rendered yet) never match everything.
    private WebElement pickBestRadio(WebDriver driver, List<WebElement> radios, String answer) {
        String ans = NaukriAnswers.normalizePeriod(answer.toLowerCase().trim());
        for (WebElement radio : radios) {
            String labelText = NaukriAnswers.normalizePeriod(getRadioLabel(driver, radio).toLowerCase().trim());
            if (labelText.equals(ans)) return radio;
        }
        for (WebElement radio : radios) {
            String labelText = NaukriAnswers.normalizePeriod(getRadioLabel(driver, radio).toLowerCase().trim());
            if (labelText.length() < 2) continue;
            if (labelText.matches(".*\\b" + java.util.regex.Pattern.quote(ans) + "\\b.*")
                    || ans.matches(".*\\b" + java.util.regex.Pattern.quote(labelText) + "\\b.*")) return radio;
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

    private String optionLabels(WebDriver driver, List<WebElement> inputs) {
        List<String> labels = new ArrayList<>();
        for (WebElement in : inputs) labels.add(getRadioLabel(driver, in));
        return String.join(" | ", labels);
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