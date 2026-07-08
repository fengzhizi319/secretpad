/*
 * Copyright 2023 Ant Group Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.secretflow.secretpad.service.impl;

import org.secretflow.secretpad.common.constant.Constants;
import org.secretflow.secretpad.common.enums.DataSourceTypeEnum;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.errorcode.DatatableErrorCode;
import org.secretflow.secretpad.common.errorcode.GraphErrorCode;
import org.secretflow.secretpad.common.errorcode.JobErrorCode;
import org.secretflow.secretpad.common.errorcode.ProjectErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.*;
import org.secretflow.secretpad.manager.integration.data.DataManager;
import org.secretflow.secretpad.manager.integration.datasource.DatasourceManager;
import org.secretflow.secretpad.manager.integration.datatable.AbstractDatatableManager;
import org.secretflow.secretpad.manager.integration.model.DatasourceDTO;
import org.secretflow.secretpad.manager.integration.model.DatatableDTO;
import org.secretflow.secretpad.manager.integration.node.AbstractNodeManager;
import org.secretflow.secretpad.manager.integration.noderoute.AbstractNodeRouteManager;
import org.secretflow.secretpad.persistence.datasync.producer.p2p.P2pDataSyncProducerTemplate;
import org.secretflow.secretpad.persistence.entity.*;
import org.secretflow.secretpad.persistence.model.*;
import org.secretflow.secretpad.persistence.projection.ProjectJobStatus;
import org.secretflow.secretpad.persistence.repository.*;
import org.secretflow.secretpad.service.ComponentService;
import org.secretflow.secretpad.service.GraphService;
import org.secretflow.secretpad.service.ProjectService;
import org.secretflow.secretpad.service.constant.ComponentConstants;
import org.secretflow.secretpad.service.enums.VoteTypeEnum;
import org.secretflow.secretpad.service.graph.ComponentTools;
import org.secretflow.secretpad.service.graph.GraphContext;
import org.secretflow.secretpad.service.graph.JobChain;
import org.secretflow.secretpad.service.model.graph.*;
import org.secretflow.secretpad.service.model.node.NodeSimpleInfo;
import org.secretflow.secretpad.service.model.project.GetProjectJobTaskOutputRequest;
import org.secretflow.secretpad.service.model.project.StopProjectJobTaskRequest;
import org.secretflow.secretpad.service.model.report.ScqlReport;
import org.secretflow.secretpad.service.util.AutonomyNodeRouteUtil;
import org.secretflow.secretpad.service.util.GraphUtils;
import org.secretflow.secretpad.service.util.ResultConvertUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.secretflow.spec.v1.AttrType;
import com.secretflow.spec.v1.ComponentDef;
import com.secretflow.spec.v1.Table;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.secretflow.proto.kuscia.TaskConfig;
import org.secretflow.v1alpha1.kusciaapi.Domaindata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.secretflow.secretpad.common.constant.ComponentConstants.*;
import static org.secretflow.secretpad.service.constant.ComponentConstants.COMP_READ_DATA_DATATABLE_ID;
import static org.secretflow.secretpad.service.constant.Constants.TEE_PROJECT_MODE;
import static org.secretflow.secretpad.service.util.JobUtils.genTaskOutputId;

/**
 * GraphServiceImpl
 * =============================================================================
 * 该类负责 SecretPad 平台中“画布（Graph/DAG）”的全生命周期管理，包括：
 *
 * 1. 画布管理：创建、删除、查询、更新画布元数据；保存/全量更新画布节点与边。
 * 2. 节点管理：单个节点的增删改查、节点最大序号刷新。
 * 3. 画布运行：把用户选中的 DAG 节点转换为可执行的 ProjectJob，经 JobChain
 *    持久化 → 渲染 → 提交给 Kuscia。
 * 4. 状态与输出查询：查询画布下各节点的最新运行状态、日志、输出结果（报告、
 *    模型、规则、联邦表等）。
 * 5. 任务控制：停止画布中正在运行的节点或整个画布的任务。
 *
 * 核心流程：
 *   前端画布(nodes/edges) ──save/update──→ project_graph / project_graph_node 表
 *                                          │
 *                                          ▼
 *   用户点击“运行”：startGraph(request) ──→ 校验权限 → 找顶层节点 → 找参与方
 *                                          │
 *                                          ▼
 *                              verifyNodeAndRouteHealthy (节点/路由健康检查)
 *                                          │
 *                                          ▼
 *                              ProjectJob.genProjectJob(graphDO, selectedNodes, parties)
 *                                          │
 *                                          ▼
 *                              jobChain.proceed(projectJob) [JobChain 三阶段]
 *                                          │
 *                                          ▼
 *                              Kuscia CreateJob gRPC 调用
 *
 * 关键依赖：
 *   - graphRepository / graphNodeRepository：画布持久化
 *   - jobRepository / taskRepository：作业与任务状态
 *   - jobChain：ProjectJob 处理链（持久化、渲染、提交）
 *   - componentService：组件元数据与 SecretPad 内置组件判断
 *   - projectService：停止任务等
 *   - datatableManager / dataManager / datasourceManager：数据表、DomainData、数据源查询
 *
 * @author yansi
 * @date 2023/5/29
 */
@Slf4j
@Service
public class GraphServiceImpl implements GraphService {

    private static final Integer DEFAULT_INITIAL_INDEX = 32;
    @Autowired
    private ProjectGraphRepository graphRepository;
    @Autowired
    private ProjectGraphNodeRepository graphNodeRepository;
    @Autowired
    private ProjectJobTaskRepository taskRepository;
    @Autowired
    private ComponentService componentService;
    @Autowired
    private ProjectResultRepository resultRepository;
    @Autowired
    private ProjectReportRepository reportRepository;
    @Autowired
    private ProjectDatatableRepository datatableRepository;
    @Autowired
    private AbstractDatatableManager datatableManager;
    @Autowired
    private ProjectJobTaskLogRepository jobTaskLogRepository;
    @Autowired
    private ProjectJobRepository jobRepository;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JobChain jobChain;
    @Autowired
    private AbstractNodeManager nodeManager;
    @Autowired
    private AbstractNodeRouteManager nodeRouteManager;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Resource
    private ProjectReadDataRepository projectReadDataRepository;

    @Resource
    private ProjectGraphDomainDatasourceServiceImpl projectGraphDomainDatasourceService;
    @Resource
    private DataManager dataManager;
    @Resource
    private ProjectApprovalConfigRepository projectApprovalConfigRepository;

    @Value("${tee.domain-id:tee}")
    private String teeNodeId;
    @Value("${secretpad.platform-type}")
    private String plaformType;
    @Value("${secretpad.node-id}")
    private String localNodeId;
    @Autowired
    private EnvServiceImpl envServiceImpl;
    @Resource
    private DatasourceManager datasourceManager;
    @Resource
    private ProjectNodeRepository projectNodeRepository;
    @Resource
    private ProjectModelPackRepository projectModelPackRepository;
    @Resource
    private ProjectScheduleJobRepository projectScheduleJobRepository;

    /**
     * 列出所有组件（按分类聚合）
     * -------------------------------------------------------------------------
     * 直接委托 ComponentService，返回组件树，供前端画布组件面板使用。
     *
     * @return 组件分类 -> 组件列表的映射
     */
    @Override
    public Map<String, CompListVO> listComponents() {
        return componentService.listComponents();
    }

    /**
     * 查询单个组件定义
     * -------------------------------------------------------------------------
     * 根据 domain/name/version 定位组件，返回 ComponentDef protobuf 对象。
     *
     * @param request 组件定位请求
     * @return 组件定义
     */
    @Override
    public ComponentDef getComponent(GetComponentRequest request) {
        return componentService.getComponent(GetComponentRequest.toComponentKey(request));
    }

    /**
     * 批量查询组件定义
     *
     * @param request 组件定位请求列表
     * @return 组件定义列表
     */
    @Override
    public List<ComponentDef> batchGetComponent(List<GetComponentRequest> request) {
        return componentService.batchGetComponent(GetComponentRequest.toComponentKeyList(request));
    }

    /**
     * 列出组件国际化信息
     *
     * @return 组件 i18n 数据
     */
    @Override
    public Object listComponentI18n() {
        return componentService.listComponentI18n();
    }

