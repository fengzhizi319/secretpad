import React, { useEffect, useState } from 'react';
import { useAuthStore } from '../features/auth/model/auth-store';
import { AppSidebar } from '../widgets/AppSidebar';
import { AppHeader } from '../widgets/AppHeader';
import { useTranslation } from '../shared/lib/i18n';
import { RouteGuard } from '../features/auth/ui/access-guard';

import { LoginPage } from '../pages/login';
import { DashboardPage } from '../pages/dashboard';
import { ProjectsPage } from '../pages/projects';
import { NodesPage } from '../pages/nodes';
import { DataTablesPage } from '../pages/data-tables';
import { DataSourcesPage } from '../pages/data-sources';
import { DAGPage } from '../pages/dag';
import { ModelsPage } from '../pages/models';
import { PeriodicTasksPage } from '../pages/periodic-tasks';
import { MessagesPage } from '../pages/messages';

export const App: React.FC = () => {
  const { t } = useTranslation();
  const { isAuthenticated, rehydrate } = useAuthStore();
  const [hydrated, setHydrated] = useState(false);
  const [currentPath, setCurrentPath] = useState('/dashboard');

  useEffect(() => {
    rehydrate();
    setHydrated(true);
  }, [rehydrate]);

  if (!hydrated) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-slate-50 dark:bg-gray-950 text-gray-500 text-sm">
        {t('app.loading')}
      </div>
    );
  }

  if (!isAuthenticated) {
    return <LoginPage onLoginSuccess={() => setCurrentPath('/dashboard')} />;
  }

  const renderContent = () => {
    switch (currentPath) {
      case '/dashboard':
        return <DashboardPage onNavigate={setCurrentPath} />;
      case '/projects':
        return <ProjectsPage onNavigate={setCurrentPath} />;
      case '/nodes':
        return <NodesPage />;
      case '/data-tables':
        return <DataTablesPage />;
      case '/data-sources':
        return <DataSourcesPage />;
      case '/dag':
        return <DAGPage />;
      case '/models':
        return <ModelsPage />;
      case '/periodic-tasks':
        return <PeriodicTasksPage />;
      case '/messages':
        return <MessagesPage />;
      default:
        return <DashboardPage onNavigate={setCurrentPath} />;
    }
  };

  const getTitle = () => {
    switch (currentPath) {
      case '/dashboard': return t('dashboard.title');
      case '/projects': return t('projects.title');
      case '/nodes': return t('nodes.title');
      case '/data-tables': return t('dataTables.title');
      case '/data-sources': return t('dataSources.title');
      case '/dag': return t('sidebar.dag');
      case '/models': return t('sidebar.models');
      case '/periodic-tasks': return t('sidebar.periodicTasks');
      case '/messages': return t('sidebar.messages');
      default: return t('app.title');
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-slate-50 dark:bg-gray-950 text-gray-900 dark:text-gray-100 font-sans">
      <AppSidebar currentPath={currentPath} onNavigate={setCurrentPath} />
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <AppHeader title={getTitle()} onNavigate={setCurrentPath} />
        <main className="flex-1 overflow-y-auto p-6">
          <RouteGuard>{renderContent()}</RouteGuard>
        </main>
      </div>
    </div>
  );
};
