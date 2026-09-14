import type { TimelineLocation } from './timeline/date'
import { pageHref } from './navigation'

interface PageNavigationProps {
  location: TimelineLocation
  currentPage: 'timeline' | 'map'
}

export function PageNavigation({ location, currentPage }: PageNavigationProps) {
  return (
    <nav className="page-navigation" aria-label="主要ページ">
      <a
        href={pageHref('/timeline', location.date, location.timezone)}
        aria-current={currentPage === 'timeline' ? 'page' : undefined}
      >
        Timeline
      </a>
      <a
        href={pageHref('/map', location.date, location.timezone)}
        aria-current={currentPage === 'map' ? 'page' : undefined}
      >
        Map
      </a>
    </nav>
  )
}
