# Phase 0受け入れ記録

## 1. 判定

Phase 0の受け入れ条件AC-01〜12を満たした。Backend、Frontend、Androidの開発基盤は、既存の依存cacheやIDE設定を使わない新しいcheckoutから再現できる。Phase 1のPC Core実装を開始できる。

検証日は2026年9月4日、対象は`main`のcommit `fc74ba3a5e9a378c4193a6c22cac5c1cac426f64`である。

## 2. 検証環境

| 項目        | 検証値                            |
| ----------- | --------------------------------- |
| OS          | Microsoft Windows NT 10.0.26200.0 |
| Git         | 2.51.0.windows.1                  |
| Python      | 3.13.15                           |
| uv          | 0.12.9                            |
| Node.js     | 24.20.0                           |
| npm         | 11.19.0                           |
| JDK         | OpenJDK 17.0.17 LTS               |
| Android SDK | Platform 36、Build-Tools 36.0.0   |
| Android AVD | Pixel 9、API 36                   |

`%TEMP%\life-timeline-p0-07-20260904202331\repo`へ`main`だけを新規cloneした。次の保存先を既存の開発環境から分離した。

- uv cache: `%TEMP%\life-timeline-p0-07-20260904202331\cache\uv`
- uv管理Python: `%TEMP%\life-timeline-p0-07-20260904202331\python`
- Python virtual environment: clean checkout内の`backend/.venv`
- npm cache: `%TEMP%\life-timeline-p0-07-20260904202331\cache\npm`
- npm dependencies: clean checkout内の`frontend/node_modules`
- Gradle User Home: `%TEMP%\life-timeline-p0-07-20260904202331\cache\gradle`

## 3. 実行結果

### 3.1 Backend

新しいuv管理領域へPython 3.13.15をダウンロードし、`uv sync --all-groups --frozen`から環境を構築した。

| 検証                             | 結果                                 |
| -------------------------------- | ------------------------------------ |
| `uv run ruff format --check .`   | 成功、4 files formatted              |
| `uv run ruff check .`            | 成功、違反0件                        |
| `uv run mypy app tests`          | 成功、3 source files                 |
| `uv run pytest`                  | 成功、2 tests passed                 |
| `uv run python scripts/smoke.py` | 成功、`GET /api/v1/health`がHTTP 200 |

### 3.2 Frontend

空のnpm cacheから`npm ci`を実行し、228 packagesをlock fileどおりに導入した。audit結果は脆弱性0件だった。

| 検証                   | 結果                                     |
| ---------------------- | ---------------------------------------- |
| `npm run format:check` | 成功                                     |
| `npm run lint`         | 成功、warning 0件                        |
| `npm run typecheck`    | 成功                                     |
| `npm run test:run`     | 成功、1 test passed                      |
| `npm run build`        | 成功、`frontend/dist/`を生成             |
| Vite loopback smoke    | 成功、`http://127.0.0.1:5173/`がHTTP 200 |

Component testでアプリ名と初期化メッセージの描画を確認し、loopback smokeで実際のVite serverが応答することを確認した。

### 3.3 Android

空のGradle User HomeへGradle 9.5.0と依存関係を取得し、次のコマンドを実行した。

```powershell
$gradleHome = Join-Path $env:TEMP 'life-timeline-p0-07-20260904202331\cache\gradle'
./android/gradlew.bat --gradle-user-home $gradleHome --no-daemon -p android spotlessCheck lintDebug testDebugUnitTest assembleDebug
```

57 tasksをすべて新規実行し、8分43秒で成功した。Android unit testは1件成功した。debug APKは`android/app/build/outputs/apk/debug/app-debug.apk`へ生成され、サイズは11,816,120 bytesだった。

生成したAPKをPixel 9 API 36のheadless emulatorへinstallし、`com.megane14916.lifetimeline/.MainActivity`がtop resumed activityになったことを確認した。確認後にアプリをuninstallし、emulatorを停止した。

### 3.4 Gitと生成物

全build後もclean checkoutの`git status --porcelain`は空だった。次の対象がignoreされることを`git check-ignore`で確認した。

