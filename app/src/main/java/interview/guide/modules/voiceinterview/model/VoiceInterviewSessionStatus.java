package interview.guide.modules.voiceinterview.model;

/**
 * 语音面试会话状态。
 */
public enum VoiceInterviewSessionStatus {
  /**
   * 进行中，WebSocket 已连接，面试正在进行。
   */
  IN_PROGRESS,

  /**
   * 已暂停，用户主动暂停或超时后会话状态已保存。
   */
  PAUSED,

  /**
   * 已完成，面试流程结束。
   */
  COMPLETED,

  /**
   * 已失败，会话处理过程中发生错误。
   */
  FAILED
}
