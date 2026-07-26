import React, { useEffect, useState } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import { apiClient, DataTable, Node, DataSource } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

function parseSchemaText(text: string): { name: string; type: string }[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [name, type = 'string'] = line.split(':');
      return { name: name.trim(), type: type.trim() };
    });
}

export const DataTablesPage: React.FC = () => {
  const { t } = useTranslation();
  const [tables, setTables] = useState<DataTable[]>([]);
  const [nodes, setNodes] = useState<Node[]>([]);
  const [sources, setSources] = useState<DataSource[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<string>('');
  const [selectedTable, setSelectedTable] = useState<DataTable | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [tableName, setTableName] = useState('');
  const [datasourceId, setDatasourceId] = useState('');
  const [relativeUri, setRelativeUri] = useState('');
  const [schemaText, setSchemaText] = useState('id:string\nvalue:int');
  const [submitting, setSubmitting] = useState(false);

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

  const loadSources = () => {
    if (!selectedNodeId) return;
    apiClient.getDataSources(selectedNodeId)
      .then((list) => {
        setSources(list);
        if (list.length > 0) {
          setDatasourceId(list[0].datasourceId);
        }
      })
      .catch(() => setSources([]));
  };

  useEffect(() => {
    loadSources();
  }, [selectedNodeId]);

  const loadTables = () => {
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
  };

  useEffect(() => {
    loadTables();
  }, [selectedNodeId]);

  const resetForm = () => {
    setTableName('');
    setRelativeUri('');
    setSchemaText('id:string\nvalue:int');
    if (sources.length > 0) {
      setDatasourceId(sources[0].datasourceId);
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      const selectedSource = sources.find((s) => s.datasourceId === datasourceId) || sources[0];
      const columns = parseSchemaText(schemaText).map((c) => ({ ...c, comment: '', classification: 'L1' }));
      await apiClient.createDataTable({
        ownerId: selectedNodeId,
        nodeIds: [selectedNodeId],
        datatableName: tableName,
        datasourceId: selectedSource.datasourceId,
        datasourceName: selectedSource.name,
        datasourceType: selectedSource.type,
        relativeUri,
        columns,
      });
      setIsModalOpen(false);
      resetForm();
      loadTables();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (table: DataTable) => {
    if (!window.confirm(t('dataTables.deleteConfirm'))) return;
    try {
      await apiClient.deleteDataTable({
        nodeId: table.nodeId || selectedNodeId,
        datatableId: table.tableId,
        datasourceId: table.datasourceId,
        datasourceType: table.datasourceType,
        relativeUri: table.relativeUri,
      });
      loadTables();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('dataTables.title')}</h2>
          <p className="text-xs text-gray-500">{t('dataTables.subtitle')}</p>
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
          <AccessGuard access={{ types: [Platform.CENTER] }}>
            <Button variant="primary" icon={<span>＋</span>} onClick={() => setIsModalOpen(true)}>{t('dataTables.import')}</Button>
          </AccessGuard>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left: Table List */}
        <div className="lg:col-span-1 space-y-3">
          {tables.length === 0 && !loading && !error && (
            <div className="text-xs text-gray-400 text-center py-6">{t('dataTables.noData')}</div>
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
                <div className="flex items-center gap-2">
                  <Badge status="success">{tbl.status}</Badge>
                  <AccessGuard access={{ types: [Platform.CENTER] }}>
                    <Button size="sm" variant="danger" onClick={(e) => { e.stopPropagation(); handleDelete(tbl); }}>{t('common.delete')}</Button>
                  </AccessGuard>
                </div>
              </div>
              <div className="text-xs text-gray-500 font-mono">ID: {tbl.tableId} • Node: {tbl.nodeName || tbl.nodeId}</div>
              <div className="mt-2 text-xs text-gray-400">{t('dataTables.rows')}: {(tbl.rowCount || 0).toLocaleString()} | {t('dataTables.columns')}: {tbl.columns.length}</div>
            </Card>
          ))}
        </div>

        {/* Right: Selected Table Schema & Classification Details */}
        {selectedTable && (
          <div className="lg:col-span-2">
            <Card title={`Schema: ${selectedTable.tableName}`}>
              <div className="mb-4 flex items-center justify-between text-xs text-gray-500 pb-3 border-b border-gray-100 dark:border-gray-800">
                <div>{t('dataTables.nodeBelongs')}: <span className="font-semibold text-gray-800 dark:text-gray-200">{selectedTable.nodeName || selectedTable.nodeId}</span></div>
                <div>{t('dataTables.rows')}: <span className="font-semibold text-gray-800 dark:text-gray-200">{(selectedTable.rowCount || 0).toLocaleString()}</span></div>
              </div>

              <table className="w-full text-left text-xs">
                <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 font-semibold uppercase">
                  <tr>
                    <th className="p-3">{t('dataTables.columnName')}</th>
                    <th className="p-3">{t('dataTables.dataType')}</th>
                    <th className="p-3">{t('dataTables.sensitivity')}</th>
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
                          {col.classification || 'L1'} {t('dataTables.standard')}
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

      <Modal
        isOpen={isModalOpen}
        onClose={() => { setIsModalOpen(false); resetForm(); }}
        title={t('dataTables.modalImportTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => { setIsModalOpen(false); resetForm(); }}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={handleCreate} loading={submitting}>{t('common.confirm')}</Button>
          </>
        }
      >
        <form onSubmit={handleCreate} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dataTables.nameLabel')}</label>
            <input
              type="text"
              value={tableName}
              onChange={(e) => setTableName(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dataTables.datasourceLabel')}</label>
            <select
              value={datasourceId}
              onChange={(e) => setDatasourceId(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              {sources.map((s) => (
                <option key={s.datasourceId} value={s.datasourceId}>{s.name} ({s.type})</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dataTables.uriLabel')}</label>
            <input
              type="text"
              value={relativeUri}
              onChange={(e) => setRelativeUri(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dataTables.schemaLabel')}</label>
            <textarea
              value={schemaText}
              onChange={(e) => setSchemaText(e.target.value)}
              rows={5}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 font-mono text-[10px] focus:outline-none focus:border-blue-500"
            />
          </div>
        </form>
      </Modal>
    </div>
  );
};