- `backend/.venv`
- `frontend/node_modules`
- `frontend/dist`
- `android/.gradle`
- `android/app/build`
- debug APK

DB、SQLite、環境変数ファイル、署名鍵、`local.properties`が追跡対象に含まれないことも確認した。

## 4. GitHub Actionsとmerge制御

P0-06をmergeした`main`のcommit `fc74ba3`に対するpush workflowはすべて成功した。

| Workflow                                                                             | 結果    |
| ------------------------------------------------------------------------------------ | ------- |
| [Frontend CI](https://github.com/Megane14916/life-timeline/actions/runs/33851058609) | success |
| [Backend CI](https://github.com/Megane14916/life-timeline/actions/runs/33851058604)  | success |
| [Android CI](https://github.com/Megane14916/life-timeline/actions/runs/33851058658)  | success |

[`main-branch-protection`](https://github.com/Megane14916/life-timeline/rules/22253391) rulesetはdefault branchに対してactiveであり、次を要求している。

- Pull Request経由の変更
- branchを最新にした状態でのrequired checks成功
- `frontend-ci`
- `backend-ci (ubuntu)`
- `backend-ci (windows)`
- `android-ci`
- branch削除とnon-fast-forward pushの禁止

[検証PR #11](https://github.com/Megane14916/life-timeline/pull/11)でFrontend testを意図的に失敗させた。BackendとAndroidのrequired checksが成功していても、`frontend-ci`がfailureの間はPRの`mergeable_state`が`blocked`になった。確認結果をPRへ記録し、マージせずに閉じてremoteの検証ブランチを削除した。

## 5. 受け入れ条件

| ID    | 結果 | 証拠                                                                                                 |
| ----- | ---- | ---------------------------------------------------------------------------------------------------- |
| AC-01 | PASS | GitHubの既存repositoryから`main`を新規cloneし、`origin/main`と同じ`fc74ba3`を取得した                |
| AC-02 | PASS | repositoryのversion指定と実versionを照合し、分離したcacheからfrozen installとWrapper buildに成功した |
| AC-03 | PASS | Frontend component test、production build、Vite loopback HTTP 200                                    |
| AC-04 | PASS | Backend API test 2件と、実Uvicorn processへのhealth HTTP 200                                         |
| AC-05 | PASS | debug APK build、CI artifact、Pixel 9 API 36へのinstallとMainActivity起動                            |
| AC-06 | PASS | Prettier、ESLint、Ruff、Spotless、Android Lint、Frontend/Backend型検査がローカルとCIで成功した       |
| AC-07 | PASS | Backend 2 tests、Frontend 1 test、Android 1 test、Backend/Frontend smokeを実行した                   |
| AC-08 | PASS | Pull Requestと`main` pushで3 workflows、4 fixed checksが実行されることを確認した                     |
| AC-09 | PASS | active rulesetと検証PR #11で、required check失敗時のmerge禁止を確認した                              |
| AC-10 | PASS | 全build後もGit statusはcleanで、秘密情報・実データ・DB・cache・build生成物は追跡されなかった         |
| AC-11 | PASS | READMEのWindows Quick Startを新しいcheckoutと分離cacheから実行できた                                 |
| AC-12 | PASS | 既存の`backend/`、`frontend/`、CIへPhase 1のDB・API・UI・`pc-core-e2e`を追加できる構成を確認した     |

## 6. Phase 1への引き継ぎ

Phase 1の開始を妨げる問題はない。次の事項はPhase 1の計画どおりに対応する。

- SQLAlchemy、Alembic、SQLite schema、seedはPhase 1で初めて追加する。
- Timelineの日跨ぎ計算とStatistics集計はBackendで実装する。
- 実SQLiteからFastAPIを経由してReactを確認する`pc-core-e2e`を最初のPC Core PRで追加し、required checkにする。
- Android Collector、Room、同期はPhase 2まで追加しない。

空cacheからのAndroid初回buildには8分43秒かかったが、CIの30分timeout内であり、失敗要因ではない。`libandroidx.graphics.path.so`をstripせずpackagingする通知が出たが、lint、unit test、APK build、emulator起動は成功した。
