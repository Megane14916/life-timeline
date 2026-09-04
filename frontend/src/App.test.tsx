import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { App } from './App'

describe('App', () => {
  it('shows the application name and initialized state', () => {
    render(<App />)

    expect(
      screen.getByRole('heading', { level: 1, name: 'required-check-failure-probe' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('開発基盤の初期化が完了しました。'),
    ).toBeInTheDocument()
  })
})
