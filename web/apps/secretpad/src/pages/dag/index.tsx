import React, { useEffect, useState } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import { apiClient, Project, GraphMetaVO, GraphDetailVO, ComponentSummaryDef } from '@secretpad/api-client';
import { DAGNextWorkspace, DAGNode, DAGEdge } from '@secretpad/dag-next';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

function mapGraphToDAG(graph?: GraphDetailVO): { nodes: DAGNode[]; edges: DAGEdge[] } {
  if (!graph) return { nodes: [], edges: [] };
  const nodes: DAGNode[] = (graph.nodes || []).map((n) => ({
    id: n.graphNodeId || String(Math.random()),
    name: n.label || n.codeName || 'Node',
    category: n.codeName?.split('/')?.[0] || 'Unknown',
    icon: '⚙️',
    status: n.status === 'SUCCEED' ? 'Success' : n.status === 'RUNNING' ? 'Running' : 'Ready',
    x: n.x ?? 100,
    y: n.y ?? 100,
  }));
  const edges: DAGEdge[] = (graph.edges || []).map((e) => ({
    id: e.edgeId || `${e.source}-${e.target}`,
    source: e.source || '',
    target: e.target || '',
  })).filter((e) => e.source && e.target);
  return { nodes, edges };
}

export const DAGPage: React.FC = () => {
  const { t } = useTranslation();
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [graphs, setGraphs] = useState<GraphMetaVO[]>([]);
  const [selectedGraph, setSelectedGraph] = useState<GraphMetaVO | null>(null);
  const [graphDetail, setGraphDetail] = useState<GraphDetailVO | null>(null);
  const [components, setComponents] = useState<ComponentSummaryDef[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newGraphName, setNewGraphName] = useState('');

  useEffect(() => {
    apiClient.getProjects()
      .then((ps) => {
        setProjects(ps);
        if (ps.length > 0) {
          setSelectedProjectId(ps[0].projectId);
        }
      })
      .catch((e) => setError(e.message));

    apiClient.getComponents()
      .then((list) => {
        const all: ComponentSummaryDef[] = [];
        list.forEach((group) => {
          (group.comps || []).forEach((c) => all.push(c));
        });
        setComponents(all);
      })
      .catch(() => setComponents([]));
  }, []);

  const loadGraphs = () => {
    if (!selectedProjectId) return;
    setLoading(true);
    apiClient.getGraphs(selectedProjectId)
      .then((gs) => {
        setGraphs(gs);
        if (gs.length > 0 && !selectedGraph) {
          setSelectedGraph(gs[0]);
        } else if (gs.length === 0) {
          setSelectedGraph(null);
          setGraphDetail(null);
        }
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadGraphs();
  }, [selectedProjectId]);

  useEffect(() => {
    if (!selectedProjectId || !selectedGraph?.graphId) {
      setGraphDetail(null);
      return;
    }
    setLoading(true);
    apiClient.getGraphDetail(selectedProjectId, selectedGraph.graphId)
      .then(setGraphDetail)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [selectedProjectId, selectedGraph?.graphId]);

  const handleCreateGraph = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProjectId || !newGraphName.trim()) return;
    try {
      await apiClient.createGraph({ projectId: selectedProjectId, name: newGraphName });
      setIsCreateModalOpen(false);
      setNewGraphName('');
      loadGraphs();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleDeleteGraph = async (graph: GraphMetaVO) => {
    if (!selectedProjectId || !graph.graphId) return;
    if (!window.confirm(t('dag.deleteConfirm'))) return;
    try {
      await apiClient.deleteGraph(selectedProjectId, graph.graphId);
      if (selectedGraph?.graphId === graph.graphId) {
        setSelectedGraph(null);
        setGraphDetail(null);
      }
      loadGraphs();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleStartGraph = async () => {
    if (!selectedProjectId || !selectedGraph?.graphId) return;
    try {
      const jobId = await apiClient.startGraph(
        selectedProjectId,
        selectedGraph.graphId,
        (graphDetail?.nodes || []).map((n) => n.graphNodeId || '').filter(Boolean)
      );
      alert(t('dag.started', { jobId }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const { nodes, edges } = mapGraphToDAG(graphDetail || undefined);

  return (
    <div className="space-y-4 h-full flex flex-col">
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('dag.title')}</h2>
          <p className="text-xs text-gray-500">{t('dag.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3 flex-wrap">
          <select
            value={selectedProjectId}
            onChange={(e) => { setSelectedProjectId(e.target.value); setSelectedGraph(null); setGraphDetail(null); }}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {projects.map((p) => (
              <option key={p.projectId} value={p.projectId}>{p.projectName}</option>
            ))}
          </select>

          <select
            value={selectedGraph?.graphId || ''}
            onChange={(e) => {
              const g = graphs.find((x) => x.graphId === e.target.value) || null;
              setSelectedGraph(g);
            }}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {graphs.map((g) => (
              <option key={g.graphId} value={g.graphId || ''}>{g.name}</option>
            ))}
          </select>

          <AccessGuard access={{ types: [Platform.CENTER] }}>
            <Button variant="primary" size="sm" onClick={() => setIsCreateModalOpen(true)}>{t('dag.create')}</Button>
            <Button variant="danger" size="sm" onClick={() => selectedGraph && handleDeleteGraph(selectedGraph)}>{t('common.delete')}</Button>
            <Button variant="outline" size="sm" onClick={handleStartGraph}>{t('dag.run')}</Button>
          </AccessGuard>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <div className="flex-1 min-h-0">
        {selectedGraph ? (
          <DAGNextWorkspace initialNodes={nodes} initialEdges={edges} />
        ) : (
          <Card className="h-full flex items-center justify-center">
            <div className="text-center text-xs text-gray-400">
              <div className="mb-2">{t('dag.noGraph')}</div>
              <AccessGuard access={{ types: [Platform.CENTER] }}>
                <Button variant="primary" size="sm" onClick={() => setIsCreateModalOpen(true)}>{t('dag.create')}</Button>
              </AccessGuard>
            </div>
          </Card>
        )}
      </div>

      {components.length > 0 && (
        <Card title={t('dag.components')}>
          <div className="flex flex-wrap gap-2 text-xs">
            {components.slice(0, 20).map((c, idx) => (
              <Badge key={idx} status="default">{c.name || c.domain}</Badge>
            ))}
          </div>
        </Card>
      )}

      <Modal
        isOpen={isCreateModalOpen}
        onClose={() => { setIsCreateModalOpen(false); setNewGraphName(''); }}
        title={t('dag.createTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => { setIsCreateModalOpen(false); setNewGraphName(''); }}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={handleCreateGraph}>{t('common.create')}</Button>
          </>
        }
      >
        <form onSubmit={handleCreateGraph} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dag.nameLabel')}</label>
            <input
              type="text"
              value={newGraphName}
              onChange={(e) => setNewGraphName(e.target.value)}
              placeholder={t('dag.namePlaceholder')}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
        </form>
      </Modal>
    </div>
  );
};
