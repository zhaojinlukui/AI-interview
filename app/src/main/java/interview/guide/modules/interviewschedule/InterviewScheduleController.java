package interview.guide.modules.interviewschedule;

import interview.guide.common.result.Result;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.model.ParseRequest;
import interview.guide.modules.interviewschedule.model.ParseResponse;
import interview.guide.modules.interviewschedule.service.InterviewParseService;
import interview.guide.modules.interviewschedule.service.InterviewScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程管理控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-schedule")
@RequiredArgsConstructor
public class InterviewScheduleController {

  private final InterviewScheduleService scheduleService;
  private final InterviewParseService parseService;

  /**
   * 解析面试邀请文本。
   *
   * @param request 解析请求
   * @return 解析结果
   */
  @PostMapping("/parse")
  public Result<ParseResponse> parse(@Valid @RequestBody ParseRequest request) {
    log.info("解析面试邀请: source={}", request.getSource());
    ParseResponse response = parseService.parse(request.getRawText(), request.getSource());
    return Result.success(response);
  }

  /**
   * 创建面试日程。
   *
   * @param request 创建请求
   * @return 创建后的面试日程
   */
  @PostMapping
  public Result<InterviewScheduleDTO> create(@Valid @RequestBody CreateInterviewRequest request) {
    log.info("创建面试日程: companyName={}, position={}",
        request.getCompanyName(), request.getPosition());
    InterviewScheduleDTO dto = scheduleService.create(request);
    return Result.success(dto);
  }

  /**
   * 查询面试日程列表。
   *
   * @param status 状态过滤，可选
   * @param start 开始时间，可选
   * @param end 结束时间，可选
   * @return 面试日程列表
   */
  @GetMapping
  public Result<List<InterviewScheduleDTO>> getAll(
      @RequestParam(required = false) String status,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
      @RequestParam(required = false)
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end
  ) {
    List<InterviewScheduleDTO> list = scheduleService.getAll(status, start, end);
    return Result.success(list);
  }

  /**
   * 更新面试日程。
   *
   * @param id 面试日程 ID
   * @param request 更新请求
   * @return 更新后的面试日程
   */
  @PutMapping("/{id}")
  public Result<InterviewScheduleDTO> update(
      @PathVariable Long id,
      @Valid @RequestBody CreateInterviewRequest request
  ) {
    log.info("更新面试日程: id={}", id);
    InterviewScheduleDTO dto = scheduleService.update(id, request);
    return Result.success(dto);
  }

  /**
   * 删除面试日程。
   *
   * @param id 面试日程 ID
   * @return 删除结果
   */
  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    log.info("删除面试日程: id={}", id);
    scheduleService.delete(id);
    return Result.success(null);
  }

  /**
   * 更新面试日程状态。
   *
   * @param id 面试日程 ID
   * @param status 新状态
   * @return 更新后的面试日程
   */
  @RequestMapping(path = "/{id}/status", method = {RequestMethod.PATCH, RequestMethod.PUT})
  public Result<InterviewScheduleDTO> updateStatus(
      @PathVariable Long id,
      @RequestParam InterviewStatus status
  ) {
    log.info("更新面试日程状态: id={}, status={}", id, status);
    InterviewScheduleDTO dto = scheduleService.updateStatus(id, status);
    return Result.success(dto);
  }
}
