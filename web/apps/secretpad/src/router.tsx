import React from 'react';
import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
  useNavigate,
} from '@tanstack/react-router';
import { ToastContainer } from '@secretpad/design-system';

import { AppLayout } from './app/AppLayout';
import { LoginPage } from './pages/login';
import { DashboardPage } from './pages/dashboard';
import { ProjectsPage } from './pages/projects';
import { NodesPage } from './pages/nodes';
import { DataTablesPage } from './pages/data-tables';
import { DataSourcesPage } from './pages/data-sources';
import { DataSourceDetailPage } from './pages/data-sources/detail';
import { DAGPage } from './pages/dag';
import { ModelsPage } from './pages/models';
import { PeriodicTasksPage } from './pages/periodic-tasks';
import { MessagesPage } from './pages/messages';
import { NodeRoutesPage } from './pages/node-routes';
import { InstitutionsPage } from './pages/institutions';
import { P2pProjectsPage } from './pages/p2p/projects';
import { P2pMyNodePage } from './pages/p2p/my-node';
import { AccountPage } from './pages/account';

/**
 * Read the auth token directly from localStorage (not the Zustand store).
 * The store is a module-level singleton whose `isAuthenticated` snapshot is taken
 * at import time; in tests the token is written to localStorage afterwards, so
 * the store value would be stale. Reading localStorage at navigation time is
 * always current.
 */
const getAuthToken = () => localStorage.getItem('secretpad-token');

const RootComponent: React.FC = () => (
  <>
    <ToastContainer />
    <Outlet />
  </>
);

export const rootRoute = createRootRoute({
  component: RootComponent,
});

const LoginRouteComponent: React.FC = () => {
  const navigate = useNavigate();
  return <LoginPage onLoginSuccess={() => navigate({ to: '/dashboard' })} />;
};

export const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  beforeLoad: () => {
    if (getAuthToken()) {
      throw redirect({ to: '/dashboard' });
    }
  },
  component: LoginRouteComponent,
});

export const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'app',
  beforeLoad: () => {
    if (!getAuthToken()) {
      throw redirect({ to: '/login' });
    }
  },
  component: AppLayout,
});

export const indexRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/',
  beforeLoad: () => {
    throw redirect({ to: '/dashboard' });
  },
});

export const dashboardRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/dashboard',
  component: DashboardPage,
});

export const projectsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/projects',
  component: ProjectsPage,
});

export const nodesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/nodes',
  component: NodesPage,
});

export const dataTablesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/data-tables',
  component: DataTablesPage,
});

export const dataSourcesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/data-sources',
  component: DataSourcesPage,
});

export const dataSourceDetailRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/data-sources/detail',
  validateSearch: (search: Record<string, unknown>) => ({
    ownerId: String(search.ownerId ?? ''),
    datasourceId: String(search.datasourceId ?? ''),
    type: String(search.type ?? ''),
  }),
  component: DataSourceDetailPage,
});

export const dagRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/dag',
  component: DAGPage,
});

export const modelsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/models',
  component: ModelsPage,
});

export const periodicTasksRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/periodic-tasks',
  component: PeriodicTasksPage,
});

export const messagesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/messages',
  component: MessagesPage,
});

export const nodeRoutesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/node-routes',
  component: NodeRoutesPage,
});

export const institutionsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/institutions',
  component: InstitutionsPage,
});

export const p2pProjectsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/p2p/projects',
  component: P2pProjectsPage,
});

export const p2pMyNodeRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/p2p/my-node',
  component: P2pMyNodePage,
});

export const accountRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/account',
  component: AccountPage,
});

const routeTree = rootRoute.addChildren([
  loginRoute,
  appRoute.addChildren([
    indexRoute,
    dashboardRoute,
    projectsRoute,
    nodesRoute,
    dataTablesRoute,
    dataSourcesRoute,
    dataSourceDetailRoute,
    dagRoute,
    modelsRoute,
    periodicTasksRoute,
    messagesRoute,
    nodeRoutesRoute,
    institutionsRoute,
    p2pProjectsRoute,
    p2pMyNodeRoute,
    accountRoute,
  ]),
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
