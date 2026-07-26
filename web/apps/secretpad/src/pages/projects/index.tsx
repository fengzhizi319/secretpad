import React, { useEffect, useState } from 'react';
import { Card, Button, Badge, Modal } from '@secretpad/design-system';
import { apiClient, Project } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';

export const ProjectsPage: React.FC<{ onNavigate: (path: string) => void }> = ({ onNavigate }) => {
  const { t } = useTranslation();
  const [projects, setProjects] = useState<Project[]>([]);
  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // New Project Form
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [computeMode, setComputeMode] = useState<'MPC' | 'FL' | 'TEE' | 'HE'>('FL');

  useEffect(() => {
    apiClient.getProjects()
      .then(setProjects)
      .catch((e) => setError(e.message));
  }, []);

  const handleCreateProject = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const newProj = await apiClient.createProject({
        projectName: name,
        description,
        computeMode,
        nodes: [],
      });
      setProjects([newProj, ...projects]);
      setIsModalOpen(false);
      setName('');
      setDescription('');
    } catch (e) {
      setError(e instanceof Error ? e.message : t('projects.createError'));
    } finally {
      setLoading(false);
    }
  };

  const filteredProjects = projects.filter((p) =>
    (p.projectName || p.name || '').toLowerCase().includes(search.toLowerCase())
  );

  const projectNodeNames = (project: Project) =>
    project.nodes.map((n) => n.nodeName || n.nodeId).join(', ');

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

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {/* Project Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
        {filteredProjects.map((project) => (
          <Card key={project.projectId} className="hover:shadow-md transition-all flex flex-col justify-between">
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
              <Button size="sm" variant="ghost" onClick={() => onNavigate('/dag')}>
                {t('projects.openDag')}
              </Button>
            </div>
          </Card>
        ))}
      </div>

      {filteredProjects.length === 0 && !error && (
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
            <Button variant="primary" onClick={handleCreateProject} loading={loading}>{t('projects.createConfirm')}</Button>
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
    </div>
  );
};
