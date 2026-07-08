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

package org.secretflow.secretpad.service.model.graph;

import org.secretflow.secretpad.common.util.UUIDUtils;
import org.secretflow.secretpad.persistence.entity.ProjectGraphDO;
import org.secretflow.secretpad.persistence.entity.ProjectGraphNodeDO;
import org.secretflow.secretpad.persistence.entity.ProjectJobDO;
import org.secretflow.secretpad.persistence.entity.ProjectTaskDO;
import org.secretflow.secretpad.persistence.model.GraphNodeTaskStatus;
import org.secretflow.secretpad.service.util.JobUtils;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ProjectJob
 * =============================================================================
 * ProjectJob 是 SecretPad 后端在“画布运行”阶段生成的**项目级作业模型**。
 *
 * 定位：
 *   它位于前端画布（Graph）与 Kuscia Job 之间，是 SecretPad 内部描述一次
 *   “运行”的中间数据结构。一次运行 = 一个 ProjectJob = 多个 JobTask。
 *
 * 生命周期：
 *   1. 用户在前端选中若干节点，调用 GraphServiceImpl.startGraph(...)。
 *   2. startGraph 通过 ProjectJob.genProjectJob(graphDO, selectedNodes, parties)
 *      构造 ProjectJob。
 *   3. ProjectJob 经 JobChain 处理：
 *        - JobPersistentHandler：调用 ProjectJob.toDO 持久化到 project_job / project_job_task 表。
 *        - JobRenderHandler：渲染每个 JobTask 的输入输出（DomainData → DistData、
 *          计算 dependencies 等）。
 *        - JobSubmittedHandler：通过 KusciaJobConverter 把 ProjectJob 转换为
 *          Kuscia 的 Job.CreateJobRequest，最终提交给 Kuscia。
 *
 * 结构与对应关系：
 *   ┌─────────────────┐       ┌─────────────────────┐       ┌─────────────────┐
 *   │   ProjectGraph  │       │     ProjectJob      │       │   KusciaJob     │
 *   │  (画布持久化)    │  →    │  (一次运行作业模型)  │  →    │  (Kuscia CRD)   │
 *   └─────────────────┘       └─────────────────────┘       └─────────────────┘
 *         nodes                     List<JobTask>                  tasks[]
 *         edges                     edges (保留拓扑)               dependencies
 *                                   fullNodes (完整节点快照)
 *
 * 关键字段：
 *   - projectId / graphId / jobId：联合定位一次运行。
 *   - fullNodes：画布中所有节点的快照，渲染时用于查找上游节点信息。
 *   - edges：画布边列表，保留 DAG 拓扑，用于计算任务依赖。
 *   - tasks：用户选中的运行节点对应的任务列表。
 *   - maxParallelism：本次运行允许的最大并行任务数，透传给 Kuscia。
 *
 * @author yansi
 * @date 2023/5/31
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProjectJob implements Serializable {
    @Serial
    private static final long serialVersionUID = 5005877919773504643L;
    /**
     * 项目 ID
     */
    private String projectId;
    /**
     * 画布 ID
     */
    private String graphId;
    /**
     * 作业名称（通常与画布名称相同）
     */
    private String name;
    /**
     * 作业 ID，由 genProjectJob 生成（UUID 前 4 位）
     */
    private String jobId;
    /**
     * 画布中所有节点的完整快照
     * -------------------------------------------------------------------------
     * 渲染阶段需要根据上游节点信息计算输入输出，因此需要保留完整画布节点，
     * 而不仅仅是用户选中的节点。
     */
    private List<GraphNodeInfo> fullNodes;
    /**
     * 画布边列表
     * -------------------------------------------------------------------------
     * 保留 DAG 的连线关系，用于 JobRenderHandler 计算每个 JobTask 的
     * dependencies（哪些上游任务必须先完成）。
     */
    private List<GraphEdge> edges;
    /**
     * 本次运行包含的任务列表
     * -------------------------------------------------------------------------
     * 每个 JobTask 对应用户选中的一个画布节点（graphNode）。
     */
    private List<JobTask> tasks;

    /**
     * 作业最大并行度
     * -------------------------------------------------------------------------
     * 与画布的 maxParallelism 一致，透传给 Kuscia Job 的 maxParallelism。
     */
    private Integer maxParallelism;

    /**
     * 根据画布信息和选中节点生成 ProjectJob
     * -------------------------------------------------------------------------
     * 执行流程：
     *   1. 生成 4 位随机 jobId。
     *   2. 从 ProjectGraphDO 复制 projectId、graphId、name、maxParallelism、
     *      edges 以及完整节点快照 fullNodes。
     *   3. 遍历用户选中的 selectedNodes，为每个节点生成一个 JobTask：
     *        - taskId = JobUtils.genTaskId(jobId, graphNodeId)
     *        - parties 取自 parties.get(graphNodeId)
     *        - node 为 GraphNodeInfo 转换后的节点信息
     *   4. 使用 Builder 构建并返回 ProjectJob。
     *
     * 注意：此时 JobTask 还没有 dependencies 和输入输出渲染，
     *      这些由后续的 JobRenderHandler 补充。
     *
     * @param graphDO       画布持久化对象
     * @param selectedNodes 用户选中的运行节点列表
     * @param parties       每个选中节点对应的参与方集合（Map<graphNodeId, Set<nodeId>>）
     * @return 新构建的项目作业模型
     */
    public static ProjectJob genProjectJob(ProjectGraphDO graphDO, List<ProjectGraphNodeDO> selectedNodes, Map<String, Set<String>> parties) {
        String jobId = UUIDUtils.random(4);
        ProjectJobBuilder jobBuilder = ProjectJob.builder()
                .maxParallelism(graphDO.getMaxParallelism())
                .projectId(graphDO.getUpk().getProjectId())
                .graphId(graphDO.getUpk().getGraphId())
                .name(graphDO.getName())
                .jobId(jobId)
                .fullNodes(GraphNodeInfo.fromDOList(graphDO.getNodes()))
                .edges(GraphEdge.fromDOList(graphDO.getEdges()));

        if (!CollectionUtils.isEmpty(selectedNodes)) {
            List<JobTask> tasks = new ArrayList<>();
            for (ProjectGraphNodeDO graphNodeDO : selectedNodes) {
                String graphNodeId = graphNodeDO.getUpk().getGraphNodeId();
                String taskId = JobUtils.genTaskId(jobId, graphNodeId);
                JobTask task = JobTask.builder()
                        .taskId(taskId)
                        .parties(new ArrayList<>(parties.get(graphNodeId)))
                        .node(GraphNodeInfo.fromDO(graphNodeDO))
                        .build();
                tasks.add(task);
            }
            jobBuilder.tasks(tasks);
        }
        return jobBuilder.build();
    }

    /**
     * 将 ProjectJob 转换为 ProjectJobDO（持久化实体）
     * -------------------------------------------------------------------------
     * 该转换由 JobPersistentHandler 调用，结果写入：
     *   - project_job 表：主表，包含 jobId、name、graphId、edges 等。
     *   - project_job_task 表：每个 JobTask 一条记录，key 为 taskId。
     *
     * 转换细节：
     *   - UPK：由 projectId + jobId 组成。
     *   - tasks：List<JobTask> → Map<String, ProjectTaskDO>，Map 的 key 是 taskId。
     *   - 每个 ProjectTaskDO 的 UPK：projectId + jobId + taskId。
     *   - graphNode：通过 GraphNodeDetail.toDO 将 GraphNodeInfo 转为可 JSON 序列化的对象。
     *   - edges：保留 DAG 拓扑，写入 project_job.edges JSON 列。
     *
     * @param job 项目作业模型
     * @return 项目作业持久化实体
     */
    public static ProjectJobDO toDO(ProjectJob job) {
        return ProjectJobDO.builder()
                .upk(new ProjectJobDO.UPK(job.getProjectId(), job.getJobId()))
                .name(job.getName())
                .tasks(job.getTasks().stream().map(t -> ProjectTaskDO.builder()
                        .upk(new ProjectTaskDO.UPK(job.getProjectId(), job.getJobId(), t.getTaskId()))
                        .parties(t.getParties())
                        .status(t.getStatus())
                        .graphNodeId(t.getNode().getGraphNodeId())
                        .graphNode(GraphNodeDetail.toDO(job.getProjectId(), job.getGraphId(), t.getNode()))
                        .build()
                ).collect(Collectors.toMap(it -> it.getUpk().getTaskId(), Function.identity())))
                .graphId(job.getGraphId())
                .edges(GraphEdge.toDOList(job.getEdges()))
                .build();
    }

    /**
     * JobTask
     * =============================================================================
     * ProjectJob 内部的任务模型，对应用户选中的一个画布节点的一次执行。
     *
     * 与持久化实体的关系：
     *   - 运行时：ProjectJob.tasks = List<JobTask>
     *   - 持久化后：project_job_task 表中每个 taskId 一条记录
     *
     * 字段说明：
     *   - taskId：任务唯一标识，格式为 genTaskId(jobId, graphNodeId)。
     *   - parties：该任务需要哪些参与方节点执行（如 ["alice", "bob"]）。
     *   - status：任务状态；genProjectJob 创建时未显式设置，后续由 JobChain/状态同步更新。
     *   - dependencies：上游任务 ID 列表；由 JobRenderHandler 根据 edges 计算得出。
     *   - node：对应画布节点的完整信息（组件参数、inputs、outputs 等）。
     */
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class JobTask implements Serializable {
        @Serial
        private static final long serialVersionUID = 291568296509217011L;
        /**
         * 任务 ID
         */
        private String taskId;
        /**
         * 参与方节点 ID 列表
         */
        private List<String> parties;
        /**
         * 任务状态（INITIALIZED / RUNNING / SUCCEED / FAILED / STOPPED）
         */
        private GraphNodeTaskStatus status;
        /**
         * 上游任务 ID 列表
         * -------------------------------------------------------------------------
         * 由 JobRenderHandler 根据 ProjectJob.edges 计算，最终透传给 KusciaJob 的
         * task.dependencies，用于 Kuscia 按 DAG 顺序调度。
         */
        private List<String> dependencies;
        /**
         * 关联的画布节点信息
         */
        private GraphNodeInfo node;

        /**
         * 将 JobTask 转换为 ProjectTaskDO（简化版本）
         * -------------------------------------------------------------------------
         * 与外层 ProjectJob.toDO 相比，此方法只转换最核心的字段，
         * 实际持久化路径主要走 ProjectJob.toDO。
         *
         * @param job  所属 ProjectJob
         * @param task 当前 JobTask
         * @return ProjectTaskDO 持久化实体
         */
        public static ProjectTaskDO toDO(ProjectJob job, JobTask task) {
            return ProjectTaskDO.builder()
                    .upk(new ProjectTaskDO.UPK(job.getProjectId(), job.getJobId(), task.getTaskId()))
                    .graphNodeId(job.getGraphId())
                    .parties(task.getParties())
                    .build();
        }
    }
}
