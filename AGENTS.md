## Agent skills

### Issue tracker

Issues are tracked on GitHub. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage labels with default naming. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Version rules

+ **三位版本号** `MAJOR.MINOR.PATCH`（如 v0.1.2）
+ **PATCH (第三位)**: 修复Bug的小迭代自动进一位（如 0.1.1 → 0.1.2）
+ **MINOR (第二位)**: 添加大功能时进一位，需用户指令确认（如 0.1.x → 0.2.0）
+ **MAJOR (第一位)**: 大迭代里程碑，需用户指令确认（如 0.x.x → 1.0.0）
+ **更新点**: 改 `app/build.gradle.kts` 中 `versionName`，写 `CHANGELOG.md` + `CHANGELOG.txt`
+ **APK命名**: 自动输出为 `TTP.{versionName}.apk`
