# AGENTS.md

## Project
- mmdb editor + awdb→mmdb converter. Full plan: `docs/plan.md` (read it first — it is the locked design).
- Multi-module Maven: `mmdb-editor-core` (pure lib), `mmdb-converter-awdb` (pure lib, depends on `com.xenoamess:awdb-java` from Central), `mmdb-editor-app` (Quarkus: picocli CLI + REST + Vue SPA, single binary `mmdb-edit`).
- groupId `com.xenoamess.mmdb_editor`, Java release 21, Quarkus 3.26.x, GraalVM CE 21 for native.

## Build & verify
- Build+test: `mvn verify`. Native: `mvn package -Dnative -DskipTests -pl mmdb-editor-app -am` (needs GraalVM CE 21).
- Frontend lives in `mmdb-editor-app/src/main/frontend` (Vite + Vue3 + Element Plus, pnpm via Quinoa).
- Oracle for writer validation: test-scope `com.maxmind.db:maxmind-db` (official reader, test-only).
- CI: `.github/workflows/build.yml` runs `mvn verify` on JDK 21/25 (temurin); a separate master-only `native` job (GraalVM CE 21) must NOT become a required PR check.
- `.github/workflows/auto-merge.yml` approves + auto-merges dependabot PRs (patch/minor always, major only for github-actions). MYTOKEN lives in the **dependabot** secret namespace.
- Branch protection on master: required checks `build (21)`, `build (25)` + strict + linear history. Merge PRs with `--rebase`/`--squash`, never `--merge`.
- dependabot.yml must NOT contain a `groups:` block (dependabot-automerge-skill Pitfall 13).

## Conventions
- Remote is `origin` → github.com/XenoAmess/mmdb-editor-java.
- awdb (Gen-B) format understanding lives in the sibling repo `awdb-java`; do not re-implement awdb reading here — depend on it.
- After every change, commit and push immediately without asking for confirmation.
