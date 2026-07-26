import React from 'react';
import { Card, Badge, Button } from '@secretpad/design-system';

export const PeriodicTasksPage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Scheduled Jobs & Cron</h2>
          <p className="text-xs text-gray-500">Quartz-scheduled periodic PSI intersections and model updating</p>
        </div>
        <Button variant="primary">Create Schedule</Button>
      </div>

      <Card bodyClassName="p-0">
        <table className="w-full text-left text-xs">
          <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 font-semibold uppercase border-b border-gray-200 dark:border-gray-800">
            <tr>
              <th className="p-4">Task Name</th>
              <th className="p-4">Cron Schedule</th>
              <th className="p-4">Status</th>
              <th className="p-4">Last Run</th>
              <th className="p-4">Next Run</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 dark:divide-gray-800">
            <tr>
              <td className="p-4 font-semibold text-gray-800 dark:text-gray-200">Daily Anti-Fraud PSI Sync</td>
              <td className="p-4 font-mono text-blue-600">0 0 2 * * ?</td>
              <td className="p-4"><Badge status="success">Active</Badge></td>
              <td className="p-4 text-gray-500">2026-07-26 02:00:00</td>
              <td className="p-4 text-gray-500">2026-07-27 02:00:00</td>
            </tr>
          </tbody>
        </table>
      </Card>
    </div>
  );
};
