package com.autoapply.sites;

import com.autoapply.services.GulfAnswers;
import com.autoapply.services.JDFilter;
import com.autoapply.services.NaukriAnswers;
import com.autoapply.util.Config;
import com.autoapply.util.DB;
import com.autoapply.util.Utils;
import io.github.cdimascio.dotenv.Dotenv;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Naukri Gulf (naukrigulf.com) auto-apply bot.
 *
 * Flow: login → for each configured Gulf location, walk the search result pages
 * → title/JD/experience filter → open job in a new tab → click Apply → answer the
 * optional recruiter questionnaire → verify the job really shows as applied.
 *
 * Naukri Gulf's markup changes often, so every lookup uses several fallback
 * selectors (and text matching where possible). Run with -Ddebug.gulf=true to
 * dump the search page, job page and apply popup HTML into debug_gulf/ when a
 * selector needs re-tuning.
 */
public class NaukriGulfBot {
    private static final String SITE = "naukrigulf";
    private static final String BASE = "https://www.naukrigulf.com";
    private static final boolean DEBUG = Boolean.getBoolean("debug.gulf");

    private final Config cfg;
    private final DB db;
    private int debugSeq = 0;

    public NaukriGulfBot(Config cfg, DB db) {
        this.cfg = cfg;
        this.db = db;
    }

    public void run() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String email = dotenv.get("NAUKRIGULF_EMAIL");
        String pass = dotenv.get("NAUKRIGULF_PASSWORD");
        if (email == null || pass == null) {
            System.out.println("Missing NAUKRIGULF_EMAIL/NAUKRIGULF_PASSWORD in .env");
            return;
        }

