import React, { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, Badge, Button, Modal, ConfirmDialog, toast } from '@secretpad/design-system';
import { apiClient, ModelPackVO, GraphNodeDetail } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

const PACKING_STATS = ['PACKING', 'PUBLISHING', 'EXPORTING'];

export const ModelsPage: React.FC = () => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  const [detailModel, setDetailModel] = useState<ModelPackVO | null>(null);
  const [detailInfo, setDetailInfo] = useState<any>(null);

  // Deploy modal
  const [deployOpen, setDeployOpen] = useState(false);
  const [deployModelId, setDeployModelId] = useState('');
  const [deployProjectId, setDeployProjectId] = useState('');

  // Pack modal
  const [packOpen, setPackOpen] = useState(false);
  const [packGraphId, setPackGraphId] = useState('');
  const [packTrainNodeId, setPackTrainNodeId] = useState('');
  const [packModelName, setPackModelName] = useState('');

  // Discard confirm
  const [discardTarget, setDiscardTarget] = useState<ModelPackVO | null>(null);

  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: () => apiClient.getProjects(),
  });
  const projects = projectsQuery.data ?? [];

  // Default the selected project to the first one once projects load.
  useEffect(() => {
    if (!selectedProjectId && projects.length > 0) {
      setSelectedProjectId(projects[0].projectId);
    }
  }, [projects, selectedProjectId]);

  const modelsQuery = useQuery({
    queryKey: ['models', selectedProjectId],
    queryFn: () => apiClient.getModels(selectedProjectId),
    enabled: !!selectedProjectId,
    // Poll while any model is still being packed/published.
    refetchInterval: (query) => {
      const hasPacking = query.state.data?.some((m) => PACKING_STATS.includes((m.modelStats || '').toUpperCase()));
      return hasPacking ? 4000 : false;
    },
  });
  const models = modelsQuery.data ?? [];

  const invalidateModels = () =>
    queryClient.invalidateQueries({ queryKey: ['models', selectedProjectId] });

  // Graphs for the pack form
  const graphsQuery = useQuery({
    queryKey: ['graphs', selectedProjectId],
    queryFn: () => apiClient.getGraphs(selectedProjectId),
    enabled: packOpen && !!selectedProjectId,
  });
  const graphs = graphsQuery.data ?? [];

  // Graph detail (train nodes) for the pack form
  const graphDetailQuery = useQuery({
    queryKey: ['graph-detail', selectedProjectId, packGraphId],
    queryFn: () => apiClient.getGraphDetail(selectedProjectId, packGraphId),
    enabled: packOpen && !!packGraphId,
  });
  const trainNodes: GraphNodeDetail[] = (graphDetailQuery.data?.nodes || []).filter((n) =>
    (n.codeName || '').includes('train')
  );

  const deployMutation = useMutation({
    mutationFn: () => apiClient.createModelServing({ modelId: deployModelId, projectId: deployProjectId }),
    onSuccess: () => {
      setDeployOpen(false);
      setDeployModelId('');
      invalidateModels();
      toast.success(t('models.deploySuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const packMutation = useMutation({
    mutationFn: () => {
      const trainNode = trainNodes.find((n) => n.graphNodeId === packTrainNodeId);
      return apiClient.packModel({
        projectId: selectedProjectId,
        graphId: packGraphId,
        trainId: packTrainNodeId,
        modelName: packModelName,
        graphNodeOutPutId: trainNode?.outputs?.[0] || `${packTrainNodeId}-output-0`,
        modelPartyConfig: [],
        modelComponent: [],
      });
    },
    onSuccess: () => {
      setPackOpen(false);
      setPackGraphId('');
      setPackTrainNodeId('');
      setPackModelName('');
      invalidateModels();
      toast.success(t('models.packSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const discardMutation = useMutation({
    mutationFn: (modelId: string) => apiClient.discardModel(modelId),
    onSuccess: () => {
      setDiscardTarget(null);
      invalidateModels();
      toast.success(t('models.discardSuccess'));
    },
    onError: (e) => {
      setDiscardTarget(null);
      setError(e instanceof Error ? e.message : String(e));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (model: ModelPackVO) => apiClient.deleteModel(model.modelId!, model.ownerId!),
    onSuccess: invalidateModels,
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const handleViewDetail = async (model: ModelPackVO) => {
    setDetailModel(model);
    setDetailInfo(null);
    if (!model.modelId || !selectedProjectId) return;
    try {
      const info = await apiClient.getModelInfo(model.modelId, selectedProjectId);
      setDetailInfo(info);
    } catch (e) {
      setDetailInfo({ error: e instanceof Error ? e.message : String(e) });
    }
  };

  const openDeploy = () => {
    setDeployModelId(models[0]?.modelId || '');
    setDeployProjectId(selectedProjectId);
    setDeployOpen(true);
  };

  const openPack = () => {
    setPackGraphId('');
    setPackTrainNodeId('');
    setPackModelName('');
    setPackOpen(true);
  };

  const loading = modelsQuery.isLoading;
  const queryError = modelsQuery.error?.message || projectsQuery.error?.message || null;

  return (
    <div className="space-y-6">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('models.title')}</h2>
          <p className="text-xs text-gray-500">{t('models.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={selectedProjectId}
            onChange={(e) => setSelectedProjectId(e.target.value)}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            {projects.map((p) => (
              <option key={p.projectId} value={p.projectId}>{p.projectName}</option>
            ))}
          </select>
          <AccessGuard access={{ types: [Platform.CENTER] }}>
            <Button variant="outline" onClick={openPack}>{t('models.pack')}</Button>
            <Button variant="primary" onClick={openDeploy}>{t('models.deploy')}</Button>
          </AccessGuard>
        </div>
      </div>

      {(error || queryError) && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error || queryError || '' })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {models.map((model) => (
          <Card key={model.modelId}>
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-bold text-sm text-gray-900 dark:text-gray-100">{model.modelName}</h3>
              <div className="flex items-center gap-2">
                <Badge status={model.servingId ? 'success' : PACKING_STATS.includes((model.modelStats || '').toUpperCase()) ? 'processing' : 'default'}>
                  {model.servingId ? t('models.statusServing') : model.modelStats || t('models.statusPack')}
                </Badge>
                <Button size="sm" variant="ghost" onClick={() => handleViewDetail(model)}>{t('models.detail')}</Button>
                <AccessGuard access={{ types: [Platform.CENTER] }}>
                  <Button size="sm" variant="ghost" onClick={() => setDiscardTarget(model)}>{t('models.discard')}</Button>
                  <Button size="sm" variant="danger" onClick={() => model.modelId && model.ownerId && deleteMutation.mutate(model)}>{t('common.delete')}</Button>
                </AccessGuard>
              </div>
            </div>
            <div className="text-xs text-gray-500 space-y-1 font-mono">
              <div>ID: {model.modelId}</div>
              <div>{t('models.stats')}: {model.modelStats || '-'}</div>
              <div>{t('models.owner')}: {model.ownerId}</div>
              <div>{t('models.createTime')}: {model.gmtCreate}</div>
            </div>
          </Card>
        ))}
      </div>

      {models.length === 0 && !loading && !queryError && (
        <div className="text-center text-xs text-gray-400 py-10">{t('models.noData')}</div>
      )}

      {/* Deploy Serving Modal */}
      <Modal
        isOpen={deployOpen}
        onClose={() => setDeployOpen(false)}
        title={t('models.deployTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeployOpen(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => deployMutation.mutate()} loading={deployMutation.isPending} disabled={!deployModelId || !deployProjectId}>{t('models.deploy')}</Button>
          </>
        }
      >
        <div className="text-xs space-y-4">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('models.name')}</label>
            <select
              value={deployModelId}
              onChange={(e) => setDeployModelId(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              {models.map((m) => (
                <option key={m.modelId} value={m.modelId || ''}>{m.modelName} ({m.modelId})</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('models.selectProject')}</label>
            <select
              value={deployProjectId}
              onChange={(e) => setDeployProjectId(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              {projects.map((p) => (
                <option key={p.projectId} value={p.projectId}>{p.projectName}</option>
              ))}
            </select>
          </div>
        </div>
      </Modal>

      {/* Pack Model Modal */}
      <Modal
        isOpen={packOpen}
        onClose={() => setPackOpen(false)}
        title={t('models.pack')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setPackOpen(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => packMutation.mutate()} loading={packMutation.isPending} disabled={!packGraphId || !packTrainNodeId || !packModelName.trim()}>{t('models.pack')}</Button>
          </>
        }
      >
        <div className="text-xs space-y-4">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('periodicTasks.selectGraph')}</label>
            <select
              value={packGraphId}
              onChange={(e) => { setPackGraphId(e.target.value); setPackTrainNodeId(''); }}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              <option value="">-</option>
              {graphs.map((g) => (
                <option key={g.graphId} value={g.graphId || ''}>{g.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('models.sampleTables')}</label>
            <select
              value={packTrainNodeId}
              onChange={(e) => setPackTrainNodeId(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              <option value="">-</option>
              {trainNodes.map((n) => (
                <option key={n.graphNodeId} value={n.graphNodeId || ''}>{n.label || n.codeName}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('models.name')}</label>
            <input
              type="text"
              value={packModelName}
              onChange={(e) => setPackModelName(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>
        </div>
      </Modal>

      {/* Model Detail Modal */}
      <Modal
        isOpen={!!detailModel}
        onClose={() => { setDetailModel(null); setDetailInfo(null); }}
        title={detailModel?.modelName || t('models.detail')}
        footer={
          <Button variant="primary" onClick={() => { setDetailModel(null); setDetailInfo(null); }}>{t('common.close')}</Button>
        }
      >
        <div className="text-xs space-y-2">
          {detailInfo?.error ? (
            <div className="text-red-500">{detailInfo.error}</div>
          ) : !detailInfo ? (
            <div className="text-gray-400">{t('common.loading')}</div>
          ) : (
            <>
              <div><span className="font-semibold">{t('models.stats')}:</span> {detailInfo?.modelStats || '-'}</div>
              <div><span className="font-semibold">{t('models.servingCount')}:</span> {detailInfo?.servingDetails?.length || 0}</div>
              {detailInfo?.servingDetails?.map((s: any, idx: number) => (
                <div key={idx} className="p-2 rounded bg-gray-50 dark:bg-gray-800 font-mono">
                  {s.nodeName || s.nodeId}: {s.endpoints || '-'}
                </div>
              ))}
            </>
          )}
        </div>
      </Modal>

      {/* Discard Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!discardTarget}
        title={t('models.discard')}
        message={t('models.discardConfirm')}
        danger
        loading={discardMutation.isPending}
        confirmText={t('models.discard')}
        cancelText={t('common.cancel')}
        onConfirm={() => discardTarget?.modelId && discardMutation.mutate(discardTarget.modelId)}
        onCancel={() => setDiscardTarget(null)}
      />
    </div>
  );
};
