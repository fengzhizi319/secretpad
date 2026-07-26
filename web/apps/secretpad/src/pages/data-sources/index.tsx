import React, { useEffect, useState } from 'react';
import { Card, Button, Badge } from '@secretpad/design-system';
import { apiClient, DataSource } from '@secretpad/api-client';

export const DataSourcesPage: React.FC = () => {
  const [sources, setSources] = useState<DataSource[]>([]);

  useEffect(() => {
    apiClient.getDataSources().then(setSources);
  }, []);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Data Sources Connections</h2>
          <p className="text-xs text-gray-500">Configure ODPS, MySQL, Local DataProxy connections</p>
        </div>
        <Button variant="primary" icon={<span>＋</span>}>Add Data Source</Button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {sources.map(ds => (
          <Card key={ds.datasourceId}>
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-bold text-sm text-gray-900 dark:text-gray-100">{ds.name}</h3>
              <Badge status="success">{ds.status}</Badge>
            </div>
            <div className="text-xs text-gray-500 space-y-1 font-mono">
              <div>ID: {ds.datasourceId}</div>
              <div>Type: {ds.type}</div>
              <div>Node: {ds.nodeId}</div>
            </div>
          </Card>
        ))}
      </div>
    </div>
  );
};
