import React from 'react';
import { DAGNextWorkspace } from '@secretpad/dag-next';

export const DAGPage: React.FC = () => {
  return (
    <div className="h-[calc(100vh-6rem)] w-full">
      <DAGNextWorkspace />
    </div>
  );
};
