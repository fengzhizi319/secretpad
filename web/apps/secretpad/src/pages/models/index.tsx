import React from 'react';
import { Card, Badge, Button } from '@secretpad/design-system';

export const ModelsPage: React.FC = () => {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between bg-white dark:bg-gray-900 p-4 rounded-xl border border-gray-200 dark:border-gray-800">
        <div>
          <h2 className="text-lg font-bold text-gray-900 dark:text-gray-100">Model Products & Inference</h2>
          <p className="text-xs text-gray-500">Deploy and evaluate trained FL/MPC machine learning models</p>
        </div>
        <Button variant="primary">Deploy Serving</Button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card title="XGBoost Medical Risk Score Model">
          <div className="text-xs text-gray-500 space-y-1 mb-3">
            <div>Model ID: mod-8801 • Mode: FL</div>
            <div>Trained in Project: Medical FL Model v3</div>
            <div>Accuracy: 94.8% • AUC: 0.962</div>
          </div>
          <Badge status="success">Serving Active</Badge>
        </Card>
      </div>
    </div>
  );
};
