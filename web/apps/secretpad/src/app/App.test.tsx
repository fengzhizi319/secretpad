import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { App } from './App';

describe('App', () => {
  it('renders the dashboard by default', async () => {
    render(<App />);
    expect(await screen.findByText('Console Dashboard')).toBeTruthy();
  });

  it('shows header and sidebar navigation', async () => {
    render(<App />);
    expect(await screen.findByText('SecretPad')).toBeTruthy();
    expect(await screen.findByText('Dashboard')).toBeTruthy();
  });
});
