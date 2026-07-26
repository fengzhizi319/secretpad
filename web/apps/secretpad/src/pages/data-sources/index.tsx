import React, { useEffect, useState } from 'react';
import { Card, Button, Badge } from '@secretpad/design-system';
import { apiClient, DataSource, Node } from '@secretpad/api-client';

export const DataSourcesPage: React.FC = () => {
  const [sources, setSources] = useState<DataSource[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiClient.getNodes()
      .then((ns) => {
        setNodes(ns);
        if (ns.length > 0) {
          setSelectedNodeId(ns[0].nodeId);
        }
      })
      .catch((e) => setError(e.message));
  }, []);

  useEffect(() => {
    if (!selectedNodeId) return;
    setLoading(true);
    setError(null);
    apiClient.getDataSources(selectedNodeId)
      .then(setSources)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [selectedNodeId]);

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Data Sources Connections</h2>
          <p className="text-xs text-gray-500">Configure ODPS, MySQL, Local DataProxy connections</p>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={selectedNodeId}
            onChange={(e) => setSelectedNodeId(e.target.value)}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {nodes.map((n) => (
              <option key={n.nodeId} value={n.nodeId}>{n.nodeName}</option>
            ))}
          </select>
          <Button variant="primary" icon={<span>＋</span>}>Add Data Source</Button>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          API Error: {error}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">Loading data sources...</div>}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {sources.map((ds) => (
          <Card key={ds.datasourceId}>
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-bold text-sm text-gray-900 dark:text-gray-100">{ds.name}</h3>
              <Badge status="success">{ds.status || 'Available'}</Badge>
            </div>
            <div className="text-xs text-gray-500 space-y-1 font-mono">
              <div>ID: {ds.datasourceId}</div>
              <div>Type: {ds.type}</div>
              <div>Nodes: {ds.nodes.map((n) => n.nodeName || n.nodeId).join(', ')}</div>
            </div>
          </Card>
        ))}
      </div>

      {sources.length === 0 && !loading && !error && (
        <div className="text-center text-xs text-gray-400 py-10">No data sources for this node</div>
      )}
    </div>
  );
};
