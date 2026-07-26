import React, { useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Card, Button, Badge, Modal, ConfirmDialog, toast } from '@secretpad/design-system';
import { apiClient, Project, JobExecution } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { AccessGuard } from '../../features/auth/ui/access-guard';
import { Platform } from '../../shared/lib/platform';

export const ProjectsPage: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // New Project Form
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [computeMode, setComputeMode] = useState<'MPC' | 'FL' | 'TEE' | 'HE'>('FL');

  // Detail drawer
  const [detailProject, setDetailProject] = useState<Project | null>(null);

  // Edit modal
  const [editProject, setEditProject] = useState<Project | null>(null);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');

  // Delete confirm
  const [deleteTarget, setDeleteTarget] = useState<Project | null>(null);

  // Add node / datatable modal
  const [addNodeOpen, setAddNodeOpen] = useState(false);
  const [addNodeSelected, setAddNodeSelected] = useState('');
  const [addTableOpen, setAddTableOpen] = useState(false);
  const [addTableNodeId, setAddTableNodeId] = useState('');
  const [addTableId, setAddTableId] = useState('');

  const projectsQuery = useQuery({
    queryKey: ['projects'],
    queryFn: () => apiClient.getProjects(),
  });
  const projects = projectsQuery.data ?? [];

  const invalidateProjects = () => queryClient.invalidateQueries({ queryKey: ['projects'] });

  // Detail + jobs queries (enabled when drawer open)
  const detailQuery = useQuery({
    queryKey: ['project-detail', detailProject?.projectId],
    queryFn: () => apiClient.getProjectDetail(detailProject!.projectId),
    enabled: !!detailProject,
  });

  const jobsQuery = useQuery({
    queryKey: ['project-jobs', detailProject?.projectId],
    queryFn: () => apiClient.getProjectJobs(detailProject!.projectId),
    enabled: !!detailProject,
  });
  const jobs: JobExecution[] = jobsQuery.data ?? [];

  // Nodes & datatables for "add" flows
  const nodesQuery = useQuery({
    queryKey: ['nodes'],
    queryFn: () => apiClient.getNodes(),
    enabled: addNodeOpen,
  });
  const allNodes = nodesQuery.data ?? [];

  const tablesQuery = useQuery({
    queryKey: ['datatables', addTableNodeId],
    queryFn: () => apiClient.getDataTables(addTableNodeId),
    enabled: addTableOpen && !!addTableNodeId,
  });
  const tables = tablesQuery.data ?? [];

  const createMutation = useMutation({
    mutationFn: (input: { projectName: string; description: string; computeMode: string }) =>
      apiClient.createProject({ ...input, nodes: [] }),
    onSuccess: () => {
      invalidateProjects();
      setIsModalOpen(false);
      setName('');
      setDescription('');
      toast.success(t('projects.createSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('projects.createError')),
  });

  const updateMutation = useMutation({
    mutationFn: () =>
      apiClient.updateProject({ projectId: editProject!.projectId, name: editName, description: editDescription }),
    onSuccess: () => {
      invalidateProjects();
      setEditProject(null);
      toast.success(t('projects.updateSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : t('projects.updateError')),
  });

  const deleteMutation = useMutation({
    mutationFn: (projectId: string) => apiClient.deleteProject(projectId),
    onSuccess: () => {
      invalidateProjects();
      setDeleteTarget(null);
      toast.success(t('projects.deleteSuccess'));
    },
    onError: (e) => {
      setDeleteTarget(null);
      setError(e instanceof Error ? e.message : String(e));
    },
  });

  const addNodeMutation = useMutation({
    mutationFn: () => apiClient.addProjectNode(detailProject!.projectId, addNodeSelected),
    onSuccess: () => {
      setAddNodeOpen(false);
      setAddNodeSelected('');
      invalidateProjects();
      queryClient.invalidateQueries({ queryKey: ['project-detail', detailProject?.projectId] });
      toast.success(t('projects.nodeAdded'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const addTableMutation = useMutation({
    mutationFn: () =>
      apiClient.addProjectDatatable({
        projectId: detailProject!.projectId,
        nodeId: addTableNodeId,
        datatableId: addTableId,
      }),
    onSuccess: () => {
      setAddTableOpen(false);
      setAddTableId('');
      toast.success(t('projects.datatableAdded'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const stopJobMutation = useMutation({
    mutationFn: (job: JobExecution) => apiClient.stopProjectJob(detailProject!.projectId, job.jobId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['project-jobs', detailProject?.projectId] });
      toast.success(t('dag.stopSuccess'));
    },
    onError: (e) => setError(e instanceof Error ? e.message : String(e)),
  });

  const handleCreateProject = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    createMutation.mutate({ projectName: name, description, computeMode });
  };

  const openEdit = (project: Project) => {
    setEditProject(project);
    setEditName(project.projectName || project.name || '');
    setEditDescription(project.description || '');
  };

  const openAddTable = () => {
    const firstNode = detailProject?.nodes?.[0]?.nodeId || '';
    setAddTableNodeId(firstNode);
    setAddTableId('');
    setAddTableOpen(true);
  };

  const filteredProjects = projects.filter((p) =>
    (p.projectName || p.name || '').toLowerCase().includes(search.toLowerCase())
  );

  const projectNodeNames = (project: Project) =>
    project.nodes.map((n) => n.nodeName || n.nodeId).join(', ');

  const jobStatusBadge = (status: string) => {
    switch (status) {
      case 'RUNNING':
        return 'processing';
      case 'SUCCEEDED':
        return 'success';
      case 'FAILED':
        return 'error';
      default:
        return 'default';
    }
  };

  return (
    <div className="space-y-6">
      {/* Header & Actions */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('projects.title')}</h2>
          <p className="text-xs text-gray-500">{t('projects.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder={t('projects.searchPlaceholder')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="px-3 py-1.5 rounded-lg text-xs bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          />
          <Button variant="primary" size="md" icon={<span>＋</span>} onClick={() => setIsModalOpen(true)}>
            {t('projects.create')}
          </Button>
        </div>
      </div>

      {(error || projectsQuery.error) && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error || projectsQuery.error?.message || '' })}
        </div>
      )}

      {/* Project Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        {filteredProjects.map((project) => (
          <Card
            key={project.projectId}
            className="hover:shadow-md transition-all flex flex-col justify-between cursor-pointer"
            onClick={() => setDetailProject(project)}
          >
            <div>
              <div className="flex items-center justify-between mb-3">
                <span className="px-2.5 py-1 rounded-full text-xs font-mono font-semibold bg-blue-50 dark:bg-blue-950 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-800">
                  Mode: {project.computeMode}
                </span>
                <Badge status={project.status === 'ACTIVE' ? 'success' : 'default'}>
                  {project.status}
                </Badge>
              </div>

              <h3 className="font-bold text-base text-gray-900 dark:text-gray-100 mb-1.5">{project.projectName}</h3>
              <p className="text-xs text-gray-500 dark:text-gray-400 line-clamp-2 mb-4">{project.description || t('projects.noDescription')}</p>
            </div>

            <div className="pt-3 border-t border-gray-100 dark:border-gray-800 flex items-center justify-between text-xs">
              <div className="flex items-center gap-1 text-gray-500">
                <span>{t('projects.joinedNodes')}:</span>
                <span className="font-semibold text-gray-700 dark:text-gray-300 truncate max-w-[120px]">
                  {projectNodeNames(project) || '-'}
                </span>
              </div>
              <div className="flex items-center gap-1">
                <AccessGuard access={{ types: [Platform.CENTER] }}>
                  <Button size="sm" variant="ghost" onClick={(e) => { e.stopPropagation(); openEdit(project); }}>{t('projects.edit')}</Button>
                  <Button size="sm" variant="danger" onClick={(e) => { e.stopPropagation(); setDeleteTarget(project); }}>{t('projects.delete')}</Button>
                </AccessGuard>
              </div>
            </div>
          </Card>
        ))}
      </div>

      {filteredProjects.length === 0 && !error && !projectsQuery.error && (
        <div className="text-center text-xs text-gray-400 py-10">{t('projects.noProjects')}</div>
      )}

      {/* Create Project Modal */}
      <Modal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        title={t('projects.modalTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setIsModalOpen(false)}>{t('projects.cancel')}</Button>
            <Button variant="primary" onClick={handleCreateProject} loading={createMutation.isPending}>{t('projects.createConfirm')}</Button>
          </>
        }
      >
        <form onSubmit={handleCreateProject} className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.nameLabel')}</label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('projects.namePlaceholder')}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
              required
            />
          </div>

          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.modeLabel')}</label>
            <select
              value={computeMode}
              onChange={(e) => setComputeMode(e.target.value as any)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              <option value="FL">{t('projects.modeFL')}</option>
              <option value="MPC">{t('projects.modeMPC')}</option>
              <option value="TEE">{t('projects.modeTEE')}</option>
              <option value="HE">{t('projects.modeHE')}</option>
            </select>
          </div>

          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.descLabel')}</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
              placeholder={t('projects.descPlaceholder')}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            />
          </div>
        </form>
      </Modal>

      {/* Edit Project Modal */}
      <Modal
        isOpen={!!editProject}
        onClose={() => setEditProject(null)}
        title={t('projects.editModalTitle')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setEditProject(null)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => updateMutation.mutate()} loading={updateMutation.isPending}>{t('common.save')}</Button>
          </>
        }
      >
        <div className="space-y-4 text-xs">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.nameLabel')}</label>
            <input
              type="text"
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            />
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.descLabel')}</label>
            <textarea
              value={editDescription}
              onChange={(e) => setEditDescription(e.target.value)}
              rows={3}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            />
          </div>
        </div>
      </Modal>

      {/* Detail Drawer */}
      {detailProject && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div className="absolute inset-0 bg-black/40" onClick={() => setDetailProject(null)} />
          <div className="relative w-full max-w-lg bg-white dark:bg-gray-900 h-full shadow-2xl overflow-y-auto p-6 space-y-6">
            <div className="flex items-start justify-between">
              <div>
                <h3 className="text-lg font-bold text-gray-900 dark:text-gray-100">
                  {detailQuery.data?.projectName || detailProject.projectName}
                </h3>
                <p className="text-xs text-gray-500 font-mono mt-1">{detailProject.projectId}</p>
              </div>
              <Button size="sm" variant="ghost" onClick={() => setDetailProject(null)}>✕</Button>
            </div>

            <div className="text-xs space-y-2">
              <div className="flex items-center gap-2">
                <span className="text-gray-500">{t('projects.modeLabel')}:</span>
                <span className="font-mono font-semibold">{detailQuery.data?.computeMode || detailProject.computeMode}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-gray-500">{t('projects.descLabel')}:</span>
                <span>{detailQuery.data?.description || detailProject.description || t('projects.noDescription')}</span>
              </div>
            </div>

            {/* Joined nodes + add actions */}
            <div>
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-sm font-semibold text-gray-800 dark:text-gray-200">{t('projects.joinedNodes')}</h4>
                <AccessGuard access={{ types: [Platform.CENTER] }}>
                  <Button size="sm" variant="outline" onClick={() => { setAddNodeSelected(''); setAddNodeOpen(true); }}>＋ {t('projects.addNode')}</Button>
                </AccessGuard>
              </div>
              <div className="flex flex-wrap gap-2">
                {(detailQuery.data?.nodes || detailProject.nodes).map((n) => (
                  <Badge key={n.nodeId} status="default">{n.nodeName || n.nodeId}</Badge>
                ))}
                {(detailQuery.data?.nodes || detailProject.nodes).length === 0 && (
                  <span className="text-xs text-gray-400">-</span>
                )}
              </div>
            </div>

            <div className="flex items-center justify-between">
              <h4 className="text-sm font-semibold text-gray-800 dark:text-gray-200">{t('projects.jobs')}</h4>
              <AccessGuard access={{ types: [Platform.CENTER] }}>
                <div className="flex items-center gap-2">
                  <Button size="sm" variant="outline" onClick={openAddTable}>＋ {t('projects.addDatatable')}</Button>
                  <Button size="sm" variant="primary" onClick={() => navigate({ to: '/dag' })}>{t('projects.openDag')}</Button>
                </div>
              </AccessGuard>
            </div>

            {jobsQuery.isLoading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}
            <div className="space-y-2">
              {jobs.length === 0 && !jobsQuery.isLoading && (
                <div className="text-xs text-gray-400 text-center py-4">{t('projects.noJobs')}</div>
              )}
              {jobs.map((job) => (
                <div key={job.jobId} className="flex items-center justify-between p-3 rounded-lg border border-gray-200 dark:border-gray-800 text-xs">
                  <div className="min-w-0">
                    <div className="font-semibold text-gray-800 dark:text-gray-200 truncate">{job.name || job.jobId}</div>
                    <div className="text-gray-400 mt-0.5 font-mono">{job.createTime} {job.duration ? `· ${job.duration}` : ''}</div>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <Badge status={jobStatusBadge(job.status)}>{job.status}</Badge>
                    {job.status === 'RUNNING' && (
                      <AccessGuard access={{ types: [Platform.CENTER] }}>
                        <Button size="sm" variant="danger" loading={stopJobMutation.isPending} onClick={() => stopJobMutation.mutate(job)}>
                          {t('projects.stopJob')}
                        </Button>
                      </AccessGuard>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Add Node Modal */}
      <Modal
        isOpen={addNodeOpen}
        onClose={() => setAddNodeOpen(false)}
        title={t('projects.addNode')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAddNodeOpen(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => addNodeMutation.mutate()} loading={addNodeMutation.isPending} disabled={!addNodeSelected}>{t('common.confirm')}</Button>
          </>
        }
      >
        <div className="text-xs">
          <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.selectNode')}</label>
          <select
            value={addNodeSelected}
            onChange={(e) => setAddNodeSelected(e.target.value)}
            className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
          >
            <option value="">-</option>
            {allNodes.map((n) => (
              <option key={n.nodeId} value={n.nodeId}>{n.nodeName} ({n.nodeId})</option>
            ))}
          </select>
        </div>
      </Modal>

      {/* Add Datatable Modal */}
      <Modal
        isOpen={addTableOpen}
        onClose={() => setAddTableOpen(false)}
        title={t('projects.addDatatable')}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAddTableOpen(false)}>{t('common.cancel')}</Button>
            <Button variant="primary" onClick={() => addTableMutation.mutate()} loading={addTableMutation.isPending} disabled={!addTableNodeId || !addTableId}>{t('common.confirm')}</Button>
          </>
        }
      >
        <div className="text-xs space-y-4">
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.selectNode')}</label>
            <select
              value={addTableNodeId}
              onChange={(e) => { setAddTableNodeId(e.target.value); setAddTableId(''); }}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              {(detailProject?.nodes || []).map((n) => (
                <option key={n.nodeId} value={n.nodeId}>{n.nodeName || n.nodeId}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block font-semibold text-gray-700 dark:text-gray-300 mb-1">{t('projects.selectDatatable')}</label>
            <select
              value={addTableId}
              onChange={(e) => setAddTableId(e.target.value)}
              className="w-full p-2.5 rounded-lg bg-gray-50 dark:bg-gray-800 border border-gray-200 dark:border-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:border-blue-500"
            >
              <option value="">-</option>
              {tables.map((tbl) => (
                <option key={tbl.tableId} value={tbl.tableId}>{tbl.tableName} ({tbl.tableId})</option>
              ))}
            </select>
          </div>
        </div>
      </Modal>

      {/* Delete Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!deleteTarget}
        title={t('projects.delete')}
        message={t('projects.deleteConfirm')}
        danger
        loading={deleteMutation.isPending}
        confirmText={t('projects.delete')}
        cancelText={t('common.cancel')}
        onConfirm={() => deleteTarget && deleteMutation.mutate(deleteTarget.projectId)}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
};
