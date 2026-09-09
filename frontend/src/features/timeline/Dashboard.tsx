import type { StatisticsResponse } from '../../api/types'
import { formatDuration, formatPlatform } from './format'

interface DashboardProps {
  data: StatisticsResponse | null
  error: string | null
  loading: boolean
  onRetry: () => void
}

export function Dashboard({ data, error, loading, onRetry }: DashboardProps) {
  return (
    <section className="dashboard panel" aria-labelledby="dashboard-title">
      <div className="section-heading">
        <div>
          <p className="eyebrow">Daily overview</p>
          <h2 id="dashboard-title">Dashboard</h2>
        </div>
        {data !== null && <span className="data-source">Statistics API</span>}
      </div>

      {loading && <p className="panel-status">統計を読み込んでいます…</p>}
      {error !== null && (
        <div className="error-state" role="alert">
          <strong>Dashboardの取得に失敗しました</strong>
          <p>{error}</p>
          <button type="button" onClick={onRetry}>
            再試行
          </button>
        </div>
      )}
      {!loading && error === null && data !== null && (
        <>
          <div className="metric-grid">
            <div className="metric-card">
              <span>記録された利用時間</span>
              <strong>{formatDuration(data.totals.usageMs)}</strong>
            </div>
            <div className="metric-card">
              <span>セッション</span>
              <strong>{data.totals.sessionCount}件</strong>
            </div>
            <div className="metric-card">
              <span>利用アプリ</span>
              <strong>{data.totals.appCount}件</strong>
            </div>
          </div>

          <div className="app-summary">
            <div className="subsection-heading">
              <h3>アプリ別</h3>
              <span>{data.items.length} apps</span>
            </div>
            {data.items.length === 0 ? (
              <p className="empty-state">この日の利用記録はありません。</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">アプリ</th>
                      <th scope="col">利用時間</th>
                      <th scope="col">Sessions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((item) => (
                      <tr key={item.appId}>
                        <th scope="row">
                          <span className="app-name">{item.appName}</span>
                          <span className="app-meta">
                            {formatPlatform(item.platform)} ·{' '}
                            {item.appIdentifier}
                          </span>
                        </th>
                        <td>{formatDuration(item.usageMs)}</td>
                        <td>{item.sessionCount}件</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  )
}
