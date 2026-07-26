import React, { useEffect, useState } from 'react';
import { useAuthStore } from '../features/auth/model/auth-store';
import { AppSidebar } from '../widgets/AppSidebar';
import { AppHeader } from '../widgets/AppHeader';

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
        Loading...
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
      case '/dashboard': return 'Console Dashboard';
      case '/projects': return 'Collaborative Projects';
      case '/nodes': return 'Kuscia Node Cluster';
      case '/data-tables': return 'Data Assets & Classifications';
      case '/data-sources': return 'Data Source Connections';
      case '/dag': return 'DAG Pipeline Workspace';
      case '/models': return 'Model Products & Inference';
      case '/periodic-tasks': return 'Scheduled Jobs';
      case '/messages': return 'Message Center';
      default: return 'SecretPad Console';
    }
  };

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-slate-50 dark:bg-gray-950 text-gray-900 dark:text-gray-100 font-sans">
      <AppSidebar currentPath={currentPath} onNavigate={setCurrentPath} />
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        <AppHeader title={getTitle()} onNavigate={setCurrentPath} />
        <main className="flex-1 overflow-y-auto p-6">
          {renderContent()}
        </main>
      </div>
    </div>
  );
};
