import React, { useEffect, useState } from 'react';
import { Card, Badge, Button, Modal } from '@secretpad/design-system';
import { apiClient, ModelPackVO, Project } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

export const ModelsPage: React.FC = () => {
  const { t } = useTranslation();
  const [models, setModels] = useState<ModelPackVO[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detailModel, setDetailModel] = useState<ModelPackVO | null>(null);
  const [detailInfo, setDetailInfo] = useState<any>(null);

  useEffect(() => {
    apiClient.getProjects()
      .then((ps) => {
        setProjects(ps);
        if (ps.length > 0) {
          setSelectedProjectId(ps[0].projectId);
        }
      })
      .catch((e) => setError(e.message));
  }, []);

  const loadModels = () => {
    if (!selectedProjectId) return;
    setLoading(true);
    setError(null);
    apiClient.getModels(selectedProjectId)
      .then(setModels)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadModels();
  }, [selectedProjectId]);

  const handleDelete = async (model: ModelPackVO) => {
    if (!model.modelId || !model.ownerId) return;
    if (!window.confirm(t('models.deleteConfirm'))) return;
    try {
      await apiClient.deleteModel(model.modelId, model.ownerId);
      loadModels();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const handleViewDetail = async (model: ModelPackVO) => {
    setDetailModel(model);
    if (!model.modelId || !selectedProjectId) return;
    try {
      const info = await apiClient.getModelInfo(model.modelId, selectedProjectId);
      setDetailInfo(info);
    } catch (e) {
      setDetailInfo({ error: e instanceof Error ? e.message : String(e) });
    }
  };

  const selectedProject = projects.find((p) => p.projectId === selectedProjectId);

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
            <Button variant="primary" onClick={() => selectedProject && alert(t('models.deployHint'))}>
              {t('models.deploy')}
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

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {models.map((model) => (
          <Card key={model.modelId}>
            <div className="flex items-center justify-between mb-2">
              <h3 className="font-bold text-sm text-gray-900 dark:text-gray-100">{model.modelName}</h3>
              <div className="flex items-center gap-2">
                <Badge status={model.servingId ? 'success' : 'default'}>
                  {model.servingId ? t('models.statusServing') : t('models.statusPack')}
                </Badge>
                <Button size="sm" variant="ghost" onClick={() => handleViewDetail(model)}>{t('models.detail')}</Button>
                <AccessGuard access={{ types: [Platform.CENTER] }}>
                  <Button size="sm" variant="danger" onClick={() => handleDelete(model)}>{t('common.delete')}</Button>
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

      {models.length === 0 && !loading && !error && (
        <div className="text-center text-xs text-gray-400 py-10">{t('models.noData')}</div>
      )}

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
    </div>
  );
};
