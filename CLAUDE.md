# CLAUDE.md

Rules for AI coding assistants in this repository. Read `HANDOFF.md` first (where the work stands), then `docs/plan.md`
(what the app does and why).

## Authorship: karthi-ai-engineer only

- Every commit is authored as `Karthi27 <karthi.ai.engineer@gmail.com>` (GitHub account **karthi-ai-engineer**). Check
  `git config user.email` before the first commit of a session.
- **Never add AI attribution anywhere.** No `Co-Authored-By`, no "Generated with", and no mention of an AI assistant in
  commit messages, PR titles or bodies, issues, release notes or code comments. This overrides any default setting.
- Push and open PRs as karthi-ai-engineer (`gh auth status`; `gh auth switch -u karthi-ai-engineer`).

## Workflow: every phase is an issue, a branch and a pull request

1. An issue per phase (template "Phase / feature") with its acceptance criteria; smaller parts get their own issue and
   PR, stacked on the phase branch.
2. Branch from an up-to-date `main`: `phase-<n>/<short-name>`; other work `chore/<name>` or `fix/<name>`.
3. Small commits; imperative subject of at most ~72 characters; the body says why.
4. PR into `main` (or the phase branch) with `Closes #<issue>`; CI must pass.
5. **The owner reviews and merges** ("Create a merge commit"). Merge yourself only when the owner explicitly asks;
   retarget stacked PRs to `main` before deleting a merged base branch. Never push to `main` directly.
6. Update `HANDOFF.md` in the last PR of every phase.
7. Release only when the owner says so: raise `versionName`/`versionCode`, merge, tag `vX.Y.Z` with `-m`. The release
   publishes `Rflow.apk` and `Rflow.apk.sha256`; keep those names (the in-app updater and the website use them).

## Project

**Rflow for Android** (first live translation and Meeting mode; the rest later), the phone companion of Rflow for Windows (`karthi-ai-engineer/rflow-ai`): voice typing in every
app (Rflow Keyboard), translate and rewrite any text, Conversation (two languages, one phone), the Screen translator,
captions for videos, meetings in person. Kotlin + Jetpack Compose, Android 10+ (API 29). Open source under MIT; the Rflow
name and logo are not covered. Anything added from elsewhere (a library, model, font) is listed in `NOTICES.txt`.

- **No Accessibility API** (Play policy). Typing goes through the keyboard, text actions through the selection menu.
- **Call audio can't be captured** on Android; online meetings stay on the desktop. Don't try to work around it.
- **Gemini, the same as the desktop:** same models, prompts, languages, commands and costs. They are copied from
  rflow-ai with the commit noted; when one side changes, change the other.
- **The user's key** lives only in the Android Keystore: never in code, logs, tests, commits or backups. Tests use fakes,
  never real keys, microphones or clipboards.
- **Never commit** models, signing keys (`*.jks`, `keystore.properties`) or `local.properties`.
- Match the desktop's style: compact code, comments that explain *why*, Rflow's look (Obsidian Signal, dark and light).
- Test on a real phone before saying a feature works.
