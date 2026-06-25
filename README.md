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
- Copy `.env.example` to `.env` and fill credentials:
```
NAUKRI_EMAIL=you@example.com
NAUKRI_PASSWORD=yourpassword
LINKEDIN_EMAIL=you@example.com
LINKEDIN_PASSWORD=yourpassword
```
- Edit `src/main/resources/config.yaml` for keywords/locations/delays.
- Put your resume at `src/main/resources/resume.pdf`.

**Note:** This starter does not bypass CAPTCHAs and may require manual steps if portals change UI. Test on a spare account first.
