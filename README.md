# Java Job Auto-Apply Bot (Selenium)

> Educational starter to automate job applications (Naukri + LinkedIn Easy Apply demo). **Use responsibly and respect Terms of Service.**

## Features
- Selenium + WebDriverManager (no manual driver path).
- YAML config for keywords, locations, caps.
- Heuristic JD filter (keywords include/exclude).
- Optional simple cover letter template.
- SQLite logging of applications.
- Manual CAPTCHA pause.

## Quick Start
```bash
# Java 17 + Maven required
mvn -v

# Build
mvn clean package

# Run (Naukri)
java -jar target/java-job-auto-apply-bot-1.0.0-jar-with-dependencies.jar --site=naukri --headful --manual-captcha

# Run (LinkedIn demo)
java -jar target/java-job-auto-apply-bot-1.0.0-jar-with-dependencies.jar --site=linkedin --headful --manual-captcha
```

### Configure

**1. Credentials** — copy `.env.example` to `.env` and fill your login details:
```bash
cp .env.example .env        # Windows: copy .env.example .env
```
```
NAUKRI_EMAIL=you@example.com
NAUKRI_PASSWORD=yourpassword
LINKEDIN_EMAIL=you@example.com
LINKEDIN_PASSWORD=yourpassword
```

**2. Personal answers** — copy `answers.example.yaml` to `answers.yaml` and fill your own
values (CTC, college, company, DOB, location, etc.). The bot uses these to auto-answer
Naukri's application chatbot questions:
```bash
cp answers.example.yaml answers.yaml    # Windows: copy answers.example.yaml answers.yaml
```
```yaml
current_ctc: "12"
expected_ctc: "18"
current_company: "Your Company"
college: "Your College"
# ...see answers.example.yaml for all fields
```
> `answers.yaml` is read at runtime from the working directory. If it's missing, the bot
> still runs using built-in example defaults, so fill it in for accurate answers.

**3. Search config** — edit `src/main/resources/config.yaml` for keywords/locations/delays.

**4. Resume** — put your resume at `src/main/resources/resume.pdf`.

> **Note:** `.env`, `answers.yaml`, `resume.pdf` and `applications.db` are gitignored, so your
> personal data never gets committed.

**Note:** This starter does not bypass CAPTCHAs and may require manual steps if portals change UI. Test on a spare account first.
