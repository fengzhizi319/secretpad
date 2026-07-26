import React, { useEffect, useRef, useState, useCallback, useMemo } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import {
  apiClient,
  Project,
  GraphMetaVO,
  GraphDetailVO,
  GraphNodeInfo,
  GraphEdge,
  ComponentSummaryDef,
} from '@secretpad/api-client';
import { DAGNextWorkspace, DAGNode, DAGEdge, DAGComponentDef } from '@secretpad/dag-next';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

const IO_COMPONENTS: DAGComponentDef[] = [
  { domain: 'read_data', name: 'datatable', icon: '📥' },
];

const PRIVACY_COMPONENTS: DAGComponentDef[] = [
  { domain: 'preprocessing', name: 'psi', icon: '🔒' },
  { domain: 'privacy', name: 'dp_noise', icon: '🛡️' },
];

const ML_COMPONENTS: DAGComponentDef[] = [
  { domain: 'ml.train', name: 'sgb_train', icon: '🤖' },
  { domain: 'ml.train', name: 'ss_glm_train', icon: '📊' },
  { domain: 'ml.train', name: 'ss_xgb_train', icon: '🌲' },
  { domain: 'ml.train', name: 'ss_sgd_train', icon: '⚡' },
  { domain: 'ml.predict', name: 'sgb_predict', icon: '🔮' },
];

const DEFAULT_COMPONENT_GROUPS: Record<string, DAGComponentDef[]> = {
  'Data I/O': IO_COMPONENTS,
  'Privacy & PSI': PRIVACY_COMPONENTS,
  'Machine Learning': ML_COMPONENTS,
};

function normalizeCodeName(codeName?: string): { domain: string; name: string } {
  if (!codeName) return { domain: 'unknown', name: 'unknown' };
  const parts = codeName.split('/');
  if (parts.length >= 2) {
    return { domain: parts.slice(0, -1).join('/'), name: parts[parts.length - 1] };
  }
  return { domain: codeName, name: codeName };
}

function mapBackendStatus(status?: string): DAGNode['status'] {
  switch (status) {
    case 'RUNNING':
      return 'Running';
    case 'SUCCEED':
      return 'Success';
    case 'FAILED':
      return 'Failed';
    case 'STOPPED':
      return 'Stopped';
    default:
      return 'Ready';
  }
}

function mapGraphToDAG(graph?: GraphDetailVO): { nodes: DAGNode[]; edges: DAGEdge[] } {
  if (!graph) return { nodes: [], edges: [] };
  const nodes: DAGNode[] = (graph.nodes || []).map((n) => {
    const { domain } = normalizeCodeName(n.codeName);
    return {
      id: n.graphNodeId || String(Math.random()),
      name: n.label || n.codeName || 'Node',
      category: domain || 'Unknown',
      icon: domain === 'read_data' ? '📥' : domain.startsWith('ml') ? '🤖' : '⚙️',
      status: mapBackendStatus(n.status),
      x: n.x ?? 100,
      y: n.y ?? 100,
      progress: n.progress,
      codeName: n.codeName,
      nodeDef: (n as any).nodeDef,
      inputs: (n as any).inputs,
      outputs: (n as any).outputs,
    };
  });
  const edges: DAGEdge[] = (graph.edges || [])
    .map((e) => ({
      id: e.edgeId || `${e.source}-${e.target}-${Math.random().toString(36).slice(2, 6)}`,
      source: e.source || '',
      target: e.target || '',
      sourceAnchor: e.sourceAnchor,
      targetAnchor: e.targetAnchor,
    }))
    .filter((e) => e.source && e.target);
  return { nodes, edges };
}

function mapDAGNodeToGraphNode(node: DAGNode): GraphNodeInfo {
  return {
    graphNodeId: node.id,
    codeName: node.codeName,
    label: node.name,
    x: Math.round(node.x),
    y: Math.round(node.y),
    inputs: node.inputs,
    outputs: node.outputs,
    nodeDef: node.nodeDef,
  };
}

