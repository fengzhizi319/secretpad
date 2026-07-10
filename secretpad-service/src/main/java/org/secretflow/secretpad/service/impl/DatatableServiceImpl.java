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

import org.secretflow.secretpad.common.enums.DataSourceTypeEnum;
import org.secretflow.secretpad.common.enums.DataTableTypeEnum;
import org.secretflow.secretpad.common.enums.PlatformTypeEnum;
import org.secretflow.secretpad.common.errorcode.ConcurrentErrorCode;
import org.secretflow.secretpad.common.errorcode.InstErrorCode;
import org.secretflow.secretpad.common.exception.SecretpadException;
import org.secretflow.secretpad.common.util.JsonUtils;
import org.secretflow.secretpad.common.util.PageUtils;
import org.secretflow.secretpad.common.util.UUIDUtils;
import org.secretflow.secretpad.manager.integration.datatable.AbstractDatatableManager;
import org.secretflow.secretpad.manager.integration.datatablegrant.AbstractDatatableGrantManager;
import org.secretflow.secretpad.manager.integration.job.AbstractJobManager;
import org.secretflow.secretpad.manager.integration.model.DatatableDTO;
import org.secretflow.secretpad.manager.integration.model.DatatableListDTO;
import org.secretflow.secretpad.manager.integration.model.NodeDTO;
import org.secretflow.secretpad.manager.integration.node.NodeManager;
import org.secretflow.secretpad.manager.integration.noderoute.AbstractNodeRouteManager;
import org.secretflow.secretpad.persistence.entity.*;
import org.secretflow.secretpad.persistence.model.TeeJobKind;
import org.secretflow.secretpad.persistence.model.TeeJobStatus;
import org.secretflow.secretpad.persistence.repository.*;
import org.secretflow.secretpad.service.DatatableService;
import org.secretflow.secretpad.service.EnvService;
import org.secretflow.secretpad.service.InstService;
import org.secretflow.secretpad.service.enums.VoteSyncTypeEnum;
import org.secretflow.secretpad.service.graph.converter.KusciaTeeDataManagerConverter;
import org.secretflow.secretpad.service.handler.datatable.DatatableHandler;
import org.secretflow.secretpad.service.model.datasync.vote.DbSyncRequest;
import org.secretflow.secretpad.service.model.datasync.vote.TeeNodeDatatableManagementSyncRequest;
import org.secretflow.secretpad.service.model.datatable.*;
import org.secretflow.secretpad.service.util.DbSyncUtil;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.javatuples.Pair;
import org.secretflow.v1alpha1.kusciaapi.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.secretflow.secretpad.common.constant.DomainDatasourceConstants.DEFAULT_DATASOURCE;
import static org.secretflow.secretpad.service.constant.TeeJobConstants.MOCK_VOTE_RESULT;
import static org.secretflow.secretpad.service.util.RateLimitUtil.verifyRate;

/**
 * 数据表（Datatable / DomainData）服务实现类。
 * <p>
 * 职责：
 * 1. 对外提供数据表的生命周期管理：列表查询、详情查看、删除、向 TEE 节点推送、从 TEE 节点拉取结果。
 * 2. 提供统一的数据表注册入口 {@link #createDataTable(CreateDatatableRequest)}，
 *    根据数据源类型（LOCAL / OSS / MYSQL / ODPS / HTTP）分派到对应的 {@link DatatableHandler}。
 * 3. 聚合“数据表被哪些项目授权使用”以及“已推送到 TEE 的状态”等展示信息。
 * 4. 在自治（autonomy）模式下支持跨节点并行查询；在中心（center）/ 边（edge）模式下按单节点查询。
 * <p>
 * 核心设计：
 * - 采用策略模式：Spring 自动把类型为 {@link DataSourceTypeEnum} -> {@link DatatableHandler} 的 Map 注入到
 *   {@link #datatableHandlerMap}，调用方只需按数据源类型取 Handler，无需关心 LOCAL/OSS/MYSQL 等差异。
 * - 数据表在 Kuscia 中对应 DomainData CR；推送到 TEE 需要先生成 DomainDataGrant 授权，再创建 Kuscia Job。
 *
 * @author xiaonan
 * @date 2023/6/7
 */
@Service
public class DatatableServiceImpl implements DatatableService {

    private final static Logger LOGGER = LoggerFactory.getLogger(DatatableServiceImpl.class);

    /**
     * TEE 推送授权信息中 domaindatagrant_id 的 key，用于从 operateInfo JSON 中反序列化。
     */
    private static final String DOMAIN_DATA_GRANT_ID = "domaindatagrant_id";

