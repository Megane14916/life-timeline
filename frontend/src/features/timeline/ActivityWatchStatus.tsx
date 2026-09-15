import type { ActivityWatchStatusResponse } from '../../api/types'

interface ActivityWatchStatusProps {
  data: ActivityWatchStatusResponse | null
  error: string | null
  loading: boolean
  importing: boolean
  onRetry: () => void
  onImport: () => void
}

const stateLabels: Record<string, string> = {
  disabled: '無効',
  idle: '待機中',
  queued: '待機列',
  running: '取り込み中',
  needs_attention: '要確認',
}

const resultLabels: Record<string, string> = {
  success: '成功',
  unavailable: 'ActivityWatchに接続できません',
  missing_window_bucket: 'window bucketを確認できません',
  missing_afk_bucket: 'AFK bucketを確認できません',
  web_details_unavailable: 'Web詳細を取得できません',
  incompatible_api: 'ActivityWatch APIを確認できません',
  too_many_events: 'イベント数が上限を超えました',
  lease_busy: '別の取り込みが実行中です',
  invalid_config: '設定を確認してください',
  protocol_error: 'ActivityWatchの応答を確認できません',
}

const detailModeLabels: Record<string, string> = {
  app_only: 'アプリ名のみ',
  titles: 'ウィンドウタイトルを許可',
  web: 'Web詳細を許可',
}

function statusLabel(value: string | null): string {
  if (value === null) return '未実行'
  return resultLabels[value] ?? '結果を確認してください'
}

function formatStatusTimestamp(value: string | null): string {
  if (value === null) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('ja-JP', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date)
}

export function ActivityWatchStatus({
  data,
  error,
  loading,
  importing,
  onRetry,
  onImport,
}: ActivityWatchStatusProps) {
  return (
    <section
      className="activitywatch-status panel"
      aria-labelledby="activitywatch-status-title"
    >
      <div className="section-heading">
        <div>
          <p className="eyebrow">Collector diagnostics</p>
          <h2 id="activitywatch-status-title">ActivityWatch</h2>
        </div>
        {data !== null && (
          <span className="data-source" data-testid="activitywatch-state">
            {stateLabels[data.state] ?? '状態を確認'}
          </span>
        )}
      </div>

      {loading && <p className="panel-status">状態を読み込んでいます…</p>}
      {error !== null && (
        <div
          className="error-state"
          role="alert"
          data-testid="activitywatch-error"
        >
          <strong>ActivityWatchの状態を取得できません</strong>
          <p>{error}</p>
          <button type="button" onClick={onRetry}>
            再試行
          </button>
        </div>
      )}
      {!loading && error === null && data !== null && (
        <>
          <div className="activitywatch-status-grid" aria-live="polite">
            <div>
              <span>連携</span>
              <strong>{data.enabled ? '有効' : '無効'}</strong>
            </div>
            <div>
              <span>Privacy mode</span>
              <strong>{detailModeLabels[data.detailMode] ?? '確認中'}</strong>
            </div>
            <div>
              <span>最終結果</span>
              <strong>{statusLabel(data.lastResult)}</strong>
            </div>
            <div>
              <span>最終成功</span>
              <strong>{formatStatusTimestamp(data.lastSuccessAt)}</strong>
            </div>
            <div>
              <span>取込済み期間</span>
              <strong>{formatStatusTimestamp(data.completedThrough)}</strong>
            </div>
            <div>
              <span>次回予定</span>
              <strong>{formatStatusTimestamp(data.nextAttemptAt)}</strong>
            </div>
            <div>
              <span>Web詳細</span>
              <strong>{data.webDetailsAvailable ? '利用可能' : 'なし'}</strong>
            </div>
          </div>

          <div className="activitywatch-actions">
            <p>
              {data.enabled
                ? 'ActivityWatchのnot-afk記録からPC利用時間を作成します。'
                : '連携は無効です。環境変数で明示的に有効化できます。'}
            </p>
            <button
              className="today-button"
              type="button"
              disabled={!data.enabled || importing}
              aria-busy={importing}
              onClick={onImport}
            >
              {importing ? '取り込みを確認中…' : '今すぐ取り込む'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}
