import { create } from 'zustand';
import { User, Platform } from '@secretpad/api-client';

interface AuthState {
  user: User | null;
  platform: Platform;
  isAuthenticated: boolean;
  theme: 'light' | 'dark';
  login: (name: string, role?: 'ADMIN' | 'DEVELOPER') => void;
  logout: () => void;
  toggleTheme: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: {
    ownerId: 'admin-001',
    name: 'admin',
    role: 'ADMIN',
    token: 'mock-jwt-token-secretpad',
  },
  platform: {
    platformType: 'CENTER',
    nodeId: 'alice',
  },
  isAuthenticated: true,
  theme: 'light',
  login: (name: string, role: 'ADMIN' | 'DEVELOPER' = 'ADMIN') => {
    set({
      user: { ownerId: `user-${Date.now()}`, name, role },
      isAuthenticated: true,
    });
  },
  logout: () => {
    set({ user: null, isAuthenticated: false });
  },
  toggleTheme: () => {
    set((state) => {
      const nextTheme = state.theme === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', nextTheme);
      if (nextTheme === 'dark') {
        document.documentElement.classList.add('dark');
      } else {
        document.documentElement.classList.remove('dark');
      }
      return { theme: nextTheme };
    });
  },
}));
