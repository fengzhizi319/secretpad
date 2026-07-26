import React from 'react';
import { useAuthStore } from '../features/auth/model/auth-store';
import { Button } from '@secretpad/design-system';

export interface AppHeaderProps {
  title?: string;
  onNavigate?: (path: string) => void;
}

export const AppHeader: React.FC<AppHeaderProps> = ({ title = 'Console Overview', onNavigate }) => {
  const { user, platform, theme, toggleTheme, logout } = useAuthStore();

  return (
    <header className="h-14 bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 px-6 flex items-center justify-between flex-shrink-0">
      {/* Breadcrumb / Title */}
      <div className="flex items-center gap-3">
        <h1 className="text-base font-semibold text-gray-900 dark:text-gray-100">{title}</h1>
        <span className="px-2 py-0.5 rounded text-[11px] font-mono bg-blue-50 dark:bg-blue-950 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-800">
          Node: {platform.nodeId} ({platform.platformType})
        </span>
      </div>

      {/* Header Right Actions */}
      <div className="flex items-center gap-3">
        <Button
          size="sm"
          variant="ghost"
          onClick={toggleTheme}
          title="Toggle Theme"
        >
          {theme === 'light' ? '🌙 Dark' : '☀️ Light'}
        </Button>

        <button
          onClick={() => onNavigate && onNavigate('/messages')}
          className="relative p-1.5 text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200 rounded-lg hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"
        >
          🔔
          <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-red-500" />
        </button>

        <div className="h-4 w-px bg-gray-200 dark:bg-gray-800" />

        {/* User Info Dropdown */}
        <div className="flex items-center gap-2.5 pl-1">
          <div className="w-8 h-8 rounded-full bg-gradient-to-br from-blue-500 to-indigo-600 flex items-center justify-center font-bold text-white text-xs shadow-xs">
            {user?.name?.[0]?.toUpperCase() || 'A'}
          </div>
          <div className="flex flex-col text-xs">
            <span className="font-semibold text-gray-800 dark:text-gray-200">{user?.name || 'admin'}</span>
            <span className="text-[10px] text-gray-400">{user?.role || 'ADMIN'}</span>
          </div>
          <Button size="sm" variant="ghost" onClick={logout} className="text-gray-400 hover:text-red-500 ml-1">
            ➔
          </Button>
        </div>
      </div>
    </header>
  );
};
