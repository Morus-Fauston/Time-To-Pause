## Agent skills

### Issue tracker

Issues are tracked on GitHub. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage labels with default naming. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

### Version rules

+ **三位版本号** `MAJOR.MINOR.PATCH`（如 v0.2.1）
+ **PATCH (第三位)**: 修复Bug的小迭代自动进一位（如 0.2.0 → 0.2.1）
+ **MINOR (第二位)**: 添加大功能时进一位，需用户指令确认（如 0.2.x → 0.3.0）
+ **MAJOR (第一位)**: 大迭代里程碑，需用户指令确认（如 0.x.x → 1.0.0）
+ **`.revised.N` 后缀**: 在已发布的版本上再次修 bug，避免版本号冲突。自动追加 `.revised.1` → `.revised.2` → ……（如 0.2.0 → 0.2.0.revised → 0.2.0.revised.1 → 0.2.0.revised.2）。三位版本号规则不变（仍保持原 `PATCH` 位），仅递补修订号
+ **更新点**: 改 `app/build.gradle.kts` 中 `versionName`，写 `CHANGELOG.md` + `CHANGELOG.txt`
+ **APK命名**: 自动输出为 `TTP.{versionName}.apk`
