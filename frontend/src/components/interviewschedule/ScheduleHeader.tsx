// frontend/src/components/interviewschedule/ScheduleHeader.tsx

import React from 'react';
import { motion } from 'framer-motion';
import { Plus, ChevronLeft, ChevronRight, Calendar, List, LayoutGrid } from 'lucide-react';
import dayjs from 'dayjs';
import type { InterviewStatus } from '../../types/interviewSchedule';

export type InterviewStatusFilter = InterviewStatus | 'ALL';

interface ScheduleHeaderProps {
  view: 'day' | 'week' | 'month' | 'list';
  onViewChange: (view: 'day' | 'week' | 'month' | 'list') => void;
  date: Date;
  onDateChange: (date: Date) => void;
  onAddClick: () => void;
  statusFilter: InterviewStatusFilter;
  onStatusFilterChange: (status: InterviewStatusFilter) => void;
}

export const ScheduleHeader: React.FC<ScheduleHeaderProps> = ({
  view,
  onViewChange,
  date,
  onDateChange,
  onAddClick,
  statusFilter,
  onStatusFilterChange,
}) => {
  const handlePrevious = () => {
    const newDate = new Date(date);
    if (view === 'day') {
      newDate.setDate(newDate.getDate() - 1);
    } else if (view === 'week') {
      newDate.setDate(newDate.getDate() - 7);
    } else if (view === 'month') {
      newDate.setMonth(newDate.getMonth() - 1);
    }
    onDateChange(newDate);
  };

  const handleNext = () => {
    const newDate = new Date(date);
    if (view === 'day') {
      newDate.setDate(newDate.getDate() + 1);
    } else if (view === 'week') {
      newDate.setDate(newDate.getDate() + 7);
    } else if (view === 'month') {
      newDate.setMonth(newDate.getMonth() + 1);
    }
    onDateChange(newDate);
  };

  const handleToday = () => {
    onDateChange(new Date());
  };

  const getTitle = () => {
    if (view === 'list') {
      return '面试列表';
    }
    return dayjs(date).format(view === 'month' ? 'YYYY年MM月' : 'YYYY年MM月DD日');
  };

  const statusOptions: Array<{ key: InterviewStatusFilter; label: string }> = [
    { key: 'ALL', label: '全部' },
    { key: 'PENDING', label: '待面试' },
    { key: 'COMPLETED', label: '已完成' },
    { key: 'CANCELLED', label: '已取消' },
    { key: 'EXPIRED', label: '已过期' },
  ];

  return (
    <motion.div
      initial={{ opacity: 0, y: -20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.3 }}
      className="bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl rounded-xl border border-slate-200/50 dark:border-slate-700/50 p-3 mb-3 shadow-lg shadow-slate-200/40 dark:shadow-slate-900/40"
    >
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-3 flex-wrap">
          <motion.h2
            key={getTitle()}
            initial={{ opacity: 0, x: -10 }}
            animate={{ opacity: 1, x: 0 }}
            className="text-lg font-display font-bold text-slate-900 dark:text-white tracking-tight"
          >
            {getTitle()}
          </motion.h2>

          {view !== 'list' && (
            <div className="flex items-center gap-1">
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={handlePrevious}
                className="p-1.5 rounded-lg text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                title="上一页"
              >
                <ChevronLeft className="w-4 h-4" />
              </motion.button>
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={handleToday}
                className="px-3 py-1.5 text-xs font-medium rounded-lg bg-primary-100/80 dark:bg-primary-500/20 text-primary-700 dark:text-primary-300 hover:bg-primary-200/90 dark:hover:bg-primary-500/30 border border-primary-200/50 dark:border-primary-400/30 backdrop-blur-sm transition-all"
              >
                今天
              </motion.button>
              <motion.button
                whileHover={{ scale: 1.05 }}
                whileTap={{ scale: 0.95 }}
                onClick={handleNext}
                className="p-1.5 rounded-lg text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                title="下一页"
              >
                <ChevronRight className="w-4 h-4" />
              </motion.button>
            </div>
          )}
        </div>

        <div className="flex items-center gap-4 flex-wrap justify-end">
          <div className="flex bg-slate-100/80 dark:bg-slate-800/80 backdrop-blur-sm rounded-lg p-1 gap-1">
            {statusOptions.map(({ key, label }) => (
              <motion.button
                key={key}
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => onStatusFilterChange(key)}
                className={`px-2.5 py-1.5 rounded-md font-medium text-xs transition-all ${
                  statusFilter === key
                    ? 'bg-white/95 dark:bg-slate-700/80 backdrop-blur-sm shadow-md text-primary-700 dark:text-primary-200 border border-slate-200/50 dark:border-slate-600/50'
                    : 'text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100/50 dark:hover:bg-slate-700/50'
                }`}
              >
                {label}
              </motion.button>
            ))}
          </div>

          <div className="flex bg-slate-100/80 dark:bg-slate-800/80 backdrop-blur-sm rounded-lg p-1 gap-1">
            {[
              { key: 'day', icon: Calendar, label: '日视图' },
              { key: 'week', icon: Calendar, label: '周视图' },
              { key: 'month', icon: LayoutGrid, label: '月视图' },
              { key: 'list', icon: List, label: '列表' },
            ].map(({ key, icon: Icon, label }) => (
              <motion.button
                key={key}
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
                onClick={() => onViewChange(key as any)}
                className={`px-2.5 py-1.5 rounded-md flex items-center gap-1.5 font-medium text-xs transition-all ${
                  view === key
                    ? 'bg-white/95 dark:bg-slate-700/80 backdrop-blur-sm shadow-md text-primary-700 dark:text-primary-200 border border-slate-200/50 dark:border-slate-600/50'
                    : 'text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white hover:bg-slate-100/50 dark:hover:bg-slate-700/50'
                }`}
              >
                <Icon className="w-3.5 h-3.5" />
                {label}
              </motion.button>
            ))}
          </div>

          <motion.button
            whileHover={{ scale: 1.05, y: -1 }}
            whileTap={{ scale: 0.95 }}
            onClick={onAddClick}
            className="px-3 py-2 bg-gradient-to-r from-primary-600 to-primary-500 dark:from-primary-500 dark:to-primary-400 text-white rounded-lg text-sm font-medium shadow-md shadow-primary-500/20 hover:shadow-lg hover:shadow-primary-500/30 hover:-translate-y-0.5 flex items-center gap-1.5 transition-all"
          >
            <Plus className="w-4 h-4" />
            添加面试
          </motion.button>
        </div>
      </div>
    </motion.div>
  );
};