    /**
     * 创建新画布
     * -------------------------------------------------------------------------
     * 执行流程：
     *   1. 生成 8 位随机 graphId。
     *   2. 构建 ProjectGraphDO，设置 ownerId 为当前登录用户所属节点。
     *   3. 把请求中的 nodes / edges 转换为持久化对象。
     *   4. 初始化 nodeMaxIndex 为 32（DEFAULT_INITIAL_INDEX），前端新增节点时从此值递增。
     *   5. 保存 project_graph 表，并同步初始化该画布的默认数据源配置。
     *
     * @param request 创建画布请求（projectId、name、nodes、edges）
     * @return 包含新生成 graphId 的视图对象
     */
    @Transactional
    @Override
    public CreateGraphVO createGraph(CreateGraphRequest request) {
        String projectId = request.getProjectId();
        String name = request.getName();
        String graphId = UUIDUtils.random(8);
        String ownerId = UserContext.getUser().getOwnerId();
        ProjectGraphDO graphDO = ProjectGraphDO.builder().upk(new ProjectGraphDO.UPK(projectId, graphId)).name(name).ownerId(ownerId).maxParallelism(1).build();
        List<GraphNode> nodes = request.getNodes();
        if (!CollectionUtils.isEmpty(nodes)) {
            graphDO.setNodes(nodes.stream().map(node -> GraphNode.toDO(projectId, graphId, node)).collect(Collectors.toList()));
        }
        List<GraphEdge> edges = request.getEdges();
        if (!CollectionUtils.isEmpty(edges)) {
            graphDO.setEdges(edges.stream().map(GraphEdge::toDO).collect(Collectors.toList()));
        }
        graphDO.setNodeMaxIndex(DEFAULT_INITIAL_INDEX);
        graphRepository.save(graphDO);
        projectGraphDomainDatasourceService.createGraphAndInitDefaultDataSource(request, graphId);
        return CreateGraphVO.builder().graphId(graphId).build();
    }

    /**
     * 删除画布
     * -------------------------------------------------------------------------
     * 先校验当前用户是否为画布所有者，再级联删除 project_graph 主表记录。
     * 由于 ProjectGraphDO.nodes 配置了 CascadeType.ALL + orphanRemoval，
     * 关联的 project_graph_node 子表记录会一并删除。
     *
     * @param request 删除画布请求（projectId、graphId）
     */
    @Override
    public void deleteGraph(DeleteGraphRequest request) {
        // check project graph owner
        ownerCheck(request.getProjectId(), request.getGraphId());
        graphRepository.deleteById(new ProjectGraphDO.UPK(request.getProjectId(), request.getGraphId()));
    }

    /**
     * 查询项目下的所有画布列表
     * -------------------------------------------------------------------------
     * 根据 projectId 查询 project_graph 表，转换为 GraphMetaVO 列表返回。
     *
     * @param request 查询请求（projectId）
     * @return 画布元数据列表
     */
    @Override
    public List<GraphMetaVO> listGraph(ListGraphRequest request) {
        List<ProjectGraphDO> graphDOList = graphRepository.findByProjectId(request.getProjectId());
        if (!CollectionUtils.isEmpty(graphDOList)) {
            return graphDOList.stream().map(GraphMetaVO::fromDO).collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    /**
     * 更新画布元数据（仅名称）
     * -------------------------------------------------------------------------
     * 1. 校验画布是否存在。
     * 2. AUTONOMY（自治/P2P）模式下，只有画布所有者才能修改名称。
     * 3. 更新 project_graph.name 并保存。
     *
     * @param request 更新请求（projectId、graphId、name）
     */
    @Override
    public void updateGraphMeta(UpdateGraphMetaRequest request) {
        Optional<ProjectGraphDO> graphDOOptional = graphRepository.findById(new ProjectGraphDO.UPK(request.getProjectId(), request.getGraphId()));
        if (graphDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
        }
        if (PlatformTypeEnum.AUTONOMY.equals(UserContext.getUser().getPlatformType()) && !UserContext.getUser().getOwnerId().equalsIgnoreCase(graphDOOptional.get().getOwnerId())) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_OWNER_CANNOT_UPDATE);
        }
        ProjectGraphDO graphDO = graphDOOptional.get();
        graphDO.setName(request.getName());
        graphRepository.save(graphDO);
    }

    /**
     * 全量更新画布（保存画布时调用）
     * -------------------------------------------------------------------------
     * 执行流程：
     *   1. Center 模式校验画布所有者；非 Center 模式仅校验画布是否存在。
     *   2. 若请求携带 nodes，清空原节点列表并替换为新的 GraphNodeInfo 列表。
     *   3. 若请求携带 edges，替换为新的边列表。
     *   4. 若请求携带 maxParallelism，更新最大并行度。
     *   5. 保存 project_graph 表，并同步更新画布数据源配置。
     *
     * 注意：本方法使用 @Transactional 保证 nodes/edges 替换与主表更新在同一个事务。
     *
     * @param request 全量更新请求（projectId、graphId、nodes、edges、maxParallelism）
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void fullUpdateGraph(FullUpdateGraphRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();
        // check project graph owner
        ProjectGraphDO graphDO;
        if (envServiceImpl.isCenter()) {
            graphDO = ownerCheck(projectId, graphId);
        } else {
            Optional<ProjectGraphDO> graphOptional = graphRepository.findById(new ProjectGraphDO.UPK(projectId, graphId));
            if (graphOptional.isEmpty()) {
                throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
            }
            graphDO = graphOptional.get();
        }
        List<GraphNodeInfo> nodes = request.getNodes();
        if (nodes != null) {
            if (graphDO.getNodes() != null) {
                graphDO.getNodes().clear();
            }
            graphDO.setNodes(GraphNodeInfo.toDOList(projectId, graphId, nodes));
        }

        List<GraphEdge> edges = request.getEdges();
        if (edges != null) {
            graphDO.setEdges(GraphEdge.toDOList(edges));
        }
        if (Objects.nonNull(request.getMaxParallelism())) {
            graphDO.setMaxParallelism(request.getMaxParallelism());
        }
        graphRepository.save(graphDO);
        projectGraphDomainDatasourceService.updateProjectGraphDomainDatasourceDOByFullUpdateGraphRequest(request);
    }

    /**
     * 更新单个画布节点
     * -------------------------------------------------------------------------
     * 1. 校验画布所有者。
     * 2. 校验节点是否存在。
     * 3. 将 GraphNodeInfo 转换为 ProjectGraphNodeDO 并保存（更新节点参数、inputs/outputs 等）。
     *
     * @param request 更新节点请求（projectId、graphId、node）
     */
    @Override
    public void updateGraphNode(UpdateGraphNodeRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();
        // check project graph owner
        ownerCheck(projectId, graphId);
        String graphNodeId = request.getNode().getGraphNodeId();
        Optional<ProjectGraphNodeDO> graphNodeDOOptional = graphNodeRepository.findById(new ProjectGraphNodeDO.UPK(projectId, graphId, graphNodeId));
        if (graphNodeDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_NOT_EXISTS);
        }

