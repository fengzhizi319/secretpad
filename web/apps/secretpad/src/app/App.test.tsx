import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { App } from './App';

const mockUser = {
  ownerId: 'kuscia-system',
  name: 'admin',
  token: 'test-token',
  platformType: 'CENTER',
  platformNodeId: 'kuscia-system',
  ownerType: 'CENTER',
};

describe('App', () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('secretpad-token', 'test-token');
    localStorage.setItem('secretpad-user', JSON.stringify(mockUser));
  });

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
