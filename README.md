# Life Timeline

Life Timelineは、PCとスマートフォンから収集した活動データをローカルで管理し、複数の形式で振り返るためのアプリケーションです。

現在はPhase 0の開発基盤を整備しています。RepositoryにはFastAPI Backend、React Frontend、Androidアプリの最小構成があり、データベース、Collector、同期、Timelineなどの製品機能は後続Phaseで追加します。

## Repository構成

| パス                 | 内容                                    |
| -------------------- | --------------------------------------- |
| `backend/`           | FastAPI APIとPythonのtest・品質設定     |
| `frontend/`          | React + TypeScript + ViteのWeb UI       |
| `android/`           | Kotlin + Jetpack ComposeのAndroidアプリ |
| `.github/workflows/` | Pull Requestと`main`で実行するCI        |
| `docs/`              | 製品仕様、設計、実装計画                |

## Windows Quick Start

### 1. 必要なtoolchain

次のversionをインストールしてください。採用理由と更新規則は[開発toolchainとプロジェクト識別子](docs/development/toolchains.md)に記載しています。

導入元は[Git for Windows](https://git-scm.com/install/windows)、[uv公式インストール手順](https://docs.astral.sh/uv/getting-started/installation/)、[Node.js Downloads](https://nodejs.org/en/download)、[Eclipse Temurin JDK 17](https://adoptium.net/temurin/releases/?version=17)、[Android Studio](https://developer.android.com/studio/install)を使用します。Node.jsとJDKは各ページで下表のversionを選択してください。

| Tool                    | Version      | 確認コマンド                      |
| ----------------------- | ------------ | --------------------------------- |
| Git                     | 現行の安定版 | `git --version`                   |
| Python                  | `3.13.15`    | `python --version`                |
| uv                      | `0.12.9`     | `uv --version`                    |
| Node.js                 | `24.20.0`    | `node --version`                  |
| npm                     | `11.19.0`    | `npm --version`                   |
| JDK                     | `17`         | `java -version`                   |
| Android SDK Platform    | API `36`     | Android StudioのSDK Managerで確認 |
| Android SDK Build-Tools | `36.0.0`     | Android StudioのSDK Managerで確認 |

uv 0.12.9の公式standalone installerとPython本体は次のコマンドで導入できます。installerの内容を確認してから実行する場合は、上記のuv公式手順を参照してください。

```powershell
powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/0.12.9/install.ps1 | iex"
uv python install 3.13.15
```

Android SDKはAndroid StudioのSDK Managerで`Android SDK Platform 36`と`Android SDK Build-Tools 36.0.0`を追加してください。`ANDROID_HOME`にはAndroid SDKのディレクトリを設定します。Gradleはrepository内のWrapperが取得するため、グローバルインストールは不要です。

### 2. Checkout

```powershell
git clone https://github.com/Megane14916/life-timeline.git
cd life-timeline
```

既にclone済みの場合は、作業ブランチを作る前に`main`を更新します。

```powershell
git switch main
git pull --ff-only
```

### 3. Backend

依存関係をlock fileどおりに導入します。

```powershell
cd backend
uv sync --all-groups --frozen
```

開発サーバーをloopbackで起動します。

```powershell
uv run uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

別のPowerShellからhealth endpointを確認します。

```powershell
Invoke-RestMethod http://127.0.0.1:8000/api/v1/health
```

`status`が`ok`なら起動成功です。検証コマンドは`backend/`で実行します。

```powershell
uv run ruff format --check .
uv run ruff check .
uv run mypy app tests
uv run pytest
uv run python scripts/smoke.py
```

実HTTP smokeは自身で一時サーバーを起動するため、ポート`8000`で動かしている開発サーバーを停止してから実行してください。Windows用wrapperの`./scripts/smoke.ps1`も同じsmokeを実行します。

### 4. Frontend

別のPowerShellでrepository rootから実行します。

```powershell
cd frontend
npm ci
npm run dev
```

ブラウザで`http://127.0.0.1:5173/`を開き、`life-timeline`と初期化済みのメッセージが表示されることを確認します。

検証コマンドは`frontend/`で実行します。

```powershell
npm run format:check
npm run lint
npm run typecheck
npm run test:run
npm run build
```

`npm run format`はPrettierでファイルを修正します。CIと同じ確認には、ファイルを書き換えない`npm run format:check`を使います。Production buildは`frontend/dist/`へ生成されます。

### 5. Android

Android SDKとJDK 17を利用できるPowerShellで、repository rootからWrapperを実行します。

```powershell
./android/gradlew.bat -p android spotlessCheck lintDebug testDebugUnitTest assembleDebug
```

成功するとdebug APKが次の場所に生成されます。

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Android Studioで実行する場合は`android/`をprojectとして開き、JDK 17とAPI 36を選択してください。Phase 0ではdebug APKのbuildを必須とし、emulatorまたは実機へのinstallは任意です。

## CIとローカル検証の対応

Pull Requestと`main`へのpushでは、変更パスに関係なく次のcheckをすべて実行します。

| Required check         | ローカルで対応する検証                                             |
| ---------------------- | ------------------------------------------------------------------ |
| `frontend-ci`          | `npm ci`、format、lint、typecheck、test、build                     |
| `backend-ci (ubuntu)`  | frozen sync、format、lint、typecheck、test、実HTTP smoke           |
| `backend-ci (windows)` | Ubuntuと同じ検証をPowerShell上で実行                               |
| `android-ci`           | Gradle Wrapper検証、Spotless、Android Lint、unit test、debug build |

Android CIの成功時には`life-timeline-debug-apk`というartifactが保存されます。GitHubのPull Requestで対象checkを開き、workflow runの`Artifacts`から取得できます。保存期間は7日です。

Phase 1でDB、製品API、React UIを接続した時点で、実DBからAPIを経由してReactまで確認する`pc-core-e2e`を追加し、required checkにします。

## よくある問題

### `uv sync --frozen`が失敗する

`python --version`と`uv --version`を確認してください。`pyproject.toml`と`uv.lock`が一致しない場合も失敗します。依存関係を意図的に変更する作業でのみlockを更新し、通常のセットアップでは`--frozen`を外して解決しないでください。

### `npm ci`がversionまたはlock fileのエラーになる

`node --version`と`npm --version`がQuick Startの値に合っているか確認してください。依存関係を変更するときは`package.json`と`package-lock.json`を同じPull Requestへ含めます。

### Backendのsmokeでポートエラーになる

ポート`8000`を利用しているprocessを確認します。

```powershell
Get-NetTCPConnection -LocalPort 8000 -ErrorAction SilentlyContinue
```

開発サーバーなどが動いている場合は停止してからsmokeを再実行します。

### Android buildでSDKまたはJavaが見つからない

`java -version`がJDK 17を指していることと、`ANDROID_HOME`が有効なSDKディレクトリを指していることを確認してください。Android StudioのSDK ManagerでAPI 36とBuild-Tools 36.0.0がインストール済みか確認します。

### ローカルでは成功するがCIで失敗する

失敗したcheckの最初のerrorを確認し、対応表のローカルコマンドを同じ順序で実行します。生成済みのvirtual environment、`node_modules`、Gradle cacheに依存している疑いがある場合は、新しいcheckoutで再現を確認します。

## ドキュメント

| 文書                                                          | 内容                                |
| ------------------------------------------------------------- | ----------------------------------- |
| [概要](docs/overview.md)                                      | 製品の目的、スコープ、用語          |
| [製品仕様](docs/product-spec.md)                              | 機能要件と利用シナリオ              |
| [データモデル](docs/data-model.md)                            | 一般化した活動データのモデル        |
| [アーキテクチャ](docs/architecture.md)                        | システム構成と責務分割              |
| [技術設計](docs/technical-design.md)                          | API、保存、同期、テスト等の技術方針 |
| [実装計画](docs/implementation-plan.md)                       | Phase構成と実装順序                 |
| [Phase 0詳細計画](docs/detailed_plan/phase0-project-setup.md) | Project Setupのタスクと受け入れ条件 |
| [Phase 1詳細計画](docs/detailed_plan/phase1-pc-core.md)       | PC Coreの実装計画                   |
| [開発toolchain](docs/development/toolchains.md)               | 固定version、識別子、更新規則       |
| [Phase 0受け入れ記録](docs/development/phase0-acceptance.md)  | clean checkout検証とPhase 1開始判定 |

Androidの`applicationId`と`namespace`は`com.megane14916.lifetimeline`です。
