import { useState } from 'react';
import { knowledgeBaseApi } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import type { UploadKnowledgeBaseBatchResponse } from '../api/knowledgebase';
import FileUploadCard from '../components/FileUploadCard';

const MAX_FILE_SIZE = 100 * 1024 * 1024;
const MAX_BATCH_FILE_COUNT = 10;
const MAX_TOTAL_UPLOAD_SIZE = 500 * 1024 * 1024;

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseBatchResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const handleUpload = async (files: File[], names?: string[]) => {
    if (files.length === 0) {
      setError('请先选择要导入的知识库文件');
      return;
    }

    if (files.length > MAX_BATCH_FILE_COUNT) {
      setError(`单次最多导入 ${MAX_BATCH_FILE_COUNT} 个知识库文件`);
      return;
    }

    const oversizedFile = files.find((file) => file.size > MAX_FILE_SIZE);
    if (oversizedFile) {
      setError(`文件 ${oversizedFile.name} 超过 100MB，知识库文件最大支持 100MB`);
      return;
    }

    const totalSize = files.reduce((sum, file) => sum + file.size, 0);
    if (totalSize > MAX_TOTAL_UPLOAD_SIZE) {
      setError('单次导入总大小不能超过 500MB');
      return;
    }

    setUploading(true);
    setError('');

    try {
      const data = await knowledgeBaseApi.uploadKnowledgeBases(files, names);
      if (data.failureCount > 0) {
        const failedItems = data.items
          .filter((item) => !item.success)
          .slice(0, 3)
          .map((item) => `${item.filename}: ${item.errorMessage}`)
          .join('；');
        setError(`已成功导入 ${data.successCount} 个，失败 ${data.failureCount} 个。${failedItems}`);
        setUploading(false);
        return;
      }
      onUploadComplete(data);
    } catch (err: unknown) {
      setError(getErrorMessage(err));
      setUploading(false);
    }
  };

  return (
    <FileUploadCard
      title="上传知识库"
      subtitle="一次导入多个文档，AI 将基于知识库内容回答您的问题"
      accept=".pdf,.doc,.docx,.txt,.md"
      formatHint="支持 PDF、DOCX、DOC、TXT、MD"
      maxSizeHint="单文件最大 100MB"
      multiple={true}
      maxFiles={MAX_BATCH_FILE_COUNT}
      uploading={uploading}
      uploadButtonText="开始导入"
      selectButtonText="选择知识库文件"
      showNameInput={true}
      nameLabel="知识库名称（可选）"
      namePlaceholder="留空则使用文件名"
      error={error}
      onUploadMultiple={handleUpload}
      onBack={onBack}
    />
  );
}
