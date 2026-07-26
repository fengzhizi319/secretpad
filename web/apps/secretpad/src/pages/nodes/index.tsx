import React, { useEffect, useState } from 'react';
import { Card, Button, Badge } from '@secretpad/design-system';
import { apiClient, Node } from '@secretpad/api-client';

export const NodesPage: React.FC = () => {
  const [nodes, setNodes] = useState<Node[]>([]);

  useEffect(() => {
    apiClient.getNodes().then(setNodes);
  }, []);

  return (
    <div className="space-y-6">
      {/* Header Bar */}
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Kuscia Node Cluster</h2>
          <p className="text-xs text-gray-500">Manage Kuscia Center, Edge, and Autonomy domain nodes</p>
        </div>
        <Button variant="primary" icon={<span>＋</span>}>Register New Node</Button>
      </div>

      {/* Nodes Table */}
      <Card bodyClassName="p-0">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 uppercase tracking-wider font-semibold border-b border-gray-200 dark:border-gray-800">
              <tr>
                <th className="p-4">Node Name</th>
                <th className="p-4">Node ID</th>
                <th className="p-4">Domain Type</th>
                <th className="p-4">Status</th>
                <th className="p-4">IP Address</th>
                <th className="p-4">Allocated Cores / RAM</th>
                <th className="p-4">Register Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800 text-gray-800 dark:text-gray-200">
              {nodes.map((node) => (
                <tr key={node.nodeId} className="hover:bg-gray-50/50 dark:hover:bg-gray-850/50">
                  <td className="p-4 font-semibold text-blue-600 dark:text-blue-400">{node.name}</td>
                  <td className="p-4 font-mono text-gray-500">{node.nodeId}</td>
                  <td className="p-4">
                    <span className="px-2 py-0.5 rounded font-mono bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300">
                      {node.type}
                    </span>
                  </td>
                  <td className="p-4">
                    <Badge status={node.status === 'Ready' ? 'success' : 'error'}>
                      {node.status}
                    </Badge>
                  </td>
                  <td className="p-4 font-mono text-gray-500">{node.ip || '127.0.0.1'}</td>
                  <td className="p-4 text-gray-600 dark:text-gray-400">{node.cpu || 8} Cores / {node.memory || 16} GB</td>
                  <td className="p-4 text-gray-400">{node.createTime}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
};
