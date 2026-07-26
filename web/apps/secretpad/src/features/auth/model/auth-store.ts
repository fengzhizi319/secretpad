import { create } from 'zustand';
import { sha256 } from '@secretpad/utils';
import { apiClient, User, Platform } from '@secretpad/api-client';

interface AuthState {
  user: User | null;
  platform: Platform;
  isAuthenticated: boolean;
  theme: 'light' | 'dark';
  rehydrate: () => void;
  login: (name: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  toggleTheme: () => void;
}

function getStoredUser(): User | null {
  try {
    const raw = localStorage.getItem('secretpad-user');
    return raw ? (JSON.parse(raw) as User) : null;
  } catch {
    return null;
  }
}

function buildPlatform(user: User | null): Platform {
  return {
    platformType: (user?.platformType as Platform['platformType']) || 'CENTER',
    nodeId: user?.platformNodeId || user?.ownerId || '',
  };
}

export const useAuthStore = create<AuthState>((set) => ({
  user: getStoredUser(),
  platform: buildPlatform(getStoredUser()),
  isAuthenticated: !!localStorage.getItem('secretpad-token'),
  theme: 'light',

  rehydrate: () => {
    const user = getStoredUser();
    set({
      user,
      platform: buildPlatform(user),
      isAuthenticated: !!localStorage.getItem('secretpad-token'),
    });
  },

  login: async (name: string, password: string) => {
    const passwordHash = await sha256(password);
    const user = await apiClient.login(name, passwordHash);
    localStorage.setItem('secretpad-user', JSON.stringify(user));
    set({
      user,
      isAuthenticated: true,
      platform: {
        platformType: (user.platformType as Platform['platformType']) || 'CENTER',
        nodeId: user.platformNodeId || user.ownerId || '',
      },
    });
  },

  logout: async () => {
    await apiClient.logout();
    localStorage.removeItem('secretpad-token');
    localStorage.removeItem('secretpad-user');
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
