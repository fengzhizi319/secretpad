import React from 'react';
import { AccessType, useHasAccess, usePlatform } from '@/shared/lib/platform';
import { useTranslation } from '@/shared/lib/i18n';

export const AccessGuard: React.FC<{ access: AccessType; children: React.ReactNode; fallback?: React.ReactNode }> = ({
  access,
  children,
  fallback,
}) => {
  const allowed = useHasAccess(access);
  if (allowed) return <>{children}</>;
  if (fallback) return <>{fallback}</>;
  return null;
};

export const RouteGuard: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { t } = useTranslation();
  const { platformType } = usePlatform();
  // For this migration phase all P0 pages are available to CENTER/EDGE/AUTONOMY.
  // P2P-specific pages or center-only management pages can be gated later.
  const allowedTypes = ['CENTER', 'EDGE', 'AUTONOMY', 'TEST'];
  if (allowedTypes.includes(platformType)) {
    return <>{children}</>;
  }
  return (
    <div className="flex-1 flex items-center justify-center text-sm text-gray-500">
      {t('access.denied')}
    </div>
  );
};
