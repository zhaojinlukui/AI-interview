// frontend/src/components/interviewschedule/ScheduleList.tsx

import React from 'react';
import { motion } from 'framer-motion';
import type { InterviewSchedule, InterviewStatus } from '../../types/interviewSchedule';
import { InterviewListItem } from './InterviewListItem';

interface ScheduleListProps {
  interviews: InterviewSchedule[];
  onEdit: (interview: InterviewSchedule) => void;
  onDelete: (id: number) => void;
  onStatusChange: (id: number, status: InterviewStatus) => void;
}

export const ScheduleList: React.FC<ScheduleListProps> = ({
  interviews = [],
  onEdit,
  onDelete,
  onStatusChange,
}) => {
  const sortedInterviews = [...interviews].sort(
    (a, b) => new Date(a.interviewTime).getTime() - new Date(b.interviewTime).getTime()
  );

  if (sortedInterviews.length === 0) {
    return (
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        className="text-center py-10"
      >
        <div className="bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl rounded-xl border border-slate-200/50 dark:border-slate-700/50 p-8 shadow-lg">
          <p className="text-slate-500 dark:text-slate-400 text-base font-medium">暂无面试记录</p>
        </div>
      </motion.div>
    );
  }

  return (
    <div className="bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl rounded-xl border border-slate-200/50 dark:border-slate-700/50 p-3 space-y-3">
      {sortedInterviews.map((interview, index) => (
        <motion.div
          key={interview.id}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.2, delay: index * 0.05 }}
        >
          <InterviewListItem
            interview={interview}
            onEdit={() => onEdit(interview)}
            onDelete={() => onDelete(interview.id)}
            onStatusChange={(status) => onStatusChange(interview.id, status)}
          />
        </motion.div>
      ))}
    </div>
  );
};