    /**
     * 调用 KusciaAPI 的线程池，用于并行查询多个节点的数据表列表，避免单节点阻塞。
     */
    @Autowired
    @Qualifier("kusciaApiFutureTaskThreadPool")
    private Executor kusciaApiFutureThreadPool;

    /**
     * 数据表 Manager，封装对 Kuscia DomainData 的查询、创建、删除等操作。
     */
    @Autowired
    private AbstractDatatableManager datatableManager;

    /**
     * 节点 Manager，用于根据 institutionId + 节点名称过滤拿到 nodeId。
     */
    @Autowired
    private NodeManager nodeManager;

    /**
     * DomainDataGrant Manager，用于在 TEE 推送前创建/查询跨域授权。
     */
    @Autowired
    private AbstractDatatableGrantManager datatableGrantManager;

    /**
     * 节点路由 Manager，用于校验当前节点与 TEE 节点之间的路由是否已建立。
     */
    @Autowired
    private AbstractNodeRouteManager nodeRouteManager;

    /**
     * Job Manager，用于创建“推数据到 TEE”或“从 TEE 拉结果”的 Kuscia Job。
     */
    @Autowired
    private AbstractJobManager jobManager;

    /**
     * TEE 数据转换器：把 {@link TeeJob} 转换为 Kuscia {@link Job.CreateJobRequest}。
     */
    @Autowired
    private KusciaTeeDataManagerConverter teeJobConverter;

    /**
     * 项目 Repository，用于补充“数据表被授权到哪些项目”的展示信息。
     */
    @Autowired
    private ProjectRepository projectRepository;

    /**
     * 项目-数据表关联 Repository，用于查询数据表在项目中的授权情况。
     */
    @Autowired
    private ProjectDatatableRepository projectDatatableRepository;

    /**
     * TEE 节点数据表管理 Repository，记录 PushAuth / Push / Pull 三类 TEE 任务状态。
     */
    @Autowired
    private TeeNodeDatatableManagementRepository teeNodeDatatableManagementRepository;

    /**
     * 项目特征表 Repository，用于 HTTP 类型数据表的项目授权查询。
     */
    @Autowired
    private ProjectFeatureTableRepository projectFeatureTableRepository;

    /**
     * 节点 Repository，用于补充列表中的节点名称。
     */
    @Autowired
    private NodeRepository nodeRepository;

    /**
     * 机构 Service，用于 autonomy 模式下校验节点是否属于当前机构。
     */
    @Autowired
    private InstService instService;

    /**
     * 环境 Service，用于判断当前部署模式（autonomy / center / edge）。
     */
    @Resource
    private EnvService envService;

    /**
     * TEE 节点 ID，未指定时使用该默认值。
     */
    @Value("${tee.domain-id:tee}")
    private String teeNodeId;

    /**
     * 当前平台类型，用于 edge 模式下把 TEE 相关 DB 记录同步到中心。
     */
    @Value("${secretpad.platform-type}")
    private String plaformType;

    /**
     * 数据表处理器映射表，Key 为数据源类型枚举，Value 为对应处理器。
     * <p>
     * Spring 会自动收集所有实现了 {@link DatatableHandler} 且标注了
     * {@link org.springframework.stereotype.Component} 的 Bean，并按其处理的类型注入 Map。
     * 例如：
     * - LOCAL -> LocalKusciaControlDatatableHandler
     * - OSS   -> OssDatatableHandler
     * - MYSQL -> MysqlDatatableHandler
     */
    @Autowired
    private Map<DataSourceTypeEnum, DatatableHandler> datatableHandlerMap;

    /**
     * 根据机构 ID 和节点名称过滤条件，查询出对应的 nodeId 列表。
     * <p>
     * 在 autonomy 模式下，前端可能只传节点名称，后端需要转换为 nodeId。
     *
     * @param instId          机构 ID
     * @param nodeNameFilter  节点名称过滤条件
     * @return nodeId 列表
     */
    private List<String> getNodeIds(String instId, List<String> nodeNameFilter) {
        List<NodeDTO> nodeDTOS = nodeManager.listReadyNodeByNames(instId, nodeNameFilter);
        return nodeDTOS.stream().map(NodeDTO::getNodeId).toList();
    }

