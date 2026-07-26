import React, { useEffect, useState } from 'react';
import { Card, Badge, Button } from '@secretpad/design-system';
import { apiClient, PageScheduledVO, Project } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

export const PeriodicTasksPage: React.FC = () => {
  const { t } = useTranslation();
  const [tasks, setTasks] = useState<PageScheduledVO[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient.getProjects()
      .then((ps) => {
        setProjects(ps);
        if (ps.length > 0) {
          setSelectedProjectId(ps[0].projectId);
        }
      })
      .catch((e) => setError(e.message));
  }, []);

  const loadTasks = () => {
    if (!selectedProjectId) return;
    setLoading(true);
    setError(null);
    apiClient.getScheduledTasks(selectedProjectId)
      .then(setTasks)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadTasks();
  }, [selectedProjectId]);

  const handleOffline = async (task: PageScheduledVO) => {
    if (!task.scheduleId) return;
    try {
      await apiClient.offlineScheduledTask(task.scheduleId);
      loadTasks();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleDelete = async (task: PageScheduledVO) => {
    if (!task.scheduleId) return;
    if (!window.confirm(t('periodicTasks.deleteConfirm'))) return;
    try {
      await apiClient.deleteScheduledTask(task.scheduleId);
      loadTasks();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const statusBadge = (status?: string) => {
    switch (status?.toUpperCase()) {
      case 'ONLINE':
      case 'ACTIVE':
        return 'success';
      case 'OFFLINE':
      case 'PAUSED':
        return 'warning';
      default:
        return 'default';
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('periodicTasks.title')}</h2>
          <p className="text-xs text-gray-500">{t('periodicTasks.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={selectedProjectId}
            onChange={(e) => setSelectedProjectId(e.target.value)}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {projects.map((p) => (
              <option key={p.projectId} value={p.projectId}>{p.projectName}</option>
            ))}
          </select>
          <AccessGuard access={{ types: [Platform.CENTER] }}>
            <Button variant="primary" onClick={() => alert(t('periodicTasks.createHint'))}>{t('periodicTasks.create')}</Button>
          </AccessGuard>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <Card bodyClassName="p-0">
        <table className="w-full text-left text-xs">
          <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 font-semibold uppercase border-b border-gray-200 dark:border-gray-800">
            <tr>
              <th className="p-4">{t('periodicTasks.name')}</th>
              <th className="p-4">{t('periodicTasks.status')}</th>
              <th className="p-4">{t('periodicTasks.creator')}</th>
              <th className="p-4">{t('periodicTasks.createTime')}</th>
              <th className="p-4">{t('common.action')}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
            {tasks.length === 0 && !loading && !error && (
              <tr>
                <td colSpan={5} className="p-4 text-center text-gray-400">{t('periodicTasks.noData')}</td>
              </tr>
            )}
            {tasks.map((task) => (
              <tr key={task.scheduleId}>
                <td className="p-4 font-semibold text-gray-800 dark:text-gray-200">
                  {task.scheduleDesc || task.scheduleId}
                </td>
                <td className="p-4">
                  <Badge status={statusBadge(task.scheduleStats)}>{task.scheduleStats}</Badge>
                </td>
                <td className="p-4 text-gray-500">{task.creator || '-'}</td>
                <td className="p-4 text-gray-500">{task.createTime}</td>
                <td className="p-4">
                  <div className="flex items-center gap-2">
                    <AccessGuard access={{ types: [Platform.CENTER] }}>
                      <Button size="sm" variant="ghost" onClick={() => handleOffline(task)}>{t('periodicTasks.offline')}</Button>
                      <Button size="sm" variant="danger" onClick={() => handleDelete(task)}>{t('common.delete')}</Button>
                    </AccessGuard>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
};