function mapDAGEdgeToGraphEdge(edge: DAGEdge): GraphEdge {
  return {
    edgeId: edge.id,
    source: edge.source,
    target: edge.target,
    sourceAnchor: edge.sourceAnchor,
    targetAnchor: edge.targetAnchor,
  };
}

export const DAGPage: React.FC = () => {
  const { t } = useTranslation();
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [graphs, setGraphs] = useState<GraphMetaVO[]>([]);
  const [selectedGraph, setSelectedGraph] = useState<GraphMetaVO | null>(null);
  const [graphDetail, setGraphDetail] = useState<GraphDetailVO | null>(null);
  const [components, setComponents] = useState<ComponentSummaryDef[]>([]);
  const [i18nMap, setI18nMap] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newGraphName, setNewGraphName] = useState('');
  const [componentGroups, setComponentGroups] = useState<Record<string, DAGComponentDef[]>>(DEFAULT_COMPONENT_GROUPS);
  const pollingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const dagLabels = useMemo(
    () => ({
      operatorLibrary: t('dag.components'),
      noOperators: t('common.empty'),
      connect: t('dag.connect'),
      clickTarget: t('dag.clickTarget'),
      connectionHint: t('dag.clickTarget'),
      parameters: t('dag.parameters'),
      logs: t('dag.logs'),
      output: t('dag.output'),
      save: t('dag.save'),
      run: t('dag.run'),
      nodeIdentifier: 'Node ID',
      operatorName: t('dag.nameLabel'),
      codeName: 'Code Name',
      executionStatus: t('common.statusActive'),
      position: 'Position',
      frontendConfig: t('dag.config'),
      nodeDef: 'NodeDef',
      applyConfig: t('dag.apply'),
      status: t('common.statusActive'),
      noLogs: t('dag.noLogs'),
      noOutput: t('dag.noOutput'),
      refresh: t('common.search'),
      nodeOutput: t('dag.output'),
      deleteNode: t('common.delete'),
    }),
    [t]
  );

  useEffect(() => {
    apiClient
      .getProjects()
      .then((ps) => {
        setProjects(ps);
        if (ps.length > 0) {
          setSelectedProjectId(ps[0].projectId);
        }
      })
      .catch((e) => setError(e.message));

    apiClient
      .getComponents()
      .then((list) => {
        const all: ComponentSummaryDef[] = [];
        const groups: Record<string, DAGComponentDef[]> = {};
        list.forEach((group) => {
          const groupName = group.name || 'Components';
          const defs: DAGComponentDef[] = [];
          (group.comps || []).forEach((c) => {
            all.push(c);
            defs.push({
              domain: c.domain || 'unknown',
              name: c.name || 'unknown',
              version: c.version,
              desc: c.desc,
              icon: c.domain?.startsWith('ml') ? '🤖' : '⚙️',
            });
          });
          if (defs.length) groups[groupName] = defs;
        });
        setComponents(all);
        if (Object.keys(groups).length > 0) {
          setComponentGroups(groups);
        }
      })
      .catch(() => setComponents([]));

    apiClient
      .listComponentI18n()
      .then(setI18nMap)
      .catch(() => setI18nMap({}));
  }, []);

  const loadGraphs = useCallback(() => {
    if (!selectedProjectId) return;
    setLoading(true);
    apiClient
      .getGraphs(selectedProjectId)
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
  }, [selectedProjectId, selectedGraph]);

  useEffect(() => {
    loadGraphs();
  }, [loadGraphs]);

  useEffect(() => {
    if (!selectedProjectId || !selectedGraph?.graphId) {
      setGraphDetail(null);
      return;
    }
    setLoading(true);
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    abortControllerRef.current = new AbortController();
    apiClient
      .getGraphDetail(selectedProjectId, selectedGraph.graphId)
      .then(setGraphDetail)
      .catch((e) => {
        if (e.name !== 'AbortError') {
          setError(e.message);
        }
      })
      .finally(() => setLoading(false));
    return () => {
      abortControllerRef.current?.abort();
    };
  }, [selectedProjectId, selectedGraph?.graphId]);

  // Poll node status when graph has running nodes
  useEffect(() => {
    if (pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }
    if (!selectedProjectId || !selectedGraph?.graphId) return;
    const hasRunning = graphDetail?.nodes?.some((n) => n.status === 'RUNNING');
    if (!hasRunning) return;

    pollingRef.current = setInterval(async () => {
      try {
        const status = await apiClient.getGraphNodeStatus(selectedProjectId, selectedGraph.graphId!);
        setGraphDetail((prev) => {
          if (!prev) return prev;
          const statusMap = new Map((status.nodes || []).map((s) => [s.graphNodeId, s]));
          return {
            ...prev,
            nodes: prev.nodes.map((n) => {
              const s = statusMap.get(n.graphNodeId);
              if (!s) return n;
              return { ...n, status: s.status, progress: s.progress };
            }),
          };
        });
        if (status.finished && pollingRef.current) {
          clearInterval(pollingRef.current);
          pollingRef.current = null;
        }
      } catch {
        // ignore polling errors
      }
    }, 3000);

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [selectedProjectId, selectedGraph?.graphId, graphDetail?.nodes]);

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

  const handleSaveGraph = async (nodes: DAGNode[], edges: DAGEdge[]) => {
    if (!selectedProjectId || !selectedGraph?.graphId) return;
    try {
      await apiClient.updateGraph(
        selectedProjectId,
        selectedGraph.graphId,
        nodes.map(mapDAGNodeToGraphNode),
        edges.map(mapDAGEdgeToGraphEdge)
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    }
  };

  const handleRunGraph = async (nodes: DAGNode[]) => {
    if (!selectedProjectId || !selectedGraph?.graphId) return;
    try {
      const jobId = await apiClient.startGraph(
        selectedProjectId,
        selectedGraph.graphId,
        nodes.map((n) => n.id)
      );
      alert(t('dag.started', { jobId }));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    }
  };

  const handleNodeConfigChange = async (node: DAGNode) => {
    if (!selectedProjectId || !selectedGraph?.graphId) return;
    try {
      await apiClient.updateGraphNode(selectedProjectId, selectedGraph.graphId, mapDAGNodeToGraphNode(node));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    }
  };

  const handleNodeLogs = async (node: DAGNode) => {
    if (!selectedProjectId || !selectedGraph?.graphId) {
      return [];
    }
    return apiClient.getGraphNodeLogs(selectedProjectId, selectedGraph.graphId, node.id);
  };

  const handleNodeOutput = async (node: DAGNode) => {
    if (!selectedProjectId || !selectedGraph?.graphId) {
      return null;
    }
    const outputId = node.outputs?.[0] || `${node.id}-output-0`;
    try {
      return await apiClient.getGraphNodeOutput(selectedProjectId, selectedGraph.graphId, node.id, outputId);
    } catch {
      return null;
    }
  };

  const handleAddNode = async (component: DAGComponentDef): Promise<DAGNode> => {
    const codeName = `${component.domain}/${component.name}`;
    const label = i18nMap[component.name] || i18nMap[codeName] || component.name;
    let outputs: string[] = [];
    try {
      const defs = await apiClient.batchGetComponent([
        { app: 'secretflow', domain: component.domain, name: component.name },
      ]);
      const def = defs[codeName] || Object.values(defs)[0];
      if (def?.outputs) {
        outputs = def.outputs.map((_: any, idx: number) => `${codeName}-output-${idx}`);
      }
    } catch {
      // fallback to no outputs
    }
    return {
      id: `${component.domain}-${component.name}-${Date.now().toString(36)}`,
      name: label,
      category: component.domain,
      icon: component.icon || '⚙️',
      status: 'Ready',
      x: 200 + Math.random() * 120,
      y: 120 + Math.random() * 80,
      codeName,
      nodeDef: {
        domain: component.domain,
        name: component.name,
        version: component.version,
      },
      inputs: [],
      outputs,
    };
  };

  const handleConnect = (sourceId: string, targetId: string) => {
    const sourceNode = graphDetail?.nodes?.find((n) => n.graphNodeId === sourceId);
    const outputIndex = sourceNode?.outputs?.length || 0;
    const sourceAnchor = `${sourceId}-output-${outputIndex}`;
    const targetAnchor = `${targetId}-input-${graphDetail?.edges?.filter((e) => e.target === targetId).length || 0}`;
    return {
      id: `edge-${Date.now().toString(36)}`,
      source: sourceId,
      target: targetId,
      sourceAnchor,
      targetAnchor,
    };
  };

  const handleNodeMove = async (node: DAGNode) => {
    await handleNodeConfigChange(node);
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
            onChange={(e) => {
              setSelectedProjectId(e.target.value);
              setSelectedGraph(null);
              setGraphDetail(null);
            }}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {projects.map((p) => (
              <option key={p.projectId} value={p.projectId}>
                {p.projectName}
              </option>
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
              <option key={g.graphId} value={g.graphId || ''}>
                {g.name}
              </option>
            ))}
          </select>

          <AccessGuard access={{ types: [Platform.CENTER] }}>
            <Button variant="primary" size="sm" onClick={() => setIsCreateModalOpen(true)}>
              {t('dag.create')}
            </Button>
            <Button variant="danger" size="sm" onClick={() => selectedGraph && handleDeleteGraph(selectedGraph)}>
              {t('common.delete')}
            </Button>
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
          <AccessGuard access={{ types: [Platform.CENTER] }} fallback={<DAGNextWorkspace readOnly title={selectedGraph.name} initialNodes={nodes} initialEdges={edges} labels={dagLabels} />}>
            <DAGNextWorkspace
              title={selectedGraph.name}
              initialNodes={nodes}
              initialEdges={edges}
              componentGroups={componentGroups}
              i18nMap={i18nMap}
              onSaveGraph={handleSaveGraph}
              onRunGraph={handleRunGraph}
              onNodeMove={handleNodeMove}
              onNodeConfigChange={handleNodeConfigChange}
              onNodeLogs={handleNodeLogs}
              onNodeOutput={handleNodeOutput}
              onAddNode={handleAddNode}
              onConnect={handleConnect}
              loading={loading}
              labels={dagLabels}
            />
          </AccessGuard>
        ) : (
          <Card className="h-full flex items-center justify-center">
            <div className="text-center text-xs text-gray-400">
              <div className="mb-2">{t('dag.noGraph')}</div>
              <AccessGuard access={{ types: [Platform.CENTER] }}>
                <Button variant="primary" size="sm" onClick={() => setIsCreateModalOpen(true)}>
                  {t('dag.create')}
                </Button>
              </AccessGuard>
            </div>
          </Card>
        )}
      </div>

      {components.length > 0 && (
        <Card title={t('dag.components')}>
          <div className="flex flex-wrap gap-2 text-xs">
            {components.slice(0, 20).map((c, idx) => (
              <Badge key={idx} status="default">
                {c.name || c.domain}
              </Badge>
            ))}
          </div>
        </Card>
      )}

      <Modal
        isOpen={isCreateModalOpen}
        onClose={() => {
          setIsCreateModalOpen(false);
          setNewGraphName('');
        }}
        title={t('dag.createTitle')}
        footer={
          <>
            <Button
              variant="ghost"
              onClick={() => {
                setIsCreateModalOpen(false);
                setNewGraphName('');
              }}
            >
              {t('common.cancel')}
            </Button>
            <Button variant="primary" onClick={handleCreateGraph}>
              {t('common.create')}
            </Button>
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
