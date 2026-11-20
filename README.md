# AIAuto

Talk to Claude from Android Auto. Pick one of your GitHub repos, say what you want changed, and Claude does the
work in the cloud. It makes the change on a new branch, runs the tests, and opens a pull request. Updates come back
as messages that the car reads aloud.

## How it works

```
 Phone / Android Auto                       Anthropic (Claude Managed Agents)          GitHub
 ─────────────────────                      ─────────────────────────────────          ──────
 You speak a request  ──▶ new session ──▶   Claude works in a cloud sandbox   ──push──▶ branch
 Updates read aloud   ◀── event stream ◀──  with your repo cloned
 "PR 12 is open"      ◀────────────────────────────────── app opens the PR ──────────▶ pull request
```

- **`core/`** is plain Kotlin. It has the Managed Agents client (official Anthropic Java SDK), the GitHub REST
  calls, and the task engine that keeps one session per task.
- **`app/`** is the Android app:
  - The phone UI uses Jetpack Compose with a warm paper-and-clay theme.
  - The car UI uses the Android for Cars App Library. It opens on one big **Talk** button; the task list,
    task details and repo picker are behind **Tasks** in the corner.
  - Each task is posted as a messaging notification. Android Auto reads these aloud and accepts voice replies.

Tap **Talk** on the car screen and say anything. Claude (Opus 5.5, low effort for quick answers) replies out loud
through the car speakers, and can start a coding task in one of your saved repos or answer a running task when you
ask it to. **New chat** starts the conversation over. Each answer counts toward the daily spending limit.

Voice input also works inside tasks: open **Tasks**, then tap **New task** and pick a repo, or open a task and tap **Reply**,
then speak. The app uses the car's microphone when Android Auto supports it, and the phone's otherwise. If voice
doesn't work, you can fall back to replying to the AIAuto message through Android Auto's messaging flow.

**Projects** shows each repo straight from GitHub: whether its main branch is passing, the last change, and its
open pull requests with their checks. Tap a pull request to hear its checks or merge it (the app asks first).

When a task's pull request opens, the app follows its checks and tells you when they pass or fail. With
**Merge when checks pass** on (the default), it turns on GitHub's auto-merge if the repo allows it, so GitHub merges
even with your phone off. Otherwise the app merges once every check is green, as long as it's still running. Failing
checks, or checks your token can't read, are never merged.

You can **Stop** a working task, **Clear** one, or clear every finished task at once. Each task shows what it has
cost so far, and a **daily limit** (default $20, set in Settings) stops Claude when the day's total reaches it.
The limit is also set as the session's budget on Anthropic's side, so it holds even if the phone loses signal.

While you're in the car, the app reads Claude's messages and short progress notes like "Building or testing"
aloud, ducking your music while it talks. You can turn this off in Settings.

## Setup

1. **Get the APK.** Download `aiauto-vX.Y.Z.apk` from the latest
   [release](https://github.com/mjparinas/aiauto/releases) and install it on your phone. You'll need to allow
   installs from that source. Every push also builds an APK, which you can find under the **Build** run's
   Artifacts in the Actions tab.
2. **Claude API key.** Create one at [platform.claude.com](https://platform.claude.com). Usage is billed to that
   account. The model is Claude Opus 5.5, and each task is a Managed Agents session.
3. **GitHub token.** Create a [fine-grained token](https://github.com/settings/personal-access-tokens/new) for
   the repos you want to use. Set **Contents** and **Pull requests** to read and write, and **Checks** and
   **Commit statuses** to read. To let GitHub merge on its own, turn on **Allow auto-merge** in the repo's settings.
4. **Settings in the app.** Paste both, then add repos as `owner/name`. The key and token are encrypted with the
   Android Keystore. The GitHub token is also sent to Anthropic for each session, so the sandbox can clone and
   push. Anthropic's proxy adds the token to git requests outside the sandbox, so code running in the sandbox
   never sees it.
5. **Android Auto.** Apps installed outside the Play Store only show up in the car after you allow them:
   - On the phone, open Android Auto settings.
   - Tap **Version** ten times to unlock developer settings.
   - In the ⋮ menu, open **Developer settings** and turn on **Unknown sources**.

   Then disconnect and reconnect the phone. Without this step the app's icon doesn't appear in the car at all.
   Messaging notifications work without it.

## Building locally

Requires JDK 17 and the Android SDK (API 35).

```
./gradlew :core:test :app:assembleDebug
```

## Making a release

In the Actions tab, open **Release**, click **Run workflow** and enter a version like `v0.2.0`. You can also push
a tag like that. Either way, the workflow builds the APK and publishes a release with the APK attached.

Releases are signed with the CI runner's temporary debug key. Android might refuse to install a new version over
an old one; if it does, uninstall the old one first. A fixed signing key stored as a repo secret would solve this.

## Limits worth knowing

- There's no public API for your claude.ai or Claude app projects, so this app runs its own agent sessions through
  the Claude API. They don't appear in the Claude app.
- Google Play doesn't accept general AI or productivity apps for Android Auto. The full car UI is for personal
  installs and Play's internal testing track.
- Costs are list prices as each session reports them, so they can differ a little from your invoice. The daily
  total is counted on this phone and resets at local midnight.
- The app's own merge-when-green only works while the app is alive, for up to three hours after a pull request
  opens. GitHub's auto-merge has no such limit, but it only waits for checks the repo marks as required.
- While you're driving, the agent declines any step that its safety check flags for review. You'll hear that it
  skipped something, and the task continues.
