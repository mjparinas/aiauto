# Changelog

## v0.1.0

First release.

- Android Auto app that runs Claude coding tasks on your GitHub repos. Pick a repo, speak a request, and Claude
  makes the change on a new branch, runs the tests and opens a pull request.
- Task updates arrive as messaging notifications that Android Auto reads aloud. Reply by voice to continue a task.
- Car UI with a task list, task details and a repo picker.
- Phone UI for settings, repos and tasks. The Claude API key and GitHub token are encrypted with the Android Keystore.
- Build workflow that produces an APK on every push, and a release workflow that publishes the APK to GitHub
  releases from a tag or the Actions tab.
