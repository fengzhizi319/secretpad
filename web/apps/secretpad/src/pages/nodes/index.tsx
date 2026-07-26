import React, { useEffect, useState } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import { apiClient, Node, CreateNodeInput, UpdateNodeInput } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

const MODE_MAP: Record<string, number> = { MPC: 0, TEE: 1, BOTH: 2 };

export const NodesPage: React.FC = () => {
  const { t } = useTranslation();
  const [nodes, setNodes] = useState<Node[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editingNode, setEditingNode] = useState<Node | null>(null);
  const [nodeName, setNodeName] = useState('');
  const [nodeMode, setNodeMode] = useState<'MPC' | 'TEE' | 'BOTH'>('MPC');
  const [netAddress, setNetAddress] = useState('');
  const [tokenModalNode, setTokenModalNode] = useState<Node | null>(null);
  const [token, setToken] = useState<{ token?: string; tokenStatus?: string } | null>(null);

  const loadNodes = () => {
    apiClient.getNodes()
      .then(setNodes)
      .catch((e) => setError(e.message));
  };

  useEffect(() => {
    loadNodes();
  }, []);

  const resetForm = () => {
    setNodeName('');
    setNodeMode('MPC');
    setNetAddress('');
    setEditingNode(null);
  };

  const openRegister = () => {
    resetForm();
    setIsModalOpen(true);
  };

  const openEdit = (node: Node) => {
    setEditingNode(node);
    setNodeName(node.nodeName);
    setNodeMode('MPC');
    setNetAddress(node.netAddress || '');
    setIsModalOpen(true);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      if (editingNode) {
        const input: UpdateNodeInput = {
          nodeId: editingNode.nodeId,
          netAddress,
        };
        await apiClient.updateNode(input);
      } else {
        const input: CreateNodeInput = {
          name: nodeName,
          mode: MODE_MAP[nodeMode],
        };
        await apiClient.createNode(input);
      }
      setIsModalOpen(false);
      resetForm();
      loadNodes();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (node: Node) => {
    if (!window.confirm(t('nodes.deleteConfirm'))) return;
    try {
      await apiClient.deleteNode(node.nodeId);
      loadNodes();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleRefresh = async (node: Node) => {
    try {
      await apiClient.refreshNode(node.nodeId);
      loadNodes();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleViewToken = async (node: Node) => {
    setTokenModalNode(node);
    try {
      const res = await apiClient.getNodeToken(node.nodeId);
      setToken(res);
    } catch (e) {
      setToken({ token: e instanceof Error ? e.message : String(e), tokenStatus: 'ERROR' });
    }
  };

  return (
    <div className="space-y-6">
      {/* Header Bar */}
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('nodes.title')}</h2>
          <p className="text-xs text-gray-500">{t('nodes.subtitle')}</p>
        </div>
        <AccessGuard access={{ types: [Platform.CENTER] }}>
          <Button variant="primary" icon={<span>＋</span>} onClick={openRegister}>{t('nodes.register')}</Button>
        </AccessGuard>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {/* Nodes Table */}
      <Card bodyClassName="p-0">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead className="bg-gray-50 dark:bg-gray-850 text-gray-500 uppercase tracking-wider font-semibold border-b border-gray-200 dark:border-gray-800">
              <tr>
                <th className="p-4">{t('nodes.name')}</th>
                <th className="p-4">{t('nodes.id')}</th>
                <th className="p-4">{t('nodes.type')}</th>
                <th className="p-4">{t('nodes.status')}</th>
                <th className="p-4">{t('nodes.netAddress')}</th>
                <th className="p-4">{t('nodes.registerTime')}</th>
                <th className="p-4">{t('common.action') || 'Action'}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-800 text-gray-800 dark:text-gray-200">
              {nodes.length === 0 && (
                <tr>
                  <td colSpan={7} className="p-4 text-center text-gray-400">{t('nodes.noNodes')}</td>
                </tr>
              )}
              {nodes.map((node) => (
                <tr key={node.nodeId} className="hover:bg-gray-50/50 dark:hover:bg-gray-850/50">
                  <td className="p-4 font-semibold text-blue-600 dark:text-blue-400">{node.nodeName}</td>
                  <td className="p-4 font-mono text-gray-500">{node.nodeId}</td>
                  <td className="p-4">
                    <span className="px-2 py-0.5 rounded font-mono bg-gray-100 dark:bg-gray-800 text-gray-700 dark:text-gray-300">
                      {node.type}
                    </span>
                  </td>
                  <td className="p-4">
                    <Badge status={node.nodeStatus === 'Ready' ? 'success' : 'default'}>
                      {node.nodeStatus}
                    </Badge>
                  </td>
                  <td className="p-4 font-mono text-gray-500">{node.netAddress || '-'}</td>
                  <td className="p-4 text-gray-400">{node.gmtCreate}</td>
                  <td className="p-4">
                    <div className="flex items-center gap-2">
                      <Button size="sm" variant="ghost" onClick={() => handleRefresh(node)}>{t('nodes.refresh')}</Button>
                      <AccessGuard access={{ types: [Platform.CENTER] }}>
                        <Button size="sm" variant="ghost" onClick={() => openEdit(node)}>{t('nodes.edit')}</Button>
                        <Button size="sm" variant="ghost" onClick={() => handleViewToken(node)}>{t('nodes.token')}</Button>
                        <Button size="sm" variant="danger" onClick={() => handleDelete(node)}>{t('nodes.delete')}</Button>
                      </AccessGuard>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* Register / Edit Modal */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => { setIsModalOpen(false); resetForm(); }}
        title={editingNode ? t('nodes.modalEditTitle') : t('nodes.modalRegisterTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => { setIsModalOpen(false); resetForm(); }}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={handleSubmit} loading={loading}>{t('common.confirm')}</Button>
          </>
        }
      >
        <form onSubmit={handleSubmit} className="space-y-4 text-xs">
          {!editingNode && (
            <div>
              <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('nodes.nodeNameLabel')}</label>
              <input
                type="text"
                value={nodeName}
                onChange={(e) => setNodeName(e.target.value)}
                placeholder={t('nodes.nodeNamePlaceholder')}
                className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
                required
              />
            </div>
          )}

          {!editingNode && (
            <div>
              <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('nodes.modeLabel')}</label>
              <select
                value={nodeMode}
                onChange={(e) => setNodeMode(e.target.value as 'MPC' | 'TEE' | 'BOTH')}
                className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="MPC">{t('nodes.modeMPC')}</option>
                <option value="TEE">{t('nodes.modeTEE')}</option>
                <option value="BOTH">{t('nodes.modeBoth')}</option>
              </select>
            </div>
          )}

          {editingNode && (
            <div>
              <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('nodes.netAddressLabel')}</label>
              <input
                type="text"
                value={netAddress}
                onChange={(e) => setNetAddress(e.target.value)}
                placeholder={t('nodes.netAddressPlaceholder')}
                className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
                required
              />
            </div>
          )}
        </form>
      </Modal>

      {/* Token Modal */}
      <Modal
        isOpen={!!tokenModalNode}
        onClose={() => { setTokenModalNode(null); setToken(null); }}
        title={tokenModalNode ? `${t('nodes.token')} - ${tokenModalNode.nodeName}` : t('nodes.token')}
        footer={
          <Button variant="primary" onClick={() => { setTokenModalNode(null); setToken(null); }}>{t('common.close')}</Button>
        }
      >
        <div className="text-xs space-y-2">
          <div className="font-semibold text-gray-700 dark:text-gray-300">Status: <span className="font-normal">{token?.tokenStatus || '...'}</span></div>
          <textarea
            readOnly
            value={token?.token || ''}
            rows={6}
            className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 font-mono text-[10px] focus:outline-none"
          />
        </div>
      </Modal>
    </div>
  );
};
