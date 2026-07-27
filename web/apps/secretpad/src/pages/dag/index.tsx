import React, { useEffect, useState, useMemo } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, Button, Badge, Modal, ConfirmDialog, toast } from '@secretpad/design-system';
import type {
  GraphMetaVO,
  GraphDetailVO,
  GraphNodeInfo,
  GraphEdge,
  ComponentSummaryDef,
} from '@secretpad/api-client';
import { apiClient } from '@secretpad/api-client';
import type { DAGNode, DAGEdge, DAGComponentDef } from '@secretpad/dag-next';
import { DAGNextWorkspace } from '@secretpad/dag-next';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

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
  const queryClient = useQueryClient();

  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [selectedGraphId, setSelectedGraphId] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [newGraphName, setNewGraphName] = useState('');
  const [isRenameModalOpen, setIsRenameModalOpen] = useState(false);
  const [renameValue, setRenameValue] = useState('');
  const [deleteGraphTarget, setDeleteGraphTarget] = useState<GraphMetaVO | null>(null);
  // PSI template wizard state
  const [isTemplateModalOpen, setIsTemplateModalOpen] = useState(false);
  const [templateGraphName, setTemplateGraphName] = useState('');
  const [templateReceiverNodeId, setTemplateReceiverNodeId] = useState('');
  const [templateReceiverTableId, setTemplateReceiverTableId] = useState('');
  const [templateReceiverKey, setTemplateReceiverKey] = useState('');
  const [templateSenderNodeId, setTemplateSenderNodeId] = useState('');
  const [templateSenderTableId, setTemplateSenderTableId] = useState('');
  const [templateSenderKey, setTemplateSenderKey] = useState('');

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
      emptyCanvas: t('dag.emptyCanvas'),
    }),
    [t]
  );

  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: () => apiClient.getProjects(),
  });
  const projects = projectsQuery.data ?? [];
  const selectedProject = useMemo(() => projects.find((p) => p.projectId === selectedProjectId), [projects, selectedProjectId]);

  // Default the selected project to the first one once projects load.
  useEffect(() => {
    if (!selectedProjectId && projects.length > 0) {
      setSelectedProjectId(projects[0].projectId);
    }
  }, [projects, selectedProjectId]);

  const componentsQuery = useQuery({
    queryKey: ['components'],
    queryFn: async () => {
      const list = await apiClient.getComponents();
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
      return { all, groups };
    },
  });
  const components = componentsQuery.data?.all ?? [];
  const componentGroups = componentsQuery.data?.groups ?? {};

  const i18nQuery = useQuery({
    queryKey: ['component-i18n'],
    queryFn: () => apiClient.listComponentI18n(),
  });
  const i18nMap = i18nQuery.data ?? {};

  const graphsQuery = useQuery({
    queryKey: ['graphs', selectedProjectId],
    queryFn: () => apiClient.getGraphs(selectedProjectId),
    enabled: !!selectedProjectId,
  });
  const graphs = graphsQuery.data ?? [];
  const selectedGraph: GraphMetaVO | null =
    graphs.find((g) => g.graphId === selectedGraphId) ?? graphs[0] ?? null;

  // Poll the graph detail while any node is running (replaces setInterval).
  const graphDetailQuery = useQuery({
    queryKey: ['graph-detail', selectedProjectId, selectedGraph?.graphId],
    queryFn: () => apiClient.getGraphDetail(selectedProjectId, selectedGraph!.graphId!),
    enabled: !!selectedProjectId && !!selectedGraph?.graphId,
    refetchInterval: (query) => {
      const hasRunning = query.state.data?.nodes?.some((n) => n.status === 'RUNNING');
      return hasRunning ? 3000 : false;
    },
  });
  const graphDetail = graphDetailQuery.data ?? null;

  const receiverTablesQuery = useQuery({
    queryKey: ['node-datatables', templateReceiverNodeId],
    queryFn: () => apiClient.getDataTables(templateReceiverNodeId),
    enabled: !!templateReceiverNodeId,
  });
  const senderTablesQuery = useQuery({
    queryKey: ['node-datatables', templateSenderNodeId],
    queryFn: () => apiClient.getDataTables(templateSenderNodeId),
    enabled: !!templateSenderNodeId,
  });
  const receiverTables = receiverTablesQuery.data ?? [];
  const senderTables = senderTablesQuery.data ?? [];
  const receiverSelectedTable = receiverTables.find((t) => t.tableId === templateReceiverTableId);
  const senderSelectedTable = senderTables.find((t) => t.tableId === templateSenderTableId);

  const invalidateGraphs = () =>
    queryClient.invalidateQueries({ queryKey: ['graphs', selectedProjectId] });

  const createGraphMutation = useMutation({
    mutationFn: () => apiClient.createGraph({ projectId: selectedProjectId, name: newGraphName }),
    onSuccess: () => {
      setIsCreateModalOpen(false);
      setNewGraphName('');
      invalidateGraphs();
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const deleteGraphMutation = useMutation({
    mutationFn: (graph: GraphMetaVO) => apiClient.deleteGraph(selectedProjectId, graph.graphId!),
    onSuccess: (_data, graph) => {
      if (selectedGraph?.graphId === graph.graphId) {
        setSelectedGraphId('');
      }
      setDeleteGraphTarget(null);
      invalidateGraphs();
      queryClient.invalidateQueries({ queryKey: ['graph-detail', selectedProjectId, graph.graphId] });
    },
    onError: (e) => {
      setDeleteGraphTarget(null);
      setError(e instanceof Error ? e.message : String(e));
    },
  });

  const stopGraphMutation = useMutation({
    mutationFn: () => apiClient.stopGraph(selectedProjectId, selectedGraph!.graphId!),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ['graph-detail', selectedProjectId, selectedGraph?.graphId],
      });
      toast.success(t('dag.stopSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const renameGraphMutation = useMutation({
    mutationFn: () => apiClient.renameGraph(selectedProjectId, selectedGraph!.graphId!, renameValue),
    onSuccess: () => {
      setIsRenameModalOpen(false);
      invalidateGraphs();
      toast.success(t('dag.renameSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  /**
   * Create a pre-wired PSI template graph:
   * 1) create an empty graph to obtain graphId,
   * 2) update it with two read_data/datatable nodes and one data_prep/psi node,
   * 3) wire the two sample tables into the PSI inputs.
   */
  const createTemplateGraphMutation = useMutation({
    mutationFn: async () => {
      if (!selectedProjectId || !selectedProject) {
        throw new Error(t('projects.selectProject'));
      }
      if (
        !templateReceiverNodeId ||
        !templateReceiverTableId ||
        !templateReceiverKey ||
        !templateSenderNodeId ||
        !templateSenderTableId ||
        !templateSenderKey
      ) {
        throw new Error(t('dag.emptyCanvas'));
      }
      const name = templateGraphName.trim() || t('dag.templatePSI');
      const graphId = await apiClient.createGraph({ projectId: selectedProjectId, name });
      const makeNodeId = (suffix: string) => `${graphId}-${suffix}`;
      const readReceiverId = makeNodeId('read-receiver');
      const readSenderId = makeNodeId('read-sender');
      const psiId = makeNodeId('psi');
      const receiverOut = `${readReceiverId}-output-0`;
      const senderOut = `${readSenderId}-output-0`;

      const makeReadNode = (id: string, tableId: string): GraphNodeInfo => ({
        graphNodeId: id,
        codeName: 'read_data/datatable',
        label: String(t('dag.readDataLabel')),
        x: id === readReceiverId ? -390 : -150,
        y: -210,
        inputs: [],
        outputs: [`${id}-output-0`],
        nodeDef: {
          domain: 'read_data',
          name: 'datatable',
          version: '0.0.1',
          attrPaths: ['datatable_selected'],
          attrs: [{ s: tableId, is_na: false }],
        },
      });

      const psiNode: GraphNodeInfo = {
        graphNodeId: psiId,
        codeName: 'data_prep/psi',
        label: String(t('dag.psiNodeLabel')),
        x: -260,
        y: -100,
        inputs: [receiverOut, senderOut],
        outputs: [`${psiId}-output-0`, `${psiId}-output-1`],
        nodeDef: {
          domain: 'data_prep',
          name: 'psi',
          version: '1.0.0',
          attrPaths: [
            'input/input_ds1/keys',
            'input/input_ds2/keys',
            'protocol',
            'sort_result',
            'receiver_parties',
            'allow_empty_result',
            'join_type',
            'input_ds1_keys_duplicated',
            'input_ds2_keys_duplicated',
          ],
          attrs: [
            { ss: [templateReceiverKey], is_na: false },
            { ss: [templateSenderKey], is_na: false },
            { s: 'PROTOCOL_RR22', is_na: false },
            { b: true, is_na: false },
            { ss: [templateReceiverNodeId, templateSenderNodeId], is_na: false },
            { is_na: true },
            { s: 'inner_join', is_na: false },
            { b: true, is_na: false },
            { b: true, is_na: false },
          ],
        },
      };

      const edges: GraphEdge[] = [
        {
          edgeId: `${receiverOut}__${psiId}-input-0`,
          source: readReceiverId,
          target: psiId,
          sourceAnchor: receiverOut,
          targetAnchor: `${psiId}-input-0`,
        },
        {
          edgeId: `${senderOut}__${psiId}-input-1`,
          source: readSenderId,
          target: psiId,
          sourceAnchor: senderOut,
          targetAnchor: `${psiId}-input-1`,
        },
      ];

      await apiClient.updateGraph(
        selectedProjectId,
        graphId,
        [makeReadNode(readReceiverId, templateReceiverTableId), makeReadNode(readSenderId, templateSenderTableId), psiNode],
        edges
      );
      return graphId;
    },
    onSuccess: (graphId) => {
      setIsTemplateModalOpen(false);
      setTemplateGraphName('');
      setTemplateReceiverNodeId('');
      setTemplateReceiverTableId('');
      setTemplateReceiverKey('');
      setTemplateSenderNodeId('');
      setTemplateSenderTableId('');
      setTemplateSenderKey('');
      invalidateGraphs();
      queryClient.invalidateQueries({ queryKey: ['graph-detail', selectedProjectId, graphId] });
      setSelectedGraphId(graphId);
      toast.success(t('dag.templateCreated'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const handleCreateGraph = (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProjectId || !newGraphName.trim()) return;
    setError(null);
    createGraphMutation.mutate();
  };

  const handleDeleteGraph = (graph: GraphMetaVO) => {
    if (!selectedProjectId || !graph.graphId) return;
    setDeleteGraphTarget(graph);
  };

  const openRename = () => {
    if (!selectedGraph) return;
    setRenameValue(selectedGraph.name || '');
    setIsRenameModalOpen(true);
  };

  const openTemplate = () => {
    if (!selectedProject || selectedProject.nodes.length < 2) {
      toast.error(t('projects.noProjects'));
      return;
    }
    const [first, second] = selectedProject.nodes;
    setTemplateReceiverNodeId(first.nodeId);
    setTemplateSenderNodeId(second.nodeId);
    setTemplateGraphName(`${t('dag.templatePSI')} ${new Date().toLocaleString()}`);
    setTemplateReceiverTableId('');
    setTemplateReceiverKey('');
    setTemplateSenderTableId('');
    setTemplateSenderKey('');
    setIsTemplateModalOpen(true);
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
      // Kick off status polling by refreshing the graph detail.
      queryClient.invalidateQueries({
        queryKey: ['graph-detail', selectedProjectId, selectedGraph.graphId],
      });
      toast.success(t('dag.started', { jobId }));
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

  const handleGetComponentDef = async (node: DAGNode) => {
    if (!node.codeName) return null;
    const { domain, name } = normalizeCodeName(node.codeName);
    try {
      const defs = await apiClient.batchGetComponent([{ domain, name }]);
      const def = (defs[node.codeName] || Object.values(defs)[0]) ?? null;
      if (!def) return null;
      return {
        desc: def.desc,
        inputs: (def.inputs || []).map((item) => ({ name: (item as { name?: string }).name })),
        outputs: (def.outputs || []).map((item) => ({ name: (item as { name?: string }).name })),
        attrs: def.attrs as Array<Record<string, unknown>> | undefined,
      };
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

  const hasRunningNodes = (graphDetail?.nodes || []).some((n) => n.status === 'RUNNING');

  const loading = graphsQuery.isLoading || graphDetailQuery.isLoading;
  const queryError =
    graphsQuery.error?.message ||
    graphDetailQuery.error?.message ||
    projectsQuery.error?.message ||
    componentsQuery.error?.message ||
    null;

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
              setSelectedGraphId('');
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
            onChange={(e) => setSelectedGraphId(e.target.value)}
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
            {selectedGraph && (
              <Button variant="outline" size="sm" onClick={openRename}>
                {t('dag.rename')}
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={openTemplate}
              disabled={!selectedProject || selectedProject.nodes.length < 2}
            >
              {t('dag.templatePSI')}
            </Button>
            {hasRunningNodes && (
              <Button variant="outline" size="sm" loading={stopGraphMutation.isPending} onClick={() => stopGraphMutation.mutate()}>
                ⏹ {t('dag.stop')}
              </Button>
            )}
            <Button variant="danger" size="sm" onClick={() => selectedGraph && handleDeleteGraph(selectedGraph)}>
              {t('common.delete')}
            </Button>
          </AccessGuard>
        </div>
      </div>

      {(error || queryError) && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error || queryError || '' })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <div className="flex-1 min-h-0">
        {selectedGraph ? (
          <AccessGuard access={{ types: [Platform.CENTER] }} fallback={<DAGNextWorkspace readOnly title={selectedGraph.name} initialNodes={nodes} initialEdges={edges} labels={dagLabels} onGetComponentDef={handleGetComponentDef} />}>
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
              onGetComponentDef={handleGetComponentDef}
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
            <Button variant="primary" onClick={handleCreateGraph} loading={createGraphMutation.isPending}>
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

      {/* Rename Graph Modal */}
      <Modal
        isOpen={isRenameModalOpen}
        onClose={() => setIsRenameModalOpen(false)}
        title={t('dag.renameTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsRenameModalOpen(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => renameGraphMutation.mutate()} loading={renameGraphMutation.isPending} disabled={!renameValue.trim()}>{t('common.save')}</Button>
          </>
        }
      >
        <div className="text-xs">
          <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('dag.nameLabel')}</label>
          <input
            type="text"
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            required
          />
        </div>
      </Modal>

      {/* Delete Graph Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!deleteGraphTarget}
        title={t('common.delete')}
        message={t('dag.deleteConfirm')}
        danger
        loading={deleteGraphMutation.isPending}
        confirmText={t('common.delete')}
        cancelText={t('common.cancel')}
        onConfirm={() => deleteGraphTarget && deleteGraphMutation.mutate(deleteGraphTarget)}
        onCancel={() => setDeleteGraphTarget(null)}
      />

      {/* PSI Template Wizard Modal */}
      <Modal
        isOpen={isTemplateModalOpen}
        onClose={() => setIsTemplateModalOpen(false)}
        title={t('dag.templateTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsTemplateModalOpen(false)}>
              {t('common.cancel')}
            </Button>
            <Button
              variant="primary"
              onClick={() => createTemplateGraphMutation.mutate()}
              loading={createTemplateGraphMutation.isPending}
              disabled={
                !templateGraphName.trim() ||
                !templateReceiverNodeId ||
                !templateReceiverTableId ||
                !templateReceiverKey ||
                !templateSenderNodeId ||
                !templateSenderTableId ||
                !templateSenderKey
              }
            >
              {t('dag.createFromTemplate')}
            </Button>
          </>
        }
      >
        <div className="space-y-4 text-xs">
          <p className="text-gray-500">{t('dag.templateHint')}</p>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">
              {t('dag.nameLabel')}
            </label>
            <input
              type="text"
              value={templateGraphName}
              onChange={(e) => setTemplateGraphName(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Receiver side */}
            <div className="space-y-2">
              <label className="block font-semibold text-gray-700 dark:text-gray-300">
                {t('dag.templateReceiverTable')}
              </label>
              <select
                value={templateReceiverNodeId}
                onChange={(e) => setTemplateReceiverNodeId(e.target.value)}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectNode')}</option>
                {selectedProject?.nodes.map((n) => (
                  <option key={n.nodeId} value={n.nodeId}>
                    {n.nodeName || n.nodeId}
                  </option>
                ))}
              </select>
              <select
                value={templateReceiverTableId}
                onChange={(e) => setTemplateReceiverTableId(e.target.value)}
                disabled={receiverTables.length === 0}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectTable')}</option>
                {receiverTables.map((t) => (
                  <option key={t.tableId} value={t.tableId}>
                    {t.tableName || t.tableId}
                  </option>
                ))}
              </select>
              <select
                value={templateReceiverKey}
                onChange={(e) => setTemplateReceiverKey(e.target.value)}
                disabled={!receiverSelectedTable?.columns?.length}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectKey')}</option>
                {(receiverSelectedTable?.columns || []).map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name} ({c.type})
                  </option>
                ))}
              </select>
            </div>
            {/* Sender side */}
            <div className="space-y-2">
              <label className="block font-semibold text-gray-700 dark:text-gray-300">
                {t('dag.templateSenderTable')}
              </label>
              <select
                value={templateSenderNodeId}
                onChange={(e) => setTemplateSenderNodeId(e.target.value)}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectNode')}</option>
                {selectedProject?.nodes.map((n) => (
                  <option key={n.nodeId} value={n.nodeId}>
                    {n.nodeName || n.nodeId}
                  </option>
                ))}
              </select>
              <select
                value={templateSenderTableId}
                onChange={(e) => setTemplateSenderTableId(e.target.value)}
                disabled={senderTables.length === 0}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectTable')}</option>
                {senderTables.map((t) => (
                  <option key={t.tableId} value={t.tableId}>
                    {t.tableName || t.tableId}
                  </option>
                ))}
              </select>
              <select
                value={templateSenderKey}
                onChange={(e) => setTemplateSenderKey(e.target.value)}
                disabled={!senderSelectedTable?.columns?.length}
                className="w-full p-2 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              >
                <option value="">{t('dag.selectKey')}</option>
                {(senderSelectedTable?.columns || []).map((c) => (
                  <option key={c.name} value={c.name}>
                    {c.name} ({c.type})
                  </option>
                ))}
              </select>
            </div>
          </div>
        </div>
      </Modal>
    </div>
  );
};