    /**
     * 按“属主”查询数据表列表，支持分页。
     * <p>
     * 执行逻辑：
     * 1. 如果是 autonomy 模式：根据当前机构和节点名称过滤拿到一批 nodeId，否则直接使用 request 中的 ownerId。
     * 2. 对每个 nodeId 异步调用 {@link #listDatatablesByNodeId} 查询该节点的数据表。
     * 3. 收集所有结果，汇总 totalDatatableNums，并补充 nodeName。
     * 4. 使用 {@link PageUtils#rangeList} 在内存中做统一分页。
     * <p>
     * 注意：autonomy 模式下可能查询多个节点，因此使用线程池并行 + 5 秒超时控制。
     *
     * @param request 列表查询请求
     * @return 跨节点聚合后的数据表列表
     */
    @Override
    public AllDatatableListVO listDatatablesByOwnerId(ListDatatableRequest request) {
        List<String> nodeIds = new ArrayList<>();
        if (envService.isAutonomy()) {
            // autonomy 模式：ownerId 对应的是机构，需要把机构下符合名称过滤的节点都查出来
            nodeIds.addAll(getNodeIds(InstServiceImpl.INST_ID, request.getNodeNamesFilter()));
        } else {
            // center / edge 模式：ownerId 直接就是 nodeId
            nodeIds.add(request.getOwnerId());
        }

        // 使用线程安全列表，供多个 CompletableFuture 并发写入
        List<DatatableNodeVO> datatableNodeVOList = new CopyOnWriteArrayList<>();
        AtomicInteger totalDatatableNum = new AtomicInteger();
        List<CompletableFuture<Void>> futures = nodeIds.stream().map(nodeId -> CompletableFuture.supplyAsync(() -> {
            ListDatatableRequest nodeRequest = createNodeRequest(request, nodeId);
            return listDatatablesByNodeId(nodeRequest);
        }, kusciaApiFutureThreadPool).handle((datatableListVO, ex) -> {
            // 单个节点异常不能影响整体结果，记录日志后返回 null
            if (ex != null) {
                LOGGER.error("Error processing nodeId {}: {}", nodeId, ex.getMessage(), ex);
                return null;
            } else {
                return datatableListVO;
            }
        }).thenAccept(datatableListVO -> {
            NodeDO nodeDO = nodeRepository.findByNodeId(nodeId);
            if (ObjectUtils.isEmpty(nodeDO)) {
                LOGGER.error("getNodeToken Cannot find node by nodeId {}.", nodeId);
                return;
            }

            totalDatatableNum.addAndGet(datatableListVO.getTotalDatatableNums());
            datatableListVO.getDatatableVOList().forEach(datatableVO -> {
                datatableNodeVOList.add(DatatableNodeVO.builder()
                        .datatableVO(datatableVO)
                        .nodeId(nodeId)
                        .nodeName(nodeDO.getName())
                        .build());
            });
        })).toList();

        try {
            // 等待所有节点查询完成，最多 5 秒
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable actualException = e.getCause();
            if (actualException instanceof SecretpadException) {
                throw (SecretpadException) actualException;
            } else {
                throw SecretpadException.of(ConcurrentErrorCode.TASK_EXECUTION_ERROR, e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SecretpadException.of(ConcurrentErrorCode.TASK_INTERRUPTED_ERROR, e);
        } catch (TimeoutException e) {
            throw SecretpadException.of(ConcurrentErrorCode.TASK_TIME_OUT_ERROR, e);
        }

        // 全量聚合后统一分页
        List<DatatableNodeVO> rangeVOList = PageUtils.rangeList(datatableNodeVOList, request.getPageSize(), request.getPageNumber());

        return AllDatatableListVO.builder()
                .datatableNodeVOList(rangeVOList)
                .totalDatatableNums(totalDatatableNum.get())
                .build();
    }

    /**
     * 按单个 nodeId 查询数据表列表，并补充项目授权信息和 TEE 推送状态。
     * <p>
     * 执行逻辑：
     * 1. 调用 datatableManager 从 Kuscia 拉取该节点下的 DomainData 列表（含分页）。
     * 2. 查询 project_datatable / project_feature_table，得到“每张数据表被授权到哪些项目”。
     *    - 普通数据表和 HTTP 类型特征表分别查询后合并。
     * 3. 查询 tee_node_datatable_management，得到该数据表最近一次推送到 TEE 的状态。
     * 4. 把 DomainData 元数据、授权项目列表、TEE 推送状态组装为 {@link DatatableVO} 返回。
     *
     * @param request 列表查询请求（ownerId 为 nodeId）
     * @return 该节点的数据表列表视图
     */
    @Override
    public DatatableListVO listDatatablesByNodeId(ListDatatableRequest request) {
        LOGGER.info("List data table by nodeId = {}", request.getOwnerId());
        DatatableListDTO dataTableListDTO = datatableManager.findByNodeId(request.getOwnerId(), request.getPageSize(), request.getPageNumber(), request.getStatusFilter(), request.getDatatableNameFilter(), request.getTypes());
        LOGGER.info("Try get a map with datatableId: DatatableDTO");
        Map<Object, DatatableDTO> datatables = dataTableListDTO.getDatatableDTOList().stream().collect(Collectors.toMap(DatatableDTO::getDatatableId, Function.identity()));
        LOGGER.info("Try get auth project pairs with Map<DatatableID, List<Pair<ProjectDatatableDO, ProjectDO>>>");
        Map<String, List<Pair<ProjectDatatableDO, ProjectDO>>> datatableAuthPairs = getAuthProjectPairs(request.getOwnerId(), Lists.newArrayList(datatables.values().stream().filter(e -> !StringUtils.equals(e.getType(), DataTableTypeEnum.HTTP.name())).map(DatatableDTO::getDatatableId).collect(Collectors.toList())));
        Map<String, List<Pair<ProjectDatatableDO, ProjectDO>>> featureAuthProjectPairs = getHttpFeatureAuthProjectPairs(request.getOwnerId(), Lists.newArrayList(datatables.values().stream().filter(e -> StringUtils.equals(e.getType(), DataTableTypeEnum.HTTP.name())).map(DatatableDTO::getDatatableId).collect(Collectors.toList())));
        //merge data table and feature table
        datatableAuthPairs.putAll(featureAuthProjectPairs);
        LOGGER.info("get datatable VO list from datatableListDTO and with datatable auth pairs.");
        // teeNodeId maybe blank
        String teeDomainId = StringUtils.isBlank(request.getTeeNodeId()) ? teeNodeId : request.getTeeNodeId();
        // query push to tee map
        Map<String, List<TeeNodeDatatableManagementDO>> pushToTeeInfoMap = getPushToTeeInfos(request.getOwnerId(), teeDomainId, Lists.newArrayList(datatables.values().stream().map(DatatableDTO::getDatatableId).collect(Collectors.toList())));

        List<DatatableVO> datatableVOList = dataTableListDTO.getDatatableDTOList().stream().map(it -> {
            List<Pair<ProjectDatatableDO, ProjectDO>> pairs = datatableAuthPairs.get(it.getDatatableId());
            List<AuthProjectVO> authProjectVOList = null;
            if (pairs != null) {
                authProjectVOList = AuthProjectVO.fromPairs(pairs);
            }
            // query management data object
            List<TeeNodeDatatableManagementDO> pushToTeeInfos = pushToTeeInfoMap.get(teeJobConverter.buildTeeDatatableId(teeDomainId, it.getDatatableId()));
            // 取最近一条推送记录作为展示状态
            TeeNodeDatatableManagementDO managementDO = CollectionUtils.isEmpty(pushToTeeInfos) ? null : pushToTeeInfos.stream().sorted(Comparator.comparing(TeeNodeDatatableManagementDO::getGmtCreate).reversed()).toList().get(0);
            DatatableDTO datatableDTO = datatables.get(it.getDatatableId());
            return DatatableVO.from(datatableDTO, authProjectVOList, managementDO);
        }).collect(Collectors.toList());
        return DatatableListVO.builder().datatableVOList(datatableVOList).totalDatatableNums(dataTableListDTO.getTotalDatatableNums()).build();
    }

    /**
     * 查询单张数据表详情。
     * <p>
     * 根据请求中的 datasourceType 从 {@link #datatableHandlerMap} 选取对应 Handler，
     * 委托给 Handler 的 queryDatatable 方法完成详情查询（不同数据源的查询方式不同）。
     *
     * @param request 数据表详情请求
     * @return 数据表节点视图
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatatableNodeVO getDatatable(GetDatatableRequest request) {
        LOGGER.info("Get datatable detail with nodeID = {}, datatable id = {}", request.getNodeId(), request.getDatatableId());
        return datatableHandlerMap.get(DataSourceTypeEnum.valueOf(request.getDatasourceType())).queryDatatable(request);
    }

    /**
     * 删除数据表。
     * <p>
     * 根据 datasourceType 选取对应 Handler，由 Handler 完成 Kuscia 侧 DomainData 的删除。
     *
     * @param request 删除请求
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDatatable(DeleteDatatableRequest request) {
        LOGGER.info("Delete datatable with node id = {}, datatable id = {}", request.getNodeId(), request.getDatatableId());
        datatableHandlerMap.get(DataSourceTypeEnum.valueOf(request.getDatasourceType())).deleteDatatable(request);
    }

    /**
     * 将数据表推送到 TEE 节点。
     * <p>
     * 执行流程：
     * 1. 参数补全：teeNodeId、datasourceId、relativeUri 为空时使用默认值。
     * 2. 校验当前节点与 TEE 节点之间的路由是否已建立（双向）。
     * 3. 查询本地是否已有 PushAuth 记录：
     *    - 若有，并记录有 domaindatagrant_id，则尝试到 Kuscia 查询该授权是否仍有效。
     *    - 若授权失效或不存在，则重新创建 DomainDataGrant。
     * 4. 创建 DomainDataGrant 后，把 grant_id 保存到 tee_node_datatable_management（PushAuth 类型）。
     * 5. 新建一条 Push 类型的 TEE 任务记录，并构造 {@link TeeJob}，
     *    经 {@link KusciaTeeDataManagerConverter} 转换为 Kuscia CreateJobRequest，
     *    最终调用 {@link AbstractJobManager#createJob} 下发到 Kuscia。
     *
     * @param request 推送请求
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pushDatatableToTeeNode(PushDatatableToTeeRequest request) {
        LOGGER.info("Push datatable to teeNode with node id = {}, datatable id = {}", request.getNodeId(), request.getDatatableId());
        boolean pushAuth = false;
        String domainDataGrantId = "";
        // teeNodeId and datasourceId maybe blank
        String teeDomainId = StringUtils.isBlank(request.getTeeNodeId()) ? teeNodeId : request.getTeeNodeId();
        String datasourceId = StringUtils.isBlank(request.getDatasourceId()) ? DEFAULT_DATASOURCE : request.getDatasourceId();
        // datatableId 与 relativeUri 在 TEE 场景下需要拼接为 TEE 侧唯一标识
        String datatableId = teeJobConverter.buildTeeDatatableId(teeNodeId, request.getDatatableId());
        String relativeUri = StringUtils.isBlank(request.getRelativeUri()) ? datatableId : request.getRelativeUri();
        // check node route
        nodeRouteManager.checkRouteNotExistInDB(request.getNodeId(), teeDomainId);
        nodeRouteManager.checkRouteNotExistInDB(teeDomainId, request.getNodeId());
        // query domain data grant id from database
        Optional<TeeNodeDatatableManagementDO> pushAuthOptional = teeNodeDatatableManagementRepository.findFirstByNodeIdAndTeeNodeIdAndDatatableIdAndKind(request.getNodeId(), teeDomainId, request.getDatatableId(), TeeJobKind.PushAuth);
        if (pushAuthOptional.isPresent()) {
            Map<String, Object> operateInfoMap = TeeJob.getOperateInfoMap(pushAuthOptional.get().getOperateInfo());
            if (!CollectionUtils.isEmpty(operateInfoMap) && operateInfoMap.containsKey(DOMAIN_DATA_GRANT_ID)) {
                pushAuth = true;
                domainDataGrantId = operateInfoMap.get(DOMAIN_DATA_GRANT_ID).toString();
            }
        }
        // query push auth from tee node for pushing datatable if pushAuth tag is true
        if (pushAuth) {
            try {
                datatableGrantManager.queryDomainGrant(request.getNodeId(), domainDataGrantId);
            } catch (Exception ex) {
                LOGGER.info("Datatable grant is empty, node id = {}, datatable id = {}", request.getNodeId(), request.getDatatableId());
                pushAuth = false;
            }
        }
        // create push auth from tee node for pushing datatable if pushAuth tag is false
        if (!pushAuth) {
            String domainGrantId = datatableGrantManager.createDomainGrant(request.getNodeId(), teeDomainId, request.getDatatableId(), "");
            // save domain grant id
            Map<String, String> domainGrantIdMap = new HashMap<>(1);
            domainGrantIdMap.put(DOMAIN_DATA_GRANT_ID, domainGrantId);
            TeeNodeDatatableManagementDO pushAuthDO = TeeNodeDatatableManagementDO.builder().upk(TeeNodeDatatableManagementDO.UPK.builder().nodeId(request.getNodeId()).datatableId(request.getDatatableId()).teeNodeId(teeDomainId).jobId(UUIDUtils.random(4)).build()).datasourceId(datasourceId).status(TeeJobStatus.SUCCESS).kind(TeeJobKind.PushAuth).operateInfo(JsonUtils.toJSONString(domainGrantIdMap)).build();
//            teeNodeDatatableManagementRepository.save(pushAuthDO);
            saveTeeNodeDatatableManagementOrPush(pushAuthDO);
        }
        Map<String, String> pushToTeeMap = new HashMap<>(1);
        pushToTeeMap.put(TeeJob.RELATIVE_URI, relativeUri);
        // save push datatable to Tee node job
        TeeNodeDatatableManagementDO pushToTeeDO = TeeNodeDatatableManagementDO.builder().upk(TeeNodeDatatableManagementDO.UPK.builder().nodeId(request.getNodeId()).datatableId(datatableId).teeNodeId(teeDomainId).jobId(UUIDUtils.random(4)).build()).datasourceId(datasourceId).status(TeeJobStatus.RUNNING).kind(TeeJobKind.Push).operateInfo(JsonUtils.toJSONString(pushToTeeMap)).build();
//        teeNodeDatatableManagementRepository.save(pushToTeeDO);
        saveTeeNodeDatatableManagementOrPush(pushToTeeDO);
        // build tee job model
        TeeJob teeJob = TeeJob.genTeeJob(pushToTeeDO, List.of(request.getNodeId(), teeDomainId), "", Collections.emptyList(), Collections.emptyList());
        // build push datatable to Tee node input config
        Job.CreateJobRequest createJobRequest = teeJobConverter.converter(teeJob);
        // create job
        jobManager.createJob(createJobRequest);
    }

    /**
     * 从 TEE 节点拉取计算结果。
     * <p>
     * 执行流程：
     * 1. 参数补全：teeNodeId、datasourceId、relativeUri、datatableId 为空时使用默认值。
     * 2. 构造 Pull 类型的 TEE 任务记录，operateInfo 中保存 relativeUri、投票结果、项目/任务/结果类型等上下文。
     * 3. 构造 {@link TeeJob} 并转换为 Kuscia CreateJobRequest，调用 jobManager 下发。
     *
     * @param nodeId           当前节点 ID
     * @param datatableId      数据表 ID
     * @param targetTeeNodeId  目标 TEE 节点 ID
     * @param datasourceId     数据源 ID
     * @param relativeUri      相对路径
     * @param voteResult       投票结果
     * @param projectId        项目 ID
     * @param projectJobId     项目作业 ID
     * @param projectJobTaskId 项目任务 ID
     * @param resultType       结果类型
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pullResultFromTeeNode(String nodeId, String datatableId, String targetTeeNodeId, String datasourceId, String relativeUri, String voteResult, String projectId, String projectJobId, String projectJobTaskId, String resultType) {
        LOGGER.info("Pull result from teeNode with node id = {}, datatable id = {}", nodeId, datatableId);
        // teeNodeId and datasourceId maybe blank
        String teeDomainId = StringUtils.isBlank(targetTeeNodeId) ? teeNodeId : targetTeeNodeId;
        datasourceId = StringUtils.isBlank(datasourceId) ? DEFAULT_DATASOURCE : datasourceId;
        datatableId = teeJobConverter.buildTeeDatatableId(nodeId, datatableId);
        relativeUri = StringUtils.isBlank(relativeUri) ? datatableId : relativeUri;
        Map<String, String> pullFromTeeMap = new HashMap<>(5);
        pullFromTeeMap.put(TeeJob.RELATIVE_URI, relativeUri);
        // mock vote result
        // String voteResult = VOTE_RESULT;
        pullFromTeeMap.put(TeeJob.VOTE_RESULT, StringUtils.isNotBlank(voteResult) ? voteResult : MOCK_VOTE_RESULT);
        pullFromTeeMap.put(TeeJob.PROJECT_ID, projectId);
        pullFromTeeMap.put(TeeJob.PROJECT_JOB_ID, projectJobId);
        pullFromTeeMap.put(TeeJob.PROJECT_JOB_TASK_ID, projectJobTaskId);
        pullFromTeeMap.put(TeeJob.RESULT_TYPE, resultType);
        // save pull result from Tee node job
        TeeNodeDatatableManagementDO pullFromTeeDO = TeeNodeDatatableManagementDO.builder().upk(TeeNodeDatatableManagementDO.UPK.builder().nodeId(nodeId).datatableId(datatableId).teeNodeId(teeDomainId).jobId(UUIDUtils.random(4)).build()).datasourceId(datasourceId).status(TeeJobStatus.RUNNING).kind(TeeJobKind.Pull).operateInfo(JsonUtils.toJSONString(pullFromTeeMap)).build();
//        teeNodeDatatableManagementRepository.save(pullFromTeeDO);
        saveTeeNodeDatatableManagementOrPush(pullFromTeeDO);
        // build tee job model
        TeeJob teeJob = TeeJob.genTeeJob(pullFromTeeDO, List.of(nodeId, teeDomainId), "", Collections.emptyList(), Collections.emptyList());
        // build push datatable to Tee node input config
        Job.CreateJobRequest createJobRequest = teeJobConverter.converter(teeJob);
        // create job
        jobManager.createJob(createJobRequest);
    }

    /**
     * 创建（注册）数据表，统一入口。
     * <p>
     * 执行逻辑：
     * 1. 调用 {@link #verifyRate()} 进行限流校验。
     * 2. 调用 {@link #verifyNodes(CreateDatatableRequest)} 在 autonomy 模式下校验节点归属。
     * 3. 根据请求中的 datasourceType 从 {@link #datatableHandlerMap} 选取 Handler，
     *    委托其 createDatatable 方法完成后续 Kuscia DomainData 创建。
     * <p>
     * 例如 LOCAL 类型会由 {@code LocalKusciaControlDatatableHandler} 把 CSV 文件信息构建为
     * CreateDomainDataRequest 并调用 KusciaAPI；OSS/MYSQL 等类型则引用已注册的 DomainDataSource。
     *
     * @param createDatatableRequest 创建数据表请求
     * @return 创建结果视图
     */
    @Override
    public CreateDatatableVO createDataTable(CreateDatatableRequest createDatatableRequest) {
        verifyRate();
        verifyNodes(createDatatableRequest);
        return datatableHandlerMap.get(DataSourceTypeEnum.valueOf(createDatatableRequest.getDatasourceType())).createDatatable(createDatatableRequest);
    }

    /**
     * 校验创建数据表请求中的节点列表。
     * <p>
     * 仅在 autonomy 模式且请求中显式传了 nodeIds 时生效：
     * - 对 nodeIds 去重；
     * - 调用 instService 检查这些节点是否都属于 ownerId 对应的机构；
     * - 若不匹配则抛出 INST_NOT_MATCH_NODE 异常。
     *
     * @param createDatatableRequest 创建数据表请求
     */
    public void verifyNodes(CreateDatatableRequest createDatatableRequest) {
        if (!CollectionUtils.isEmpty(createDatatableRequest.getNodeIds()) && envService.isAutonomy()) {
            List<String> distinctNodes = createDatatableRequest.getNodeIds().stream().distinct().collect(Collectors.toList());
            if (!instService.checkNodesInInst(createDatatableRequest.getOwnerId(), distinctNodes)) {
                throw SecretpadException.of(InstErrorCode.INST_NOT_MATCH_NODE);
            }
            createDatatableRequest.setNodeIds(distinctNodes);
        }
    }

    /**
     * 根据 nodeId 查询所有数据表，仅返回基础信息用于下拉选择等场景。
     *
     * @param nodeId 节点 ID
     * @return 数据表基础视图列表
     */
    @Override
    public List<DatatableVO> findDatatableByNodeId(String nodeId) {
        List<DatatableDTO> datatableDTOS = datatableManager.findAllDatatableByNodeId(nodeId);
        if (CollectionUtils.isEmpty(datatableDTOS)) {
            return Collections.EMPTY_LIST;
        }
        return datatableDTOS.stream().map(e -> DatatableVO.builder().datatableId(e.getDatatableId()).datatableName(e.getDatatableName()).type(e.getType()).datasourceId(e.getDatasourceId()).nodeId(e.getNodeId()).build()).collect(Collectors.toList());
    }

    /**
     * 查询普通数据表的项目授权信息。
     * <p>
     * 根据 nodeId 和 datatableId 列表查询 project_datatable，然后按 datatableId 分组，
     * 并关联 project 表补充项目名称等信息。
     *
     * @param nodeId       节点 ID
     * @param datatableIds 数据表 ID 列表
     * @return Map<datatableId, List<Pair<ProjectDatatableDO, ProjectDO>>>
     */
    private Map<String, List<Pair<ProjectDatatableDO, ProjectDO>>> getAuthProjectPairs(String nodeId, List<String> datatableIds) {
        List<ProjectDatatableDO> authProjectDatatables = projectDatatableRepository.authProjectDatatablesByDatatableIds(nodeId, datatableIds);
        return getStringListMap(authProjectDatatables);
    }

    /**
     * 查询 HTTP 类型特征表的项目授权信息。
     * <p>
     * 逻辑与 {@link #getAuthProjectPairs} 类似，但数据源是 project_feature_table。
     * 将 {@link ProjectFeatureTableDO} 转换为 {@link ProjectDatatableDO} 后统一走 getStringListMap 处理。
     *
     * @param nodeId          节点 ID
     * @param featureTableIds 特征表 ID 列表
     * @return Map<featureTableId, List<Pair<ProjectDatatableDO, ProjectDO>>>
     */
    private Map<String, List<Pair<ProjectDatatableDO, ProjectDO>>> getHttpFeatureAuthProjectPairs(String nodeId, List<String> featureTableIds) {
        List<ProjectFeatureTableDO> featureTableDOS = projectFeatureTableRepository.findByNodeIdAndFeatureTableIds(nodeId, featureTableIds);
        List<ProjectDatatableDO> authProjectDatatables = featureTableDOS.stream().map(e -> ProjectDatatableDO.builder().tableConfig(e.getTableConfig()).source(e.getSource()).upk(new ProjectDatatableDO.UPK(e.getUpk().getProjectId(), e.getUpk().getNodeId(), e.getUpk().getFeatureTableId())).build()).collect(Collectors.toList());
        return getStringListMap(authProjectDatatables);
    }

    /**
     * 把“项目-数据表授权记录”转换为按 datatableId 分组的 Pair 列表。
     * <p>
     * Pair 的 Value0 是授权记录本身，Value1 是对应的 ProjectDO，便于前端展示数据表被授权到哪些项目。
     *
     * @param authProjectDatatables 授权记录列表
     * @return Map<datatableId, List<Pair<ProjectDatatableDO, ProjectDO>>>
     */
    private Map<String, List<Pair<ProjectDatatableDO, ProjectDO>>> getStringListMap(List<ProjectDatatableDO> authProjectDatatables) {
        List<String> projectIds = authProjectDatatables.stream().map(it -> it.getUpk().getProjectId()).collect(Collectors.toList());
        Map<String, ProjectDO> projectMap = projectRepository.findAllById(projectIds).stream().collect(Collectors.toMap(ProjectDO::getProjectId, Function.identity()));
        return authProjectDatatables.stream().map(
                        // List<Pair>
                        it -> new Pair<>(it, projectMap.getOrDefault(it.getUpk().getProjectId(), null))).filter(it -> it.getValue1() != null)
                // Map<datatable, List<Pair>>
                .collect(Collectors.groupingBy(it -> it.getValue0().getUpk().getDatatableId()));
    }

    /**
     * 查询数据表推送到 TEE 的状态信息。
     * <p>
     * 由于 TEE 侧的数据表 ID 会与原始 ID 拼接（{@link KusciaTeeDataManagerConverter#buildTeeDatatableId}），
     * 所以先转换 datatableId，再查询 tee_node_datatable_management 中 kind = Push 的记录，
     * 最后按转换后的 datatableId 分组返回。
     *
     * @param nodeId       节点 ID
     * @param teeNodeId    TEE 节点 ID
     * @param datatableIds 数据表 ID 列表
     * @return Map<teeDatatableId, List<TeeNodeDatatableManagementDO>>
     */
    private Map<String, List<TeeNodeDatatableManagementDO>> getPushToTeeInfos(String nodeId, String teeNodeId, List<String> datatableIds) {
        List<String> teeDatatables = datatableIds.stream().map(datatableId -> teeJobConverter.buildTeeDatatableId(teeNodeId, datatableId)).toList();
        // batch query push to tee job list by datatableIds
        List<TeeNodeDatatableManagementDO> managementList = teeNodeDatatableManagementRepository.findAllByNodeIdAndTeeNodeIdAndDatatableIdsAndKind(nodeId, teeNodeId, teeDatatables, TeeJobKind.Push);
        if (CollectionUtils.isEmpty(managementList)) {
            return Collections.emptyMap();
        }
        // collect by datatable id
        return managementList.stream().collect(Collectors.groupingBy(it -> it.getUpk().getDatatableId()));
    }

    /**
     * 保存 TEE 数据表管理记录。
     * <p>
     * 在 edge 平台模式下，数据需要同步到中心节点，因此通过 {@link DbSyncUtil#dbDataSyncToCenter} 发送同步请求；
     * 其他模式（center / autonomy）则直接写入本地数据库。
     *
     * @param saveDO 待保存的 TEE 数据表管理记录
     */
    private void saveTeeNodeDatatableManagementOrPush(TeeNodeDatatableManagementDO saveDO) {
        if (PlatformTypeEnum.EDGE.equals(PlatformTypeEnum.valueOf(plaformType))) {
            TeeNodeDatatableManagementSyncRequest request = TeeNodeDatatableManagementSyncRequest.parse2VO(saveDO);
            DbSyncRequest dbSyncRequest = DbSyncRequest.builder().syncDataType(VoteSyncTypeEnum.TEE_NODE_DATATABLE_MANAGEMENT.name()).projectNodesInfo(request).build();
            DbSyncUtil.dbDataSyncToCenter(dbSyncRequest);
        } else {
            teeNodeDatatableManagementRepository.save(saveDO);
        }
    }

    /**
     * 为跨节点查询构造单节点的 ListDatatableRequest。
     * <p>
     * 保留原有的过滤条件（statusFilter、datatableNameFilter、types、teeNodeId），
     * 仅把 ownerId 替换为当前要查询的 nodeId。
     *
     * @param request 原始请求
     * @param nodeId  目标节点 ID
     * @return 针对该节点的请求对象
     */
    private ListDatatableRequest createNodeRequest(ListDatatableRequest request, String nodeId) {
        return ListDatatableRequest.builder().statusFilter(request.getStatusFilter()).datatableNameFilter(request.getDatatableNameFilter()).types(request.getTypes()).ownerId(nodeId).teeNodeId(request.getTeeNodeId()).build();
    }


}
