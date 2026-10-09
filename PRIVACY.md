# Public-repository privacy

CamPulse is published from a reviewed source snapshot with **fresh Git history**. The earlier private repository, device inventories, account files, logs, captures, build outputs, and IDE state are not part of this repository. Do not merge, mirror, or push the private repository's branches or tags here: deleting a file in a later commit does not remove it from history.

## Data that must stay private

- Real device identifiers and inventory descriptions, especially labels naming people or private locations.
- Account IDs, passwords, session/verification values, access tokens, environment files, and private keys.
- Imported batch files, packet captures, raw model replies, screenshots, diagnostic exports, and research outputs containing identifying data.
- Private device/client network addresses or filesystem paths tied to a person or deployment.

Keep real inputs and account files **outside the checkout**. Never attach them to public issues, pull requests, Actions logs, or release assets. The desktop's detailed diagnostic trace is not the same as its privacy-conscious persistent logging; review and redact any information before sharing it.

## Safeguards

- `.gitignore` excludes CSV/text inventories, account properties, environment files, key material, logs, and captures. The sole CSV exception is the reviewed `src/test/resources/batch-synthetic.csv` fixture.
- That fixture is fabricated from scratch, not a redacted copy of a real inventory. It is test-only and imported without network work. No identifier namespace is guaranteed reserved: do not use fabricated examples for live probes.
- Maven packages only the reviewed `logback.xml` runtime resource. `PublicResourcesTest` checks this allowlist so even ignored local input files cannot enter a clean application build.
- `scripts/check_public_files.py` checks Git's index for forbidden publication artifacts, including forced additions and symlinks. The local checkout's pre-commit hook runs it; enable that hook after cloning using the command below.
- Commit metadata uses the maintainer's GitHub no-reply email rather than a personal email address.

Enable the local filename guard after cloning (Python 3 is required for this development helper, not for the application or Maven tests):

```sh
git config core.hooksPath .githooks
python3 scripts/check_public_files.py
```

Before publishing changes, review `git diff --cached` and run a secret scan, for example with [Gitleaks](https://github.com/gitleaks/gitleaks):

```sh
gitleaks git . --redact --log-opts="--all"
mvn clean verify
```

GitHub Actions is not configured for this publication. Run the filename guard, secret scan, and Maven verification locally before pushing. Ignore rules and scanners are **not proof that arbitrary source, images, or prose contain no PII**, and hooks can be bypassed. Review content locally before committing or pushing; never use `git add -f` to publish private files.

If a credential is exposed, revoke or rotate it immediately. Removing a file in a new commit is insufficient; coordinate history cleanup separately without rewriting unrelated contributors' work.
