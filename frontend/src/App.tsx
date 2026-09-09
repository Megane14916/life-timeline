import './app.css'

import { TimelinePage } from './features/timeline/TimelinePage'

export function App() {
  const path = window.location.pathname

  if (path === '/' || path === '/timeline') {
    return <TimelinePage />
  }

  return (
    <main className="app-shell">
      <section className="panel not-found" aria-labelledby="not-found-title">
        <p className="eyebrow">Page not found</p>
        <h1 id="not-found-title">404</h1>
        <p className="status-message">
          指定されたページは見つかりませんでした。
        </p>
        <a className="back-link" href="/timeline">
          Timelineへ戻る
        </a>
      </section>
    </main>
  )
}
