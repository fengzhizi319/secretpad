import React from 'react';
import { Card, Badge } from '@secretpad/design-system';

export const MessagesPage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Message & Approval Center</h2>
          <p className="text-xs text-gray-500">Node invitations, data access approvals, and system alerts</p>
        </div>
      </div>

      <Card bodyClassName="p-0">
        <div className="divide-y divide-gray-100 dark:divide-gray-800 text-xs">
          <div className="p-4 flex items-center justify-between hover:bg-gray-50/50 dark:hover:bg-gray-850/50">
            <div>
              <div className="font-semibold text-gray-900 dark:text-gray-100">Bob Node Data Authorization Request</div>
              <div className="text-gray-500 mt-1">Bob requests access to table patient_clinical_records for Project Medical FL Model v3</div>
            </div>
            <Badge status="warning">Pending Approval</Badge>
          </div>
          <div className="p-4 flex items-center justify-between hover:bg-gray-50/50 dark:hover:bg-gray-850/50">
            <div>
              <div className="font-semibold text-gray-900 dark:text-gray-100">Job Execution Succeeded</div>
              <div className="text-gray-500 mt-1">XGBoost Federated Training finished in 4m 12s with 0 errors</div>
            </div>
            <Badge status="success">Info</Badge>
          </div>
        </div>
      </Card>
    </div>
  );
};
