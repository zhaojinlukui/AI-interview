package interview.guide.modules.interviewschedule.service;

import interview.guide.common.auth.CurrentUserContext;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试日程服务
 * 负责面试日程的增删改查操作，包括创建、更新、删除、状态变更和列表查询
 */
@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    /**
     * 创建面试日程
     * 设置当前用户ID和初始状态后保存到数据库
     */
    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        // 将请求参数复制到实体对象
        BeanUtils.copyProperties(request, entity);
        // 设置当前登录用户ID
        entity.setUserId(CurrentUserContext.getRequiredUserId());
        // 设置初始状态为待面试
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    /**
     * 更新面试日程
     * 仅允许修改日程信息，不更新ID和状态字段
     */
    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        // 复制请求参数到实体，排除id和status字段，防止被覆盖
        BeanUtils.copyProperties(request, entity, "id", "status");
        return toDTO(repository.save(entity));
    }

    /**
     * 删除面试日程
     */
    @Transactional
    public void delete(Long id) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        repository.delete(entity);
    }

    /**
     * 更新面试日程状态
     * 用于标记面试为已完成、已取消等状态
     */
    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    /**
     * 查询面试日程列表
     * 支持按状态筛选、按时间范围筛选或查询全部
     */
    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
        List<InterviewScheduleEntity> entities;
        String userId = CurrentUserContext.getRequiredUserId();

        if (start != null && end != null) {
            // 按时间范围查询
            entities = repository.findByUserIdAndInterviewTimeBetween(userId, start, end);
        } else if (status != null) {
            // 按状态筛选查询
            entities = repository.findByUserIdAndStatus(userId, InterviewStatus.valueOf(status));
        } else {
            // 查询当前用户的所有日程
            entities = repository.findByUserId(userId);
        }

        return entities.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * 根据ID获取面试日程实体，不存在则抛出异常
     * 同时校验该日程属于当前登录用户
     */
    private InterviewScheduleEntity getByIdOrThrow(Long id) {
        return repository.findByIdAndUserId(id, CurrentUserContext.getRequiredUserId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND,
                        "面试日程不存在: " + id
                ));
    }

    /**
     * 将实体对象转换为DTO
     */
    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}