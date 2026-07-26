import React, { useEffect, useState } from 'react';
import { Card, Badge, Button } from '@secretpad/design-system';
import { apiClient, MessageVO, User } from '@secretpad/api-client';
import { useTranslation } from '../../shared/lib/i18n';
import { useAuthStore } from '../../features/auth/model/auth-store';

export const MessagesPage: React.FC = () => {
  const { t } = useTranslation();
  const { user } = useAuthStore();
  const [messages, setMessages] = useState<MessageVO[]>([]);
  const [pendingCount, setPendingCount] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ownerId = (user as User | null)?.ownerId || '';

  const loadMessages = () => {
    if (!ownerId) return;
    setLoading(true);
    setError(null);
    Promise.all([
      apiClient.getMessages(ownerId, 1, 100),
      apiClient.getPendingMessageCount(ownerId),
    ])
      .then(([list, count]) => {
        setMessages(list);
        setPendingCount(count);
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadMessages();
  }, [ownerId]);

  const statusBadge = (status?: string) => {
    switch (status?.toUpperCase()) {
      case 'PENDING':
      case 'WAITING':
        return 'warning';
      case 'AGREE':
      case 'APPROVED':
      case 'SUCCEED':
        return 'success';
      case 'REJECT':
      case 'FAILED':
        return 'error';
      default:
        return 'default';
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">{t('messages.title')}</h2>
          <p className="text-xs text-gray-500">{t('messages.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3">
          <Badge status={pendingCount > 0 ? 'warning' : 'success'}>
            {t('messages.pendingCount', { count: pendingCount })}
          </Badge>
          <Button size="sm" variant="outline" onClick={loadMessages}>{t('common.refresh')}</Button>
        </div>
      </div>

      {error && (
        <div className="text-xs text-red-500 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 rounded-lg px-4 py-2">
          {t('common.error', { message: error })}
        </div>
      )}

      {loading && <div className="text-xs text-gray-400">{t('common.loading')}</div>}

      <Card bodyClassName="p-0">
        <div className="divide-y divide-gray-100 dark:divide-gray-800 text-xs">
          {messages.length === 0 && !loading && !error && (
            <div className="p-4 text-center text-gray-400">{t('messages.noData')}</div>
          )}
          {messages.map((msg) => (
            <div key={msg.voteID || msg.messageName} className="p-4 flex items-center justify-between hover:bg-gray-50/50 dark:hover:bg-gray-850/50">
              <div>
                <div className="font-semibold text-gray-900 dark:text-gray-100">{msg.messageName}</div>
                <div className="text-gray-500 mt-1">
                  {t('messages.type')}: {msg.type} • {t('messages.createTime')}: {msg.createTime}
                </div>
              </div>
              <Badge status={statusBadge(msg.status)}>
                {msg.status || t('messages.statusUnknown')}
              </Badge>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
};
