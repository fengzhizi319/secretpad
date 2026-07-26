import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { LoginPage } from './index';

const mockLogin = vi.fn();
const mockOnLoginSuccess = vi.fn();

vi.mock('../../features/auth/model/auth-store', () => ({
  useAuthStore: () => ({
    login: mockLogin,
  }),
}));

beforeEach(() => {
  vi.resetAllMocks();
});

describe('LoginPage', () => {
  it('calls login with username and password on submit', async () => {
    mockLogin.mockResolvedValueOnce(undefined);
    render(<LoginPage onLoginSuccess={mockOnLoginSuccess} />);

    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'admin' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '12345678' } });
    fireEvent.click(screen.getByRole('button', { name: /Sign In/ }));

    await waitFor(() => {
      expect(mockLogin).toHaveBeenCalledWith('admin', '12345678');
      expect(mockOnLoginSuccess).toHaveBeenCalled();
    });
  });

  it('shows an error message when login fails', async () => {
    mockLogin.mockRejectedValueOnce(new Error('invalid password'));
    render(<LoginPage onLoginSuccess={mockOnLoginSuccess} />);

    fireEvent.click(screen.getByRole('button', { name: /Sign In/ }));

    expect(await screen.findByText('invalid password')).toBeTruthy();
    expect(mockOnLoginSuccess).not.toHaveBeenCalled();
  });
});