        WebDriver driver = Utils.newDriver(cfg.getRun().isHeadless());
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(2));

        try {
            if (!login(driver, email, pass)) {
                System.out.println("🛑 Could not log in to Naukri Gulf. Aborting.");
                return;
            }

            Config.Site.NaukriGulf gcfg = cfg.getSite().getNaukrigulf();
            JDFilter filter = new JDFilter(cfg);
            int cap = cfg.getRun().getDaily_max_applications();
            int count = 0;

            List<String> keywords = gcfg.getKeywords().isEmpty()
                    ? List.of(String.join(" ", cfg.getSearch().getKeywords()))
                    : gcfg.getKeywords();
            List<String> locations = new ArrayList<>(gcfg.getLocations());
            if (locations.isEmpty()) locations.add("");

            // Each keyword gets its own search; jobs already seen under an earlier
            // keyword are skipped through the DB check.
            for (String keyword : keywords) {
                String kw = slug(keyword);
                System.out.println("🔑 Keyword: " + keyword);
                for (String loc : locations) {
                    for (int page = 1; page <= gcfg.getMax_pages(); page++) {
                        String url = searchUrl(kw, loc, page);
                        System.out.println("🔍 Search URL: " + url);
                        driver.get(url);
                        Utils.humanSleep(2.0, 3.0);
                        debugDump(driver, "srp");

                        List<Map<String, String>> jobs = collectJobs(driver);
                        System.out.println("📌 Jobs on page " + page + (loc.isEmpty() ? "" : " (" + loc + ")") + ": " + jobs.size());
                        if (jobs.isEmpty()) break;

                        String mainTab = driver.getWindowHandle();
                        for (Map<String, String> job : jobs) {
                            if (count >= cap) {
                                System.out.println("Reached daily max: " + cap);
                                return;
                            }
                            String link = job.get("href");
                            String title = job.get("title");
                            String company = job.getOrDefault("company", "");
                            String cardText = job.getOrDefault("text", "");
                            try {
                                if (db.exists(link)) {
                                    System.out.println("⏭️ Already processed: " + title);
                                    continue;
                                }
                                // Only "Easy Apply" cards apply on Naukri Gulf itself; the rest send
                            // you to the company site or do nothing on click.
                            if (gcfg.isEasy_apply_only() && !cardText.toLowerCase().contains("easy apply")) {
                                System.out.println("⏭️ Not Easy Apply: " + title);
                                continue;
                            }
                            // Naukri Gulf's own dummy postings ("Info_Test", "Testing", ...)
                                if (company.matches("(?i).*(^|[^a-z])test.*")) {
                                    System.out.println("⏭️ Test posting: " + title + " @ " + company);
                                    db.upsert(SITE, link, title, company, loc, "skipped", "test-posting");
                                    continue;
                                }
                                if (!filter.titleAllowed(title)) {
                                    System.out.println("⏭️ Title blocked: " + title);
                                    db.upsert(SITE, link, title, company, loc, "skipped", "title-filter");
                                    continue;
                                }
                                if (!experienceFits(cardText, gcfg.getExperience_years(), gcfg.getExperience_tolerance())) {
                                    System.out.println("⏭️ Experience mismatch: " + title);
                                    db.upsert(SITE, link, title, company, loc, "skipped", "experience");
                                    continue;
                                }

                                String status = applyToJob(driver, mainTab, link, title, filter);
                                switch (status) {
                                    case "applied" -> {
                                        count++;
                                        db.upsert(SITE, link, title, company, loc, "applied", "");
                                        System.out.println("✅ Applied (" + count + "/" + cap + "): " + title + " @ " + company);
                                    }
                                    case "already-applied" -> db.upsert(SITE, link, title, company, loc, "applied", "already-applied");
                                    // "failed" isn't treated as processed, so the job is retried next run
                                    case "not-confirmed" -> db.upsert(SITE, link, title, company, loc, "failed", status);
                                    default -> db.upsert(SITE, link, title, company, loc, "skipped", status);
                                }
                                Utils.humanSleep(cfg.getRun().getRandom_delay_sec_min(), cfg.getRun().getRandom_delay_sec_max());

                            } catch (Exception e) {
                                if (isDeadSession(e)) {
                                    System.out.println("🛑 Browser session lost — Chrome was closed, crashed, or updated mid-run. Aborting.");
                                    return;
                                }
                                System.out.println("⚠️ Job error: " + e.getMessage());
                                closeExtraTabs(driver, mainTab);
                            }
                        }
                    }
                }
            }
            System.out.println("🏁 Done. Applied to " + count + " jobs.");
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    // ===================== Login =====================

    private boolean login(WebDriver driver, String email, String pass) {
        driver.get(BASE + "/jobseeker/login");
        Utils.humanSleep(2.0, 3.0);

        WebElement emailField = firstVisible(driver,
                "input[type='email']", "input[name*='email' i]", "input[id*='email' i]",
                "input[name*='user' i]", "input[placeholder*='email' i]");
        if (emailField == null) {
            // Login page URL changed — open the login popup from the home page instead.
            driver.get(BASE);
            Utils.humanSleep(2.0, 3.0);
            clickByText(driver, "Login", "Log in", "Sign in");
            Utils.humanSleep(1.5, 2.0);
            emailField = firstVisible(driver,
                    "input[type='email']", "input[name*='email' i]", "input[id*='email' i]",
                    "input[name*='user' i]", "input[placeholder*='email' i]");
        }
        WebElement passField = firstVisible(driver, "input[type='password']");

        if (emailField != null && passField != null) {
            emailField.clear();
            emailField.sendKeys(email);
            passField.clear();
            passField.sendKeys(pass);
            Utils.humanSleep(0.5, 1.0);
            // Submit the password field's own form. A text search for "Login" hits the
            // header nav link (<a class="nav-link">LOGIN</a>) first, which just reloads
            // the login page — that's why auto-login used to fall through to manual.
            List<WebElement> submit = passField.findElements(By.xpath("./ancestor::form[1]//button[@type='submit']"));
            if (!submit.isEmpty()) submit.get(0).click();
            else passField.sendKeys(Keys.ENTER);
        } else {
            System.out.println("⚠️ Login form not found automatically.");
        }

        try {
            new WebDriverWait(driver, Duration.ofSeconds(25)).until(d -> isLoggedIn(d));
            System.out.println("✅ Login successful");
            return true;
        } catch (TimeoutException e) {
            if (!cfg.getRun().isManualCaptcha() || cfg.getRun().isHeadless()) return false;
        }

        // OTP / CAPTCHA / changed form — let the user finish login in the browser.
        System.out.println("🙋 Please complete the login manually in the browser (waiting up to 3 minutes)...");
        try {
            new WebDriverWait(driver, Duration.ofMinutes(3)).until(d -> isLoggedIn(d));
            System.out.println("✅ Login successful (manual)");
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    private boolean isLoggedIn(WebDriver d) {
        String url = d.getCurrentUrl().toLowerCase();
        if (url.contains("login")) return false;
        // Logged-out pages still show a visible password field (login popup)
        return firstVisible(d, "input[type='password']") == null;
    }

    // ===================== Search =====================

    // e.g. https://www.naukrigulf.com/java-developer-jobs-in-uae-2
    private String searchUrl(String kw, String loc, int page) {
        String url = BASE + "/" + kw + "-jobs" + (loc.isBlank() ? "" : "-in-" + slug(loc));
        return page > 1 ? url + "-" + page : url;
    }

    // Collect job links from the result page. Naukri Gulf job detail URLs contain
    // "-jid-<digits>", which is far more stable than the card CSS classes.
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> collectJobs(WebDriver driver) {
        // Lazy-loaded cards: scroll to the bottom once before reading.
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
        Utils.humanSleep(1.0, 1.5);
        js.executeScript("window.scrollTo(0, 0);");

        Object res = js.executeScript(
            "var seen={}, out=[];" +
            "document.querySelectorAll('a[href*=\"-jid-\"]').forEach(function(a){" +
            "  var href=a.href.split('?')[0].split('#')[0];" +
            "  if(seen[href]) return;" +
            "  var card=a.closest('[class*=\"tuple\"],[class*=\"ng-box\"],[class*=\"srp\"],article,li')||a.parentElement;" +
            "  var t=(a.getAttribute('title')||'').trim();" +
            "  if(!t){ var h=card.querySelector('[class*=\"designation\"],[class*=\"position\"],[class*=\"title\"],h2,h3');" +
            "          t=((h&&h.innerText)||a.innerText||'').trim().split('\\n')[0]; }" +
            "  if(!t) return;" +
            "  var c=card.querySelector('[class*=\"company\"],[class*=\"org\"]');" +
            "  seen[href]=1;" +
            "  out.push({href:href,title:t,company:c?c.innerText.trim().split('\\n')[0]:'',text:(card.innerText||'').slice(0,600)});" +
            "});" +
            "return out;");
        List<Map<String, String>> jobs = new ArrayList<>();
        if (res instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                Map<String, String> j = new HashMap<>();
                m.forEach((k, v) -> j.put(k, v == null ? "" : String.valueOf(v)));
                jobs.add(j);
            }
        }
        return jobs;
    }

    // Card shows e.g. "3 - 8 Years". Lower-experience jobs are always kept; skip
    // only when the job's minimum exceeds years + tolerance. Unknown format = allow.
    private boolean experienceFits(String text, int years, int tolerance) {
        // "years?" — cards say "0 - 1 Year" (singular) too
        Matcher m = Pattern.compile("(\\d+)\\s*(?:-|to)\\s*\\d+\\s*(?:yrs?|years?)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return true;
        return Integer.parseInt(m.group(1)) <= years + tolerance;
    }

    // ===================== Apply =====================

    private String applyToJob(WebDriver driver, String mainTab, String link, String title, JDFilter filter) {
        ((JavascriptExecutor) driver).executeScript("window.open(arguments[0], '_blank');", link);
        switchToNewestTab(driver);
        Utils.humanSleep(2.0, 2.5);
        try {
            debugDump(driver, "job");

            String jd = textOf(driver, "[class*='job-description']", "[class*='jobDescription']",
                    "[class*='jd-']", "[class*='description']");
            if (jd.isEmpty()) jd = textOf(driver, "body");
            if (!filter.titleHasInclude(title) && !filter.shouldApply(title, jd)) {
                System.out.println("⏭️ JD filter skip: " + title);
                return "filter";
            }

            if (isAppliedOnPage(driver)) {
                System.out.println("⏭️ Already applied earlier: " + title);
                return "already-applied";
            }

            WebElement applyBtn = findApplyButton(driver);
            if (applyBtn == null) {
                System.out.println("❌ No Apply button (company-site / expired?): " + title);
                return "no-apply-button";
            }

            int tabsBefore = driver.getWindowHandles().size();
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", applyBtn);
            Utils.humanSleep(0.4, 0.8);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", applyBtn);
            Utils.humanSleep(2.0, 2.5);
            // Plain "Apply" buttons sometimes ignore a JS click (page stays unchanged).
            // Retry once with a real mouse click before giving up.
            if (driver.getWindowHandles().size() == tabsBefore && findQuestionForm(driver) == null
                    && !isApplyConfirmed(driver)) {
                try {
                    if (applyBtn.isDisplayed()) {
                        System.out.println("🔁 No reaction to Apply — retrying with a real click");
                        applyBtn.click();
                        Utils.humanSleep(2.0, 2.5);
                    }
                } catch (Exception ignored) {}
            }
            debugDump(driver, "after-apply");

            if (driver.getWindowHandles().size() > tabsBefore || !driver.getCurrentUrl().contains("naukrigulf.com")) {
                System.out.println("↗️ Redirects to company website — skipping: " + title);
                return "external-apply";
            }

            return answerQuestionnaire(driver) ? "applied" : "not-confirmed";
        } finally {
            closeExtraTabs(driver, mainTab);
        }
    }

    private WebElement findApplyButton(WebDriver driver) {
        List<WebElement> candidates = driver.findElements(By.xpath(
                "//button | //a | //*[@role='button'] | //input[@type='button' or @type='submit']"));
        for (WebElement el : candidates) {
            try {
                if (!el.isDisplayed()) continue;
                String t = (el.getText() + " " + Objects.toString(el.getAttribute("value"), "")).trim().toLowerCase();
                if (t.isEmpty() || t.length() > 25) continue;
                if (t.contains("company") || t.contains("website") || t.contains("applied") || t.contains("similar")) continue;
                if (t.equals("apply") || t.equals("apply now") || t.equals("easy apply") || t.equals("quick apply")
                        || t.equals("apply job") || t.equals("apply for this job")) {
                    System.out.println("🔘 Apply button found: '" + t + "'");
                    return el;
                }
            } catch (StaleElementReferenceException ignored) {}
        }
        return null;
    }

    // Handles the optional recruiter questionnaire popup, then verifies.
    // Returns true only when the page confirms the application.
    private boolean answerQuestionnaire(WebDriver driver) {
        String lastSignature = "";
        int stale = 0;

        for (int step = 0; step < 15; step++) {
            if (isApplyConfirmed(driver)) {
                System.out.println("🔎 Verify: confirmation detected → applied=true");
                return true;
            }

            WebElement form = findQuestionForm(driver);
            if (form == null) {
                if (++stale > 3) break;
                Utils.humanSleep(1.0, 1.5);
                continue;
            }
            debugDump(driver, "questions");

            String signature = form.getText();
            if (signature.equals(lastSignature)) {
                if (++stale > 3) { System.out.println("⚠️ Questionnaire stuck (required field we can't answer?)."); break; }
            } else {
                stale = 0;
                lastSignature = signature;
            }

            fillForm(driver, form);
            Utils.humanSleep(0.5, 1.0);
            if (!clickSubmit(driver, form)) {
                System.out.println("⚠️ No Submit/Next button found in questionnaire.");
            }
            Utils.humanSleep(1.5, 2.0);
        }

        boolean ok = isApplyConfirmed(driver);
        System.out.println("🔎 Verify: final check → applied=" + ok);
        return ok;
    }

    // The visible popup / drawer that holds unanswered questions.
    private WebElement findQuestionForm(WebDriver driver) {
        String[] sels = {"[role='dialog']", "[class*='modal' i]", "[class*='questionnaire' i]",
                "[class*='question' i]", "[class*='popup' i]", "[class*='drawer' i]"};
        for (String sel : sels) {
            for (WebElement el : driver.findElements(By.cssSelector(sel))) {
                try {
                    if (!el.isDisplayed()) continue;
                    List<WebElement> inputs = el.findElements(By.cssSelector(
                            "input:not([type='hidden']):not([type='search']), textarea, select"));
                    boolean anyVisible = inputs.stream().anyMatch(i -> {
                        try { return i.isDisplayed() || "radio".equals(i.getAttribute("type")) || "checkbox".equals(i.getAttribute("type")); }
                        catch (Exception e) { return false; }
                    });
                    // Ignore the site search bar / login widgets
                    if (anyVisible && el.findElements(By.cssSelector("input[type='password']")).isEmpty()) return el;
                } catch (StaleElementReferenceException ignored) {}
            }
        }
        return null;
    }

    private void fillForm(WebDriver driver, WebElement form) {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Radio groups — one answer per name
        Map<String, List<WebElement>> groups = new LinkedHashMap<>();
        for (WebElement r : form.findElements(By.cssSelector("input[type='radio']"))) {
            groups.computeIfAbsent(Objects.toString(r.getAttribute("name"), "_"), k -> new ArrayList<>()).add(r);
        }
        for (List<WebElement> group : groups.values()) {
            if (group.stream().anyMatch(WebElement::isSelected)) continue;
            String q = questionText(driver, group.get(0));
            String ans = GulfAnswers.getAnswer(q);
            WebElement pick = pickOption(driver, group, ans);
            js.executeScript("arguments[0].click();", pick);
            System.out.println("🔘 Q: " + q + " → " + labelOf(driver, pick));
            Utils.humanSleep(0.2, 0.4);
        }

        // Checkbox questions (Yes/No or multi-select) — tick the best match if none ticked
        Map<String, List<WebElement>> cbGroups = new LinkedHashMap<>();
        for (WebElement c : form.findElements(By.cssSelector("input[type='checkbox']"))) {
            cbGroups.computeIfAbsent(Objects.toString(c.getAttribute("name"), "_"), k -> new ArrayList<>()).add(c);
        }
        for (List<WebElement> group : cbGroups.values()) {
            if (group.stream().anyMatch(WebElement::isSelected)) continue;
            String q = questionText(driver, group.get(0));
            WebElement pick = pickOption(driver, group, GulfAnswers.getAnswer(q));
            js.executeScript("arguments[0].click();", pick);
            System.out.println("☑️ Q: " + q + " → " + labelOf(driver, pick));
        }

        // Dropdowns
        for (WebElement s : form.findElements(By.tagName("select"))) {
            try {
                Select sel = new Select(s);
                String current = sel.getFirstSelectedOption().getText().trim().toLowerCase();
                if (!current.isEmpty() && !current.startsWith("select") && !current.startsWith("choose")) continue;
                String q = questionText(driver, s);
                String ans = normalizePeriod(GulfAnswers.getAnswer(q).toLowerCase());
                List<WebElement> opts = sel.getOptions();
                WebElement best = null;
                for (WebElement o : opts) {
                    String t = normalizePeriod(o.getText().trim().toLowerCase());
                    if (t.isEmpty() || t.startsWith("select") || t.startsWith("choose")) continue;
                    if (best == null) best = o;
                    if (t.equals(ans) || t.contains(ans) || ans.contains(t)) { best = o; break; }
                }
                if (best != null) {
                    sel.selectByVisibleText(best.getText());
                    System.out.println("🔽 Q: " + q + " → " + best.getText().trim());
                }
            } catch (Exception ignored) {}
        }

        // "Droope" dropdowns: a read-only-looking <input class="float-droope-input"
        // placeholder="Select"> plus a hidden <ul> of <li class="list-item">. Typed
        // text is ignored by the site — an <li> has to be clicked.
        for (WebElement in : form.findElements(By.cssSelector("input.float-droope-input"))) {
            try {
                if (!Objects.toString(in.getAttribute("value"), "").isBlank()) continue;
                String q = questionText(driver, in);
                String ans = normalizePeriod(GulfAnswers.getAnswer(q).toLowerCase());
                WebElement group = in.findElement(By.xpath("./ancestor::div[contains(@class,'input-row')][1]"));
                // Open the list like a user would (real click), then wait for it to show.
                js.executeScript("arguments[0].scrollIntoView({block:'center'});", in);
                in.click();
                List<WebElement> items = group.findElements(By.cssSelector("li.list-item"));
                if (items.isEmpty()) {
                    System.out.println("⚠️ Dropdown has no options: " + q);
                    continue;
                }
                try {
                    new WebDriverWait(driver, Duration.ofSeconds(3))
                            .until(d -> items.stream().anyMatch(WebElement::isDisplayed));
                } catch (TimeoutException e) {
                    System.out.println("⚠️ Dropdown list did not open: " + q);
                }
                WebElement best = null;
                for (WebElement li : items) {
                    String t = normalizePeriod(li.getAttribute("textContent").trim().toLowerCase());
                    if (t.isEmpty()) continue;
                    if (best == null) best = li;
                    if (t.equals(ans) || ans.matches(".*\\b" + Pattern.quote(t) + "\\b.*")
                            || t.matches(".*\\b" + Pattern.quote(ans) + "\\b.*")) { best = li; break; }
                }
                if (best == null) continue;
                String choice = best.getAttribute("textContent").trim();

                // A JS .click() on the <li> only paints the text into the box; the
                // widget needs real mouse events. Real click when visible, otherwise
                // fire the full mousedown/mouseup/click sequence.
                if (best.isDisplayed()) {
                    new org.openqa.selenium.interactions.Actions(driver).moveToElement(best).click().perform();
                } else {
                    js.executeScript(
                        "['mousedown','mouseup','click'].forEach(function(t){" +
                        "arguments[0].dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});", best);
                }
                Utils.humanSleep(0.4, 0.7);

                String shown = Objects.toString(in.getAttribute("value"), "").trim();
                boolean ok = shown.equalsIgnoreCase(choice)
                        || !group.findElements(By.cssSelector(".selected, .active, [class*='chip'], [class*='tag']")).isEmpty();
                System.out.println("🔽 Q: " + q + " → " + choice + (ok ? "" : "  (⚠️ selection not confirmed, box shows '" + shown + "')"));
            } catch (Exception e) {
                System.out.println("⚠️ Dropdown fill failed: " + e.getMessage());
            }
        }

        // Text / number / textarea — only fill empty, visible fields
        for (WebElement in : form.findElements(By.cssSelector(
                "input[type='text'], input[type='number'], input[type='tel'], input:not([type]), textarea"))) {
            try {
                if (!in.isDisplayed() || !in.isEnabled()) continue;
                if (Objects.toString(in.getAttribute("class"), "").contains("droope")) continue;
                if (!Objects.toString(in.getAttribute("value"), "").isBlank()) continue;
                String q = questionText(driver, in);
                String ans = GulfAnswers.getAnswer(q);
                if ("number".equals(in.getAttribute("type"))) ans = ans.replaceAll("[^0-9.]", "");
                in.click();
                in.sendKeys(ans);
                js.executeScript("arguments[0].dispatchEvent(new Event('input',{bubbles:true}));" +
                        "arguments[0].dispatchEvent(new Event('change',{bubbles:true}));", in);
                System.out.println("✏️ Q: " + q + " → " + ans);
            } catch (Exception ignored) {}
        }
    }

    private static String normalizePeriod(String s) {
        return NaukriAnswers.normalizePeriod(s);
    }

    private WebElement pickOption(WebDriver driver, List<WebElement> options, String answer) {
        String ans = normalizePeriod(answer.toLowerCase().trim());
        for (WebElement o : options) {
            String l = normalizePeriod(labelOf(driver, o).toLowerCase());
            if (l.equals(ans)) return o;
        }
        for (WebElement o : options) {
            String l = normalizePeriod(labelOf(driver, o).toLowerCase());
            if (l.length() < 2) continue;
            // word-boundary match so "no" doesn't hit "noida"/"not"
            if (l.matches(".*\\b" + Pattern.quote(ans) + "\\b.*") || ans.matches(".*\\b" + Pattern.quote(l) + "\\b.*")) return o;
        }
        return options.get(0);
    }

    private String labelOf(WebDriver driver, WebElement input) {
        try {
            String id = input.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                List<WebElement> l = driver.findElements(By.cssSelector("label[for='" + id.replace("'", "\\'") + "']"));
                if (!l.isEmpty() && !l.get(0).getText().isBlank()) return l.get(0).getText().trim();
            }
        } catch (Exception ignored) {}
        try {
            String t = input.findElement(By.xpath("./ancestor::label[1]")).getText().trim();
            if (!t.isEmpty()) return t;
        } catch (Exception ignored) {}
        try { return input.findElement(By.xpath("..")).getText().trim(); } catch (Exception ignored) {}
        return Objects.toString(input.getAttribute("value"), "");
    }

    // Best-effort question text for an input: its own label/placeholder, or the
    // first line of the nearest ancestor block that carries some text.
    private String questionText(WebDriver driver, WebElement input) {
        Object r = ((JavascriptExecutor) driver).executeScript(
            "var el=arguments[0];" +
            "var a=el.getAttribute('aria-label')||'';" +
            "if(a.trim()) return a.trim();" +
            "var type=(el.getAttribute('type')||'').toLowerCase();" +
            "if(type!=='radio' && type!=='checkbox'){" +
            "  if(el.id){ var l=document.querySelector('label[for=\"'+el.id+'\"]'); if(l&&l.innerText.trim()) return l.innerText.trim(); }" +
            "  if(el.placeholder&&el.placeholder.trim().length>3) return el.placeholder.trim();" +
            "}" +
            // Naukri Gulf: <div class="form-row"><label class="question">Q</label><div class="answers">…
            "var row=el.closest('.form-row');" +
            "if(row){ var ql=row.querySelector('.question'); if(ql&&ql.innerText.trim()) return ql.innerText.trim(); }" +
            "var p=el.parentElement;" +
            "for(var i=0;i<6&&p;i++){" +
            "  var own=p.querySelector(':scope > .question, :scope > label.question');" +
            "  if(own&&own.innerText.trim()) return own.innerText.trim();" +
            "  var cls=(p.className+'').toLowerCase();" +
            "  var t=(p.innerText||'').trim();" +
            "  if(t && (cls.indexOf('question')>=0 || cls.indexOf('ques')>=0 || p.tagName==='FIELDSET' || t.split('\\n').length>2))" +
            "    return t.split('\\n')[0];" +
            "  p=p.parentElement;" +
            "}" +
            "return '';", input);
        return r == null ? "" : r.toString();
    }

    private boolean clickSubmit(WebDriver driver, WebElement form) {
        String[] texts = {"submit", "apply", "apply now", "next", "continue", "save", "send", "proceed", "done"};
        List<WebElement> btns = form.findElements(By.cssSelector("button, [role='button'], input[type='submit']"));
        // The popup's own buttons may sit just outside the matched form element
        if (btns.isEmpty()) btns = driver.findElements(By.cssSelector("[role='dialog'] button, [class*='modal' i] button"));
        for (String want : texts) {
            for (WebElement b : btns) {
                try {
                    if (!b.isDisplayed() || !b.isEnabled()) continue;
                    String t = (b.getText() + " " + Objects.toString(b.getAttribute("value"), "")).trim().toLowerCase();
                    // prefix match too: the questionnaire button reads "Submit & Apply"
                    if (t.equals(want) || t.startsWith(want + " ")) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", b);
                        System.out.println("💾 Clicked: " + b.getText().trim());
                        return true;
                    }
                } catch (StaleElementReferenceException ignored) {}
            }
        }
        return false;
    }

    // ===================== Verification =====================

    private boolean isApplyConfirmed(WebDriver driver) {
        String body = textOf(driver, "body").toLowerCase();
        for (String phrase : new String[]{"successfully applied", "applied successfully", "application has been sent",
                "application submitted", "you have applied", "application sent successfully"}) {
            if (body.contains(phrase)) {
                System.out.println("🔎 Confirmed by page text: \"" + phrase + "\"");
                debugDump(driver, "confirmed");
                return true;
            }
        }
        if (isAppliedOnPage(driver)) {
            System.out.println("🔎 Confirmed by Applied button on job page");
            debugDump(driver, "confirmed");
            return true;
        }
        return false;
    }

    // The job page's apply button turns into "Applied" once the application exists.
    // On revisits it is rendered as <p class="jd-button applied-text">Already Applied</p>.
    private boolean isAppliedOnPage(WebDriver driver) {
        for (WebElement el : driver.findElements(By.cssSelector(".applied-text"))) {
            try { if (el.isDisplayed()) return true; } catch (Exception ignored) {}
        }
        for (WebElement el : driver.findElements(By.xpath(
                "//button[normalize-space()='Applied'] | //a[normalize-space()='Applied'] | //*[@role='button' and normalize-space()='Applied']" +
                " | //*[contains(@class,'applied') and normalize-space()='Applied']"))) {
            try { if (el.isDisplayed()) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    // ===================== Helpers =====================

    private static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private WebElement firstVisible(WebDriver driver, String... selectors) {
        for (String sel : selectors) {
            try {
                for (WebElement el : driver.findElements(By.cssSelector(sel))) {
                    if (el.isDisplayed()) return el;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean clickByText(WebDriver driver, String... texts) {
        for (String text : texts) {
            List<WebElement> els = driver.findElements(By.xpath(
                    "//button[normalize-space()='" + text + "'] | //a[normalize-space()='" + text + "']" +
                    " | //input[@type='submit' and @value='" + text + "']"));
            for (WebElement el : els) {
                try {
                    if (el.isDisplayed()) {
                        ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
                        return true;
                    }
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private String textOf(WebDriver driver, String... selectors) {
        for (String sel : selectors) {
            try {
                for (WebElement el : driver.findElements(By.cssSelector(sel))) {
                    String t = el.getText();
                    if (t != null && !t.isBlank()) return t;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private void switchToNewestTab(WebDriver driver) {
        List<String> tabs = new ArrayList<>(driver.getWindowHandles());
        driver.switchTo().window(tabs.get(tabs.size() - 1));
    }

    private void closeExtraTabs(WebDriver driver, String mainTab) {
        try {
            for (String h : driver.getWindowHandles()) {
                if (!h.equals(mainTab)) {
                    driver.switchTo().window(h);
                    driver.close();
                }
            }
            driver.switchTo().window(mainTab);
        } catch (Exception ignored) {}
    }

    private boolean isDeadSession(Throwable e) {
        if (e instanceof NoSuchSessionException) return true;
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("invalid session id");
    }

    // -Ddebug.gulf=true → save page HTML to debug_gulf/ for selector tuning.
    private void debugDump(WebDriver driver, String tag) {
        if (!DEBUG) return;
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("debug_gulf");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve(String.format("%03d-%s.html", ++debugSeq, tag));
            java.nio.file.Files.writeString(f, "<!-- " + driver.getCurrentUrl() + " -->\n" + driver.getPageSource());
            System.out.println("🐞 [debug] saved " + f);
        } catch (Exception e) {
            System.out.println("🐞 [debug] dump failed: " + e.getMessage());
        }
    }
}
