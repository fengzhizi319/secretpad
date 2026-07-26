import React, { useEffect, useState } from 'react';
import { Card, Button, Badge } from '@secretpad/design-system';
import { apiClient, DataTable, Node } from '@secretpad/api-client';

export const DataTablesPage: React.FC = () => {
  const [tables, setTables] = useState<DataTable[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string>('');
  const [selectedTable, setSelectedTable] = useState<DataTable | null>(null);
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
    apiClient.getDataTables(selectedNodeId)
      .then((res) => {
        setTables(res);
        if (res.length > 0) {
          setSelectedTable(res[0]);
        } else {
          setSelectedTable(null);
        }
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [selectedNodeId]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Data Assets & Classification</h2>
          <p className="text-xs text-gray-500">Local privacy data assets, L1~L5 sensitivity classifications and schemas</p>
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
          <Button variant="primary" icon={<span>＋</span>}>Import Local Table</Button>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          API Error: {error}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">Loading data tables...</div>}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left: Table List */}
        <div className="lg:col-span-1 space-y-3">
          {tables.length === 0 && !loading && !error && (
            <div className="text-xs text-gray-400 text-center py-6">No data tables for this node</div>
          )}
          {tables.map((tbl) => (
            <Card
              key={tbl.tableId}
              onClick={() => setSelectedTable(tbl)}
              className={`cursor-pointer transition-all ${
                selectedTable?.tableId === tbl.tableId ? 'border-blue-500 ring-2 ring-blue-500/20' : 'hover:border-gray-300'
              }`}
            >
              <div className="flex items-center justify-between mb-1.5">
                <span className="font-semibold text-sm text-gray-900 dark:text-gray-100">{tbl.tableName}</span>
                <Badge status="success">{tbl.status}</Badge>
              </div>
              <div className="text-xs text-gray-500 font-mono">ID: {tbl.tableId} • Node: {tbl.nodeName || tbl.nodeId}</div>
              <div className="mt-2 text-xs text-gray-400">Rows: {(tbl.rowCount || 0).toLocaleString()} | Columns: {tbl.columns.length}</div>
            </Card>
          ))}
        </div>

        {/* Right: Selected Table Schema & Classification Details */}
        {selectedTable && (
          <div className="lg:col-span-2">
            <Card title={`Schema: ${selectedTable.tableName}`}>
              <div className="mb-4 flex items-center justify-between text-xs text-gray-500 pb-3 border-b border-gray-100 dark:border-gray-800">
                <div>Node Belongs: <span className="font-semibold text-gray-800 dark:text-gray-200">{selectedTable.nodeName || selectedTable.nodeId}</span></div>
                <div>Total Records: <span className="font-semibold text-gray-800 dark:text-gray-200">{(selectedTable.rowCount || 0).toLocaleString()}</span></div>
              </div>

              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 font-semibold uppercase">
                  <tr>
                    <th className="p-3">Column Name</th>
                    <th className="p-3">Data Type</th>
                    <th className="p-3">Sensitivity Level</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100 dark:border-gray-800">
                  {selectedTable.columns.map((col, idx) => (
                    <tr key={idx}>
                      <td className="p-3 font-mono font-medium text-gray-800 dark:text-gray-200">{col.name}</td>
                      <td className="p-3 font-mono text-gray-500">{col.type}</td>
                      <td className="p-3">
                        <span className={`px-2 py-0.5 rounded text-[10px] font-semibold font-mono ${
                          col.classification === 'L3' ? 'bg-amber-100 dark:bg-amber-950 text-amber-600' :
                          col.classification === 'L2' ? 'bg-blue-100 dark:bg-blue-950 text-blue-600' :
                          'bg-gray-100 dark:bg-gray-800 text-gray-600'
                        }`}>
                          {col.classification || 'L1'} Standard
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          </div>
        )}
      </div>
    </div>
  );
};