        ProjectGraphNodeDO graphNodeDO = GraphNodeInfo.toDO(projectId, graphId, request.getNode());
        graphNodeRepository.save(graphNodeDO);
    }

    /**
     * 查询画布详情
     * -------------------------------------------------------------------------
     * 1. 根据 projectId + graphId 查询 project_graph。
     * 2. 调用 getLatestTaskStatus 获取画布下每个节点的最新运行状态。
     * 3. 将 ProjectGraphDO 与节点状态合并为 GraphDetailVO。
     * 4. 补充数据源配置（各节点可使用的默认数据源）。
     *
     * @param request 查询请求（projectId、graphId）
     * @return 画布详情（包含 nodes、edges、各节点状态、数据源配置）
     */
    @Transactional
    @Override
    public GraphDetailVO getGraphDetail(GetGraphRequest request) {
        Optional<ProjectGraphDO> graphDOOptional = graphRepository.findById(new ProjectGraphDO.UPK(request.getProjectId(), request.getGraphId()));
        if (graphDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
        }
        ProjectGraphDO graphDO = graphDOOptional.get();
        List<GraphNodeStatusVO> nodeStatus = getLatestTaskStatus(graphDO).getNodes();
        GraphDetailVO graphDetailVO = GraphDetailVO.fromDO(graphDO, nodeStatus);
        graphDetailVO.setDataSourceConfig(projectGraphDomainDatasourceService.convertToGraphDetailVODataSourceConfig(request));
        return graphDetailVO;
    }

    /**
     * 查询画布节点的输出结果（按最新任务）
     * -------------------------------------------------------------------------
     * 1. 根据 projectId + graphNodeId 找到该节点最近一次执行的任务 ProjectTaskDO。
     * 2. 调用 getGraphNodeTaskOutputVO 解析该任务的指定 outputId 结果。
     *
     * @param request 查询请求（projectId、graphNodeId、outputId）
     * @return 节点输出视图（包含输出类型、结果元数据、各方数据表信息等）
     */
    @Override
    public GraphNodeOutputVO getGraphNodeOutput(GraphNodeOutputRequest request) {
        String projectId = request.getProjectId();
        Optional<ProjectTaskDO> taskDOOptional = taskRepository.findLatestTasks(projectId, request.getGraphNodeId());
        if (taskDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_OUTPUT_NOT_EXISTS);
        }
        return getGraphNodeTaskOutputVO(taskDOOptional.get(), request.getOutputId());
    }

    /**
     * 按 jobId + taskId + outputId 查询指定任务的输出结果
     * -------------------------------------------------------------------------
     * 1. 通过 openProjectJobTask 打开指定 ProjectJob / ProjectScheduleJob 并取出任务。
     * 2. 调用 getGraphNodeTaskOutputVO 解析输出。
     *
     * @param request 查询请求（projectId、jobId、taskId、outputId）
     * @return 节点输出视图
     */
    @Override
    public GraphNodeOutputVO getGraphNodeTaskOutputVO(GetProjectJobTaskOutputRequest request) {
        ProjectTaskDO jobTask = openProjectJobTask(request.getJobId(), request.getTaskId());
        return getGraphNodeTaskOutputVO(jobTask, request.getOutputId());
    }

    /**
     * 刷新画布的节点最大序号
     * -------------------------------------------------------------------------
     * 前端新增节点时需要保证 node id 不冲突，因此向服务端申请一个新的序号。
     * 逻辑：
     *   - 若前端传入的 currentIndex 大于 DB 中存储的 nodeMaxIndex，则以 currentIndex 为准，
     *     并把 DB 中值设为 currentIndex + 1。
     *   - 否则把 DB 中 nodeMaxIndex 加 1 返回。
     *
     * @param request 刷新请求（projectId、graphId、currentIndex）
     * @return 当前可用的最大序号
     */
    @Override
    public GraphNodeMaxIndexRefreshVO refreshNodeMaxIndex(GraphNodeMaxIndexRefreshRequest request) {
        Optional<ProjectGraphDO> projectGraphDOOptional = graphRepository.findById(new ProjectGraphDO.UPK(request.getProjectId(), request.getGraphId()));
        if (projectGraphDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
        }
        ProjectGraphDO projectGraphDO = projectGraphDOOptional.get();
        Integer index = projectGraphDO.getNodeMaxIndex();
        if (Objects.nonNull(request.getCurrentIndex()) && request.getCurrentIndex() > index) {
            projectGraphDO.setNodeMaxIndex(request.getCurrentIndex() + 1);
            index = request.getCurrentIndex();
        } else {
            projectGraphDO.setNodeMaxIndex(index + 1);
        }
        graphRepository.save(projectGraphDO);
        return GraphNodeMaxIndexRefreshVO.builder().maxIndex(index).build();
    }

    /**
     * 根据 jobId + taskId 打开项目任务
     * -------------------------------------------------------------------------
     * 兼容普通 ProjectJob 与定时调度 ProjectScheduleJob：
     *   1. 先按 jobId 查 project_job 表。
     *   2. 若不存在，则查 project_schedule_job 表，并转换为 ProjectJobDO。
     *   3. 从 job.tasks 中取出指定 taskId 的 ProjectTaskDO。
     *
     * @param jobId  作业 ID
     * @param taskId 任务 ID
     * @return 项目任务对象
     */
    private ProjectTaskDO openProjectJobTask(String jobId, String taskId) {
        Optional<ProjectJobDO> jobOpt = jobRepository.findByJobId(jobId);
        if (jobOpt.isEmpty()) {
            Optional<ProjectScheduleJobDO> byJobId = projectScheduleJobRepository.findByJobId(jobId);
            if (byJobId.isEmpty()) {
                throw SecretpadException.of(JobErrorCode.PROJECT_JOB_NOT_EXISTS);
            } else {
                jobOpt = Optional.of(ProjectScheduleJobDO.convertToProjectJobDO(byJobId.get()));
            }
        }
        ProjectJobDO job = jobOpt.get();
        if (!job.getTasks().containsKey(taskId)) {
            throw SecretpadException.of(JobErrorCode.PROJECT_JOB_TASK_NOT_EXISTS);
        }
        return job.getTasks().get(taskId);
    }

    /**
     * 构建指定任务、指定 outputId 的输出视图
     * -------------------------------------------------------------------------
     * 这是输出查询的核心方法，支持两类组件：
     *
     * 1. SecretPad 内置组件（如 read_data/datatable、read_model）：
     *    - 直接查询 project_datatable / project_model_pack 等表组装结果。
     * 2. SecretFlow 组件（如 ml.train/ss_sgd_train）：
     *    - 通过 taskDO.graphNode.outputs 校验 outputId 是否合法。
     *    - 按 projectId + taskId + latestOutputId 查 project_result 表。
     *    - 根据 ResultKind（Report/Model/Rule/FedTable/READ_DATA）分别构造 OutputResult。
     *    - AUTONOMY 模式下需要考虑目标节点转换（targetNodeId）。
     *
     * @param taskDO   项目任务对象
     * @param outputId 输出端口 ID
     * @return 节点输出视图
     */
    private GraphNodeOutputVO getGraphNodeTaskOutputVO(ProjectTaskDO taskDO, String outputId) {
        String projectId = taskDO.getUpk().getProjectId();
        GraphNodeOutputVO outputVO = GraphNodeOutputVO.builder().build();
        List<GraphNodeOutputVO.OutputResult> outputResults = new ArrayList<>();

        ProjectGraphNodeDO graphNode = taskDO.getGraphNode();
        GraphNodeInfo graphNodeInfo = GraphNodeInfo.fromDO(graphNode);
        if (componentService.isSecretpadComponent(graphNodeInfo)) {
            if (ComponentConstants.COMP_READ_MODEL_ID.equals(graphNodeInfo.codeName)) {
                /** history model read mode pack record **/
                String datatableId = ComponentTools.getDataTableId(graphNodeInfo);
                Optional<ProjectModelPackDO> modelPackDOOptional = projectModelPackRepository.findById(datatableId);
                if (modelPackDOOptional.isPresent()) {
                    ProjectModelPackDO projectModelPackDO = modelPackDOOptional.get();
                    outputVO.setCodeName(graphNodeInfo.codeName);
                    outputVO.setType(ResultKind.Model.getName());
                    outputVO.setGmtCreate(DateTimes.toRfc3339(projectModelPackDO.getGmtCreate()));
                    outputVO.setGmtModified(DateTimes.toRfc3339(projectModelPackDO.getGmtModified()));
                    List<PartyDataSource> partyDataSources = projectModelPackDO.getPartyDataSources();
                    for (PartyDataSource source : partyDataSources) {
                        GraphNodeOutputVO.OutputResult result = GraphNodeOutputVO.OutputResult
                                .builder()
                                .nodeId(source.getPartyId())
                                .path(source.getDatasource())
                                .type(ResultKind.Model.getName())
                                .tableId(datatableId).dsId(source.getDatasource()).datasourceType(source.getDatasource()).build();
                        outputResults.add(result);
                    }
                }
            } else { /** read data */
                outputVO.setType(ResultKind.FedTable.getName());
                outputVO.setCodeName(graphNodeInfo.codeName);
                String datatableId = ComponentTools.getDataTableId(graphNodeInfo);
                List<ProjectDatatableDO> datatableDOS = datatableRepository.findByDatableId(projectId, datatableId);
                if (!CollectionUtils.isEmpty(datatableDOS)) {
                    for (ProjectDatatableDO datatableDO : datatableDOS) {
                        GraphNodeOutputVO.OutputResult outputResult = fromDatatable(datatableDO, null, null);
                        outputResults.add(outputResult);
                    }
                    outputVO.setGmtCreate(DateTimes.toRfc3339(datatableDOS.get(0).getGmtCreate()));
                    outputVO.setGmtModified(DateTimes.toRfc3339(datatableDOS.get(0).getGmtModified()));
                }
            }
        } else {
            String jobId = taskDO.getUpk().getJobId();
            String taskId = taskDO.getUpk().getTaskId();
            outputVO.setTaskId(taskId);
            outputVO.setJobId(jobId);
            Optional<ProjectJobDO> projectJobDOOptional = jobRepository.findById(new ProjectJobDO.UPK(projectId, jobId));
            if (projectJobDOOptional.isEmpty()) {
                Optional<ProjectScheduleJobDO> byJobId = projectScheduleJobRepository.findByJobId(jobId);
                if (byJobId.isEmpty()) {
                    throw SecretpadException.of(JobErrorCode.PROJECT_JOB_NOT_EXISTS);
                } else {
                    projectJobDOOptional = Optional.of(ProjectScheduleJobDO.convertToProjectJobDO(byJobId.get()));
                }
            }
            outputVO.setGraphID(projectJobDOOptional.get().getGraphId());
            List<String> outputs = taskDO.getGraphNode().getOutputs();
            if (CollectionUtils.isEmpty(outputs) || outputs.contains(outputId)) {
                String latestOutputId = genTaskOutputId(jobId, outputId);
                List<ProjectResultDO> resultDOS = resultRepository.findByOutputId(projectId, taskId, latestOutputId);
                //task file compensation binning modifications and model param modifications
                compensationSecretPadComponent(taskDO, outputId, outputVO);
                if (!CollectionUtils.isEmpty(resultDOS)) {
                    for (ProjectResultDO resultDO : resultDOS) {
                        ResultKind resultKind = resultDO.getUpk().getKind();
                        outputVO.setType(GraphNodeOutputVO.typeFromResultKind(resultKind));
                        outputVO.setCodeName(taskDO.getGraphNode().getCodeName());
                        outputVO.setGmtCreate(DateTimes.toRfc3339(resultDO.getGmtCreate()));
                        outputVO.setGmtModified(DateTimes.toRfc3339(resultDO.getGmtModified()));
                        String nodeId = resultDO.getUpk().getNodeId();
                        String refId = resultDO.getUpk().getRefId();
                        GraphNodeOutputVO.OutputResult outputResult;
                        String content; //TODO:not sure
                        String targetNodeId = nodeId;
                        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType)) && !P2pDataSyncProducerTemplate.nodeIds.contains(targetNodeId)) {
                            List<ProjectNodeDO> projectNodeDOList = projectNodeRepository.findByProjectId(projectId);
                            if (!CollectionUtils.isEmpty(projectNodeDOList)) {
                                List<String> list = projectNodeDOList.stream().map(ProjectNodeDO::getUpk)
                                        .map(ProjectNodeDO.UPK::getNodeId).filter(n -> !taskDO.getParties().contains(n)).toList();
                                if (!CollectionUtils.isEmpty(list)) {
                                    targetNodeId = list.get(0);
                                }
                            }
                        }
                        Domaindata.DomainData domainData = dataManager.queryDomainData(nodeId, refId, targetNodeId);
                        String datasourceId = domainData.getDatasourceId();
                        Optional<DatasourceDTO> datasourceOpt = datasourceManager.findById(DatasourceDTO.NodeDatasourceId.from(targetNodeId, datasourceId));

                        String datasourceType = DataSourceTypeEnum.kuscia2platform(datasourceOpt.get().getType());

                        switch (resultKind) {
                            case Report:
                                return getGraphNodeOutputVO(projectId, latestOutputId, outputVO);
                            case READ_DATA:
                                Optional<ProjectReadDataDO> readDataDOOptional = projectReadDataRepository.findById(new ProjectReadDataDO.UPK(projectId, latestOutputId));
                                if (readDataDOOptional.isEmpty()) {
                                    throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_OUTPUT_NOT_EXISTS);
                                }
                                ProjectReadDataDO readDataDO = readDataDOOptional.get();
                                content = readDataDO.getContent();
                                log.info("content is {}", content);

                                Gson gson = new Gson();
                                String outputTabs = (String) outputVO.getTabs();
                                String json1 = gson.toJson(outputTabs);
                                JsonElement jsonElement = gson.fromJson(json1, JsonElement.class);
                                log.info("json1 is {}", json1);

                                JsonElement contentElement = gson.fromJson(content, JsonElement.class);
                                JsonArray contentJsonArray = contentElement.getAsJsonArray();
                                contentJsonArray.set(0, jsonElement);
                                String json = gson.toJson(contentJsonArray);

                                log.info("json is {}", json);
                                outputVO.setTabs(json);
                                return outputVO;
                            case Model:
                                outputResult = GraphNodeOutputVO.OutputResult.builder().nodeId(nodeId).path(latestOutputId).type(ResultKind.Model.getName()).tableId(latestOutputId).dsId(datasourceId).datasourceType(datasourceType).build();
                                outputResults.add(outputResult);
                                break;
                            case Rule:
                                outputResult = GraphNodeOutputVO.OutputResult.builder().nodeId(nodeId).path(latestOutputId).type(ResultKind.Rule.getName()).tableId(latestOutputId).dsId(datasourceId).datasourceType(datasourceType).build();
                                outputResults.add(outputResult);
                                break;
                            case FedTable:
                                Optional<ProjectDatatableDO> datatableDOOptional = datatableRepository.findById(new ProjectDatatableDO.UPK(projectId, nodeId, latestOutputId));
                                if (datatableDOOptional.isEmpty()) {
                                    throw SecretpadException.of(DatatableErrorCode.DATATABLE_NOT_EXISTS);
                                }
                                outputResult = fromDatatable(datatableDOOptional.get(), null, null);
                                outputResult.setDatasourceType(datasourceType);
                                outputResults.add(outputResult);
                                break;
                            default:
                                throw SecretpadException.of(DatatableErrorCode.UNSUPPORTED_DATATABLE_TYPE);
                        }
                    }
                }
            }
        }
        if (!CollectionUtils.isEmpty(outputResults)) {
            for (GraphNodeOutputVO.OutputResult outputResult : outputResults) {
                NodeDO nodeDO = nodeRepository.findByNodeId(outputResult.getNodeId());
                String nodeName = ObjectUtils.isEmpty(nodeDO) ? outputResult.getNodeId() : nodeDO.getName();
                outputResult.setNodeName(nodeName);
            }
        }


        Table.HeaderItem fileHeader = Table.HeaderItem.newBuilder().setType(String.valueOf(AttrType.AT_STRING)).setName("metas").build();
        GraphNodeOutputVO.FileMeta fileMeta = GraphNodeOutputVO.FileMeta.builder().headers(ProtoUtils.protosToListMap(List.of(fileHeader))).rows(outputResults).build();
        outputVO.setMeta(fileMeta);
        return outputVO;
    }

    /**
     * 读取报告类型输出
     * -------------------------------------------------------------------------
     * 从 project_report 表中取出报告内容 JSON，解析并设置到 outputVO.tabs。
     * 若内容为 SCQL 报告格式，先转换再返回。
     *
     * @param projectId     项目 ID
     * @param latestOutputId 最新输出 ID
     * @param outputVO      待填充的输出视图
     * @return 填充后的输出视图
     */
    private @NotNull GraphNodeOutputVO getGraphNodeOutputVO(String projectId, String latestOutputId, GraphNodeOutputVO outputVO) {
        Optional<ProjectReportDO> reportDOOptional = reportRepository.findById(new ProjectReportDO.UPK(projectId, latestOutputId));
        if (reportDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_OUTPUT_NOT_EXISTS);
        }
        ProjectReportDO reportDO = reportDOOptional.get();
        JsonNode jsonNode = JsonUtils.parseObject(reportDO.getContent());
        if (Objects.isNull(jsonNode.get("type"))) {
            ScqlReport scqlReport = JsonUtils.toJavaObject(jsonNode, ScqlReport.class);
            List<String> reasons = scqlReport.getWarnings().stream()
                    .map(ScqlReport.SQLWarning::getReason)
                    .toList();
            outputVO.setWarning(reasons);
            String content = ResultConvertUtil.convertScqlToSfReport(scqlReport);
            jsonNode = JsonUtils.parseObject(content);
        }
        Object tabs = null;
        if (ObjectUtils.isNotEmpty(jsonNode)) {
            JsonNode meta = jsonNode.get("meta");
            if (ObjectUtils.isNotEmpty(meta)) {
                tabs = meta.get("tabs");
            }
        }
        outputVO.setTabs(ObjectUtils.isEmpty(tabs) ? new ArrayList<>() : tabs);
        return outputVO;
    }

    /**
     * 补偿 SecretPad 内置组件的输出展示
     * -------------------------------------------------------------------------
     * 针对 binning_modifications 和 model_param_modifications 这两个内置组件，
     * 它们的第二个输出（outputId 以 "1" 结尾）需要从前置 read_data 任务的结果中
     * 补偿 raw tabs 内容，否则前端无法正常展示。
     *
     * @param taskDO   当前任务
     * @param outputId 输出端口 ID
     * @param outputVO 输出视图
     */
    private void compensationSecretPadComponent(ProjectTaskDO taskDO, String outputId, GraphNodeOutputVO outputVO) {
        String projectId = taskDO.getUpk().getProjectId();
        ProjectGraphNodeDO graphNode = taskDO.getGraphNode();
        String type = outputId.substring(outputId.length() - 1);
        log.debug("compensationSecretPadComponent CodeName:{}  outputId:{}  type：{}  Label:{}", graphNode.getCodeName(), outputId, type, graphNode.getLabel());
        if ((BINNING_MODIFICATIONS_CODENAME.equals(graphNode.getCodeName()) && "1".equals(type)) || (MODEL_PARAM_MODIFICATIONS_CODENAME.equals(graphNode.getCodeName()) && "1".equals(type))) {
            String inputId = taskDO.getGraphNode().getInputs().get(0);
            String graphNodeId = inputId;
            int i = graphNodeId.lastIndexOf('-');
            graphNodeId = graphNodeId.substring(0, i);
            i = graphNodeId.lastIndexOf('-');
            graphNodeId = graphNodeId.substring(0, i);
            log.debug("-- inputId {} graphNodeId {}", inputId, graphNodeId);
            Optional<ProjectTaskDO> projectTaskDOOptional = taskRepository.findLatestTasks(projectId, graphNodeId);
            if (projectTaskDOOptional.isEmpty()) {
                throw SecretpadException.of(DatatableErrorCode.DATATABLE_NOT_EXISTS);
            }
            String taskId = projectTaskDOOptional.get().getUpk().getJobId();
            String taskOutputId = genTaskOutputId(taskId, inputId);

            ProjectReadDataDO projectReadDataDO = projectReadDataRepository.findByProjectIdAndOutputIdLaste(projectId, taskOutputId);
            if (!ObjectUtils.isEmpty(projectReadDataDO)) {
                String contentResult = projectReadDataDO.getRaw();
                outputVO.setTabs(contentResult);
                log.debug("tabs result is {}", contentResult);
                outputVO.setType(GraphNodeOutputVO.typeFromResultKind(ResultKind.READ_DATA));
                outputVO.setCodeName(taskDO.getGraphNode().getCodeName());
            }
        }

    }

    /**
     * 按 nodeId + resultId 查询结果输出
     * -------------------------------------------------------------------------
     * 主要用于结果中心或模型/规则列表中点击查看某条结果。
     * 1. 查 project_result 表定位结果记录。
     * 2. 打开对应 ProjectJobTask。
     * 3. 根据 ResultKind 组装 OutputResult；TEE 模式下需转换 centerResultId 和 centerNodeId。
     *
     * @param nodeId   节点 ID（domainId）
     * @param resultId 结果引用 ID
     * @return 节点输出视图
     */
    @Override
    public GraphNodeOutputVO getResultOutputVO(String nodeId, String resultId) {
        GraphNodeOutputVO outputVO = GraphNodeOutputVO.builder().build();
        List<GraphNodeOutputVO.OutputResult> outputResults = new ArrayList<>();
        Table.HeaderItem fileHeader = Table.HeaderItem.newBuilder().setType(String.valueOf(AttrType.AT_STRING)).setName("metas").build();
        Optional<ProjectResultDO> resultOpt = resultRepository.findByNodeIdAndRefId(nodeId, resultId);
        if (resultOpt.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_OUTPUT_NOT_EXISTS);
        }
        ProjectResultDO resultDO = resultOpt.get();
        ProjectTaskDO task = openProjectJobTask(resultDO.getJobId(), resultDO.getTaskId());
        ResultKind resultKind = resultDO.getUpk().getKind();
        outputVO.setType(GraphNodeOutputVO.typeFromResultKind(resultKind));
        outputVO.setCodeName(task.getGraphNode().getCodeName());
        outputVO.setGmtCreate(DateTimes.toRfc3339(resultDO.getGmtCreate()));
        outputVO.setGmtModified(DateTimes.toRfc3339(resultDO.getGmtModified()));
        String projectId = resultDO.getUpk().getProjectId();
        Optional<ProjectDO> projectOpt = projectRepository.findById(projectId);
        if (projectOpt.isEmpty()) {
            throw SecretpadException.of(ProjectErrorCode.PROJECT_NOT_EXISTS);
        }
        // rebuild node id and result id when project is tee mode
        String centerNodeId = nodeId;
        String centerResultId = resultId;
        if (StringUtils.endsWithIgnoreCase(projectOpt.get().getComputeMode(), TEE_PROJECT_MODE)) {
            centerResultId = resultId.replace(nodeId + "-", "");
            centerNodeId = teeNodeId;
        }
        GraphNodeOutputVO.OutputResult outputResult;
        switch (resultKind) {
            case Report:
                return getGraphNodeOutputVO(projectId, centerResultId, outputVO);
            case Model:
            case Rule:
                outputResult = GraphNodeOutputVO.OutputResult.builder().nodeId(centerNodeId).path(centerResultId).build();
                outputResults.add(outputResult);
                break;
            case FedTable:
                Optional<ProjectDatatableDO> datatableDOOptional = datatableRepository.findById(new ProjectDatatableDO.UPK(projectId, centerNodeId, centerResultId));
                if (datatableDOOptional.isEmpty()) {
                    throw SecretpadException.of(DatatableErrorCode.DATATABLE_NOT_EXISTS);
                }
                outputResult = fromDatatable(datatableDOOptional.get(), nodeId, resultId);
                outputResults.add(outputResult);
                break;
            default:
                throw SecretpadException.of(DatatableErrorCode.UNSUPPORTED_DATATABLE_TYPE);
        }
        GraphNodeOutputVO.FileMeta fileMeta = GraphNodeOutputVO.FileMeta.builder().headers(ProtoUtils.protosToListMap(List.of(fileHeader))).rows(outputResults).build();
        outputVO.setMeta(fileMeta);
        return outputVO;
    }

    /**
     * 从 ProjectDatatableDO 构建输出结果项
     * -------------------------------------------------------------------------
     * 1. 解析 tableConfig 得到字段名、字段类型列表；
     *    Center 模式下会过滤掉非当前 edge 的 individual 列（保护隐私）。
     * 2. 组装 OutputResult（nodeId、type、fields、tableId）。
     * 3. 通过 datatableManager 查询对应 DomainData，补充 relativeUri 和 datasourceId。
     *
     * @param datatableDO 项目数据表对象
     * @param edgeNodeId  当前连线的源节点 ID（用于 individual 列过滤）
     * @param edgeTableId 当前连线的源表 ID
     * @return 输出结果项
     */
    private GraphNodeOutputVO.OutputResult fromDatatable(ProjectDatatableDO datatableDO, String edgeNodeId, String edgeTableId) {
        List<ProjectDatatableDO.TableColumnConfig> tableConfig = datatableDO.getTableConfig();
        List<String> fields = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (!CollectionUtils.isEmpty(tableConfig)) {
            tableConfig.forEach(config -> {
                if (envServiceImpl.isCenter() && StringUtils.isNotEmpty(config.getColComment())
                        && config.getColComment().startsWith("individual")
                        && !config.getColComment().equals("individual:" + edgeNodeId)) {
                    log.info("individual table not show in graph node output result is center mode");
                } else {
                    fields.add(config.getColName());
                    types.add(config.getColType());
                }
            });
        }
        String projectId = datatableDO.getProjectId();
        String nodeId = datatableDO.getUpk().getNodeId();
        String tableId = datatableDO.getUpk().getDatatableId();
        GraphNodeOutputVO.OutputResult outputResult = GraphNodeOutputVO.OutputResult.builder().nodeId(nodeId).type(nodeRepository.findByNodeId(nodeId).getType()).fields(String.join(",", fields)).fieldTypes(String.join(",", types)).tableId(tableId).build();
        Optional<ProjectDO> projectOpt = projectRepository.findById(datatableDO.getProjectId());
        if (projectOpt.isEmpty()) {
            throw SecretpadException.of(ProjectErrorCode.PROJECT_NOT_EXISTS);
        }
        log.warn("record  edgeNodeId={} graphNodeId={} , datatableDO={}", edgeNodeId, edgeTableId, JsonUtils.toJSONString(datatableDO));
        DatatableDTO.NodeDatatableId query = DatatableDTO.NodeDatatableId.from(nodeManager.getTargetNodeId(nodeId, projectId), tableId);
        Optional<DatatableDTO> datatableDTOOptional = datatableManager.findById(query);

        if (datatableDTOOptional.isPresent()) {
            DatatableDTO datatableDTO = datatableDTOOptional.get();
            outputResult.setPath(datatableDTO.getRelativeUri());
            outputResult.setDsId(datatableDTO.getDatasourceId());
        }
        return outputResult;
    }

    /**
     * 启动画布运行（核心入口）
     * -------------------------------------------------------------------------
     * 用户在前端选中部分节点点击“运行”后触发。执行流程：
     *
     * 1. 校验画布所有者，并确认画布非空。
     * 2. 校验用户选中的 nodes 是否全部存在于当前画布。
     * 3. 查找项目信息。
     * 4. 计算每个选中节点的“顶层节点”集合（findTopNodes）：
     *    即当前节点运行所需的最小上游依赖集合，用于确定参与方。
     * 5. 根据顶层节点涉及的数据表，计算每个选中节点对应的参与方 parties（findParties）。
     * 6. 把项目信息、参与方列表、断点标志写入 GraphContext 线程上下文。
     * 7. TEE 模式下：所有参与方强制替换为 TEE 节点。
     * 8. 健康检查：verifyNodeAndRouteHealthy 校验各节点就绪、跨节点路由可达。
     * 9. 生成 ProjectJob：ProjectJob.genProjectJob(graphDO, selectedNodes, parties)。
     * 10. 经 JobChain 处理：持久化 → 渲染输入输出 → 提交 Kuscia CreateJob。
     * 11. 非定时调度场景清理 GraphContext。
     *
     * @param request 启动请求（projectId、graphId、nodes、breakpoint 等）
     * @return 包含新生成 jobId 的 StartGraphVO
     */
    @Transactional
    @Override
    public StartGraphVO startGraph(StartGraphRequest request) {
        // check project graph owner
        ProjectGraphDO graphDO = ownerCheck(request.getProjectId(), request.getGraphId());
        List<ProjectGraphNodeDO> nodeDOList = graphDO.getNodes();
        if (CollectionUtils.isEmpty(nodeDOList)) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_NOT_EXISTS);
        }
        List<String> nodeIds = request.getNodes();
        List<ProjectGraphNodeDO> selectedNodes = nodeDOList.stream().filter(nodeDO -> nodeIds.contains(nodeDO.getUpk().getGraphNodeId())).collect(Collectors.toList());
        if (selectedNodes.size() != nodeIds.size()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_NOT_EXISTS);
        }
        Optional<ProjectDO> projectOpt = projectRepository.findById(request.getProjectId());
        if (projectOpt.isEmpty()) {
            throw SecretpadException.of(ProjectErrorCode.PROJECT_NOT_EXISTS);
        }
        List<GraphContext.GraphParty> partyList = new ArrayList<>();
        Map<String, Set<String>> topNodes = findTopNodes(graphDO.getEdges(), selectedNodes);
        Map<String, Set<String>> parties = findParties(graphDO.getNodes(), topNodes, request.getProjectId(), partyList);
        GraphContext.set(projectOpt.get(), GraphContext.GraphParties.builder().parties(partyList).build(), request.getBreakpoint());
        if (GraphContext.isTee()) {
            parties = new HashMap<>();
            String teeNodeId = GraphContext.getTeeNodeId();
            Set<String> partyNodes = new HashSet<>();
            partyNodes.add(teeNodeId);
            for (Map.Entry<String, Set<String>> entry : topNodes.entrySet()) {
                parties.put(entry.getKey(), partyNodes);
            }
        }

        verifyNodeAndRouteHealthy(parties.values().stream().flatMap(Set::stream).collect(Collectors.toSet()), request.getProjectId());
        ProjectJob projectJob = ProjectJob.genProjectJob(graphDO, selectedNodes, parties);
        jobChain.proceed(projectJob);
        if (!GraphContext.isScheduled()) {
            GraphContext.remove();
        }
        return new StartGraphVO(projectJob.getJobId());
    }

    /**
     * 校验画布所有者
     * -------------------------------------------------------------------------
     * 1. 校验画布是否存在。
     * 2. 校验当前登录用户的 ownerId 是否与画布的 ownerId 一致；不一致则抛出无权限异常。
     *
     * @param projectId 项目 ID
     * @param graphId   画布 ID
     * @return 校验通过的画布对象
     */
    private ProjectGraphDO ownerCheck(String projectId, String graphId) {
        Optional<ProjectGraphDO> graphOptional = graphRepository.findById(new ProjectGraphDO.UPK(projectId, graphId));
        if (graphOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
        }
        ProjectGraphDO graphDO = graphOptional.get();
        String ownerId = UserContext.getUser().getOwnerId();
        if (!StringUtils.equals(ownerId, graphDO.getOwnerId())) {
            throw SecretpadException.of(GraphErrorCode.NON_OUR_CREATION_CAN_VIEWED);
        }
        return graphDO;
    }

    /**
     * 查询画布下各节点的最新运行状态
     * -------------------------------------------------------------------------
     * 1. 根据 projectId + graphId 加载画布。
     * 2. 调用 getLatestTaskStatus 获取每个 graphNodeId 对应的最新任务状态。
     *
     * @param request 查询请求（projectId、graphId）
     * @return 画布状态（各节点状态 + 是否全部完成）
     */
    @Override
    public GraphStatus listGraphNodeStatus(ListGraphNodeStatusRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();
        Optional<ProjectGraphDO> graphDOOptional = graphRepository.findById(new ProjectGraphDO.UPK(projectId, graphId));
        if (graphDOOptional.isEmpty()) {
            throw SecretpadException.of(GraphErrorCode.GRAPH_NOT_EXISTS);
        }
        return getLatestTaskStatus(graphDOOptional.get());
    }

    /**
     * 获取画布下每个节点的最新任务状态
     * -------------------------------------------------------------------------
     * 对每个 graphNode：
     *   1. 查 project_job_task 表找到该节点最近一次任务。
     *   2. 填充 taskId、jobId、parties、progress、status。
     *   3. 若没有任何任务记录，则状态为 STAGING。
     * 最后汇总所有关联 job 的状态，判断整个画布是否已结束（finished）。
     *
     * TODO：目前对每个节点单独查表，可优化为一次 SQL。
     *
     * @param graphDO 画布对象
     * @return 画布状态视图
     */
    public GraphStatus getLatestTaskStatus(ProjectGraphDO graphDO) {
        String projectId = graphDO.getUpk().getProjectId();
        List<ProjectGraphNodeDO> nodes = graphDO.getNodes();
        GraphStatus graphStatus = new GraphStatus();
        List<GraphNodeStatusVO> nodeStatus = new ArrayList<>();
        List<String> jobIds = new ArrayList<>();
        // find the latest task associated with graphNode
        if (!CollectionUtils.isEmpty(nodes)) {
            List<String> graphNodeIds = nodes.stream().map(node -> node.getUpk().getGraphNodeId()).toList();
            for (String graphNodeId : graphNodeIds) {
                GraphNodeStatusVO nodeStatusVO = new GraphNodeStatusVO();
                nodeStatusVO.setGraphNodeId(graphNodeId);
                Optional<ProjectTaskDO> taskDOOptional = taskRepository.findLatestTasks(projectId, graphNodeId);
                GraphNodeTaskStatus status = GraphNodeTaskStatus.STAGING;
                if (taskDOOptional.isPresent()) {
                    status = taskDOOptional.get().getStatus();
                    nodeStatusVO.setTaskId(taskDOOptional.get().getUpk().getTaskId());
                    nodeStatusVO.setJobId(taskDOOptional.get().getUpk().getJobId());
                    nodeStatusVO.setParties(nodeRepository.findByNodeIdIn(taskDOOptional.get().getParties()).stream().map(e -> NodeSimpleInfo.builder().nodeName(e.getName()).nodeId(e.getNodeId()).build()).collect(Collectors.toList()));
                    nodeStatusVO.setProgress(taskDOOptional.get().getExtraInfo().getProgress());
                    jobIds.add(taskDOOptional.get().getUpk().getJobId());
                }
                nodeStatusVO.setStatus(status);
                nodeStatus.add(nodeStatusVO);
            }
        }

        // resolve job status
        boolean finished = true;
        if (!CollectionUtils.isEmpty(jobIds)) {
            List<ProjectJobStatus> jobStatuses = jobRepository.findStatusByJobIds(projectId, jobIds);
            for (ProjectJobStatus job : jobStatuses) {
                if (!job.isFinished()) {
                    finished = false;
                    break;
                }
            }
        }

        graphStatus.setNodes(nodeStatus);
        graphStatus.setFinished(finished);
        return graphStatus;
    }


    /**
     * 查询画布节点的运行日志
     * -------------------------------------------------------------------------
     * 1. 根据 projectId + graphNodeId 找到该节点最新任务。
     * 2. 从 project_job_task_log 表读取该任务的日志列表并去重。
     * 3. 对于 read_data/datatable 内置组件，若日志为空，则构造默认 start/succeed 日志兜底。
     * 4. 对同一任务多次运行的 start/succeed 日志进行去重（distinctSpecifyLogs）。
     *
     * @param request 查询请求（projectId、graphNodeId）
     * @return 节点任务日志视图
     */
    @Override
    public GraphNodeTaskLogsVO getGraphNodeLogs(GraphNodeLogsRequest request) {
        Optional<ProjectTaskDO> taskDOOptional = taskRepository.findLatestTasks(request.getProjectId(), request.getGraphNodeId());
        if (taskDOOptional.isEmpty()) {
            throw SecretpadException.of(JobErrorCode.PROJECT_JOB_TASK_NOT_EXISTS);
        }
        ProjectTaskDO task = taskDOOptional.get();
        GraphNodeTaskLogsVO graphNodeTaskLogsVO = new GraphNodeTaskLogsVO(task.getStatus(),
                jobTaskLogRepository.findAllByJobTaskId(task.getUpk().getJobId(), task.getUpk().getTaskId())
                        .stream().map(ProjectJobTaskLogDO::getLog).distinct().collect(Collectors.toList()));
        if (graphNodeTaskLogsVO.getLogs().isEmpty() && COMP_READ_DATA_DATATABLE_ID.equals(task.getGraphNode().getCodeName())) {
            String jobId = task.getUpk().getJobId();
            String taskId = task.getUpk().getTaskId();
            graphNodeTaskLogsVO.setLogs(Arrays.asList(
                    ProjectJobTaskLogDO.makeLog(task.getGmtCreate(), String.format("the jobId=%s, taskId=%s start ...", jobId, taskId)),
                    ProjectJobTaskLogDO.makeLog(task.getGmtCreate(), String.format("the jobId=%s, taskId=%s succeed", jobId, taskId))
            ));
        }
        String logPrefix = String.format("INFO the jobId=%s, taskId=%s-%s", task.getUpk().getJobId(), task.getUpk().getJobId(), request.getGraphNodeId());
        log.info("log de duplication matching， {}", logPrefix);
        distinctSpecifyLogs(graphNodeTaskLogsVO, logPrefix + " start");
        distinctSpecifyLogs(graphNodeTaskLogsVO, logPrefix + " succeed");
        return graphNodeTaskLogsVO;
    }

    /**
     * 停止画布节点或整个画布的运行任务
     * -------------------------------------------------------------------------
     * 1. 校验画布所有者。
     * 2. 若 graphNodeId 为空，则停止该画布下所有 RUNNING 状态的 ProjectJob。
     * 3. 若 graphNodeId 不为空，则停止与该节点关联的所有 RUNNING 任务对应的 ProjectJob。
     * 4. 调用 projectService.stopProjectJob 逐一向 Kuscia 发送 StopJob 请求。
     *
     * @param request 停止请求（projectId、graphId、graphNodeId 可选）
     */
    @Override
    public void stopGraphNode(StopGraphNodeRequest request) {
        String projectId = request.getProjectId();
        String graphId = request.getGraphId();
        String graphNodeId = request.getGraphNodeId();
        // check project graph owner
        ownerCheck(projectId, graphId);
        List<StopProjectJobTaskRequest> stopRequests = new ArrayList<>();
        if (Strings.isNullOrEmpty(graphNodeId)) {
            // find all running jobs in whole graph
            List<ProjectJobDO> runningJobs = jobRepository.findByStatus(projectId, graphId, GraphJobStatus.RUNNING);
            if (!CollectionUtils.isEmpty(runningJobs)) {
                stopRequests = runningJobs.stream().map(job -> new StopProjectJobTaskRequest(projectId, job.getUpk().getJobId())).collect(Collectors.toList());
            }
        } else {
            // find all running jobs associated with graphNode
            List<ProjectTaskDO> runningTasks = taskRepository.findByStatus(projectId, graphNodeId, GraphNodeTaskStatus.RUNNING);
            if (!CollectionUtils.isEmpty(runningTasks)) {
                stopRequests = runningTasks.stream().map(task -> new StopProjectJobTaskRequest(projectId, task.getUpk().getJobId())).collect(Collectors.toList());
            }
        }
        if (!CollectionUtils.isEmpty(stopRequests)) {
            stopRequests.forEach(req -> projectService.stopProjectJob(req));
        }
    }


    /**
     * 校验参与方节点健康与跨节点路由可达
     * -------------------------------------------------------------------------
     * 在提交 Kuscia Job 之前执行，保证任务所需各方均可用：
     *
     * - AUTONOMY（自治/P2P）模式：
     *   1. 单方任务直接通过。
     *   2. 多方任务时，根据本机构节点与路由表判断源→目的路由是否可用；
     *      不可用且存在项目邀请关系时抛异常。
     * - 非 AUTONOMY 模式（Center/Edge）：
     *   1. 检查每个参与方节点是否就绪（nodeManager.checkNodeReady）。
     *   2. 检查每对参与方之间的节点路由是否就绪（nodeRouteManager.checkNodeRouteReady）。
     *
     * @param parties   本次运行涉及的所有参与方节点 ID 集合
     * @param projectId 项目 ID
     */
    public void verifyNodeAndRouteHealthy(Set<String> parties, String projectId) {
        log.info("before graph run healthy check: {}", parties);
        if (PlatformTypeEnum.AUTONOMY.equals(PlatformTypeEnum.valueOf(plaformType))) {
            // unilateral mission
            if (parties.size() == 1) {
                return;
            }
            Set<String> instNodeIds = nodeRepository.findByInstId(UserContext.getUser().getOwnerId()).stream().map(NodeDO::getNodeId).collect(Collectors.toSet());
            instNodeIds.retainAll(parties);
            Map<String, List<AutonomyNodeRouteUtil.AutonomySourceNodeRouteInfo>> autonomySelfDstNodeRouteInfoMap = AutonomyNodeRouteUtil.getAutonomySelfDstNodeRouteInfoMap();

            Map<String, List<AutonomyNodeRouteUtil.AutonomySourceNodeRouteInfo>> filterAutonomySelfDstNodeRouteInfoMap = autonomySelfDstNodeRouteInfoMap.entrySet().stream()
                    .filter(entry -> instNodeIds.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Optional<ProjectApprovalConfigDO> projectApprovalConfigDOOptional = projectApprovalConfigRepository.findByProjectIdAndType(projectId, VoteTypeEnum.PROJECT_CREATE.name());
            if (projectApprovalConfigDOOptional.isEmpty()) {
                throw SecretpadException.of(ProjectErrorCode.PROJECT_NOT_EXISTS, "project approval config not exists");
            }
            List<ParticipantNodeInstVO> participantNodeInstVOS = projectApprovalConfigDOOptional.get().getParticipantNodeInfo();
            for (String party : parties) {
                if (!filterAutonomySelfDstNodeRouteInfoMap.containsKey(party)) {
                    boolean find = false;
                    for (Map.Entry<String, List<AutonomyNodeRouteUtil.AutonomySourceNodeRouteInfo>> entry : filterAutonomySelfDstNodeRouteInfoMap.entrySet()) {
                        Optional<AutonomyNodeRouteUtil.AutonomySourceNodeRouteInfo> nodeRouteInfoOptional = entry.getValue().stream().filter(e -> StringUtils.equals(e.getSourceNodeId(), party)).findAny();
                        if (nodeRouteInfoOptional.isPresent()) {
                            find = true;
                            if (!nodeRouteInfoOptional.get().isSourceToDstIsAvailable()) {
                                for (ParticipantNodeInstVO vo : participantNodeInstVOS) {
                                    if (vo.getInitiatorNodeId().equals(nodeRouteInfoOptional.get().getSourceNodeId())) {
                                        if (vo.getInvitees().contains(entry.getKey())) {
                                            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_ROUTE_NOT_EXISTS, party + "->" + entry.getKey());
                                        }
                                    } else if (vo.getInvitees().contains(nodeRouteInfoOptional.get().getSourceNodeId())) {
                                        String initiatorNodeId = vo.getInitiatorNodeId();
                                        if (StringUtils.equals(initiatorNodeId, entry.getKey())) {
                                            throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_ROUTE_NOT_EXISTS, party + "-> " + entry.getKey());
                                        }
                                    }
                                }
                            }
                            break;
                        }
                    }
                    if (!find) {
                        throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_ROUTE_NOT_EXISTS, party + "-> " + parties);
                    }
                }
            }


            // now allow The Initiator Not parties
            /*if (!parties.contains(localNodeId)) {
                throw SecretpadException.of(GraphErrorCode.GRAPH_JOB_INVALID, "parties must contains " + localNodeId);
            }

            for (String party : parties) {
                if (StringUtils.equals(party, localNodeId)) {
                    continue;
                }
                if (!nodeRouteManager.checkNodeRouteReady(party, localNodeId)) {
                    throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_ROUTE_NOT_EXISTS, party + "->" + localNodeId);
                }
            }*/


            return;
        }
        parties.forEach(node -> {
            if (!nodeManager.checkNodeReady(node)) {
                NodeDO nodeDO = nodeRepository.findByNodeId(node);
                String msg = ObjectUtils.isEmpty(nodeDO) ? node : nodeDO.getName();
                throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_NOT_EXISTS, msg);
            }
        });
        for (String partySrc : parties) {
            for (String partyDst : parties) {
                if (!partySrc.equals(partyDst) && !nodeRouteManager.checkNodeRouteReady(partySrc, partyDst, localNodeId)) {
                    NodeDO partySrcNodeDO = nodeRepository.findByNodeId(partySrc);
                    NodeDO partyDstNodeDO = nodeRepository.findByNodeId(partyDst);
                    String msg1 = ObjectUtils.isEmpty(partySrcNodeDO) ? partySrc : partySrcNodeDO.getName();
                    String msg2 = ObjectUtils.isEmpty(partyDstNodeDO) ? partyDst : partyDstNodeDO.getName();
                    throw SecretpadException.of(GraphErrorCode.GRAPH_NODE_ROUTE_NOT_EXISTS, msg1 + "->" + msg2);
                }
            }
        }
    }


    /**
     * 去除重复的开始/成功日志
     * -------------------------------------------------------------------------
     * 同一节点被多次运行时，日志中会出现多条 start/succeed 记录；
     * 本方法保留第一条匹配记录，后续重复记录（不含 failed）被剔除。
     *
     * @param graphNodeTaskLogsVO 日志视图
     * @param distinctValue       需要去重的日志关键字
     */
    private void distinctSpecifyLogs(GraphNodeTaskLogsVO graphNodeTaskLogsVO, String distinctValue) {
        List<String> logs = graphNodeTaskLogsVO.getLogs();
        List<String> uniqueList = new ArrayList<>();
        boolean flag = false;
        for (String str : logs) {
            if (str.contains(distinctValue) && !str.contains("failed")) {
                if (!flag) {
                    uniqueList.add(str);
                } else {
                    log.info("remove log {}", str);
                }
                flag = true;
            } else {
                uniqueList.add(str);
            }
        }
        graphNodeTaskLogsVO.setLogs(uniqueList);
    }

    /**
     * 计算每个选中节点的顶层依赖节点集合
     * -------------------------------------------------------------------------
     * “顶层节点”指运行当前节点所必须的最小上游节点集合（通过 edges 逆向追溯得到）。
     * 该集合用于后续 findParties 确定每个任务的参与方。
     *
     * @param edges        画布边列表
     * @param selectedNodes 用户选中的运行节点
     * @return Map<graphNodeId, 顶层节点 graphNodeId 集合>
     */
    private Map<String, Set<String>> findTopNodes(List<GraphEdgeDO> edges, List<ProjectGraphNodeDO> selectedNodes) {
        Map<String, Set<String>> tops = new HashMap<>();
        selectedNodes.forEach(node -> {
            Set<String> topNodes = GraphUtils.findTopNodes(edges, node.getUpk().getGraphNodeId());
            tops.put(node.getUpk().getGraphNodeId(), topNodes);
        });
        return tops;
    }

    /**
     * 计算每个选中节点对应的参与方集合
     * -------------------------------------------------------------------------
     * 对每个选中节点：
     *   1. 遍历其顶层依赖节点。
     *   2. 若顶层节点是 read_data/datatable 类组件，从 project_datatable 表找到该数据表归属的 nodeId，
     *      即为参与方。
     *   3. 收集列属性（ColumnAttr）到 GraphContext，供后续 TaskInputConfig 使用。
     *   4. 特殊处理 data_prep/unbalance_psi_cache 组件：额外加入隐藏的 partyId。
     *
     * 注意：按顶层集合大小排序后再处理，保证小集合先被解析，避免上下文覆盖问题。
     *
     * @param nodes      画布所有节点
     * @param tops       每个选中节点的顶层依赖集合
     * @param projectId  项目 ID
     * @param partyList  输出参数：收集各数据表与节点的对应关系
     * @return Map<graphNodeId, 参与方 nodeId 集合>
     */
    private Map<String, Set<String>> findParties(List<ProjectGraphNodeDO> nodes, Map<String, Set<String>> tops, String projectId, List<GraphContext.GraphParty> partyList) {
        Map<String, Set<String>> result = new HashMap<>();
        Map<String, ProjectGraphNodeDO> nodeDOMap = nodes.stream().collect(Collectors.toMap(e -> (e.getUpk()).getGraphNodeId(), Function.identity()));
        List<Map.Entry<String, Set<String>>> entryList = new ArrayList<>(tops.entrySet());
        entryList.sort(Comparator.comparingInt(e -> e.getValue().size()));
        for (Map.Entry<String, Set<String>> entry : entryList) {
            List<TaskConfig.TableAttr> partyLists = new ArrayList<>();
            Set<String> parties = new HashSet<>();
            entry.getValue().forEach(e -> {
                ProjectGraphNodeDO projectGraphNodeDO = nodeDOMap.get(e);
                GraphNodeInfo graphNodeInfo = GraphNodeInfo.fromDO(projectGraphNodeDO);
                String datatableId = ComponentTools.getDataTableId(graphNodeInfo);
                if (StringUtils.isNotBlank(datatableId)) {
                    List<ProjectDatatableDO> datatableDOS = datatableRepository.findByDatableId(projectId, datatableId);
                    if (!CollectionUtils.isEmpty(datatableDOS)) {
                        parties.addAll(datatableDOS.stream().map(datatableDO -> datatableDO.getUpk().getNodeId()).toList());
                        partyList.add(GraphContext.GraphParty.builder().datatableId(datatableId).node(datatableDOS.get(0).getUpk().getNodeId()).build());
                        Optional<ProjectDatatableDO> projectDatatableDOOptional = datatableRepository.findById(new ProjectDatatableDO.UPK(projectId, datatableDOS.get(0).getUpk().getNodeId(), datatableId));
                        if (projectDatatableDOOptional.isPresent()) {
                            ProjectDatatableDO projectDatatableDO = projectDatatableDOOptional.get();
                            List<ProjectDatatableDO.TableColumnConfig> tableConfigs = projectDatatableDO.getTableConfig();
                            List<TaskConfig.ColumnAttr> columnAttrs = tableConfigs.stream().map(this::parse).collect(Collectors.toList());
                            TaskConfig.TableAttr tableAttr = TaskConfig.TableAttr.newBuilder().setTableId(datatableId).addAllColumnAttrs(columnAttrs).build();
                            partyLists.add(tableAttr);
                        }
                    }
                }

            });
            // for fake two parties
            ProjectGraphNodeDO currNodeDO = nodeDOMap.get(entry.getKey());
            if (currNodeDO != null && DATA_PREP_UNBALANCE_PSI_CACHE.equalsIgnoreCase(currNodeDO.getCodeName())) {
                String partyId = ComponentTools.getHiddenPartyId(currNodeDO.getNodeDef());
                if (StringUtils.isNotEmpty(partyId)) {
                    parties.add(partyId);
                }
            }

            result.put(entry.getKey(), parties);
            GraphContext.set(partyLists);
        }

        return result;
    }


    /**
     * 将 ProjectDatatableDO 的列配置转换为 TaskConfig.ColumnAttr
     * -------------------------------------------------------------------------
     * 列类型映射规则：
     *   - associateKey = true          → COL_TYPE_ID（ID 列）
     *   - groupKey = false 且 protection = true → COL_TYPE_LABEL（标签列）
     *   - groupKey = false 且 protection = false → COL_TYPE_FEATURE（特征列）
     *   - groupKey = true              → COL_TYPE_BIN（分箱列）
     *
     * @param columnConfig 数据表列配置
     * @return Protobuf ColumnAttr 对象
     */
    private TaskConfig.ColumnAttr parse(ProjectDatatableDO.TableColumnConfig columnConfig) {
        String colType;
        if (columnConfig.isAssociateKey()) {
            colType = Constants.COL_TYPE_ID;
        } else if (!columnConfig.isGroupKey()) {
            colType = columnConfig.isProtection() ? Constants.COL_TYPE_LABEL : Constants.COL_TYPE_FEATURE;
        } else {
            colType = Constants.COL_TYPE_BIN;
        }
        return TaskConfig.ColumnAttr.newBuilder().setColName(columnConfig.getColName()).setColType(colType).build();
    }
}
